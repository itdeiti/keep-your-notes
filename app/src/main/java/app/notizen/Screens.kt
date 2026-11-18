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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.InputChip
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    val counts = remember(vm.notes) { vm.tagCounts() }
    val tq = vm.tagQuery
    val tags = counts.map { it.first }
        .filter { tq.isNullOrEmpty() || it.contains(tq) }
        .let { l -> vm.tag?.let { sel -> listOf(sel) + (l - sel) } ?: l }
    var pickTag by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    if (showFolders) FolderDialog(vm) { showFolders = false }
    if (pickTag) TagFilterPicker(counts, onPick = { vm.tag = it; vm.query = ""; pickTag = false }) { pickTag = false }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notizen") },
                actions = {
                    TextButton(onClick = { vm.showTasks() }) { Text("☑", style = MaterialTheme.typography.titleMedium) }
                    TextButton(onClick = { showFolders = true }) { Text("📁", style = MaterialTheme.typography.titleMedium) }
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
                placeholder = { Text("Suchen, #tag für Tags") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true, shape = RoundedCornerShape(28.dp),
            )
            val conflicts = vm.conflictCount
            if (conflicts > 0 || vm.showConflicts) FilterChip(
                selected = vm.showConflicts,
                onClick = { vm.showConflicts = !vm.showConflicts },
                label = { Text("⚠ " + if (conflicts == 1) "1 Konflikt" else "$conflicts Konflikte") },
                trailingIcon = if (vm.showConflicts) ({ Text("×") }) else null,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            vm.folderFilter?.let { f ->
                InputChip(
                    selected = true, onClick = { vm.folderFilter = null },
                    label = { Text("📁 $f") }, trailingIcon = { Text("×") },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            if (counts.isNotEmpty()) LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { AssistChip(onClick = { pickTag = true }, label = { Text("Tags…") }) }
                items(tags, key = { it }) { t ->
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

// ---------------- tasks ----------------

/** All open tasks of the vault: overdue, today, next 7 days, later, without date. */
@Composable
fun TasksScreen(vm: AppVM) {
    BackHandler { vm.screen = Screen.List }
    val today = LocalDate.now()
    val groups = remember(vm.notes, vm.justDone, today) { Tasks.grouped(vm.taskItems(), today) }
    var showNoDate by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aufgaben") },
                navigationIcon = {
                    IconButton({ vm.screen = Screen.List }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
                actions = {
                    if (vm.busy) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.sync() }) { Icon(Icons.Default.Refresh, "Synchronisieren") }
                },
            )
        },
    ) { pad ->
        if (groups.isEmpty()) {
            Text(
                "Keine offenen Aufgaben.", color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(pad).padding(24.dp),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            for ((g, list) in groups) {
                val folded = g == Tasks.Group.NoDate && !showNoDate
                item(key = "h-" + g.name) {
                    val head = "${g.label} (${list.size})" + if (g == Tasks.Group.NoDate) (if (folded) "  ▸" else "  ▾") else ""
                    Text(
                        head, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = if (g == Tasks.Group.Overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                            .let { if (g == Tasks.Group.NoDate) it.clickable { showNoDate = !showNoDate } else it }
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                if (!folded) items(list, key = { "t-" + it.key }) { t ->
                    TaskRow(t, g, onToggle = { vm.toggleTask(t) }, onOpen = { vm.openTask(t) })
                }
            }
        }
    }
}

@Composable
private fun TaskRow(t: Tasks.Item, g: Tasks.Group, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = t.done, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                t.text, style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
                color = if (t.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            val date = when {
                t.date == null -> null
                g == Tasks.Group.Today -> t.time
                else -> t.date.format(
                    DateTimeFormatter.ofPattern(if (t.date.year == LocalDate.now().year) "EE d.M." else "EE d.M.yyyy", Locale.GERMAN),
                ) + (t.time?.let { " $it" } ?: "")
            }
            Text(
                listOfNotNull(date, t.recur?.let { "🔁 $it" }, titleOf(t.path)).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = if (g == Tasks.Group.Overdue && !t.done) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
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
    var pickRecur by remember { mutableStateOf(false) }
    if (pickRecur) RecurDialog(onPick = { vm.edBody = Markdown.setRecurrence(vm.edBody, it); pickRecur = false }) { pickRecur = false }
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
          // Title, properties and text scroll as one page, so the header moves out of the way while writing
          // and the cursor can always be brought above the keyboard.
          val scroll = rememberScrollState()
          var bodyLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
          var bodyFocused by remember { mutableStateOf(false) }
          val cursorView = remember { BringIntoViewRequester() }
          val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
          val lineGap = with(LocalDensity.current) { 32.dp.toPx() }
          LaunchedEffect(vm.edBody.selection, vm.edBody.text, imeBottom, bodyFocused, bodyLayout) {
              val layout = bodyLayout ?: return@LaunchedEffect
              if (!bodyFocused) return@LaunchedEffect
              val pos = vm.edBody.selection.end.coerceIn(0, layout.layoutInput.text.length)
              val r = layout.getCursorRect(pos)
              cursorView.bringIntoView(Rect(r.left, r.top - lineGap, r.right, r.bottom + lineGap))
          }
          Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
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
            vm.edConflictOf?.let { orig -> ConflictBanner(orig, vm) }
            PropsSection(vm)
            TagRow(vm)
            if (vm.clipping) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                if (vm.edPreview) {
                    MarkdownView(
                        vm.edBody.text, c,
                        onToggle = { vm.toggleTask(it) },
                        onEdit = { vm.editAt(it) },
                        onLink = { openLink(ctx, vm, it) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp)
                            .clickable { vm.editAt(null) }
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
                        onTextLayout = { bodyLayout = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp)
                            .bringIntoViewRequester(cursorView)
                            .focusRequester(focus)
                            .onFocusChanged { bodyFocused = it.isFocused }
                            .padding(start = 16.dp, end = 16.dp, bottom = 48.dp),
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
                    Tool("⇥") { vm.edBody = Markdown.indent(vm.edBody) }
                    Tool("⇤") { vm.edBody = Markdown.indent(vm.edBody, outdent = true) }
                    Tool("📅") { pickDue() }
                    Tool("🔁") { pickRecur = true }
                    Tool("🎤") { dictate() }
                    Tool("B", bold = true) { vm.edBody = Markdown.wrap(vm.edBody, "**") }
                    Tool("I", italic = true) { vm.edBody = Markdown.wrap(vm.edBody, "*") }
                    Tool("S", strike = true) { vm.edBody = Markdown.wrap(vm.edBody, "~~") }
                    Tool("H") { vm.edBody = Markdown.cycleHeading(vm.edBody) }
                    Tool("•") { vm.edBody = Markdown.toggleBullet(vm.edBody) }
                    Tool("🔗") { vm.edBody = Markdown.link(vm.edBody) }
                    Tool("▦") { vm.edBody = Markdown.table(vm.edBody) }
                }
            }
        }
    }
}

/** Shown on a conflict copy: what happened, and the two ways out. */
@Composable
private fun ConflictBanner(original: String, vm: AppVM) {
    var confirm by remember { mutableStateOf<Boolean?>(null) } // true = keep this, false = discard
    confirm?.let { keep ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (keep) "Handy-Version behalten?" else "Kopie verwerfen?") },
            text = {
                Text(
                    if (keep) "Diese Kopie ersetzt „${titleOf(original)}“. Die Server-Version bleibt in der Git-Historie."
                    else "„${titleOf(original)}“ bleibt wie auf dem Server, diese Kopie wird gelöscht (bleibt in der Git-Historie).",
                )
            },
            confirmButton = { TextButton({ confirm = null; vm.resolveConflict(keep) }) { Text(if (keep) "Behalten" else "Verwerfen") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Abbrechen") } },
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Konfliktkopie: So stand die Notiz auf dem Handy, als „${titleOf(original)}“ gleichzeitig woanders geändert wurde.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.openConflictOriginal() }) { Text("Original") }
                TextButton(onClick = { confirm = true }) { Text("Diese behalten") }
                TextButton(onClick = { confirm = false }) { Text("Verwerfen") }
            }
        }
    }
}

/** Repeat rule for the task in the current line (Tasks plugin syntax, German labels). */
@Composable
private fun RecurDialog(onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var whenDone by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wiederholen") },
        text = {
            Column {
                for (r in Recurrence.CHOICES) {
                    val rule = r + if (whenDone) " when done" else ""
                    Text(
                        Recurrence.parse(r)!!.label(),
                        Modifier.fillMaxWidth().clickable { onPick(rule) }.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Row(Modifier.fillMaxWidth().clickable { whenDone = !whenDone }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = whenDone, onCheckedChange = { whenDone = it })
                    Text("ab Erledigung zählen", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text("Nicht wiederholen") } },
    )
}

/** Opens a date picker; writes YYYY-MM-DD and keeps a time part that was already there (created: ...T14:32). */
private fun pickDate(ctx: android.content.Context, current: String, onPick: (String) -> Unit) {
    val d = runCatching { LocalDate.parse(current.take(10)) }.getOrDefault(LocalDate.now())
    val time = if (current.length > 10 && current[10] == 'T') current.substring(10) else ""
    DatePickerDialog(ctx, { _, y, m, day -> onPick(LocalDate.of(y, m + 1, day).toString() + time) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
}

/** Frontmatter as editable properties, collapsed by default (like Obsidian "Properties"). */
@Composable
private fun PropsSection(vm: AppVM) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val ps = vm.edProps
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 16.dp)) {
            Text(
                (if (open) "▾ " else "▸ ") + "Eigenschaften" + if (ps.isNotEmpty()) " (${ps.size})" else "",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline,
            )
        }
        TextButton(onClick = { moving = true }) {
            Text("📁 " + vm.edFolderNow.ifEmpty { "/" }, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (moving) MoveDialog(vm) { moving = false }
    if (!open) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ps.forEach { p ->
            key(vm.edKey, p.key) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(p.key, Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isDateProp(p)) {
                        TextButton(onClick = { pickDate(ctx, p.value) { vm.setProp(p.key, it) } }, modifier = Modifier.weight(1f)) {
                            Text("📅 " + p.value.ifEmpty { "Datum wählen" }, Modifier.fillMaxWidth())
                        }
                    } else {
                        var v by remember { mutableStateOf(p.value) }
                        BasicTextField(
                            value = v, onValueChange = { v = it.replace("\n", ""); vm.setProp(p.key, v, p.list) },
                            singleLine = true, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box { if (v.isEmpty()) Text("leer", color = MaterialTheme.colorScheme.outline); inner() }
                            },
                        )
                    }
                    TextButton(onClick = { vm.removeProp(p.key) }) { Text("×") }
                }
            }
        }
        TextButton(onClick = { adding = true }) { Text("＋ Eigenschaft") }
    }
    if (adding) AddPropDialog(onAdd = { k, v -> vm.setProp(k, v); adding = false }) { adding = false }
}

/** Pick a folder (PARA first, subfolders indented) or type a new one like "projekte/umzug-2027". */
@Composable
private fun MoveDialog(vm: AppVM, onDone: () -> Unit) {
    val folders = remember { vm.folders() }
    var newFolder by remember { mutableStateOf("") }
    val current = vm.edFolderNow
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Verschieben nach") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(folders) { f ->
                        val depth = f.count { it == '/' }
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.moveTo(f); onDone() }
                                .padding(start = (depth * 20).dp, top = 10.dp, bottom = 10.dp),
                        ) {
                            Text(
                                (if (f == current) "✓ " else "📁 ") + f.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (f == current) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    newFolder, { newFolder = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Neuer Ordner") }, placeholder = { Text("z.B. projekte/umzug-2027") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (Vault.cleanFolder(newFolder).isNotEmpty()) { vm.moveTo(newFolder); onDone() }
                    }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.moveTo(newFolder); onDone() }, enabled = Vault.cleanFolder(newFolder).isNotEmpty()) {
                Text("In neuen Ordner")
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Abbrechen") } },
    )
}

