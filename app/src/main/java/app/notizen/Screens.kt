@file:OptIn(ExperimentalMaterial3Api::class)

package app.notizen

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun mdColors(): MdColors {
    val s = MaterialTheme.colorScheme
    return remember(s) { MdColors(s.outline, s.primary, s.tertiary, s.surfaceVariant, s.secondary) }
}

// ---------------- list ----------------

@Composable
fun ListScreen(vm: AppVM) {
    val c = mdColors()
    val notes = vm.filtered()
    val tags = remember(vm.notes) {
        vm.notes.flatMap { it.tags }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notizen") },
                actions = {
                    if (vm.busy) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.sync() }) { Icon(Icons.Default.Refresh, "Synchronisieren") }
                    IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.Default.Settings, "Einstellungen") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.newNote("note") }) { Icon(Icons.Default.Add, "Neue Notiz") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                placeholder = { Text("Suchen") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true, shape = RoundedCornerShape(28.dp),
            )
            if (tags.isNotEmpty()) LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(tags) { t ->
                    FilterChip(
                        selected = vm.tag == t,
                        onClick = { vm.tag = if (vm.tag == t) null else t },
                        label = { Text("#$t") },
                    )
                }
            }
            if (vm.status.isNotBlank()) Text(
                vm.status, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalItemSpacing = 8.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(notes, key = { it.path }) { n -> NoteCard(n, c) { vm.open(n) } }
            }
        }
    }
}

