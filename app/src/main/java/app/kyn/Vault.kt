package app.kyn

import android.content.Context
import android.os.Environment
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class Note(val path: String, val front: List<String>, val body: String, val modified: Long) {
    val title: String get() = titleOf(path)
    val folder: String get() = path.substringBeforeLast('/', "")
    val pinned: Boolean get() = isPinned(front)
    val tags: Set<String> get() = Markdown.tags(body) + frontTags(front)
}

fun titleOf(path: String) = path.substringAfterLast('/').removeSuffix(".md")
fun isPinned(front: List<String>) = front.any { it.trim() == "pinned: true" }

fun frontTags(front: List<String>): Set<String> {
    val i = front.indexOfFirst { it.startsWith("tags:") }
    if (i < 0) return emptySet()
    val inline = front[i].removePrefix("tags:").trim().removePrefix("[").removeSuffix("]")
    val items = if (inline.isNotBlank()) inline.split(',')
    else front.drop(i + 1).takeWhile { it.trimStart().startsWith("- ") }.map { it.trimStart().removePrefix("- ") }
    return items.map { it.trim().trim('"', '\'').removePrefix("#") }.filter { it.isNotEmpty() }.toSet()
}

/** Frontmatter with the tags line replaced (inline form `tags: [a, b]`); no tags removes the line. */
fun withTags(front: List<String>, tags: List<String>): List<String> {
    val out = mutableListOf<String>()
    var skipping = false
    for (l in front) {
        if (l.startsWith("tags:")) { skipping = l.removePrefix("tags:").isBlank(); continue }
        if (skipping && l.trimStart().startsWith("- ")) continue
        skipping = false
        out += l
    }
    if (tags.isNotEmpty()) out += "tags: [${tags.joinToString(", ")}]"
    return out
}

/** List filter: selected [tag], plus "#abc" in [query] = tags containing "abc", else full-text search. Pinned first, newest first. */
fun filterNotes(notes: List<Note>, tag: String?, query: String, folder: String? = null): List<Note> {
    val q = query.trim()
    val tq = q.takeIf { it.startsWith("#") }?.let { cleanTag(it) }
    return notes.filter { n ->
        (folder == null || n.folder == folder || n.folder.startsWith("$folder/")) &&
        (tag == null || tag in n.tags) && when {
            tq != null -> tq.isEmpty() || n.tags.any { it.contains(tq) }
            else -> q.isEmpty() || n.title.contains(q, true) || n.body.contains(q, true)
        }
    }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.modified })
}

/** One frontmatter property for display/editing; lists are shown comma-separated. */
data class Prop(val key: String, val value: String, val list: Boolean)

private val PROP_LINE = Regex("""^([A-Za-z_][\w-]*):\s?(.*)$""")
private val DATE_START = Regex("""^\d{4}-\d{2}-\d{2}""")
private val DATE_KEYS = setOf("created", "date", "datum", "due", "faellig", "fällig", "deadline", "updated", "modified", "geburtstag")

fun props(front: List<String>): List<Prop> {
    val out = mutableListOf<Prop>()
    for (l in front) {
        val m = PROP_LINE.find(l)
        if (m != null) { out += Prop(m.groupValues[1], m.groupValues[2].trim(), false); continue }
        val t = l.trim()
        if (t.startsWith("- ") && out.isNotEmpty()) {
            val p = out.last()
            val item = t.removePrefix("- ").trim().trim('"', '\'')
            out[out.lastIndex] = p.copy(value = if (p.value.isEmpty()) item else p.value + ", " + item, list = true)
        }
    }
    return out.map { p ->
        val v = p.value
        when {
            v.startsWith("[") && v.endsWith("]") -> p.copy(
                value = v.drop(1).dropLast(1).split(",").map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }.joinToString(", "),
                list = true,
            )
            v.length >= 2 && ((v.first() == '"' && v.last() == '"') || (v.first() == '\'' && v.last() == '\'')) ->
                p.copy(value = v.drop(1).dropLast(1).replace("\\\"", "\""))
            else -> p
        }
    }
}

fun isDateProp(p: Prop) = !p.list && (DATE_START.containsMatchIn(p.value) || p.key.lowercase() in DATE_KEYS)