@Composable
private fun AddPropDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var isDate by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eigenschaft hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") },
                    placeholder = { Text("z.B. due, ort, quelle") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !isDate, onClick = { isDate = false; value = "" }, label = { Text("Text") })
                    FilterChip(selected = isDate, onClick = { isDate = true; value = LocalDate.now().toString() }, label = { Text("Datum") })
                }
                if (isDate) {
                    OutlinedButton(onClick = { pickDate(ctx, value) { value = it } }, modifier = Modifier.fillMaxWidth()) { Text("📅 $value") }
                } else {
                    OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("Wert") }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, value) }, enabled = cleanKey(name).isNotEmpty()) { Text("Hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Folder overview: tap filters the list, "Verschieben" moves a folder with everything in it. */
@Composable
private fun FolderDialog(vm: AppVM, onDone: () -> Unit) {
    val counts = remember(vm.notes) { vm.folderCounts() }
    var moving by remember { mutableStateOf<String?>(null) }
    val para = Vault.PARA_FOLDERS
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Ordner") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Text("Alle Notizen", Modifier.fillMaxWidth().clickable { vm.folderFilter = null; onDone() }.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (vm.folderFilter == null) FontWeight.Bold else FontWeight.Normal)
                }
                items(counts) { (f, n) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.folderFilter = f; onDone() }
                            .padding(start = (f.count { it == '/' } * 20).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("📁 " + f.substringAfterLast('/'), Modifier.weight(1f).padding(vertical = 10.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (vm.folderFilter == f) FontWeight.Bold else FontWeight.Normal)
                        Text("$n", color = MaterialTheme.colorScheme.outline)
                        if (f !in para) TextButton(onClick = { moving = f }) { Text("Verschieben") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Schließen") } },
    )
    moving?.let { src ->
        FolderTargetDialog(vm, src, onDone = { moving = null })
    }
}

@Composable
private fun FolderTargetDialog(vm: AppVM, src: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val targets = remember { listOf("") + vm.folders().filter { it != src && !it.startsWith("$src/") && it != src.substringBeforeLast('/', "") } }
    var custom by remember { mutableStateOf("") }
    fun go(parent: String) {
        if (!vm.moveFolder(src, parent)) Toast.makeText(ctx, "Geht nicht", Toast.LENGTH_SHORT).show()
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("„${src.substringAfterLast('/')}“ verschieben nach") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(targets) { f ->
                        Text(
                            if (f.isEmpty()) "📁 / (oberste Ebene)" else "📁 " + f.substringAfterLast('/'),
                            Modifier.fillMaxWidth().clickable { go(f) }
                                .padding(start = (if (f.isEmpty()) 0 else (f.count { it == '/' } * 20)).dp, top = 10.dp, bottom = 10.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                OutlinedTextField(
                    custom, { custom = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Neuer Zielordner") }, placeholder = { Text("z.B. archiv/2026") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { go(custom) }, enabled = Vault.cleanFolder(custom).isNotEmpty()) { Text("Dorthin") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Abbrechen") } },
    )
}

/** Searchable list of all tags with counts; tap filters the note list. */
@Composable
private fun TagFilterPicker(counts: List<Pair<String, Int>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = cleanTag(query)
    val shown = counts.filter { q.isEmpty() || it.first.contains(q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nach Tag filtern") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Tag suchen") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { shown.firstOrNull()?.let { onPick(it.first) } }),
                )
                if (shown.isEmpty()) Text("Kein Tag gefunden", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outline)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown) { (t, n) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#$t", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text("$n", color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

/** Tags of the open note: tap × to remove, ＋ to add an existing or new tag. */
@Composable
private fun TagRow(vm: AppVM) {
    var adding by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(vm.edTags) { t ->
            InputChip(
                selected = false,
                onClick = { vm.removeTag(t) },
                label = { Text("#$t") },
                trailingIcon = { Text("×") },
            )
        }
        item {
            AssistChip(onClick = { adding = true }, label = { Text(if (vm.edTags.isEmpty()) "＋ Tag" else "＋") })
        }
    }
    if (adding) TagPicker(vm) { adding = false }
}

@Composable
private fun TagPicker(vm: AppVM, onDone: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember(vm.notes) { vm.allTags() }
    val q = cleanTag(query)
    val matches = all.filter { it !in vm.edTags && (q.isEmpty() || it.contains(q)) }.take(30)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Tag hinzufügen") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("suchen oder neu") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (q.isNotEmpty()) { vm.addTag(matches.firstOrNull { it == q } ?: q); onDone() } }),
                )
                if (q.isNotEmpty() && q !in all && q !in vm.edTags) {
                    TextButton(onClick = { vm.addTag(q); onDone() }) { Text("Neu: #$q") }
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(matches) { t ->
                        Text(
                            "#$t",
                            modifier = Modifier.fillMaxWidth().clickable { vm.addTag(t); onDone() }.padding(vertical = 10.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Fertig") } },
    )
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

/** Markdown table: header bold, equal columns, cells wrap; separator row is skipped. */
@Composable
private fun MdTable(rows: List<String>, c: MdColors, onTap: () -> Unit, onLink: (String) -> Unit, modifier: Modifier) {
    val data = rows.filterIndexed { i, _ -> i != 1 }.map { Markdown.cells(it) }
    val cols = data.maxOf { it.size }
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.border(1.dp, line, RoundedCornerShape(6.dp))) {
        data.forEachIndexed { r, cells ->
            if (r > 0) HorizontalDivider(color = line)
            Row(Modifier.fillMaxWidth().then(if (r == 0) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)) {
                for (ci in 0 until cols) {
                    MdText(
                        Markdown.render(cells.getOrElse(ci) { "" }, c),
                        MaterialTheme.typography.bodyMedium.copy(fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal),
                        onTap, onLink,
                        Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** Read view: rendered lines, tappable checkboxes. */
@Composable
private fun MarkdownView(
    body: String, c: MdColors, onToggle: (Int) -> Unit, onEdit: (Int) -> Unit, onLink: (String) -> Unit, modifier: Modifier,
) {
    val base = MaterialTheme.typography.bodyLarge
    val lines = body.split("\n")
    // table blocks: header row followed by a |---| separator
    val tableAt = mutableMapOf<Int, Int>() // start line -> end (exclusive)
    var k = 0
    while (k < lines.size) {
        if (Markdown.isTableRow(lines[k]) && k + 1 < lines.size && Markdown.TABLE_SEP.matches(lines[k + 1])) {
            var e = k + 2
            while (e < lines.size && Markdown.isTableRow(lines[e])) e++
            tableAt[k] = e; k = e
        } else k++
    }
    Column(modifier) {
        var skipUntil = -1
        lines.forEachIndexed { i, line ->
          if (i < skipUntil) return@forEachIndexed
          val tableEnd = tableAt[i]
          if (tableEnd != null) {
              skipUntil = tableEnd
              val edit = { onEdit(tableEnd - 1) }
              MdTable(lines.subList(i, tableEnd), c, edit, onLink, Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = edit))
              return@forEachIndexed
          }
          val edit = { onEdit(i) }
          Box(Modifier.fillMaxWidth().clickable(onClick = edit)) {
            val task = Markdown.TASK.find(line)
            val head = Markdown.HEADING.find(line)
            val bullet = Markdown.BULLET.find(line)
            val lead = line.takeWhile { it == ' ' || it == '\t' }
            val indent = (lead.count { it == ' ' } + lead.count { it == '\t' } * 4).coerceAtMost(16) * 6
            when {
                task != null -> Row(Modifier.padding(start = indent.dp), verticalAlignment = Alignment.CenterVertically) {
                    val done = task.groupValues[2] != " "
                    Checkbox(checked = done, onCheckedChange = { onToggle(i) })
                    MdText(
                        Markdown.render(line.substring(task.value.length), c),
                        base.copy(
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            color = if (done) c.marker else MaterialTheme.colorScheme.onSurface,
                        ),
                        edit, onLink,
                    )
                }
                head != null -> MdText(
                    Markdown.render(line.substring(head.value.length), c),
                    when (head.groupValues[1].length) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }.copy(fontWeight = FontWeight.Bold),
                    edit, onLink, Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                bullet != null -> Row(Modifier.padding(start = indent.dp, top = 2.dp)) {
                    val mark = bullet.groupValues[1]
                    Text(if (mark[0].isDigit()) "$mark " else "•  ", style = base, color = c.marker)
                    MdText(Markdown.render(line.substring(bullet.value.length), c), base, edit, onLink)
                }
                line.startsWith(">") -> MdText(
                    Markdown.render(line.removePrefix(">").trimStart(), c),
                    base.copy(fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.secondary),
                    edit, onLink, Modifier.padding(start = 12.dp, top = 2.dp),
                )
                line.trim() == "---" -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                line.isBlank() -> Spacer(Modifier.height(10.dp))
                else -> MdText(Markdown.render(line, c), base, edit, onLink)
            }
          }
        }
    }
}

/**
 * Rendered text whose links react to taps; a tap anywhere else calls [onTap].
 * Own gesture handling instead of LinkAnnotation, because the surrounding line/table/view
 * is itself clickable (tap = edit) and must not swallow or race with link taps.
 */
@Composable
private fun MdText(text: AnnotatedString, style: TextStyle, onTap: () -> Unit, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tapNow by rememberUpdatedState(onTap)
    val linkNow by rememberUpdatedState(onLink)
    val hasLinks = remember(text) { text.getStringAnnotations(Markdown.LINK_TAG, 0, text.length).isNotEmpty() }
    val tap = if (!hasLinks) modifier else modifier.pointerInput(text) {
        detectTapGestures { pos ->
            val l = layout
            val line = l?.getLineForVerticalPosition(pos.y)
            val inside = l != null && line != null && pos.x >= l.getLineLeft(line) && pos.x <= l.getLineRight(line)
            val off = if (inside) l!!.getOffsetForPosition(pos) else -1
            val hit = if (off < 0) null else text.getStringAnnotations(Markdown.LINK_TAG, off, off + 1).firstOrNull()
            if (hit != null) linkNow(hit.item) else tapNow()
        }
    }
    Text(text, tap, style = style, onTextLayout = { layout = it })
}

/** Web links open in the browser, everything else is looked up as a note in the vault. */
private fun openLink(ctx: android.content.Context, vm: AppVM, raw: String) {
    when (val t = Markdown.linkTarget(raw)) {
        is Markdown.Target.Web -> try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(ctx, "Keine App für diesen Link", Toast.LENGTH_SHORT).show()
        }
        is Markdown.Target.Note -> if (!vm.openNote(t.name)) Toast.makeText(ctx, "Notiz „${t.name}“ nicht gefunden", Toast.LENGTH_SHORT).show()
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
            LockSettings()
            Text(
                "Vault-Ordner: " + Vault.dir(ctx).path,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            val version = remember {
                runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
                    ?.let { "Version ${it.versionName} (Build ${it.longVersionCode})" }
            }
            if (version != null) Text(
                version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** "4. App-Sperre": set, change or remove the PIN and choose when the app locks. */
@Composable
private fun LockSettings() {
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

@Composable
private fun Permission(label: String, granted: Boolean, request: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text((if (granted) "✓ " else "✗ ") + label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!granted) OutlinedButton(onClick = request) { Text("Erlauben") }
    }
}
