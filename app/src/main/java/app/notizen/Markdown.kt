package app.notizen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import java.time.LocalDate
import java.time.LocalTime

data class MdColors(val marker: Color, val link: Color, val tag: Color, val code: Color, val meta: Color)

/** Small Markdown subset: what the app styles itself. Everything else stays plain text. */
object Markdown {
    val TASK = Regex("""^(\s*[-*+] \[( |x|X)\] )""")
    val BULLET = Regex("""^\s*([-*+]|\d+[.)]) """)
    val HEADING = Regex("""^(#{1,6}) """)
    val LIST = Regex("""^(\s*)([-*+] \[[ xX]\] |[-*+] |\d+[.)] )""")
    private val PREFIX = Regex("""^(\s*)(#{1,6} |[-*+] \[[ xX]\] |[-*+] |\d+[.)] )?""")
    private val META_STRIP = Regex("""\s*(📅|⏰|✅|⏳|🛫|➕) ?\d{4}-\d{2}-\d{2}( \d{1,2}:\d{2})?""")
    private val DUE = Regex("""📅 ?(\d{4}-\d{2}-\d{2})""")

    private enum class K { BOLD, STRIKE, ITALIC, CODE, IMAGE, LINK, WIKI, TAG, META }

    private val RULES = listOf(
        K.BOLD to Regex("""\*\*(.+?)\*\*"""),
        K.STRIKE to Regex("""~~(.+?)~~"""),
        K.ITALIC to Regex("""(?<![*\w])\*(?![\s*])(.+?)(?<![\s*])\*(?![*\w])"""),
        K.CODE to Regex("""`([^`]+)`"""),
        K.IMAGE to Regex("""!\[([^\]]*)\]\(([^)\s]+)\)"""),
        K.LINK to Regex("""\[([^\]]+)\]\(([^)\s]+)\)"""),
        K.WIKI to Regex("""\[\[([^\]|]+)(?:\|([^\]]+))?\]\]"""),
        K.TAG to Regex("""(?<![\w#&/])#([\p{L}_][\p{L}\d_/-]*)"""),
        K.META to Regex("""(📅|⏰|✅) ?(\d{4}-\d{2}-\d{2}(?: \d{1,2}:\d{2})?)"""),
    )
    private val TAG_RE = RULES.first { it.first == K.TAG }.second