private fun yamlScalar(v: String): String {
    val t = v.trim()
    val risky = t.isNotEmpty() && (t.first() in "[]{}>|*&!%@`#'\"-?:," || ": " in t || " #" in t || t.endsWith(":"))
    return if (risky) "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else t
}

/** Set (value != null) or remove (value == null) a property, keeping its position. */
fun withProp(front: List<String>, key: String, value: String?, list: Boolean = false): List<String> {
    val out = mutableListOf<String>()
    var skipping = false
    var pos = -1
    for (l in front) {
        if (PROP_LINE.find(l)?.groupValues?.get(1) == key) { skipping = true; pos = out.size; continue }
        if (skipping && l.trimStart().startsWith("- ")) continue
        skipping = false
        out += l
    }
    if (value != null) {
        val line = "$key: " + if (list) "[" + value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") + "]"
        else yamlScalar(value)
        if (pos >= 0) out.add(pos, line) else out += line
    }
    return out
}

/** "Mein Feld" -> "Mein_Feld" (YAML-safe key) */
fun cleanKey(raw: String) = raw.trim().replace(Regex("\\s+"), "_").replace(Regex("[^\\w-]"), "")

/** "Mein Tag!" -> "mein-tag" */
fun cleanTag(raw: String) = raw.trim().removePrefix("#").lowercase()
    .replace(Regex("\\s+"), "-").replace(Regex("[^\\p{L}\\d_/-]"), "").trim('-')

object Vault {
    /** Obsidian template folder in the vault root: templates are not notes (placeholders, no content). */
    const val TEMPLATES = "templates"

    /** Folders the app never shows: dot folders (.git, .obsidian, .trash …) and the root [TEMPLATES] folder. */
    internal fun hidden(root: File, dir: File): Boolean =
        dir.name.startsWith(".") || (dir.parentFile == root && dir.name.equals(TEMPLATES, ignoreCase = true))
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")

    fun dir(ctx: Context): File = Prefs.load(ctx).let { dirFor(ctx, it.storage, it.folder) }

