@file:OptIn(ExperimentalMaterial3Api::class)
package app.kyn

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * Read-only view of Meld Encrypt content. Plaintext is held only in `remember` state and [SecretSession]:
 * never rememberSaveable (saved instance state can be written to disk), never the editor, never Vault.write.
 */

/**
 * Password input. The typed text lives in plain `remember` state (not saved across process death) and is
 * cleared after each attempt; the keyboard is told it is a password (no learning, no suggestions).
 * [onSubmit] gets a CharArray it may wipe and returns whether the password worked.
 */
@Composable
private fun PasswordInput(hint: String, onSubmit: suspend (CharArray) -> Boolean, onDone: () -> Unit = {}) {
    var text by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val submit = submit@{
        if (busy || text.isEmpty()) return@submit
        val pw = text.toCharArray()
        text = ""
        busy = true
        scope.launch {
            val ok = onSubmit(pw)
            busy = false
            wrong = !ok
            if (ok) onDone()
        }
        Unit
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hint.isNotBlank()) Text(stringResource(R.string.secret_hint, hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(
            value = text, onValueChange = { text = it; wrong = false },
            label = { Text(stringResource(R.string.secret_password)) },
            singleLine = true, enabled = !busy, isError = wrong,
            supportingText = if (wrong) { { Text(stringResource(R.string.secret_wrong)) } } else null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { submit() }, enabled = !busy && text.isNotEmpty()) { Text(stringResource(R.string.secret_unlock)) }
            if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** Whole encrypted note (.mdenc), read-only. */
@Composable
fun SecretScreen(vm: AppVM) {
    val n = vm.secretNote ?: return
    val ctx = LocalContext.current
    val c = mdColors()
    BackHandler { vm.closeSecret() }
    var file by remember(n.path) { mutableStateOf<Result<MeldCrypto.FileData>?>(null) }
    LaunchedEffect(n.path) { file = runCatching { vm.secretFile(n) } }
    var plain by remember(n.path) { mutableStateOf<Plaintext?>(null) }
    val version = SecretSession.version
    LaunchedEffect(file, version) {
        val data = file?.getOrNull() ?: return@LaunchedEffect
        val blob = data.blob
        plain = if (blob == null) Plaintext("") else SecretSession.cached(blob) ?: SecretSession.tryKnown(blob)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton({ vm.closeSecret() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.screens_back)) } },
                title = { Text("🔐 " + n.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (SecretSession.unlocked) TextButton({ SecretSession.lock() }) { Text(stringResource(R.string.secret_lock)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            val f = file
            val p = plain
            when {
                f == null -> CircularProgressIndicator()
                f.isFailure -> Text(stringResource(R.string.secret_unsupported))
                p != null -> {
                    Text(stringResource(R.string.secret_read_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    val body = remember(p) { Vault.parse(p.text).second }
                    MarkdownView(
                        body, c, onToggle = {}, onEdit = {}, onLink = { openLink(ctx, vm, it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), readOnly = true,
                    )
                }
                else -> {
                    val blob = f.getOrNull()?.blob ?: return@Column
                    Text(stringResource(R.string.secret_locked_note), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                    PasswordInput(f.getOrNull()?.hint.orEmpty(), onSubmit = { pw -> SecretSession.unlock(blob, pw)?.also { plain = it } != null })
                }
            }
        }
    }
}

/** A note line with encrypted sections: text around them as is, each section locked or decrypted. */
@Composable
internal fun SecretLine(line: String, blocks: List<MeldCrypto.Inline>, c: MdColors, onLink: (String) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var pos = 0
        for (b in blocks) {
            val before = line.substring(pos, b.range.first)
            if (before.isNotBlank()) Text(Markdown.render(before.trim(), c), style = MaterialTheme.typography.bodyLarge)
            SecretBlock(b, c, onLink)
            pos = b.range.last + 1
        }
        val after = line.substring(pos)
        if (after.isNotBlank()) Text(Markdown.render(after.trim(), c), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SecretBlock(i: MeldCrypto.Inline, c: MdColors, onLink: (String) -> Unit) {
    val blob = remember(i.base64, i.version) { runCatching { MeldCrypto.blob(i) }.getOrNull() }
    var plain by remember(i.base64) { mutableStateOf<Plaintext?>(null) }
    var ask by remember { mutableStateOf(false) }
    val version = SecretSession.version
    LaunchedEffect(i.base64, version) {
        plain = blob?.let { SecretSession.cached(it) ?: SecretSession.tryKnown(it) }
    }
    val p = plain
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().then(if (p == null && blob != null) Modifier.clickable { ask = true } else Modifier),
    ) {
        Column(Modifier.padding(10.dp)) {
            when {
                blob == null -> Text(stringResource(R.string.secret_inline_unsupported), style = MaterialTheme.typography.bodySmall)
                p == null -> Text(
                    "🔐 " + stringResource(R.string.secret_inline_locked) + (if (i.hint.isNotBlank()) " · 💡 " + i.hint else ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                )
                else -> MarkdownView(p.text, c, onToggle = {}, onEdit = {}, onLink = onLink, modifier = Modifier.fillMaxWidth(), readOnly = true)
            }
        }
    }
    if (ask && blob != null) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text(stringResource(R.string.secret_inline_title)) },
        text = { PasswordInput(i.hint, onSubmit = { pw -> SecretSession.unlock(blob, pw)?.also { plain = it } != null }, onDone = { ask = false }) },
        confirmButton = {},
        dismissButton = { TextButton({ ask = false }) { Text(stringResource(R.string.screens_cancel)) } },
    )
}
