package app.kyn

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.delay

/**
 * PIN lock for the app UI. Only the PIN's salted PBKDF2 hash is stored (app-private prefs).
 * This hides the notes from someone holding the unlocked phone; it does not encrypt the vault.
 * In "Obsidian" storage mode the vault stays readable by Obsidian and file managers, see [Storage].
 */
object AppLock {
    private const val ITER = 120_000
    const val MIN_LEN = 4
    const val MAX_LEN = 8
    /** Choices for "lock after", in seconds in the background. */
    val DELAYS = listOf(0, 60, 300)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("lock", Context.MODE_PRIVATE)

    /** UI state; a fresh process starts locked. */
    var locked by mutableStateOf(true)
    private var backgroundAt = 0L

    fun enabled(ctx: Context) = sp(ctx).contains("hash")
    fun pinLength(ctx: Context) = sp(ctx).getInt("len", 0)
    fun delay(ctx: Context) = sp(ctx).getInt("delay", 60)
    fun setDelay(ctx: Context, s: Int) = sp(ctx).edit().putInt("delay", s).apply()

    fun validPin(pin: String) = pin.length in MIN_LEN..MAX_LEN && pin.all { it.isDigit() }

    internal fun hash(pin: String, salt: ByteArray, iter: Int = ITER): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iter, 256)).encoded

    fun setPin(ctx: Context, pin: String) {
        require(validPin(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp(ctx).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt("len", pin.length)
            .apply()
        resetFails(sp(ctx))
        locked = false
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove("hash").remove("salt").remove("len").apply()
        resetFails(sp(ctx))
        locked = false
    }

    /**
     * Wait after the [fails]-th wrong PIN in a row: none for the first four,
     * then 30 s, doubling each time, at most 1 h.
     */
    fun penaltyMs(fails: Int): Long =
        if (fails < 5) 0L else (30_000L shl (fails - 5).coerceAtMost(7)).coerceAtMost(3_600_000L)

    /**
     * Milliseconds until the next attempt is allowed. Checked against wall clock and time since boot,
     * so moving the system clock forward does not skip the wait (a reboot resets only the boot clock).
     */
    fun waitMs(ctx: Context): Long {
        val p = sp(ctx)
        val wall = p.getLong("until", 0) - System.currentTimeMillis()
        val nowEl = SystemClock.elapsedRealtime()
        val sameBoot = nowEl >= p.getLong("failEl", Long.MAX_VALUE)
        val boot = if (sameBoot) p.getLong("untilEl", 0) - nowEl else 0
        return maxOf(wall, boot, 0)
    }

    private fun resetFails(p: android.content.SharedPreferences) =
        p.edit().remove("fails").remove("until").remove("untilEl").remove("failEl").apply()

    /** Checks the PIN; see [penaltyMs]. Fails closed if the stored hash is incomplete. */
    fun check(ctx: Context, pin: String): Boolean {
        val p = sp(ctx)
        if (waitMs(ctx) > 0) return false
        val salt = p.getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val want = p.getString("hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val ok = salt != null && want != null && MessageDigest.isEqual(hash(pin, salt), want)
        if (ok) resetFails(p)
        else {
            val fails = p.getInt("fails", 0) + 1
            val penalty = penaltyMs(fails)
            val nowEl = SystemClock.elapsedRealtime()
            p.edit().putInt("fails", fails)
                .putLong("until", System.currentTimeMillis() + penalty)
                .putLong("failEl", nowEl).putLong("untilEl", nowEl + penalty)
                .apply()
        }
        return ok
    }

    fun unlock(ctx: Context, pin: String): Boolean = check(ctx, pin).also { if (it) locked = false }

    /** Activity stopped (not just rotated). */
    fun onBackground() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    /** Activity started: lock if a PIN is set and the app was away longer than the chosen delay. */
    fun onForeground(ctx: Context) {
        val at = backgroundAt
        backgroundAt = 0
        if (!enabled(ctx)) { locked = false; return }
        if (at == 0L) return // no real background since the last check (first start: already locked)
        if (SystemClock.elapsedRealtime() - at >= delay(ctx) * 1000L) locked = true
    }
}

/**
 * Activity that shows notes: locks after time in the background and keeps note content out of the
 * recent-apps preview and screenshots while a PIN is set. Content goes inside [LockGate].
 */
abstract class LockedActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        AppLock.onForeground(this)
        secure()
    }

    /** Also here: the recents preview is taken when leaving, maybe right after a PIN was set. */
    override fun onPause() {
        super.onPause()
        secure()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AppLock.onBackground()
    }

    private fun secure() {
        if (AppLock.enabled(this)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
