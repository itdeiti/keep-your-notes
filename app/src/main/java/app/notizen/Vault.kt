package app.notizen

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

private fun frontTags(front: List<String>): Set<String> {
    val i = front.indexOfFirst { it.startsWith("tags:") }
    if (i < 0) return emptySet()
    val inline = front[i].removePrefix("tags:").trim().removePrefix("[").removeSuffix("]")
    val items = if (inline.isNotBlank()) inline.split(',')
    else front.drop(i + 1).takeWhile { it.trimStart().startsWith("- ") }.map { it.trimStart().removePrefix("- ") }
    return items.map { it.trim().trim('"', '\'').removePrefix("#") }.filter { it.isNotEmpty() }.toSet()
}

object Vault {
    private val skip = setOf(".git", ".obsidian", ".trash")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")

    @Suppress("DEPRECATION")
    fun dir(ctx: Context): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        Prefs.load(ctx).folder,
    )

    fun all(ctx: Context): List<Note> {
        val root = dir(ctx)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { it.name !in skip }
            .filter { it.isFile && it.extension == "md" }
            .mapNotNull { runCatching { read(root, it) }.getOrNull() }
            .toList()
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

    fun nowIso(): String = LocalDateTime.now().withSecond(0).withNano(0).toString()
}
