package app.kyn

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
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
    private var backgroundAt = 0L

    /** Bumped on every change, so Compose re-reads [cached] and [unlocked]. */
    var version by mutableIntStateOf(0)
        private set

    val unlocked: Boolean get() = passwords.isNotEmpty()

    fun cached(b: MeldCrypto.Blob): Plaintext? = cache[b.key]

    /** Try the passwords of this session (off the main thread: PBKDF2 takes up to a second). */
    suspend fun tryKnown(b: MeldCrypto.Blob): Plaintext? {
        cache[b.key]?.let { return it }
        val known = synchronized(passwords) { passwords.map { it.copyOf() } }
        try {
            for (p in known) {
                val t = withContext(Dispatchers.Default) { MeldCrypto.decrypt(b, p) } ?: continue
                return remember(b, t)
            }
            return null
        } finally {
            known.forEach { it.fill('\u0000') }
        }
    }

    /** Decrypt with a newly typed password; on success it is kept for this session. [password] is wiped. */
    suspend fun unlock(b: MeldCrypto.Blob, password: CharArray): Plaintext? {
        try {
            val t = withContext(Dispatchers.Default) { MeldCrypto.decrypt(b, password) } ?: return null
            synchronized(passwords) { if (passwords.none { it.contentEquals(password) }) passwords += password.copyOf() }
            return remember(b, t)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun remember(b: MeldCrypto.Blob, text: String): Plaintext =
        Plaintext(text).also { cache[b.key] = it; version++ }

    /** Forget all passwords and decrypted text. */
    fun lock() {
        if (passwords.isEmpty() && cache.isEmpty()) return
        synchronized(passwords) { passwords.forEach { it.fill('\u0000') }; passwords.clear() }
        cache.clear()
        version++
    }

    fun onBackground() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    /** Back in the app: forget secrets if the PIN lock engaged or the app was away at least the PIN delay. */
    fun onForeground(ctx: Context) {
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
