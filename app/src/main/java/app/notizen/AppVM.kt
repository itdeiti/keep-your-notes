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
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { List, Edit, Settings }

class AppVM(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var screen by mutableStateOf(Screen.List)
    var notes by mutableStateOf(listOf<Note>())
    var query by mutableStateOf("")
    var tag by mutableStateOf<String?>(null)
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
    private var edFolder = "inbox"
    private var edSaved = ""

    val edPinned get() = isPinned(edFront)

    init {
        if (!ready()) screen = Screen.Settings
        reload()
    }

    fun ready() = Prefs.load(ctx).repoUrl.isNotBlank() &&
        Environment.isExternalStorageManager() && GitSync.isCloned(ctx)

    fun filtered(): List<Note> {
        val q = query.trim()
        return notes.filter { n ->
            (tag == null || tag in n.tags) &&
                (q.isEmpty() || n.title.contains(q, true) || n.body.contains(q, true))
        }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.modified })
    }

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
            i.hasExtra("new") -> newNote(i.getStringExtra("new") ?: "note")
            i.hasExtra("open") -> openPath(i.getStringExtra("open")!!)
            i.hasExtra("edit") -> openPath(i.getStringExtra("edit")!!, edit = true)
            else -> return
        }
        // Consume, so a configuration change does not replay it.
        i.removeExtra("new"); i.removeExtra("open"); i.removeExtra("edit"); i.action = null
    }

    // ---------- editor ----------

    private fun snapshot() = edTitle + "\u0000" + Vault.serialize(edFront, edBody.text)

    fun open(n: Note, edit: Boolean = false) {
        saveEditor()
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

    private fun openPath(path: String, edit: Boolean = false) {
        val root = Vault.dir(ctx)
        val f = File(root, path)
        if (f.exists()) open(Vault.read(root, f), edit)
    }

    fun newNote(kind: String, text: String = "", title: String = "", folder: String = "inbox", front: List<String> = emptyList()) {
        saveEditor()
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
        screen = Screen.List
        reload()
    }

    fun deleteNote() {
        val path = edPath
        edPath = null; edTitle = ""; edBody = TextFieldValue("")
        screen = Screen.List
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

    fun togglePin() {
        edFront = if (edPinned) edFront.filter { it.trim() != "pinned: true" } else edFront + "pinned: true"
    }

    fun toggleTask(line: Int) {
        edBody = edBody.copy(text = Markdown.toggleTaskLine(edBody.text, line))
        saveEditor()
    }
}
