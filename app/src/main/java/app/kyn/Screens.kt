@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.kyn

import android.Manifest
import androidx.compose.foundation.layout.FlowRow
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch

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
                title = { Text(stringResource(R.string.list_title)) },
                actions = {
                    TextButton(onClick = { vm.showTasks() }) { Text("☑", style = MaterialTheme.typography.titleMedium) }
                    TextButton(onClick = { showFolders = true }) { Text("📁", style = MaterialTheme.typography.titleMedium) }
                    if (vm.busy) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.sync() }) { Icon(Icons.Default.Refresh, stringResource(R.string.screens_sync)) }
                    IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.Default.Settings, stringResource(R.string.screens_settings)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.newNote("note") }) { Icon(Icons.Default.Add, stringResource(R.string.list_new_note)) }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                placeholder = { Text(stringResource(R.string.list_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true, shape = RoundedCornerShape(28.dp),
            )
            val conflicts = vm.conflictCount
            if (conflicts > 0 || vm.showConflicts) FilterChip(
                selected = vm.showConflicts,
                onClick = { vm.showConflicts = !vm.showConflicts },
                label = { Text(pluralStringResource(R.plurals.list_conflicts, conflicts, conflicts)) },
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
                item { AssistChip(onClick = { pickTag = true }, label = { Text(stringResource(R.string.list_tags_chip)) }) }
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
    val ctx = LocalContext.current
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
                "📅 " + Dates.dayMonth(ctx, due),
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
                title = { Text(stringResource(R.string.tasks_title)) },
                navigationIcon = {
                    IconButton({ vm.screen = Screen.List }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.screens_back)) }
                },
                actions = {
                    if (vm.busy) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.sync() }) { Icon(Icons.Default.Refresh, stringResource(R.string.screens_sync)) }
                },
            )
        },
    ) { pad ->
        if (groups.isEmpty()) {
            Text(
                stringResource(R.string.tasks_empty), color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(pad).padding(24.dp),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            for ((g, list) in groups) {
                val folded = g == Tasks.Group.NoDate && !showNoDate
                item(key = "h-" + g.name) {
                    val head = stringResource(g.label) + " (${list.size})" + if (g == Tasks.Group.NoDate) (if (folded) "  ▸" else "  ▾") else ""
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
    val ctx = LocalContext.current
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
            val time = t.time?.let { s -> runCatching { Dates.time(ctx, LocalTime.parse(s.padStart(5, '0'))) }.getOrDefault(s) }
            val date = when {
                t.date == null -> null
                g == Tasks.Group.Today -> time
                else -> Dates.weekdayDate(ctx, t.date) + (time?.let { " $it" } ?: "")
            }
            Text(
                listOfNotNull(date, t.recur?.let { "🔁 " + it.label(ctx.resources) }, titleOf(t.path)).joinToString("  ·  "),
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
            Toast.makeText(ctx, ctx.getString(R.string.editor_no_speech), Toast.LENGTH_SHORT).show()
        }
    }
    val pickDue = {
        val today = LocalDate.now()
        val def = runCatching { LocalTime.parse(Prefs.load(ctx).defaultTime.padStart(5, '0')) }.getOrDefault(LocalTime.of(9, 0))
        DatePickerDialog(ctx, { _, y, m, d ->
            val date = LocalDate.of(y, m + 1, d)
            TimePickerDialog(ctx, { _, h, min ->
                vm.edBody = Markdown.setDue(vm.edBody, date, LocalTime.of(h, min))
            }, def.hour, def.minute, android.text.format.DateFormat.is24HourFormat(ctx)).apply {
                setButton(DialogInterface.BUTTON_NEUTRAL, ctx.getString(R.string.editor_due_no_time)) { _, _ ->
                    vm.edBody = Markdown.setDue(vm.edBody, date, null)
                }
            }.show()
        }, today.year, today.monthValue - 1, today.dayOfMonth).show()
    }

    // Keyboard or microphone only once the PIN is entered: opening a note from a widget while
    // locked must not raise the keyboard over the PIN screen or start dictation behind it.
    var openPending by remember(vm.edKey) { mutableStateOf(true) }
    LaunchedEffect(vm.edKey, AppLock.locked) {
        if (AppLock.locked || !openPending) return@LaunchedEffect
        openPending = false
        if (vm.dictateOnOpen) { vm.dictateOnOpen = false; dictate() }
        else if (!vm.edPreview) runCatching { focus.requestFocus() }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.editor_delete_title)) },
        text = { Text(stringResource(R.string.editor_delete_text)) },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteNote() }) { Text(stringResource(R.string.screens_delete)) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.screens_cancel)) } },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton({ vm.closeEditor() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.screens_back)) }
                },
                title = {},
                actions = {
                    TextButton({ vm.togglePin() }) {
                        Text("📌", Modifier.alpha(if (vm.edPinned) 1f else 0.3f))
                    }
                    TextButton({ vm.edPreview = !vm.edPreview }) { Text(stringResource(if (vm.edPreview) R.string.editor_edit else R.string.editor_view)) }
                    IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.screens_delete)) }
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
                        if (vm.edTitle.isEmpty()) Text(stringResource(R.string.editor_title_hint), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
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
                        onEditSecret = { i, t -> vm.replaceSection(i, t) },
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
                                if (vm.edBody.text.isEmpty()) Text(stringResource(R.string.editor_body_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
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
                    Tool("▦") { vm.edBody = Markdown.table(vm.edBody, ctx.getString(R.string.md_table_column)) }
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
            title = { Text(stringResource(if (keep) R.string.conflict_keep_title else R.string.conflict_discard_title)) },
            text = {
                Text(stringResource(if (keep) R.string.conflict_keep_text else R.string.conflict_discard_text, titleOf(original)))
            },
            confirmButton = { TextButton({ confirm = null; vm.resolveConflict(keep) }) { Text(stringResource(if (keep) R.string.conflict_keep else R.string.conflict_discard)) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.screens_cancel)) } },
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.conflict_banner, titleOf(original)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.openConflictOriginal() }) { Text(stringResource(R.string.conflict_original)) }
                TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.conflict_keep_this)) }
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.conflict_discard)) }
            }
        }
    }
}

