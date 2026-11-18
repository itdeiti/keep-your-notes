package app.kyn

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** Decrypted text. Lives only in memory: not Parcelable/Serializable, and never printed. */
class Plaintext internal constructor(val text: String) {
    override fun toString() = "Plaintext(***)"
}

/**
 * Passwords and decrypted Meld Encrypt content, in memory only (process-wide, so every screen shares it).
 * Nothing here is ever written to disk, prefs, saved state or logs. [lock] forgets everything;
 * it runs when the app was in the background longer than the PIN delay, when the PIN lock engages,
 * and on the lock button. Plaintext must never reach [Vault.write], `notes` or `rememberSaveable`.
 */
object SecretSession {
    private val passwords = mutableListOf<CharArray>()
    private val cache = mutableMapOf<String, Plaintext>()
    /** Blob key -> the (session-owned) password that opened it; re-encrypting uses the same one. */
    private val owner = mutableMapOf<String, CharArray>()
    /** Session password -> blob keys it already failed on: never derive the same (blob, password) twice. */
    private val failed = java.util.IdentityHashMap<CharArray, MutableSet<String>>()
    private var backgroundAt = 0L

    /** Bumped on every change, so Compose re-reads [cached] and [unlocked]. */
    var version by mutableIntStateOf(0)
        private set

    val unlocked: Boolean get() = passwords.isNotEmpty()

    fun cached(b: MeldCrypto.Blob): Plaintext? = cache[b.key]

    /** Try the passwords of this session (off the main thread: PBKDF2 takes up to a second). */
    suspend fun tryKnown(b: MeldCrypto.Blob): Plaintext? {
        cache[b.key]?.let { return it }
        val known = synchronized(passwords) { passwords.filter { failed[it]?.contains(b.key) != true } }
        for (p in known) {
            val copy = p.copyOf()
            val t = try { derive(b, copy) } finally { copy.fill('\u0000') }
            synchronized(passwords) {
                if (p !in passwords) return null // locked meanwhile
                if (t == null) failed.getOrPut(p) { mutableSetOf() } += b.key
            }
            if (t == null) continue
            owner[b.key] = p
            return remember(b, t)
        }
        return null
    }

    /** Decrypt with a newly typed password; on success it is kept for this session. [password] is wiped. */
    suspend fun unlock(b: MeldCrypto.Blob, password: CharArray): Plaintext? {
        try {
            val t = derive(b, password) ?: return null
            owner[b.key] = synchronized(passwords) {
                passwords.firstOrNull { it.contentEquals(password) } ?: password.copyOf().also { passwords += it }
            }
            return remember(b, t)
        } finally {
            password.fill('\u0000')
        }
    }

    /** Decrypt off the main thread; stops deriving the key when the calling coroutine is cancelled. */
    private suspend fun derive(b: MeldCrypto.Blob, password: CharArray): String? = withContext(Dispatchers.Default) {
        val job = coroutineContext[Job]
        MeldCrypto.decrypt(b, password) { job?.isActive == false }
    }

    /**
     * Encrypt [text] with the password that opened [old] (v3, fresh salt and IV). The result is cached as
     * decrypted, so the view shows the new text without asking again. Null if [old] was not unlocked
     * in this session (or the session was locked meanwhile).
     */
    suspend fun encryptLike(old: MeldCrypto.Blob, text: String): MeldCrypto.V3? {
        val pw = synchronized(passwords) { owner[old.key]?.takeIf { it in passwords }?.copyOf() } ?: return null
        try {
            val b = withContext(Dispatchers.Default) { MeldCrypto.encryptV3(text, pw) }
            synchronized(passwords) {
                val p = passwords.firstOrNull { it.contentEquals(pw) } ?: return null
                owner[b.key] = p
            }
            remember(b, text)
            return b
        } finally {
            pw.fill('\u0000')
        }
    }

    /**
     * Encrypt [text] with a newly chosen [password] (v3) and keep it in the session, so the result opens
     * without asking again. [password] is wiped.
     */
    suspend fun encryptNew(text: String, password: CharArray): MeldCrypto.V3 {
        try {
            val b = withContext(Dispatchers.Default) { MeldCrypto.encryptV3(text, password) }
            owner[b.key] = synchronized(passwords) {
                passwords.firstOrNull { it.contentEquals(password) } ?: password.copyOf().also { passwords += it }
            }
            remember(b, text)
            return b
        } finally {
            password.fill('\u0000')
        }
    }

    /** Called after [lock] (main thread), e.g. to drop drafts that hold plaintext. Set by [AppVM]. */
    var onLocked: (() -> Unit)? = null

    private fun remember(b: MeldCrypto.Blob, text: String): Plaintext =
        Plaintext(text).also { cache[b.key] = it; version++ }

    /** Forget all passwords and decrypted text. */
    fun lock() {
        if (passwords.isEmpty() && cache.isEmpty()) return
        synchronized(passwords) { passwords.forEach { it.fill('\u0000') }; passwords.clear(); owner.clear(); failed.clear() }
        cache.clear()
        version++
        onLocked?.invoke()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val lockLater = Runnable { lock() }

    /**
     * App left: lock after the PIN delay even if it never comes back to the foreground, so passwords do
     * not sit in memory for hours. (A frozen process runs this late; [onForeground] checks again.)
     * Drafts were saved in onStop before this, with the password copied synchronously.
     */
    fun onBackground(ctx: Context) {
        backgroundAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(lockLater)
        if (unlocked) handler.postDelayed(lockLater, AppLock.delay(ctx) * 1000L)
    }

    /** Back in the app: forget secrets if the PIN lock engaged or the app was away at least the PIN delay. */
    fun onForeground(ctx: Context) {
        handler.removeCallbacks(lockLater)
        val at = backgroundAt
        backgroundAt = 0
        if (AppLock.locked && AppLock.enabled(ctx)) { lock(); return }
        if (at != 0L && SystemClock.elapsedRealtime() - at >= AppLock.delay(ctx) * 1000L) lock()
    }

    /** No screenshots / recents preview while secrets are unlocked (or a PIN is set). */
    fun secureWindow(a: Activity) {
        if (unlocked || AppLock.enabled(a)) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
