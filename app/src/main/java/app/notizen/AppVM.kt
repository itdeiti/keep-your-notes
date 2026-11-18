package app.notizen

import android.app.Application
import android.content.Intent
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { List, Tasks, Edit, Settings }

class AppVM(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var screen by mutableStateOf(Screen.List)
    var notes by mutableStateOf(listOf<Note>())
    var query by mutableStateOf("")
    var tag by mutableStateOf<String?>(null)
    /** List shows only conflict copies (chip "⚠ Konflikte", or the conflict notification). */
    var showConflicts by mutableStateOf(false)
    var folderFilter by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var tick by mutableIntStateOf(0)

    // editor
    var edPath by mutableStateOf<String?>(null)
    var edTitle by mutableStateOf("")
    var edBody by mutableStateOf(TextFieldValue(""))
    var edFront by mutableStateOf(listOf<String>())
    var edPreview by mutableStateOf(false)
    var edKey by mutableIntStateOf(0)
    var dictateOnOpen by mutableStateOf(false)
    private var clipKey by mutableIntStateOf(-1)
    val clipping get() = clipKey == edKey
    private var edFolder by mutableStateOf("inbox")
    private var edSaved = ""

    val edPinned get() = isPinned(edFront)

    /** Where the editor goes back to. */
    private var returnTo = Screen.List

    // tasks screen: ticked here, kept visible (struck through) until the screen is left
    var justDone by mutableStateOf(mapOf<String, Tasks.Item>())
    private val taskLock = Mutex()

    init {
        if (!ready()) screen = Screen.Settings
        reload()
    }

    fun ready() = Prefs.load(ctx).repoUrl.isNotBlank() &&
        Environment.isExternalStorageManager() && GitSync.isCloned(ctx)

    /** "#ko" in the search field filters by tags containing "ko"; anything else searches title and text. */
    val tagQuery: String? get() = query.trim().takeIf { it.startsWith("#") }?.let { cleanTag(it) }

    fun filtered(): List<Note> =
        if (showConflicts) notes.filter { Conflicts.isCopy(it.path) } else filterNotes(notes, tag, query, folderFilter)

    val conflictCount: Int get() = notes.count { Conflicts.isCopy(it.path) }

    /** Folder -> number of notes in it and below. */
    fun folderCounts(): List<Pair<String, Int>> {
        val folders = Vault.folders(Vault.dir(ctx))
        return folders.map { f -> f to notes.count { it.folder == f || it.folder.startsWith("$f/") } }
    }

    /** Move a whole folder into [parent]; returns false if not allowed. */
    fun moveFolder(src: String, parent: String): Boolean {
        val target = Vault.moveFolder(Vault.dir(ctx), src, parent) ?: return false
        if (folderFilter == src) folderFilter = target
        viewModelScope.launch {
            GitSync.commit(ctx, "Ordner verschoben: $src -> $target")
            withContext(Dispatchers.IO) { Reminders.rescan(ctx); NotesWidget.refresh(ctx) }
            SyncWorker.enqueue(ctx)
            reload()
        }
        return true
    }

    /** All tags with how many notes use them, most used first. */
    fun tagCounts(): List<Pair<String, Int>> = notes.flatMap { it.tags }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key to it.value }

    fun reload() {
        viewModelScope.launch {
            notes = withContext(Dispatchers.IO) {
                if (Environment.isExternalStorageManager()) Vault.all(ctx).also { NotesWidget.refresh(ctx) } else emptyList()
            }
            status = Status.get(ctx)
        }
    }

    fun sync() {
        if (busy || !ready()) return
        viewModelScope.launch {
            busy = true
            try {
                GitSync.sync(ctx)
                withContext(Dispatchers.IO) { Reminders.rescan(ctx) }
            } catch (_: Exception) {
                SyncWorker.enqueue(ctx) // retry when the network is back
            } finally {
                busy = false
                reload()
            }
        }
    }

    fun showTasks() {
        justDone = emptyMap()
        screen = Screen.Tasks
    }

    /** Open tasks (minus the ones just ticked) plus the ticked ones, so a tick can be undone. */
    fun taskItems(): List<Tasks.Item> = Tasks.open(notes).filter { it.key !in justDone } + justDone.values

    /** Tick an open task or untick one ticked on this screen; writes, commits, syncs later. */
    fun toggleTask(item: Tasks.Item) {
        if (item.done) justDone = justDone.filterValues { it != item }
        else justDone = justDone + (item.key to item.copy(line = Tasks.toggled(item.line), done = true))
        viewModelScope.launch {
            taskLock.withLock { withContext(Dispatchers.IO) { Reminders.toggle(ctx, item.path, item.line) } }
            reload()
        }
    }

    fun openTask(item: Tasks.Item) = openPath(item.path)

    fun onResume() {
        tick++
        if (ready() && screen != Screen.Edit) sync()
    }

    fun setup(p: Prefs, token: String) {
        Prefs.save(ctx, p)
        if (token.isNotBlank()) Secrets.put(ctx, token.trim())
        viewModelScope.launch {
            busy = true
            Status.set(ctx, "Klone …")
            status = Status.get(ctx)
            try {
                GitSync.cloneOrOpen(ctx)
                GitSync.sync(ctx)
                withContext(Dispatchers.IO) { Reminders.rescan(ctx) }
                screen = Screen.List
            } catch (e: Exception) {
                Status.set(ctx, "⚠ ${GitSync.explain(e)}")
            } finally {
                busy = false
                reload()
            }
        }
    }

    fun handleIntent(i: Intent?) {
        if (i == null || !ready()) return
        when {
            i.action == Intent.ACTION_SEND -> clip(
                i.getStringExtra(Intent.EXTRA_TEXT).orEmpty(), i.getStringExtra(Intent.EXTRA_SUBJECT), false,
            )
            i.action == Intent.ACTION_PROCESS_TEXT -> clip(
                i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(), null, true,
            )
            i.hasExtra("conflicts") -> { saveEditor(); showConflicts = true; screen = Screen.List; reload() }
            i.hasExtra("new") -> newNote(i.getStringExtra("new") ?: "note")
            i.hasExtra("open") -> openPath(i.getStringExtra("open")!!)
            i.hasExtra("edit") -> openPath(i.getStringExtra("edit")!!, edit = true)
            else -> return
        }
        // Consume, so a configuration change does not replay it.
        i.removeExtra("new"); i.removeExtra("open"); i.removeExtra("edit"); i.removeExtra("conflicts"); i.action = null
    }

    // ---------- editor ----------

    private fun snapshot() = edTitle + "\u0000" + Vault.serialize(edFront, edBody.text)

    fun open(n: Note, edit: Boolean = false) {
        saveEditor()
        if (screen != Screen.Edit) returnTo = if (screen == Screen.Tasks) Screen.Tasks else Screen.List
        edPath = n.path
        edTitle = n.title
        edFront = n.front
        edBody = TextFieldValue(n.body, TextRange(n.body.trimEnd().length))
        edPreview = !edit && n.body.isNotBlank()
        edSaved = snapshot()
        dictateOnOpen = false
        edKey++
        screen = Screen.Edit
    }

    /** Follow a [[wiki link]] or relative Markdown link: path first, then title (case-insensitive). */
    fun openNote(name: String): Boolean {
        val key = name.trim().trimStart('/').lowercase()
        if (key.isEmpty()) return false
        val n = notes.firstOrNull { it.path.removeSuffix(".md").lowercase() == key }
            ?: notes.firstOrNull { it.path.removeSuffix(".md").lowercase().endsWith("/$key") }
            ?: notes.firstOrNull { it.title.lowercase() == key.substringAfterLast('/') }
            ?: return false
        open(n)
        return true
    }

    private fun openPath(path: String, edit: Boolean = false) {
        val root = Vault.dir(ctx)
        val f = File(root, path)
        if (f.exists()) open(Vault.read(root, f), edit)
    }

    fun newNote(kind: String, text: String = "", title: String = "", folder: String = "inbox", front: List<String> = emptyList()) {
        saveEditor()
        if (screen != Screen.Edit) returnTo = Screen.List
        edPath = null
        edTitle = title
        edFolder = folder
        edFront = listOf("created: ${Vault.nowIso()}") + front
        val body = if (kind == "list") "- [ ] " else text
        edBody = TextFieldValue(body, TextRange(body.length))
        edPreview = false
        edSaved = if (text.isNotBlank()) "" else snapshot()
        dictateOnOpen = kind == "dictate"
        edKey++
        screen = Screen.Edit
    }

    /** Write the file and commit locally; push happens in the background. */
    fun saveEditor() {
        if (screen != Screen.Edit || clipping || snapshot() == edSaved) return
        val body = edBody.text
        val title = edTitle.trim()
        if (edPath == null && title.isBlank() && body.isBlank()) return
        val root = Vault.dir(ctx)
        var path = edPath
        if (path == null) {
            val first = body.lineSequence().map { Markdown.stripTaskMeta(it.replace(Markdown.LIST, "").trimStart('#', ' ')) }
                .firstOrNull { it.isNotBlank() }.orEmpty().take(40)
            path = Vault.uniquePath(root, edFolder, title.ifBlank { first })
        } else if (title.isNotBlank() && title != titleOf(path)) {
            val np = Vault.uniquePath(root, path.substringBeforeLast('/', ""), title)
            if (File(root, path).renameTo(File(root, np))) path = np
        }
        Vault.write(root, path, edFront, body)
        edPath = path
        edTitle = titleOf(path)
        edSaved = snapshot()
        val msg = "Notiz: ${titleOf(path)}"
        viewModelScope.launch {
            GitSync.commit(ctx, msg)
            withContext(Dispatchers.IO) { Reminders.rescan(ctx); NotesWidget.refresh(ctx) }
            SyncWorker.enqueue(ctx)
        }
    }

    /** Shared text or link → Markdown note in Clippings/. A bare link loads the whole page. */
    private fun clip(text: String, subject: String?, selection: Boolean) {
        if (text.isBlank()) return
        when (val share = Clipper.parseShare(text, subject, selection)) {
            is Clipper.Share.Plain -> newNote("note", text = share.text, title = share.title.orEmpty())
            is Clipper.Share.Quote -> showClip(Clipper.quote(share))
            is Clipper.Share.Page -> {
                newNote("note", title = share.title.orEmpty(), folder = Clipper.FOLDER)
                edBody = TextFieldValue("Lade ${share.url} …")
                edPreview = true
                val key = edKey
                clipKey = key
                viewModelScope.launch {
                    val c = withContext(Dispatchers.IO) {
                        try { Clipper.page(share) } catch (e: Exception) { Clipper.failed(share, e) }
                    }
                    clipKey = -1
                    if (edKey == key && screen == Screen.Edit) {
                        edTitle = c.title
                        edFront = edFront + c.front
                        edBody = TextFieldValue(c.body)
                        edSaved = ""
                        saveEditor()
                    } else { // user left meanwhile: save straight to disk
                        val root = Vault.dir(ctx)
                        Vault.write(root, Vault.uniquePath(root, Clipper.FOLDER, c.title), listOf("created: ${Vault.nowIso()}") + c.front, c.body)
                        GitSync.commit(ctx, "Clip: ${c.title}")
                        SyncWorker.enqueue(ctx)
                        reload()
                    }
                }
            }
        }
    }

    private fun showClip(c: Clipper.Clip) {
        newNote("note", text = c.body, title = c.title, folder = Clipper.FOLDER, front = c.front)
        edPreview = true
    }

    fun closeEditor() {
        saveEditor()
        screen = returnTo
        reload()
    }

    /** Original of the open note if it is a conflict copy, else null. */
    val edConflictOf: String? get() = edPath?.let { Conflicts.original(it) }

    fun openConflictOriginal() {
        val orig = edConflictOf ?: return
        if (File(Vault.dir(ctx), orig).exists()) openPath(orig)
    }

    /** keep = phone version (copy replaces the original), else server version (copy is deleted). */
    fun resolveConflict(keep: Boolean) {
        saveEditor()
        val copy = edPath ?: return
        val orig = Conflicts.original(copy) ?: return
        val root = Vault.dir(ctx)
        val ok = if (keep) Conflicts.keepCopy(root, copy) != null else Conflicts.discardCopy(root, copy)
        if (!ok) return
        val msg = "Konflikt gelöst: ${titleOf(orig)} (" + (if (keep) "Handy-Version" else "Server-Version") + ")"
        viewModelScope.launch {
            GitSync.commit(ctx, msg)
            withContext(Dispatchers.IO) { Reminders.rescan(ctx); NotesWidget.refresh(ctx) }
            SyncWorker.enqueue(ctx)
        }
        edPath = null
        if (File(root, orig).exists()) openPath(orig) else { screen = returnTo; reload() }
        if (conflictCount <= 1) showConflicts = false
    }

    fun deleteNote() {
        val path = edPath
        edPath = null; edTitle = ""; edBody = TextFieldValue("")
        screen = returnTo
        if (path != null) {
            File(Vault.dir(ctx), path).delete()
            viewModelScope.launch {
                GitSync.commit(ctx, "Gelöscht: ${titleOf(path)}")
                withContext(Dispatchers.IO) { Reminders.rescan(ctx) }
                SyncWorker.enqueue(ctx)
            }
        }
        reload()
    }

    /** Leave the read view with the cursor at the end of the tapped line (null = end of note). */
    fun editAt(line: Int?) {
        val t = edBody.text
        val pos = if (line == null) t.trimEnd().length
        else t.split("\n").take(line + 1).sumOf { it.length + 1 } - 1
        edBody = edBody.copy(selection = TextRange(pos.coerceIn(0, t.length)))
        edPreview = false
        edKey++
    }

    val edTags: List<String> get() = frontTags(edFront).toList()

    /** All tags in the vault, most used first (frontmatter and #tags in text). */
    fun allTags(): List<String> = notes.flatMap { it.tags }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }

    fun addTag(raw: String) {
        val t = cleanTag(raw)
        if (t.isEmpty() || t in edTags) return
        edFront = withTags(edFront, edTags + t)
    }

    fun removeTag(t: String) {
        edFront = withTags(edFront, edTags - t)
    }

    /** Frontmatter properties shown in "Eigenschaften" (tags and pinned have their own controls). */
    val edProps: List<Prop> get() = props(edFront).filter { it.key != "tags" && it.key != "pinned" }

    fun setProp(key: String, value: String, list: Boolean = false) {
        val k = cleanKey(key)
        if (k.isNotEmpty()) edFront = withProp(edFront, k, value, list)
    }

    fun removeProp(key: String) {
        edFront = withProp(edFront, key, null)
    }

    /** Folder of the open note ("" = vault root). */
    val edFolderNow: String get() = edPath?.substringBeforeLast('/', "") ?: edFolder

    fun folders(): List<String> = Vault.folders(Vault.dir(ctx))

    /** Move the open note into [folder] (created if missing), commit and sync. */
    fun moveTo(folder: String) {
        val f = Vault.cleanFolder(folder)
        if (f == edFolderNow) return
        if (edPath == null) { edFolder = f; return } // not saved yet: will be created there
        saveEditor()
        val root = Vault.dir(ctx)
        val old = edPath ?: return
        val np = Vault.uniquePath(root, f, titleOf(old))
        File(root, np).parentFile?.mkdirs()
        if (!File(root, old).renameTo(File(root, np))) return
        edPath = np
        edSaved = snapshot()
        val msg = "Verschoben: ${titleOf(np)} -> ${f.ifEmpty { "/" }}"
        viewModelScope.launch {
            GitSync.commit(ctx, msg)
            withContext(Dispatchers.IO) { Reminders.rescan(ctx); NotesWidget.refresh(ctx) }
            SyncWorker.enqueue(ctx)
        }
    }

    fun togglePin() {
        edFront = if (edPinned) edFront.filter { it.trim() != "pinned: true" } else edFront + "pinned: true"
    }

    fun toggleTask(line: Int) {
        edBody = edBody.copy(text = Markdown.toggleTaskLine(edBody.text, line))
        saveEditor()
    }
}