    @Suppress("DEPRECATION")
    fun dirFor(ctx: Context, storage: Storage, folder: String): File = when (storage) {
        Storage.Shared -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), folder)
        Storage.Private -> File(ctx.filesDir, "vault")
    }

    /** Can the app read and write the vault location (shared storage needs "all files" access)? */
    fun accessible(ctx: Context) = Prefs.load(ctx).storage == Storage.Private || Environment.isExternalStorageManager()

    /**
     * Move the whole vault (with .git) to [to] and switch the setting. Copy first, switch, then delete
     * the old copy, so an interrupted move never leaves the app without its notes. Caller holds the git lock.
     * Returns the number of files that could not be deleted from the old place (0 = clean).
     */
    fun relocate(ctx: Context, to: Storage): Int {
        val p = Prefs.load(ctx)
        if (p.storage == to) return 0
        val from = dirFor(ctx, p.storage, p.folder)
        val target = dirFor(ctx, to, p.folder)
        if (from.isDirectory) {
            if (target.exists() && target.list()?.isNotEmpty() == true) error(ctx.getString(R.string.err_folder_not_empty, target.path))
            // copy next to the target, then rename: a killed copy leaves only a temp folder behind
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.deleteRecursively()
            try {
                if (!from.copyRecursively(tmp, overwrite = false)) error(ctx.getString(R.string.err_copy_failed))
                target.delete() // empty or missing
                if (!tmp.renameTo(target)) error(ctx.getString(R.string.err_rename_failed))
            } catch (e: Exception) {
                tmp.deleteRecursively()
                throw e
            }
        }
        Prefs.save(ctx, p.copy(storage = to))
        return removeAll(from)
    }

    /** Delete [dir] with everything in it; returns how many files are still there afterwards. */
    fun removeAll(dir: File): Int {
        dir.deleteRecursively()
        return if (dir.exists()) dir.walkTopDown().count { it.isFile } else 0
    }

    /**
     * After switching to private storage: the old Documents folder if it still exists
     * (delete failed, or the app was killed right after the switch). Its files stay readable by other apps.
     */
    fun leftoverShared(ctx: Context): File? {
        val p = Prefs.load(ctx)
        if (p.storage != Storage.Private || !Environment.isExternalStorageManager()) return null
        return dirFor(ctx, Storage.Shared, p.folder).takeIf { it.exists() }
    }

    fun all(ctx: Context): List<Note> {
        val root = dir(ctx)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { it == root || !hidden(root, it) }
            .filter { it.isFile && it.extension == "md" }
            .mapNotNull { runCatching { read(root, it) }.getOrNull() }
            .toList()
    }

    private val PARA = listOf("inbox", "projekte", "bereiche", "wissen", "archiv")

    /** All folders below the vault root (no dot folders), PARA folders first, subfolders after their parent. */
    fun folders(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        val all = root.walkTopDown().onEnter { it == root || !hidden(root, it) }
            .filter { it.isDirectory && it != root }.map { it.relativeTo(root).path }.toList()
        return sortFolders(all + PARA.filter { it !in all })
    }

    fun sortFolders(paths: List<String>): List<String> = paths.distinct().sortedWith(
        compareBy<String>({ PARA.indexOf(it.substringBefore('/')).let { i -> if (i < 0) PARA.size else i } }, { it.lowercase() })
    )

    /** "Projekte / Umzug 2027/" -> "Projekte/Umzug 2027"; no dot folders, no "..". */
    fun cleanFolder(raw: String) = raw.split('/').map { it.trim().replace(Regex("""[\\:*?"<>|]"""), "") }
        .filter { it.isNotEmpty() && !it.startsWith(".") }.joinToString("/")

    val PARA_FOLDERS get() = PARA

    /**
     * Move folder [src] (with everything in it) into [parent] ("" = vault root).
     * Returns the new path, or null if not allowed (PARA root folders, into itself) or failed.
     */
    fun moveFolder(root: File, src: String, parent: String): String? {
        val s = cleanFolder(src)
        val p = cleanFolder(parent)
        if (s.isEmpty() || s in PARA) return null
        if (p == s || p.startsWith("$s/")) return null
        val name = s.substringAfterLast('/')
        var target = if (p.isEmpty()) name else "$p/$name"
        if (target == s) return null
        var n = 2
        while (File(root, target).exists()) target = (if (p.isEmpty()) "" else "$p/") + "$name ${n++}"
        File(root, target).parentFile?.mkdirs()
        return if (File(root, s).renameTo(File(root, target))) target else null
    }

    /**
     * [path] as a note file inside [root], or null if it points elsewhere (`..`, absolute, dot folders,
     * not Markdown). For paths that come from intents.
     */
    fun safeFile(root: File, path: String): File? {
        val r = root.canonicalFile
        val f = File(r, path).canonicalFile
        val rel = f.relativeToOrNull(r)?.path ?: return null
        if (rel.isEmpty() || rel.startsWith("..") || f.extension != "md") return null
        if (rel.split(File.separatorChar).any { it.startsWith(".") }) return null
        return f
    }

    fun read(root: File, f: File): Note {
        val (front, body) = parse(f.readText())
        return Note(f.relativeTo(root).path, front, body, f.lastModified())
    }

    fun parse(text: String): Pair<List<String>, String> {
        val lines = text.replace("\r\n", "\n").split("\n")
        if (lines.firstOrNull() != "---") return emptyList<String>() to lines.joinToString("\n")
        val end = lines.drop(1).indexOf("---")
        if (end < 0) return emptyList<String>() to lines.joinToString("\n")
        return lines.subList(1, end + 1) to lines.drop(end + 2).joinToString("\n")
    }

    fun serialize(front: List<String>, body: String) =
        if (front.isEmpty()) body else "---\n${front.joinToString("\n")}\n---\n$body"

    fun write(root: File, path: String, front: List<String>, body: String) {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        f.writeText(serialize(front, body))
    }

    /** Free path like "inbox/Einkauf.md", "inbox/Einkauf 2.md". */
    fun uniquePath(root: File, folder: String, title: String): String {
        var name = title.replace(Regex("""[\\/:*?"<>|#^\[\]\n]"""), "").trim().take(80)
        if (name.isEmpty()) name = LocalDateTime.now().format(stamp)
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        var path = "$prefix$name.md"
        var n = 2
        while (File(root, path).exists()) path = "$prefix$name ${n++}.md"
        return path
    }

    /** `created:` keeps date and time (YYYY-MM-DDTHH:MM); other date properties are YYYY-MM-DD. */
    fun nowIso(): String = LocalDateTime.now().withSecond(0).withNano(0).toString()
}
