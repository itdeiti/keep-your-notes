package app.kyn

import android.appwidget.AppWidgetManager
import androidx.annotation.StringRes
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What one list widget shows. Empty selections = no restriction. Folders, tags and single notes
 * are combined with OR: a note is selected if it lies in a chosen folder (or below), has a chosen tag,
 * or was picked by name. [exclude] turns the selection into a block list: everything except it.
 */
data class WidgetFilter(
    val title: String = "",
    val folders: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val notes: Set<String> = emptySet(),
    val content: Content = Content.All,
    val exclude: Boolean = false,
) {
    enum class Content(@StringRes val label: Int) {
        All(R.string.widget_content_all), Tasks(R.string.widget_content_tasks),
        OpenTasks(R.string.widget_content_open_tasks), Titles(R.string.widget_content_titles),
    }

    val restricted get() = folders.isNotEmpty() || tags.isNotEmpty() || notes.isNotEmpty()

    fun matches(n: Note): Boolean = !restricted || selected(n) != exclude

    private fun selected(n: Note): Boolean =
        n.path in notes ||
            folders.any { f -> if (f.isEmpty()) n.folder.isEmpty() else n.folder == f || n.folder.startsWith("$f/") } ||
            n.tags.any { it in tags }

    /** Only-tasks modes drop notes that have nothing to show. */
    fun keep(n: Note): Boolean = when (content) {
        Content.All, Content.Titles -> true
        Content.Tasks -> n.body.lineSequence().any { Markdown.TASK.containsMatchIn(it) }
        Content.OpenTasks -> n.body.lineSequence().any { Markdown.TASK.find(it)?.groupValues?.get(2) == " " }
    }

    fun apply(all: List<Note>): List<Note> = all.filter { matches(it) && keep(it) }

    companion object {
        /** Folder value for notes directly in the vault root. */
        const val ROOT = ""

        private fun sp(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

        fun load(ctx: Context, id: Int): WidgetFilter {
            val p = sp(ctx)
            return WidgetFilter(
                title = p.getString("title_$id", "")!!,
                folders = p.getStringSet("folders_$id", emptySet())!!.toSet(),
                tags = p.getStringSet("tags_$id", emptySet())!!.toSet(),
                notes = p.getStringSet("notes_$id", emptySet())!!.toSet(),
                content = runCatching { Content.valueOf(p.getString("content_$id", "All")!!) }.getOrDefault(Content.All),
                exclude = p.getBoolean("exclude_$id", false),
            )
        }

        fun save(ctx: Context, id: Int, f: WidgetFilter) {
            sp(ctx).edit()
                .putString("title_$id", f.title.trim())
                .putStringSet("folders_$id", f.folders)
                .putStringSet("tags_$id", f.tags)
                .putStringSet("notes_$id", f.notes)
                .putString("content_$id", f.content.name)
                .putBoolean("exclude_$id", f.exclude)
                .commit() // the widget update right after reads it
        }

        fun delete(ctx: Context, ids: IntArray) {
            val e = sp(ctx).edit()
            for (id in ids) for (k in listOf("title", "folders", "tags", "notes", "content", "exclude")) e.remove("${k}_$id")
            e.apply()
        }
    }
}

/**
 * Opens when a "Notizliste" widget is placed, and again via long-press → widget settings.
 * Started by the launcher; the result tells it whether to keep the new widget.
 */
class WidgetConfigActivity : LockedActivity() {
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        // exported for the launcher: only accept ids of our own list widgets
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider
        val tasks = provider == ComponentName(this, TasksWidget::class.java)
        if (!tasks && provider != ComponentName(this, NotesWidget::class.java)) { finish(); return }
        enableEdgeToEdge()
        if (!AppLock.enabled(this)) AppLock.locked = false
        val start = WidgetFilter.load(this, id)
        val app = applicationContext
        setContent {
            // reading the whole vault can take a moment: off the main thread
            val vault by produceState<Pair<List<Note>, List<String>>?>(null) {
                value = withContext(Dispatchers.IO) {
                    if (Vault.accessible(app) && GitSync.isCloned(app)) Vault.all(app) to Vault.folders(Vault.dir(app))
                    else emptyList<Note>() to emptyList()
                }
            }
            KynTheme {
                // note titles are shown here, so the PIN applies like in the app
                LockGate(onBack = ::finish) {
                    Surface {
                        val v = vault
                        if (v == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else WidgetConfigScreen(start, v.first, v.second, tasks, onSave = ::save, onCancel = ::finish)
                    }
                }
            }
        }
    }

    private fun save(f: WidgetFilter) {
        WidgetFilter.save(this, id, f)
        val app = applicationContext
        App.scope.launch { NotesWidget.refresh(app) }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WidgetConfigScreen(
    start: WidgetFilter, notes: List<Note>, folders: List<String>, tasks: Boolean,
    onSave: (WidgetFilter) -> Unit, onCancel: () -> Unit,
) {
    var f by remember { mutableStateOf(start) }
    var search by remember { mutableStateOf("") }
    val tags = remember(notes) {
        notes.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }
    val hasRoot = remember(notes) { notes.any { it.folder.isEmpty() } }
    val folderChoices = (if (hasRoot) listOf(WidgetFilter.ROOT) else emptyList()) + folders
    // picked notes first, then by title
    val noteChoices = remember(notes, search, f.notes) {
        notes.filter { search.isBlank() || it.title.contains(search.trim(), ignoreCase = true) || it.path in f.notes }
            .sortedWith(compareByDescending<Note> { it.path in f.notes }.thenBy { it.title.lowercase() })
    }
    // task list: count open tasks, the content modes do not apply
    val count = remember(f, notes) { if (tasks) Tasks.open(f.apply(notes)).size else f.apply(notes).size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (tasks) R.string.widget_tasks_config_label else R.string.widgetcfg_title)) },
                navigationIcon = { IconButton(onCancel) { Icon(Icons.Default.Close, stringResource(R.string.widgetcfg_cancel)) } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            when {
                                tasks && f.restricted -> R.string.widgetcfg_selection_tasks
                                tasks -> R.string.widgetcfg_all_tasks
                                f.restricted -> R.string.widgetcfg_selection_count
                                else -> R.string.widgetcfg_all_count
                            },
                            count,
                        ),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    )
                    if (f.restricted) TextButton({ f = f.copy(folders = emptySet(), tags = emptySet(), notes = emptySet()) }) { Text(stringResource(R.string.widgetcfg_reset)) }
                    Button({ onSave(f) }) { Text(stringResource(R.string.widgetcfg_save)) }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                OutlinedTextField(
                    f.title, { f = f.copy(title = it) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.widgetcfg_widget_title)) }, placeholder = { Text(stringResource(if (tasks) R.string.widget_tasks_title else R.string.widget_list_title)) },
                )
                if (!tasks) {
                    Section(stringResource(R.string.widgetcfg_content))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in WidgetFilter.Content.entries) FilterChip(
                            selected = f.content == c, onClick = { f = f.copy(content = c) }, label = { Text(stringResource(c.label)) },
                        )
                    }
                }
                Text(
                    stringResource(if (tasks) R.string.widgetcfg_hint_tasks else R.string.widgetcfg_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Section(stringResource(R.string.widgetcfg_mode))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!f.exclude, { f = f.copy(exclude = false) }, label = { Text(stringResource(R.string.widgetcfg_mode_include)) })
                    FilterChip(f.exclude, { f = f.copy(exclude = true) }, label = { Text(stringResource(R.string.widgetcfg_mode_exclude)) })
                }
                if (notes.isEmpty()) Text(
                    stringResource(R.string.widgetcfg_no_vault),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (folderChoices.isNotEmpty()) {
                item { Section(stringResource(R.string.widgetcfg_folders)) }
                items(folderChoices, key = { "f:$it" }) { folder ->
                    val depth = folder.count { it == '/' }
                    CheckRow(
                        if (folder == WidgetFilter.ROOT) stringResource(R.string.widgetcfg_root_folder) else folder.substringAfterLast('/'),
                        folder in f.folders, Modifier.padding(start = (depth * 20).dp),
                    ) { on -> f = f.copy(folders = if (on) f.folders + folder else f.folders - folder) }
                }
            }
            if (tags.isNotEmpty()) item {
                Section(stringResource(R.string.widgetcfg_tags))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in tags) FilterChip(
                        selected = t in f.tags,
                        onClick = { f = f.copy(tags = if (t in f.tags) f.tags - t else f.tags + t) },
                        label = { Text("#$t") },
                    )
                }
            }
            if (notes.isNotEmpty()) {
                item {
                    Section(stringResource(R.string.widgetcfg_single_notes))
                    OutlinedTextField(
                        search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text(stringResource(R.string.widgetcfg_search)) },
                    )
                }
                items(noteChoices, key = { "n:${it.path}" }) { n ->
                    CheckRow(n.title, n.path in f.notes, sub = n.folder.ifEmpty { null }) { on ->
                        f = f.copy(notes = if (on) f.notes + n.path else f.notes - n.path)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
private fun CheckRow(label: String, checked: Boolean, modifier: Modifier = Modifier, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange)
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
    }
}
