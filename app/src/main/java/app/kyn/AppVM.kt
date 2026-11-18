package app.kyn

import android.app.Application
import android.content.Intent
import android.widget.Toast
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

enum class Screen { List, Tasks, Edit, Secret, Settings }

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
    /** File time when the note was loaded or last saved: detects writes from the widget, sync or notifications. */
    private var edMtime = 0L

    val edPinned get() = isPinned(edFront)

    /** Encrypted note on [Screen.Secret]; its plaintext lives only in [SecretSession] and [secretDraft]. */
    var secretNote by mutableStateOf<Note?>(null)
        private set
    /** Plaintext being edited (memory only, never saved state); null = not editing. Dropped on lock. */
    var secretDraft by mutableStateOf<String?>(null)
    var secretSaving by mutableStateOf(false)
        private set
    /** Bumped after a save, so the screen reloads the (new) ciphertext. */
    var secretRev by mutableIntStateOf(0)
        private set
    private var secretBase: MeldCrypto.Blob? = null
    private var secretHint = ""
    private var secretSaved: String? = null
    private var secretMtime = 0L
    private val secretLock = Mutex()
    private val dropDrafts: () -> Unit = { secretDraft = null; secretBase = null; secretSaved = null }

    /** Where the editor goes back to. */
    private var returnTo = Screen.List

    // tasks screen: ticked here, kept visible (struck through) until the screen is left
    var justDone by mutableStateOf(mapOf<String, Tasks.Item>())
    private val taskLock = Mutex()

    init {
        if (!ready()) screen = Screen.Settings
        SecretSession.onLocked = dropDrafts
        reload()
    }

    override fun onCleared() {
        if (SecretSession.onLocked === dropDrafts) SecretSession.onLocked = null
    }

    fun ready() = Prefs.load(ctx).repoUrl.isNotBlank() &&
        Vault.accessible(ctx) && GitSync.isCloned(ctx)

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
        afterChange(ctx.getString(R.string.commit_folder_moved, src, target)) { reload() }
        return true
    }

    /**
     * After the vault changed on disk: commit locally, refresh alarms and widgets, push in the background.
     * [then] runs on the main thread when that is done.
     */
    private fun afterChange(message: String, then: () -> Unit = {}) {
        viewModelScope.launch { afterChangeNow(message); then() }
    }

    private suspend fun afterChangeNow(message: String) {
        GitSync.commit(ctx, message)
        withContext(Dispatchers.IO) { Reminders.rescan(ctx); NotesWidget.refresh(ctx) }
        SyncWorker.enqueue(ctx)
    }

    /** All tags with how many notes use them, most used first. */
    fun tagCounts(): List<Pair<String, Int>> = countTags(notes)

    fun reload() {
        viewModelScope.launch {
            notes = withContext(Dispatchers.IO) {
                if (Vault.accessible(ctx)) Vault.all(ctx).also { NotesWidget.refresh(ctx) } else emptyList()
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

    private fun changedOnDisk(path: String): Boolean {
        val f = File(Vault.dir(ctx), path)
        return edMtime != 0L && f.exists() && f.lastModified() != edMtime
    }

    /** Back in the app with an unchanged editor: show what the widget or sync wrote meanwhile. */
    private fun reloadEditorIfChanged() {
        val path = edPath ?: return
        if (screen != Screen.Edit || snapshot() != edSaved || !changedOnDisk(path)) return
        val root = Vault.dir(ctx)
        val preview = edPreview
        open(Vault.read(root, File(root, path)), edit = !preview)
    }

    fun onResume() {
        reloadEditorIfChanged()
        tick++
        if (ready() && screen != Screen.Edit) sync()
    }

    /**
     * Move the vault between Documents (Obsidian) and app-private storage. Messages go through [Status],
     * so the [reload] at the end does not overwrite them.
     */
    fun changeStorage(to: Storage) {
        if (busy) return
        saveEditor()
        viewModelScope.launch {
            busy = true
            status = ctx.getString(R.string.vault_moving)
            try {
                val left = GitSync.exclusive { Vault.relocate(ctx, to) }
                withContext(Dispatchers.IO) { Reminders.rescan(ctx) }
                Status.set(ctx, when {
                    left > 0 -> ctx.resources.getQuantityString(R.plurals.vault_moved_leftover, left, left)
                    to == Storage.Private -> ctx.getString(R.string.vault_now_private)
                    else -> ctx.getString(R.string.vault_now_shared)
                })
            } catch (e: Exception) {
                Status.set(ctx, ctx.getString(R.string.vault_move_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
                tick++
                reload()
            }
        }
    }

    /** Remove the old public vault folder that a move to private storage left behind. */
    fun deleteLeftover() {
        val dir = Vault.leftoverShared(ctx) ?: return
        viewModelScope.launch {
            val left = withContext(Dispatchers.IO) { Vault.removeAll(dir) }
            Status.set(
                ctx,
                if (left == 0) ctx.getString(R.string.vault_leftover_deleted)
                else ctx.resources.getQuantityString(R.plurals.vault_leftover_not_deleted, left, left, dir.path),
            )
            tick++
            reload()
        }
    }

    fun setup(p: Prefs, token: String) {
        Prefs.save(ctx, p)
        if (token.isNotBlank()) Secrets.put(ctx, token.trim())
        viewModelScope.launch {
            busy = true
            Status.set(ctx, ctx.getString(R.string.status_cloning))
            status = Status.get(ctx)
            try {
                GitSync.cloneOrOpen(ctx)
                GitSync.sync(ctx)
                withContext(Dispatchers.IO) { Reminders.rescan(ctx) }
                screen = Screen.List
            } catch (e: Exception) {
                Status.set(ctx, "⚠ " + GitSync.explain(ctx, e))
            } finally {
                busy = false
                reload()
            }
        }
    }

    private data class Share(val text: String, val subject: String?, val selection: Boolean)

    /** Shared text received while locked: nothing is written (or fetched) until the PIN is entered. */
    private var pendingShare by mutableStateOf<Share?>(null)
    val pendingShareHint: String? get() = pendingShare?.let { ctx.getString(R.string.share_pending_hint) }

    fun runPendingShare() {
        val s = pendingShare ?: return
        pendingShare = null
        clip(s.text, s.subject, s.selection)
    }

    private fun share(s: Share) {
        if (s.text.isBlank()) return
        if (AppLock.locked) pendingShare = s else clip(s.text, s.subject, s.selection)
    }

    fun handleIntent(i: Intent?) {
        if (i == null || !ready()) return
        when {
            i.action == Intent.ACTION_SEND -> share(
                Share(i.getStringExtra(Intent.EXTRA_TEXT).orEmpty(), i.getStringExtra(Intent.EXTRA_SUBJECT), false),
            )
            i.action == Intent.ACTION_PROCESS_TEXT -> share(
                Share(i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(), null, true),
            )
            i.hasExtra("conflicts") -> { saveEditor(); showConflicts = true; screen = Screen.List; reload() }
            i.hasExtra("tasks") -> { saveEditor(); showTasks() }
            i.hasExtra("new") -> newNote(i.getStringExtra("new") ?: "note")
            i.hasExtra("open") -> openPath(i.getStringExtra("open")!!)
            i.hasExtra("edit") -> openPath(i.getStringExtra("edit")!!, edit = true)
            else -> return
        }
        // Consume, so a configuration change does not replay it.
        i.removeExtra("new"); i.removeExtra("open"); i.removeExtra("edit"); i.removeExtra("conflicts"); i.removeExtra("tasks"); i.action = null
    }

    // ---------- editor ----------

    private fun snapshot() = edTitle + "\u0000" + Vault.serialize(edFront, edBody.text)

    fun open(n: Note, edit: Boolean = false) {
        saveEditor()
        if (n.encrypted) { openSecret(n); return }
        if (screen != Screen.Edit) returnTo = if (screen == Screen.Tasks) Screen.Tasks else Screen.List
        edPath = n.path
        edTitle = n.title
        edFront = n.front
        edBody = TextFieldValue(n.body, TextRange(n.body.trimEnd().length))
        edPreview = !edit && n.body.isNotBlank()
        edSaved = snapshot()
        edMtime = n.modified
        dictateOnOpen = false
        edKey++
        screen = Screen.Edit
    }

    /** Follow a [[wiki link]] or relative Markdown link: path first, then title (case-insensitive). */
    fun openNote(name: String): Boolean {
        val key = name.trim().trimStart('/').lowercase()
        if (key.isEmpty()) return false
        val n = notes.firstOrNull { withoutExt(it.path).lowercase() == key }
            ?: notes.firstOrNull { withoutExt(it.path).lowercase().endsWith("/$key") }
            ?: notes.firstOrNull { it.title.lowercase() == key.substringAfterLast('/') }
            ?: return false
        open(n)
        return true
    }

    /** Paths come from intents (widget, notifications, but MainActivity is exported): only notes inside the vault. */
    private fun openPath(path: String, edit: Boolean = false) {
        val root = Vault.dir(ctx)
        val f = Vault.safeFile(root, path, encrypted = true) ?: return
        if (f.exists()) open(Vault.read(root.canonicalFile, f), edit)
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
        edMtime = 0L
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
        if (path != null && changedOnDisk(path)) {
            // Someone else wrote the file meanwhile (widget task, tick, sync): keep theirs,
            // save ours next to it as a conflict copy; the editor shows the conflict banner.
            path = Conflicts.copyPath(path)
            status = ctx.getString(R.string.editor_changed_meanwhile)
        } else if (path == null) {
            val first = body.lineSequence().map { Markdown.stripTaskMeta(it.replace(Markdown.LIST, "").trimStart('#', ' ')) }
                .firstOrNull { it.isNotBlank() }.orEmpty().take(40)
            path = Vault.uniquePath(root, edFolder, title.ifBlank { first })
        } else if (title.isNotBlank() && title != titleOf(path)) {
            val np = Vault.uniquePath(root, path.substringBeforeLast('/', ""), title)
            if (File(root, path).renameTo(File(root, np))) path = np
        }
        Vault.write(root, path, edFront, body)
        edMtime = File(root, path).lastModified()
        edPath = path
        edTitle = titleOf(path)
        edSaved = snapshot()
        afterChange(ctx.getString(R.string.commit_note, titleOf(path)))
    }

    // ---------- encrypted notes (read-only) ----------

    private fun openSecret(n: Note) {
        if (screen != Screen.Edit && screen != Screen.Secret) returnTo = if (screen == Screen.Tasks) Screen.Tasks else Screen.List
        secretNote = n
        screen = Screen.Secret
    }

    fun closeSecret() {
        saveSecret {
            secretDraft = null
            secretNote = null
            screen = returnTo
            reload()
        }
    }

    /** Ciphertext of an encrypted note; throws [MeldCrypto.Unsupported] for formats kyn cannot read. */
    suspend fun secretFile(n: Note): MeldCrypto.FileData = withContext(Dispatchers.IO) {
        val root = Vault.dir(ctx)
        val f = Vault.safeFile(root, n.path, encrypted = true) ?: throw MeldCrypto.Unsupported("?")
        val mtime = f.lastModified()
        MeldCrypto.parseFile(f.readText()).also { if (secretDraft == null) secretMtime = mtime }
    }

    /** Start editing the open encrypted note, decrypted from [blob] (needs a password of this session). */
    fun startSecretEdit(file: MeldCrypto.FileData, blob: MeldCrypto.Blob, plain: Plaintext) {
        secretBase = blob
        secretHint = file.hint
        secretSaved = plain.text
        secretDraft = plain.text
    }

    /**
     * Encrypt the draft with the password that opened the note and write it (only ciphertext reaches disk,
     * see [Vault.writeEncrypted]). If the file changed meanwhile (sync, Obsidian), the draft goes to a
     * conflict copy next to it. [then] runs on the main thread afterwards, also when nothing changed.
     */
    fun saveSecret(then: () -> Unit = {}) {
        val text = secretDraft
        val base = secretBase
        val n = secretNote
        if (text == null || base == null || n == null || text == secretSaved) { then(); return }
        viewModelScope.launch {
            secretLock.withLock {
                secretSaving = true
                try {
                    val b = SecretSession.encryptLike(base, text)
                    if (b == null) {
                        Toast.makeText(ctx, ctx.getString(R.string.secret_save_failed), Toast.LENGTH_LONG).show()
                        return@withLock
                    }
                    val root = Vault.dir(ctx)
                    var path = n.path
                    val f = File(root, path)
                    if (secretMtime != 0L && f.exists() && f.lastModified() != secretMtime) {
                        path = Conflicts.copyPath(path)
                        Toast.makeText(ctx, ctx.getString(R.string.editor_changed_meanwhile), Toast.LENGTH_LONG).show()
                    }
                    withContext(Dispatchers.IO) { Vault.writeEncrypted(root, path, MeldCrypto.fileJson(secretHint, b)) }
                    secretMtime = File(root, path).lastModified()
                    secretBase = b
                    secretSaved = text
                    if (path != n.path) secretNote = n.copy(path = path, modified = secretMtime)
                    secretRev++
                    afterChangeNow(ctx.getString(R.string.commit_note, titleOf(path)))
                } catch (e: Exception) {
                    Toast.makeText(ctx, ctx.getString(R.string.secret_save_failed), Toast.LENGTH_LONG).show()
                    return@withLock
                } finally {
                    secretSaving = false
                }
            }
            then()
        }
    }

    /** Hint for an inline section: no 💡 (marker) and no line break (a section is one line). */
    fun validInlineHint(h: String) = "💡" !in h && h.none { it == '\n' || it == '\r' }

    /**
     * Turn the open note into an encrypted note like the plugin's "Encrypt note": the whole file content
     * (frontmatter included) goes into "<name>.mdenc" next to it, the .md is deleted. The editor drops the
     * plaintext and the encrypted note opens. Earlier versions stay readable in the git history.
     */
    suspend fun encryptNote(password: CharArray, hint: String): Boolean {
        saveEditor()
        val path = edPath ?: run { password.fill('\u0000'); return false }
        val text = Vault.serialize(edFront, edBody.text)
        val root = Vault.dir(ctx)
        val folder = path.substringBeforeLast('/', "")
        val base = (if (folder.isEmpty()) "" else "$folder/") + titleOf(path)
        var target = "$base.mdenc"
        var n = 2
        while (File(root, target).exists()) target = "$base ${n++}.mdenc"
        return try {
            val b = SecretSession.encryptNew(text, password)
            withContext(Dispatchers.IO) {
                Vault.writeEncrypted(root, target, MeldCrypto.fileJson(hint, b))
                File(root, path).delete()
            }
            // drop the plaintext from the editor before anything else can save it
            edPath = null; edTitle = ""; edFront = emptyList(); edBody = TextFieldValue(""); edSaved = snapshot(); edMtime = 0L
            afterChangeNow(ctx.getString(R.string.commit_encrypted, titleOf(target)))
            val f = File(root, target)
            openSecret(Note(target, emptyList(), "", f.lastModified(), encrypted = true))
            reload()
            true
        } catch (e: Exception) {
            Toast.makeText(ctx, ctx.getString(R.string.secret_save_failed), Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * Replace the selected text with an encrypted section (v3, like the plugin's "Encrypt selection").
     * [visible] = shown as 🔐 in Obsidian's reading view (plugin default), else wrapped in %% comments.
     */
    suspend fun encryptSelection(password: CharArray, hint: String, visible: Boolean): Boolean {
        val v = edBody
        val sel = v.selection
        val text = v.text.substring(sel.min, sel.max)
        if (text.isEmpty() || "🔐" in text || !validInlineHint(hint)) { password.fill('\u0000'); return false }
        val b = SecretSession.encryptNew(text, password)
        if (edBody.text != v.text) return false // changed meanwhile (sync reload): do not guess where
        val section = MeldCrypto.inlineText(b, hint, hidden = !visible)
        edBody = TextFieldValue(v.text.replaceRange(sel.min, sel.max, section), TextRange(sel.min + section.length))
        saveEditor()
        return true
    }

    /**
     * Replace encrypted section [i] of the open note with [text], encrypted again with the same password
     * and hint (as v3). Only the new ciphertext goes into the editor text, which is then saved.
     */
    suspend fun replaceSection(i: MeldCrypto.Inline, text: String): Boolean {
        val old = runCatching { MeldCrypto.blob(i) }.getOrNull() ?: return false
        val b = SecretSession.encryptLike(old, text) ?: return false
        val body = edBody.text
        val at = body.indexOf(i.raw)
        if (at < 0) return false
        edBody = TextFieldValue(body.replaceRange(at, at + i.raw.length, MeldCrypto.inlineText(b, i.hint, i.hidden)))
        saveEditor()
        return true
    }

    /** Shared text or link → Markdown note in Clippings/. A bare link loads the whole page. */
    private fun clip(text: String, subject: String?, selection: Boolean) {
        if (text.isBlank()) return
        when (val share = Clipper.parseShare(text, subject, selection)) {
            is Clipper.Share.Plain -> newNote("note", text = share.text, title = share.title.orEmpty())
            is Clipper.Share.Quote -> showClip(Clipper.quote(share, Clipper.texts(ctx)))
            is Clipper.Share.Page -> {
                newNote("note", title = share.title.orEmpty(), folder = Clipper.FOLDER)
                edBody = TextFieldValue(ctx.getString(R.string.clip_loading, share.url))
                edPreview = true
                val key = edKey
                clipKey = key
                viewModelScope.launch {
                    val c = withContext(Dispatchers.IO) {
                        val t = Clipper.texts(ctx)
                        try { Clipper.page(share, t) } catch (e: Exception) { Clipper.failed(share, e, t) }
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
                        afterChangeNow(ctx.getString(R.string.commit_clip, c.title))
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
        afterChange(
            ctx.getString(
                if (keep) R.string.commit_conflict_resolved_phone else R.string.commit_conflict_resolved_server, titleOf(orig),
            ),
        )
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
            afterChange(ctx.getString(R.string.commit_deleted, titleOf(path)))
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
    fun allTags(): List<String> = tagCounts().map { it.first }

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
        afterChange(ctx.getString(R.string.commit_moved, titleOf(np), f.ifEmpty { "/" }))
    }

    fun togglePin() {
        edFront = if (edPinned) edFront.filter { it.trim() != "pinned: true" } else edFront + "pinned: true"
    }

    fun toggleTask(line: Int) {
        edBody = edBody.copy(text = Markdown.toggleTaskLine(edBody.text, line))
        saveEditor()
    }
}