/** Repeat rule for the task in the current line (Tasks plugin syntax, labels in the app language). */
@Composable
private fun RecurDialog(onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val res = LocalContext.current.resources
    var whenDone by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recur_dialog_title)) },
        text = {
            Column {
                for (r in Recurrence.CHOICES) {
                    val rule = r + if (whenDone) " when done" else ""
                    Text(
                        Recurrence.parse(r)!!.label(res),
                        Modifier.fillMaxWidth().clickable { onPick(rule) }.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Row(Modifier.fillMaxWidth().clickable { whenDone = !whenDone }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = whenDone, onCheckedChange = { whenDone = it })
                    Text(stringResource(R.string.recur_dialog_when_done), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screens_cancel)) } },
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.recur_dialog_none)) } },
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
                (if (open) "▾ " else "▸ ") + if (ps.isNotEmpty()) stringResource(R.string.props_title_count, ps.size) else stringResource(R.string.props_title),
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
                            Text("📅 " + p.value.ifEmpty { stringResource(R.string.props_pick_date) }, Modifier.fillMaxWidth())
                        }
                    } else {
                        var v by remember { mutableStateOf(p.value) }
                        BasicTextField(
                            value = v, onValueChange = { v = it.replace("\n", ""); vm.setProp(p.key, v, p.list) },
                            singleLine = true, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box { if (v.isEmpty()) Text(stringResource(R.string.props_empty), color = MaterialTheme.colorScheme.outline); inner() }
                            },
                        )
                    }
                    TextButton(onClick = { vm.removeProp(p.key) }) { Text("×") }
                }
            }
        }
        TextButton(onClick = { adding = true }) { Text(stringResource(R.string.props_add)) }
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
        title = { Text(stringResource(R.string.move_title)) },
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
                    label = { Text(stringResource(R.string.move_new_folder)) }, placeholder = { Text(stringResource(R.string.move_new_folder_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (Vault.cleanFolder(newFolder).isNotEmpty()) { vm.moveTo(newFolder); onDone() }
                    }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.moveTo(newFolder); onDone() }, enabled = Vault.cleanFolder(newFolder).isNotEmpty()) {
                Text(stringResource(R.string.move_to_new_folder))
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.screens_cancel)) } },
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
        title = { Text(stringResource(R.string.props_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.props_add_name)) },
                    placeholder = { Text(stringResource(R.string.props_add_name_hint)) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !isDate, onClick = { isDate = false; value = "" }, label = { Text(stringResource(R.string.props_add_type_text)) })
                    FilterChip(selected = isDate, onClick = { isDate = true; value = LocalDate.now().toString() }, label = { Text(stringResource(R.string.props_add_type_date)) })
                }
                if (isDate) {
                    OutlinedButton(onClick = { pickDate(ctx, value) { value = it } }, modifier = Modifier.fillMaxWidth()) { Text("📅 $value") }
                } else {
                    OutlinedTextField(value, { value = it }, singleLine = true, label = { Text(stringResource(R.string.props_add_value)) }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, value) }, enabled = cleanKey(name).isNotEmpty()) { Text(stringResource(R.string.props_add_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screens_cancel)) } },
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
        title = { Text(stringResource(R.string.folders_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Text(stringResource(R.string.folders_all), Modifier.fillMaxWidth().clickable { vm.folderFilter = null; onDone() }.padding(vertical = 10.dp),
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
                        if (f !in para) TextButton(onClick = { moving = f }) { Text(stringResource(R.string.folders_move)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.screens_close)) } },
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
        if (!vm.moveFolder(src, parent)) Toast.makeText(ctx, ctx.getString(R.string.folders_move_failed), Toast.LENGTH_SHORT).show()
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.folders_move_title, src.substringAfterLast('/'))) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(targets) { f ->
                        Text(
                            if (f.isEmpty()) stringResource(R.string.folders_top_level) else "📁 " + f.substringAfterLast('/'),
                            Modifier.fillMaxWidth().clickable { go(f) }
                                .padding(start = (if (f.isEmpty()) 0 else (f.count { it == '/' } * 20)).dp, top = 10.dp, bottom = 10.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                OutlinedTextField(
                    custom, { custom = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(stringResource(R.string.folders_new_target)) }, placeholder = { Text(stringResource(R.string.folders_new_target_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { go(custom) }, enabled = Vault.cleanFolder(custom).isNotEmpty()) { Text(stringResource(R.string.folders_move_here)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.screens_cancel)) } },
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
        title = { Text(stringResource(R.string.tags_filter_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.tags_filter_hint)) }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { shown.firstOrNull()?.let { onPick(it.first) } }),
                )
                if (shown.isEmpty()) Text(stringResource(R.string.tags_filter_none), Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outline)
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screens_close)) } },
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
            AssistChip(onClick = { adding = true }, label = { Text(if (vm.edTags.isEmpty()) stringResource(R.string.tags_add_chip) else "＋") })
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
        title = { Text(stringResource(R.string.tags_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.tags_dialog_hint)) }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (q.isNotEmpty()) { vm.addTag(matches.firstOrNull { it == q } ?: q); onDone() } }),
                )
                if (q.isNotEmpty() && q !in all && q !in vm.edTags) {
                    TextButton(onClick = { vm.addTag(q); onDone() }) { Text(stringResource(R.string.tags_dialog_new, q)) }
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
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.tags_dialog_done)) } },
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
    val storage = remember(tick, vm.busy) { Prefs.load(ctx).storage }
    var askStorage by remember { mutableStateOf<Storage?>(null) }
    val notif = remember(tick) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.tick++ }
    val ready = remember(tick, vm.busy) { vm.ready() }
    BackHandler(enabled = ready) { vm.screen = Screen.List }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    if (ready) IconButton({ vm.screen = Screen.List }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.screens_back)) }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!ready) Text(
                stringResource(R.string.settings_intro),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.settings_storage_section), style = MaterialTheme.typography.titleMedium)
            StorageChoice(
                Storage.Shared, storage, stringResource(R.string.settings_storage_shared_title),
                stringResource(R.string.settings_storage_shared_detail, folder.ifBlank { Prefs.defaultFolder(ctx) }),
                enabled = !vm.busy,
            ) { askStorage = it }
            StorageChoice(
                Storage.Private, storage, stringResource(R.string.settings_storage_private_title),
                stringResource(R.string.settings_storage_private_detail),
                enabled = !vm.busy,
            ) { askStorage = it }
            val leftover = remember(tick, vm.busy) { Vault.leftoverShared(ctx) }
            var askLeftover by remember { mutableStateOf(false) }
            if (leftover != null) {
                Text(
                    stringResource(R.string.settings_leftover_warning, leftover.path),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { askLeftover = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_leftover_delete))
                }
                if (askLeftover) {
                    val files = remember(leftover) { leftover.walkTopDown().count { it.isFile } }
                    AlertDialog(
                        onDismissRequest = { askLeftover = false },
                        title = { Text(stringResource(R.string.settings_leftover_delete_title)) },
                        text = { Text(pluralStringResource(R.plurals.settings_leftover_delete_text, files, leftover.path, files)) },
                        confirmButton = { TextButton({ askLeftover = false; vm.deleteLeftover() }) { Text(stringResource(R.string.screens_delete)) } },
                        dismissButton = { TextButton({ askLeftover = false }) { Text(stringResource(R.string.screens_cancel)) } },
                    )
                }
            }
            if (storage == Storage.Shared) {
                Permission(stringResource(R.string.settings_perm_files), files) {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    )
                }
                if (!files) Text(
                    stringResource(R.string.settings_perm_files_needed),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
            Permission(stringResource(R.string.settings_perm_notifications), notif) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            Text(stringResource(R.string.settings_github_section), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_github_intro),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            val urlProblem = repoUrlProblem(url)
            OutlinedTextField(
                url, { url = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.settings_repo_url)) },
                placeholder = { Text("https://github.com/you/notes.git") },
                isError = urlProblem != null,
                supportingText = { Text(stringResource(urlProblem ?: R.string.settings_repo_url_help)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(if (hasToken) R.string.settings_token_saved else R.string.settings_token)) },
                supportingText = { Text(stringResource(R.string.settings_token_help)) },
                visualTransformation = PasswordVisualTransformation(),
            )
            if (storage == Storage.Shared) OutlinedTextField(folder, { folder = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.settings_folder_name)) })
            OutlinedTextField(
                name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.settings_commit_name)) },
                supportingText = { Text(stringResource(R.string.settings_commit_help)) },
            )
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.settings_commit_email)) })

            Text(stringResource(R.string.settings_reminders_section), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(time, { time = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.settings_default_time)) })

            val missing = buildList {
                if (storage == Storage.Shared && !files) add(stringResource(R.string.settings_missing_files))
                if (url.isBlank() || urlProblem != null) add(stringResource(R.string.settings_missing_url))
                if (token.isBlank() && !hasToken) add(stringResource(R.string.settings_missing_token))
            }
            if (missing.isNotEmpty() && !vm.busy) Text(
                stringResource(R.string.settings_missing, missing.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
            Button(
                enabled = !vm.busy && missing.isEmpty(),
                onClick = {
                    vm.setup(Prefs.load(ctx).copy(repoUrl = url, folder = folder, name = name, email = email, defaultTime = time), token)
                    token = ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (ready) R.string.settings_save_sync else R.string.settings_save_clone)) }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.status.isNotBlank()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
            LockSettings()
            Text(stringResource(R.string.settings_appearance_section), style = MaterialTheme.typography.titleMedium)
            var theme by remember { mutableStateOf(ThemeMode.load(ctx)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in ThemeMode.entries) FilterChip(
                    selected = theme == m,
                    onClick = { theme = m; ThemeMode.save(ctx, m) },
                    label = { Text(stringResource(m.label)) },
                )
            }
            Text(stringResource(R.string.settings_font_size), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in FontSize.entries) FilterChip(
                    selected = FontSize.current == f,
                    onClick = { FontSize.save(ctx, f) },
                    label = { Text(stringResource(f.label)) },
                )
            }
            LanguageSettings(number = 6)
            askStorage?.let { to ->
                val cloned = remember { GitSync.isCloned(ctx) }
                AlertDialog(
                    onDismissRequest = { askStorage = null },
                    title = { Text(stringResource(if (to == Storage.Private) R.string.settings_storage_private_ask else R.string.settings_storage_shared_ask)) },
                    text = {
                        Text(
                            when {
                                !cloned -> stringResource(R.string.settings_storage_not_cloned)
                                to == Storage.Private -> stringResource(R.string.settings_storage_to_private)
                                else -> stringResource(
                                    if (files) R.string.settings_storage_to_shared else R.string.settings_storage_to_shared_needs_files,
                                    folder.ifBlank { Prefs.defaultFolder(ctx) },
                                )
                            },
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = to == Storage.Private || files,
                            onClick = {
                                if (folder.isNotBlank() && folder != Prefs.load(ctx).folder) Prefs.save(ctx, Prefs.load(ctx).copy(folder = folder))
                                vm.changeStorage(to)
                                askStorage = null
                            },
                        ) { Text(stringResource(if (cloned) R.string.settings_storage_move else R.string.settings_storage_apply)) }
                    },
                    dismissButton = { TextButton({ askStorage = null }) { Text(stringResource(R.string.screens_cancel)) } },
                )
            }
            Text(
                stringResource(R.string.settings_vault_dir, Vault.dir(ctx).path),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            val version = remember {
                runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
                    ?.let { ctx.getString(R.string.settings_version, it.versionName, it.longVersionCode) }
            }
            if (version != null) Text(
                version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Why [url] cannot be the notes repository, or null if it looks fine (empty counts as fine, see "missing"). */
@androidx.annotation.StringRes
private fun repoUrlProblem(raw: String): Int? = raw.trim().let { url -> when {
    url.isBlank() -> null
    // JGit here has no SSH keys; https (or http/git for own servers) works
    url.startsWith("git@") || url.startsWith("ssh://") -> R.string.settings_repo_url_https
    // a token in the URL would end up in .git/config (readable by other apps in shared storage)
    GitSync.withoutCredentials(url) != url -> R.string.settings_repo_url_credentials
    Regex("""github\.com/[^/]+/(keep-your-notes|kyn|notizen-app)(\.git)?/?$""", RegexOption.IGNORE_CASE).containsMatchIn(url) ->
        R.string.settings_repo_url_is_app
    else -> null
} }

@Composable
private fun StorageChoice(
    value: Storage, current: Storage, title: String, detail: String, enabled: Boolean, onPick: (Storage) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled && value != current) { onPick(value) },
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = value == current, onClick = { onPick(value) }, enabled = enabled && value != current)
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Permission(label: String, granted: Boolean, request: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text((if (granted) "✓ " else "✗ ") + label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!granted) OutlinedButton(onClick = request) { Text(stringResource(R.string.settings_perm_allow)) }
    }
}
