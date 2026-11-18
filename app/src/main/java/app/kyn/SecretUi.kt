@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.kyn

import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.core.widget.doAfterTextChanged
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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

/**
 * Multi-line text field for plaintext: a plain EditText, because Compose cannot ask the keyboard for
 * incognito mode. IME_FLAG_NO_PERSONALIZED_LEARNING keeps Gboard & co. from learning the words;
 * isSaveEnabled = false keeps the text out of the saved instance state (which can be written to disk).
 */
@Composable
private fun SecureTextEditor(initial: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val size = MaterialTheme.typography.bodyLarge.fontSize.value
    AndroidView(
        factory = { ctx ->
            EditText(ctx).apply {
                isSaveEnabled = false
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                isSingleLine = false
                gravity = Gravity.TOP or Gravity.START
                background = null
                setTextColor(color)
                textSize = size
                setText(initial)
                doAfterTextChanged { onChange(it?.toString().orEmpty()) }
            }
        },
        modifier = modifier,
    )
}

/** Whole encrypted note (.mdenc): read view, and editing with the password it was opened with. */
@Composable
fun SecretScreen(vm: AppVM) {
    val n = vm.secretNote ?: return
    val ctx = LocalContext.current
    val c = mdColors()
    BackHandler { vm.closeSecret() }
    var file by remember(n.path) { mutableStateOf<Result<MeldCrypto.FileData>?>(null) }
    LaunchedEffect(n.path, vm.secretRev) { file = runCatching { vm.secretFile(n) } }
    val editing = vm.secretDraft != null
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
                    val blob = file?.getOrNull()?.blob
                    val p = plain
                    if (vm.secretSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    if (editing) TextButton({ vm.saveSecret { vm.secretDraft = null } }) { Text(stringResource(R.string.secret_done)) }
                    else if (p != null && blob != null) TextButton({ vm.startSecretEdit(file!!.getOrThrow(), blob, p) }) { Text(stringResource(R.string.editor_edit)) }
                    if (SecretSession.unlocked) TextButton({ vm.saveSecret { SecretSession.lock() } }) { Text(stringResource(R.string.secret_lock)) }
                },
            )
        },
    ) { pad ->
        val draft = vm.secretDraft
        if (draft != null) {
            SecureTextEditor(
                draft, onChange = { vm.secretDraft = it },
                Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize().padding(horizontal = 12.dp),
            )
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            val f = file
            val p = plain
            when {
                f == null -> CircularProgressIndicator()
                f.isFailure -> Text(stringResource(R.string.secret_unsupported))
                p != null -> {
                    val body = remember(p) { Vault.parse(p.text).second }
                    MarkdownView(
                        body, c, onToggle = {}, onEdit = {}, onLink = { openLink(ctx, vm, it) },
                        modifier = Modifier.fillMaxWidth(), readOnly = true,
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
internal fun SecretLine(
    line: String, blocks: List<MeldCrypto.Inline>, c: MdColors, onLink: (String) -> Unit, modifier: Modifier,
    onEdit: (suspend (MeldCrypto.Inline, String) -> Boolean)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var pos = 0
        for (b in blocks) {
            val before = line.substring(pos, b.range.first)
            if (before.isNotBlank()) Text(Markdown.render(before.trim(), c), style = MaterialTheme.typography.bodyLarge)
            SecretBlock(b, c, onLink, onEdit)
            pos = b.range.last + 1
        }
        val after = line.substring(pos)
        if (after.isNotBlank()) Text(Markdown.render(after.trim(), c), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SecretBlock(i: MeldCrypto.Inline, c: MdColors, onLink: (String) -> Unit, onEdit: (suspend (MeldCrypto.Inline, String) -> Boolean)?) {
    val blob = remember(i.base64, i.version) { runCatching { MeldCrypto.blob(i) }.getOrNull() }
    var plain by remember(i.base64) { mutableStateOf<Plaintext?>(null) }
    var ask by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<String?>(null) } // plaintext draft, memory only
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
                else -> {
                    MarkdownView(p.text, c, onToggle = {}, onEdit = {}, onLink = onLink, modifier = Modifier.fillMaxWidth(), readOnly = true)
                    if (onEdit != null) TextButton({ edit = p.text; failed = false }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.editor_edit)) }
                }
            }
        }
    }
    val draft = edit
    if (draft != null && onEdit != null) AlertDialog(
        onDismissRequest = { if (!saving) edit = null },
        title = { Text(stringResource(R.string.secret_inline_title)) },
        text = {
            Column {
                SecureTextEditor(draft, onChange = { edit = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp))
                if (failed) Text(stringResource(R.string.secret_save_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    val ok = onEdit(i, edit.orEmpty())
                    saving = false
                    if (ok) edit = null else failed = true
                }
            }) { Text(stringResource(R.string.secret_save)) }
        },
        dismissButton = { TextButton({ edit = null }, enabled = !saving) { Text(stringResource(R.string.screens_cancel)) } },
    )
    if (ask && blob != null) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text(stringResource(R.string.secret_inline_title)) },
        text = { PasswordInput(i.hint, onSubmit = { pw -> SecretSession.unlock(blob, pw)?.also { plain = it } != null }, onDone = { ask = false }) },
        confirmButton = {},
        dismissButton = { TextButton({ ask = false }) { Text(stringResource(R.string.screens_cancel)) } },
    )
}
