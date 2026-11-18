package app.notizen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows [content] only while unlocked; otherwise the keypad on top. The content stays composed
 * (editor state, pending dictation result), hidden from touch and accessibility.
 */
@Composable
fun LockGate(onBack: () -> Unit, hint: String? = null, content: @Composable () -> Unit) {
    val locked = AppLock.locked
    Box(Modifier.fillMaxSize()) {
        Box(if (locked) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) { content() }
        if (locked) PinScreen(onBack, hint)
    }
}

/** Full-screen keypad shown while [AppLock.locked]. */
@Composable
private fun PinScreen(onBack: () -> Unit, hint: String?) {
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
            if (hint != null) Text(
                hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
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

/** "4. App-Sperre": set, change or remove the PIN and choose when the app locks. */
@Composable
fun LockSettings() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(AppLock.enabled(ctx)) }
    var delay by remember { mutableStateOf(AppLock.delay(ctx)) }
    var mode by remember { mutableStateOf<PinMode?>(null) }

    Text("4. App-Sperre", style = MaterialTheme.typography.titleMedium)
    Text(
        if (on) "PIN aktiv. Die App sperrt sich, wenn sie im Hintergrund war."
        else "Schützt die App mit einer PIN. Die Dateien im Vault-Ordner bleiben unverschlüsselt.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
    )
    if (!on) OutlinedButton(onClick = { mode = PinMode.Set }, modifier = Modifier.fillMaxWidth()) { Text("PIN festlegen") }
    else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { mode = PinMode.Change }, modifier = Modifier.weight(1f)) { Text("PIN ändern") }
            OutlinedButton(onClick = { mode = PinMode.Remove }, modifier = Modifier.weight(1f)) { Text("PIN entfernen") }
        }
        Text("Sperren nach", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in AppLock.DELAYS) FilterChip(
                selected = delay == d,
                onClick = { delay = d; AppLock.setDelay(ctx, d) },
                label = { Text(when (d) { 0 -> "Sofort"; else -> "${d / 60} Min." }) },
            )
        }
    }
    mode?.let { m -> PinDialog(m, onDone = { mode = null; on = AppLock.enabled(ctx) }) }
}

private enum class PinMode { Set, Change, Remove }

@Composable
private fun PinDialog(mode: PinMode, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val needOld = mode != PinMode.Set
    val needNew = mode != PinMode.Remove
    val digits = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    fun field(v: String, set: (String) -> Unit, label: String) = @Composable {
        OutlinedTextField(
            v, { t -> set(t.filter { it.isDigit() }.take(AppLock.MAX_LEN)); error = "" }, Modifier.fillMaxWidth(),
            label = { Text(label) }, singleLine = true, keyboardOptions = digits,
            visualTransformation = PasswordVisualTransformation(),
        )
    }

    fun confirm() {
        if (needNew && !AppLock.validPin(new1)) { error = "PIN: ${AppLock.MIN_LEN}–${AppLock.MAX_LEN} Ziffern"; return }
        if (needNew && new1 != new2) { error = "PINs stimmen nicht überein"; return }
        working = true
        scope.launch {
            val ok = withContext(Dispatchers.Default) {
                if (needOld && !AppLock.check(ctx, old)) return@withContext false
                if (needNew) AppLock.setPin(ctx, new1) else AppLock.clear(ctx)
                true
            }
            working = false
            if (ok) {
                Toast.makeText(ctx, if (needNew) "PIN gespeichert" else "PIN entfernt", Toast.LENGTH_SHORT).show()
                onDone()
            } else {
                val wait = AppLock.waitMs(ctx)
                error = if (wait > 0) "Falsche PIN – warten ${(wait + 999) / 1000} s" else "Aktuelle PIN falsch"
                old = ""
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(when (mode) { PinMode.Set -> "PIN festlegen"; PinMode.Change -> "PIN ändern"; PinMode.Remove -> "PIN entfernen" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needOld) field(old, { old = it }, "Aktuelle PIN")()
                if (needNew) {
                    field(new1, { new1 = it }, "Neue PIN (${AppLock.MIN_LEN}–${AppLock.MAX_LEN} Ziffern)")()
                    field(new2, { new2 = it }, "Neue PIN wiederholen")()
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = ::confirm, enabled = !working) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Abbrechen") } },
    )
}