    private fun style(k: K, c: MdColors) = when (k) {
        K.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        K.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        K.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        K.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = c.code)
        K.LINK, K.WIKI, K.IMAGE -> SpanStyle(color = c.link)
        K.TAG -> SpanStyle(color = c.tag)
        K.META -> SpanStyle(color = c.meta, fontSize = 0.85.em)
    }

    private fun headingSize(level: Int) = when (level) { 1 -> 1.45.em; 2 -> 1.3.em; 3 -> 1.15.em; else -> 1.05.em }

    fun tags(text: String): Set<String> = TAG_RE.findAll(text).map { it.groupValues[1] }.toSet()

    fun stripTaskMeta(s: String) = s.replace(META_STRIP, "").trim()

    /** Earliest due date of an open task. */
    fun nextDue(body: String): LocalDate? = body.lineSequence()
        .filter { TASK.find(it)?.groupValues?.get(2) == " " }
        .mapNotNull { DUE.find(it)?.groupValues?.get(1) }
        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        .minOrNull()

    /** Edit mode: keep every character, style it, dim the syntax. */
    fun highlight(text: String, c: MdColors): AnnotatedString = buildAnnotatedString {
        append(text)
        val dim = SpanStyle(color = c.marker)
        var off = 0
        for (line in text.split("\n")) {
            val end = off + line.length
            HEADING.find(line)?.let { h ->
                val lvl = h.groupValues[1].length
                addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize(lvl)), off, end)
                addStyle(dim, off, off + lvl + 1)
            }
            val task = TASK.find(line)
            if (task != null) {
                addStyle(dim, off, off + task.value.length)
                if (task.groupValues[2] != " ")
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = c.marker), off + task.value.length, end)
            } else BULLET.find(line)?.let { addStyle(dim, off, off + it.value.length) }
            for ((k, re) in RULES) for (m in re.findAll(line)) {
                val s = off + m.range.first
                val e = off + m.range.last + 1
                if (k == K.TAG || k == K.META) { addStyle(style(k, c), s, e); continue }
                val g = m.groups[1]!!.range
                addStyle(style(k, c), off + g.first, off + g.last + 1)
                addStyle(dim, s, off + g.first)
                addStyle(dim, off + g.last + 1, e)
            }
            off = end + 1
        }
    }

    /** View mode: syntax removed, links clickable. */
    fun render(line: String, c: MdColors, links: Boolean = true): AnnotatedString = buildAnnotatedString {
        val ms = RULES.flatMap { (k, re) -> re.findAll(line).map { k to it } }
            .sortedWith(compareBy({ it.second.range.first }, { -it.second.range.last }))
        var pos = 0
        for ((k, m) in ms) {
            if (m.range.first < pos) continue
            append(line.substring(pos, m.range.first))
            when (k) {
                K.LINK -> if (links) {
                    withLink(LinkAnnotation.Url(m.groupValues[2], TextLinkStyles(SpanStyle(color = c.link, textDecoration = TextDecoration.Underline)))) {
                        append(m.groupValues[1])
                    }
                } else withStyle(style(k, c)) { append(m.groupValues[1]) }
                K.IMAGE -> if (links) {
                    withLink(LinkAnnotation.Url(m.groupValues[2], TextLinkStyles(SpanStyle(color = c.link)))) {
                        append("🖼 " + m.groupValues[1].ifBlank { "Bild" })
                    }
                } else append("🖼")
                K.WIKI -> withStyle(style(k, c)) { append(m.groupValues[2].ifEmpty { m.groupValues[1] }) }
                K.TAG, K.META -> withStyle(style(k, c)) { append(m.value) }
                else -> withStyle(style(k, c)) { append(m.groupValues[1]) }
            }
            pos = m.range.last + 1
        }
        append(line.substring(pos))
    }

    /** Card preview in the list. */
    fun preview(body: String, c: MdColors, title: String = "", maxLines: Int = 8): AnnotatedString = buildAnnotatedString {
        var n = 0
        var first = true
        for (raw in body.split("\n")) {
            if (raw.isBlank() || n >= maxLines || TABLE_SEP.matches(raw)) continue
            if (first) {
                first = false
                val plain = stripTaskMeta(raw.replace(LIST, "").trimStart('#', '>', ' '))
                if (plain.equals(title.trim(), ignoreCase = true)) continue
            }
            if (n > 0) append("\n")
            val task = TASK.find(raw)
            val head = HEADING.find(raw)
            val bullet = BULLET.find(raw)
            when {
                task != null -> {
                    val done = task.groupValues[2] != " "
                    append(if (done) "☑ " else "☐ ")
                    val r = render(stripTaskMeta(raw.substring(task.value.length)), c, false)
                    if (done) withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = c.marker)) { append(r) }
                    else append(r)
                }
                head != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(render(raw.substring(head.value.length), c, false))
                }
                bullet != null -> { append("• "); append(render(raw.substring(bullet.value.length), c, false)) }
                isTableRow(raw) -> append(render(cells(raw).filter { it.isNotEmpty() }.joinToString("  ·  "), c, false))
                raw.startsWith(">") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(render(raw.removePrefix(">").trimStart(), c, false))
                }
                else -> append(render(raw, c, false))
            }
            n++
        }
    }

    // ---------- editing helpers ----------

    private fun lineStart(t: String, pos: Int) = t.lastIndexOf('\n', pos - 1) + 1
    private fun lineEnd(t: String, start: Int) = t.indexOf('\n', start).let { if (it < 0) t.length else it }

    fun insert(v: TextFieldValue, s: String): TextFieldValue {
        val a = v.selection.min
        val b = v.selection.max
        return TextFieldValue(v.text.substring(0, a) + s + v.text.substring(b), TextRange(a + s.length))
    }

    /** Dictated text: add a space if the cursor sits right after a word. */
    fun insertSpaced(v: TextFieldValue, s: String): TextFieldValue {
        val a = v.selection.min
        val needSpace = a > 0 && !v.text[a - 1].isWhitespace()
        return insert(v, (if (needSpace) " " else "") + s)
    }

    fun wrap(v: TextFieldValue, l: String, r: String = l): TextFieldValue {
        val s = v.selection.min
        val e = v.selection.max
        val t = v.text
        val nt = t.substring(0, s) + l + t.substring(s, e) + r + t.substring(e)
        return TextFieldValue(nt, if (s == e) TextRange(s + l.length) else TextRange(s + l.length, e + l.length))
    }

    fun link(v: TextFieldValue): TextFieldValue {
        val s = v.selection.min
        val e = v.selection.max
        val w = wrap(v, "[", "]()")
        return w.copy(selection = TextRange(if (s == e) s + 1 else e + 3))
    }

    private fun linePrefix(v: TextFieldValue, transform: (String) -> String): TextFieldValue {
        val t = v.text
        val ls = lineStart(t, v.selection.min)
        val le = lineEnd(t, ls)
        val line = t.substring(ls, le)
        val m = PREFIX.find(line)!!
        val indent = m.groupValues[1]
        val old = m.groupValues[2]
        val np = transform(old)
        val delta = np.length - old.length
        val body0 = ls + indent.length
        fun adj(p: Int) = if (p < body0) p else maxOf(body0, p + delta)
        val nt = t.substring(0, ls) + indent + np + line.substring(m.value.length) + t.substring(le)
        return TextFieldValue(nt, TextRange(adj(v.selection.start), adj(v.selection.end)))
    }

    fun toggleBullet(v: TextFieldValue) = linePrefix(v) { if (it == "- ") "" else "- " }
    fun toggleTask(v: TextFieldValue) = linePrefix(v) { if (it.contains("[")) "" else "- [ ] " }
    fun cycleHeading(v: TextFieldValue) = linePrefix(v) {
        when (it) { "# " -> "## "; "## " -> "### "; "### " -> ""; else -> "# " }
    }

    private fun outdentLine(l: String) = when {
        l.startsWith("\t") -> l.substring(1)
        else -> l.substring(l.takeWhile { it == ' ' }.length.coerceAtMost(4))
    }

    /** Indent (tab, like Obsidian) or outdent every line touched by the selection; the selection moves along. */
    fun indent(v: TextFieldValue, outdent: Boolean = false): TextFieldValue {
        val t = v.text
        val start = lineStart(t, v.selection.min)
        val end = lineEnd(t, maxOf(v.selection.max, start))
        val lines = t.substring(start, end).split("\n")
        val changed = lines.map { if (outdent) outdentLine(it) else "\t" + it }
        val deltaFirst = changed.first().length - lines.first().length
        val deltaAll = changed.sumOf { it.length } - lines.sumOf { it.length }
        val nt = t.substring(0, start) + changed.joinToString("\n") + t.substring(end)
        val a = (v.selection.min + deltaFirst).coerceAtLeast(start)
        val b = (v.selection.max + deltaAll).coerceAtLeast(a)
        return TextFieldValue(nt, if (v.selection.collapsed) TextRange(a) else TextRange(a, b))
    }

    /** Insert an empty 2x2 table on its own lines. */
    fun table(v: TextFieldValue): TextFieldValue {
        val t = v.text
        val at = v.selection.min
        val before = if (at > 0 && t[at - 1] != '\n') "\n" else ""
        val tpl = "| Spalte 1 | Spalte 2 |\n| --- | --- |\n|  |  |\n"
        val nt = t.substring(0, at) + before + tpl + t.substring(at)
        return TextFieldValue(nt, TextRange(at + before.length + 2, at + before.length + 10)) // selects "Spalte 1"
    }

    // ---------- tables ----------

    val TABLE_SEP = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")

    fun isTableRow(l: String) = l.trimStart().startsWith("|")

    fun cells(l: String): List<String> =
        l.trim().removePrefix("|").removeSuffix("|").split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }

    /** Enter in a list continues it; Enter on an empty item ends the list. */
    fun continueList(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        if (new.text.length != old.text.length + 1 || !new.selection.collapsed) return new
        val p = new.selection.start
        val t = new.text
        if (p == 0 || t[p - 1] != '\n' || t.substring(0, p - 1) != old.text.substring(0, p - 1)) return new
        val ls = lineStart(t, p - 1)
        val prev = t.substring(ls, p - 1)
        val m = LIST.find(prev) ?: return new
        if (prev.substring(m.value.length).isBlank()) {
            val indent = m.groupValues[1]
            if (indent.isNotEmpty()) { // empty nested item: go one level up instead of ending the list
                val line = outdentLine(prev)
                val nt = t.substring(0, ls) + line + t.substring(p)
                return TextFieldValue(nt, TextRange(ls + line.length))
            }
            return TextFieldValue(t.substring(0, ls) + t.substring(p), TextRange(ls))
        }
        val marker = m.groupValues[2]
        val next = when {
            marker.contains("[") -> marker.substring(0, 2) + "[ ] "
            marker[0].isDigit() -> "${marker.takeWhile { it.isDigit() }.toInt() + 1}${marker[marker.length - 2]} "
            else -> marker
        }
        val ins = m.groupValues[1] + next
        return TextFieldValue(t.substring(0, p) + ins + t.substring(p), TextRange(p + ins.length))
    }

    /**
     * Make the current line a task with due date (📅) and optional reminder (⏰), Obsidian Tasks/Reminder format.
     * The cursor ends after the task text, before the dates, so typing continues the description.
     */
    fun setDue(v: TextFieldValue, date: LocalDate, time: LocalTime?): TextFieldValue {
        val t = v.text
        val ls = lineStart(t, v.selection.min)
        val le = lineEnd(t, ls)
        val raw = t.substring(ls, le)
        val task = Regex("""^(\s*[-*+] \[( |x|X)\])\s?""").find(raw)
        val list = if (task == null) LIST.find(raw) else null
        val prefix = when {
            task != null -> task.groupValues[1] + " "
            list != null -> list.groupValues[1] + "- [ ] "
            else -> "- [ ] "
        }
        val rest = raw.substring(task?.value?.length ?: list?.value?.length ?: 0)
        val text = rest.replace(Regex("""\s*(📅|⏰) ?\d{4}-\d{2}-\d{2}( \d{1,2}:\d{2})?"""), "").trim()
        val meta = "📅 $date" + (time?.let { " ⏰ $date $it" } ?: "")
        val line = prefix + text + " " + meta
        val cursor = ls + prefix.length + text.length
        return TextFieldValue(t.substring(0, ls) + line + t.substring(le), TextRange(cursor))
    }

    /** Tick or untick task in line [i]; ticking adds "✅ date" like the Tasks plugin. */
    fun toggleTaskLine(text: String, i: Int): String {
        val lines = text.split("\n").toMutableList()
        val l = lines.getOrNull(i) ?: return text
        val m = TASK.find(l) ?: return text
        lines[i] = if (m.groupValues[2] == " ") l.replaceFirst("[ ]", "[x]") + " ✅ ${LocalDate.now()}"
        else l.replaceFirst(Regex("""\[[xX]\]"""), "[ ]").replace(Regex("""\s*✅ ?\d{4}-\d{2}-\d{2}"""), "")
        return lines.joinToString("\n")
    }
}

class MdHighlight(private val c: MdColors) : VisualTransformation {
    override fun filter(text: AnnotatedString) =
        TransformedText(Markdown.highlight(text.text, c), OffsetMapping.Identity)
}