@Composable
private fun NoteCard(n: Note, c: MdColors, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                (if (n.pinned) "📌 " else "") + n.title,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val prev = remember(n.body, n.title, c) { Markdown.preview(n.body, c, n.title) }
            if (prev.isNotEmpty()) Text(
                prev, style = MaterialTheme.typography.bodySmall, maxLines = 10,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
            val due = remember(n.body) { Markdown.nextDue(n.body) }
            if (due != null) Text(
                "📅 " + due.format(DateTimeFormatter.ofPattern("d.M.")),
                style = MaterialTheme.typography.labelSmall,
                color = if (due <= LocalDate.now()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (n.folder.isNotEmpty() && n.folder != "inbox") Text(
                n.folder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// ---------------- editor ----------------

@Composable
fun EditorScreen(vm: AppVM) {
    val ctx = LocalContext.current
    val c = mdColors()
    val focus = remember { FocusRequester() }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler { vm.closeEditor() }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            vm.edPreview = false
            vm.edBody = Markdown.insertSpaced(vm.edBody, it)
        }
    }
    val dictate = {
        try {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            )
        } catch (_: Exception) {
            Toast.makeText(ctx, "Keine Spracherkennung gefunden", Toast.LENGTH_SHORT).show()
        }
    }
    val pickDue = {
        val today = LocalDate.now()
        val def = runCatching { LocalTime.parse(Prefs.load(ctx).defaultTime.padStart(5, '0')) }.getOrDefault(LocalTime.of(9, 0))
        DatePickerDialog(ctx, { _, y, m, d ->
            val date = LocalDate.of(y, m + 1, d)
            TimePickerDialog(ctx, { _, h, min ->
                vm.edBody = Markdown.setDue(vm.edBody, date, LocalTime.of(h, min))
            }, def.hour, def.minute, true).apply {
                setButton(DialogInterface.BUTTON_NEUTRAL, "Ohne Uhrzeit") { _, _ ->
                    vm.edBody = Markdown.setDue(vm.edBody, date, null)
                }
            }.show()
        }, today.year, today.monthValue - 1, today.dayOfMonth).show()
    }

    LaunchedEffect(vm.edKey) {
        if (vm.dictateOnOpen) { vm.dictateOnOpen = false; dictate() }
        else if (!vm.edPreview) runCatching { focus.requestFocus() }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Notiz löschen?") },
        text = { Text("Bleibt in der Git-Historie erhalten.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteNote() }) { Text("Löschen") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Abbrechen") } },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton({ vm.closeEditor() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
                title = {},
                actions = {
                    TextButton({ vm.togglePin() }) {
                        Text("📌", Modifier.alpha(if (vm.edPinned) 1f else 0.3f))
                    }
                    TextButton({ vm.edPreview = !vm.edPreview }) { Text(if (vm.edPreview) "Bearbeiten" else "Ansehen") }
                    IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, "Löschen") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            val onSurface = MaterialTheme.colorScheme.onSurface
            BasicTextField(
                value = vm.edTitle, onValueChange = { vm.edTitle = it.replace("\n", "") },
                textStyle = MaterialTheme.typography.titleLarge.copy(color = onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (vm.edTitle.isEmpty()) Text("Titel", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
                        inner()
                    }
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.edPreview) {
                    MarkdownView(
                        vm.edBody.text, c,
                        onToggle = { vm.toggleTask(it) },
                        modifier = Modifier.fillMaxSize()
                            .clickable { vm.edPreview = false; vm.edKey++ }
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                } else {
                    val transform = remember(c) { MdHighlight(c) }
                    BasicTextField(
                        value = vm.edBody,
                        onValueChange = { vm.edBody = Markdown.continueList(vm.edBody, it) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        visualTransformation = transform,
                        modifier = Modifier.fillMaxSize().focusRequester(focus).padding(horizontal = 16.dp),
                        decorationBox = { inner ->
                            Box {
                                if (vm.edBody.text.isEmpty()) Text("Notiz", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                                inner()
                            }
                        },
                    )
                }
            }
            if (!vm.edPreview) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                    Tool("☑") { vm.edBody = Markdown.toggleTask(vm.edBody) }
                    Tool("📅") { pickDue() }
                    Tool("🎤") { dictate() }
                    Tool("B", bold = true) { vm.edBody = Markdown.wrap(vm.edBody, "**") }
                    Tool("I", italic = true) { vm.edBody = Markdown.wrap(vm.edBody, "*") }
                    Tool("S", strike = true) { vm.edBody = Markdown.wrap(vm.edBody, "~~") }
                    Tool("H") { vm.edBody = Markdown.cycleHeading(vm.edBody) }
                    Tool("•") { vm.edBody = Markdown.toggleBullet(vm.edBody) }
                    Tool("🔗") { vm.edBody = Markdown.link(vm.edBody) }
                }
            }
        }
    }
}

@Composable
private fun Tool(label: String, bold: Boolean = false, italic: Boolean = false, strike: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
        )
    }
}

/** Read view: rendered lines, tappable checkboxes. */
@Composable
private fun MarkdownView(body: String, c: MdColors, onToggle: (Int) -> Unit, modifier: Modifier) {
    val base = MaterialTheme.typography.bodyLarge
    Column(modifier) {
        body.split("\n").forEachIndexed { i, line ->
            val task = Markdown.TASK.find(line)
            val head = Markdown.HEADING.find(line)
            val bullet = Markdown.BULLET.find(line)
            val indent = (line.length - line.trimStart().length).coerceAtMost(12) * 6
            when {
                task != null -> Row(Modifier.padding(start = indent.dp), verticalAlignment = Alignment.CenterVertically) {
                    val done = task.groupValues[2] != " "
                    Checkbox(checked = done, onCheckedChange = { onToggle(i) })
                    Text(
                        Markdown.render(line.substring(task.value.length), c), style = base,
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                        color = if (done) c.marker else MaterialTheme.colorScheme.onSurface,
                    )
                }
                head != null -> Text(
                    Markdown.render(line.substring(head.value.length), c),
                    style = when (head.groupValues[1].length) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                bullet != null -> Row(Modifier.padding(start = indent.dp, top = 2.dp)) {
                    val mark = bullet.groupValues[1]
                    Text(if (mark[0].isDigit()) "$mark " else "•  ", style = base, color = c.marker)
                    Text(Markdown.render(line.substring(bullet.value.length), c), style = base)
                }
                line.startsWith(">") -> Text(
                    Markdown.render(line.removePrefix(">").trimStart(), c), style = base,
                    fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
                line.trim() == "---" -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                line.isBlank() -> Spacer(Modifier.height(10.dp))
                else -> Text(Markdown.render(line, c), style = base)
            }
        }
    }
}

// ---------------- settings ----------------

@Composable
fun SettingsScreen(vm: AppVM) {
    val ctx = LocalContext.current
    val p0 = remember { Prefs.load(ctx) }
    var url by rememberSaveable { mutableStateOf(p0.repoUrl) }
    var token by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf(p0.folder) }
    var name by rememberSaveable { mutableStateOf(p0.name) }
    var email by rememberSaveable { mutableStateOf(p0.email) }
    var time by rememberSaveable { mutableStateOf(p0.defaultTime) }
    val tick = vm.tick
    val hasToken = remember(tick, vm.busy) { Secrets.get(ctx) != null }
    val files = remember(tick) { Environment.isExternalStorageManager() }
    val notif = remember(tick) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.tick++ }
    val ready = remember(tick, vm.busy) { vm.ready() }
    BackHandler(enabled = ready) { vm.screen = Screen.List }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einrichten") },
                navigationIcon = {
                    if (ready) IconButton({ vm.screen = Screen.List }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("1. Berechtigungen", style = MaterialTheme.typography.titleMedium)
            Permission("Zugriff auf alle Dateien (für den gemeinsamen Ordner mit Obsidian)", files) {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                )
            }
            Permission("Benachrichtigungen (für Erinnerungen)", notif) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            Text("2. GitHub-Repo", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Repo-URL") },
                placeholder = { Text("https://github.com/name/vault.git") }, singleLine = true)
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (hasToken) "Token (gespeichert, leer lassen)" else "Fine-grained Token") },
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(folder, { folder = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Ordnername in Documents/") })
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name für Commits") })
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("E-Mail für Commits") })

            Text("3. Erinnerungen", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(time, { time = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Uhrzeit, wenn nur ein Datum gesetzt ist (HH:MM)") })

            Button(
                enabled = !vm.busy && files && url.isNotBlank() && (token.isNotBlank() || hasToken),
                onClick = { vm.setup(Prefs(url, folder, name, email, time), token); token = "" },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ready) "Speichern & synchronisieren" else "Speichern & klonen") }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.status.isNotBlank()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
            Text(
                "Vault-Ordner: " + Vault.dir(ctx).path,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun Permission(label: String, granted: Boolean, request: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text((if (granted) "✓ " else "✗ ") + label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!granted) OutlinedButton(onClick = request) { Text("Erlauben") }
    }
}
