package app.notizen

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN lock for the app UI. Only the PIN's salted PBKDF2 hash is stored (app-private prefs).
 * This hides the notes from someone holding the unlocked phone; it does not encrypt the vault,
 * which lives in shared storage and stays readable by Obsidian and file managers.
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
            .putInt("fails", 0).putLong("until", 0)
            .apply()
        locked = false
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove("hash").remove("salt").remove("len").remove("fails").remove("until").apply()
        locked = false
    }

    /** Milliseconds until the next attempt is allowed (after repeated wrong PINs). */
    fun waitMs(ctx: Context): Long = (sp(ctx).getLong("until", 0) - System.currentTimeMillis()).coerceAtLeast(0)

    /** Checks the PIN; wrong PINs from the 5th on cost 30 s, doubling each time (max 1 h). */
    fun check(ctx: Context, pin: String): Boolean {
        val p = sp(ctx)
        if (waitMs(ctx) > 0) return false
        val salt = Base64.decode(p.getString("salt", null) ?: return true, Base64.NO_WRAP)
        val want = Base64.decode(p.getString("hash", null) ?: return true, Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(hash(pin, salt), want)
        if (ok) {
            p.edit().putInt("fails", 0).putLong("until", 0).apply()
        } else {
            val fails = p.getInt("fails", 0) + 1
            val penalty = if (fails < 5) 0L else (30_000L shl (fails - 5).coerceAtMost(7)).coerceAtMost(3_600_000L)
            p.edit().putInt("fails", fails).putLong("until", System.currentTimeMillis() + penalty).apply()
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

/** Full-screen keypad shown instead of the app while [AppLock.locked]. */
@Composable
fun PinScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val len = remember { AppLock.pinLength(ctx) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var waitUntil by remember { mutableLongStateOf(System.currentTimeMillis() + AppLock.waitMs(ctx)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    BackHandler(onBack = onBack)
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.clearFocus(force = true) } // no keyboard typing into the hidden editor
    LaunchedEffect(waitUntil) {
        while (System.currentTimeMillis() < waitUntil) { now = System.currentTimeMillis(); delay(250) }
        now = System.currentTimeMillis()
    }
    val blocked = now < waitUntil

    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }

    fun submit() {
        if (blocked || checking || pin.length < AppLock.MIN_LEN) return
        checking = true
        val tried = pin
        scope.launch {
            // PBKDF2 takes a moment: keep it off the UI thread
            val ok = withContext(Dispatchers.Default) { AppLock.unlock(ctx, tried) }
            checking = false
            if (!ok) {
                pin = ""
                error = "Falsche PIN"
                waitUntil = System.currentTimeMillis() + AppLock.waitMs(ctx)
                now = System.currentTimeMillis()
            }
        }
    }

    fun type(d: Char) {
        if (blocked || checking || pin.length >= AppLock.MAX_LEN) return
        pin += d
        error = ""
        if (pin.length == len) submit()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🔒", fontSize = 40.sp)
            Spacer(Modifier.height(8.dp))
            Text("Notizen gesperrt", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(24.dp))
            Text(
                if (pin.isEmpty()) " " else "●".repeat(pin.length).chunked(1).joinToString(" "),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    blocked -> "Zu viele Versuche – warten ${(waitUntil - now + 999) / 1000} s"
                    else -> error.ifEmpty { "PIN eingeben" }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (blocked || error.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(24.dp))
            val rows = listOf("123", "456", "789")
            for (r in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { for (d in r) Key(d.toString(), !blocked) { type(d) } }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Key("⌫", pin.isNotEmpty()) { pin = pin.dropLast(1) }
                Key("0", !blocked) { type('0') }
                Key("OK", !blocked && pin.length >= AppLock.MIN_LEN) { submit() }
            }
        }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        if (label == "⌫" || label == "OK") TextButton(onClick, Modifier.size(72.dp), enabled = enabled) { Text(label, fontSize = 20.sp) }
        else FilledTonalButton(onClick, Modifier.size(72.dp), enabled = enabled) { Text(label, fontSize = 24.sp) }
    }
}
