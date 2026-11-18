package app.kyn

import androidx.annotation.StringRes
import java.time.LocalDate

/** Open tasks across the whole vault, grouped by due date for the "Aufgaben" screen. */
object Tasks {
    /** Task metadata, shared with [Reminders] and the widgets so all of them read a line the same way. */
    val DUE = Regex("""📅 ?(\d{4}-\d{2}-\d{2})""")
    private val SCHEDULED = Regex("""⏳ ?(\d{4}-\d{2}-\d{2})""")
    val REMIND = Regex("""⏰ ?(\d{4}-\d{2}-\d{2})(?:[ T](\d{1,2}:\d{2}))?""")

    enum class Group(@StringRes val label: Int) {
        Overdue(R.string.tasks_overdue), Today(R.string.tasks_today), Soon(R.string.tasks_soon),
        Later(R.string.tasks_later), NoDate(R.string.tasks_no_date),
    }

    /** One task line. [line] is the exact text in the file, used to find it again when ticking. */
    data class Item(
        val path: String,
        val line: String,
        val text: String,
        val date: LocalDate?,
        val time: String?,
        val done: Boolean = false,
        /** 🔁 rule, shown with [Recurrence.Rule.label]. */
        val recur: Recurrence.Rule? = null,
    ) {
        val key get() = "$path|$line"
    }

    /** Due date: 📅, else ⏳ (scheduled), else the date of ⏰. */
    fun parse(path: String, line: String): Item? {
        val m = Markdown.TASK.find(line) ?: return null
        val r = REMIND.find(line)
        val raw = DUE.find(line)?.groupValues?.get(1)
            ?: SCHEDULED.find(line)?.groupValues?.get(1)
            ?: r?.groupValues?.get(1)
        val date = raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val time = r?.groupValues?.get(2)?.ifEmpty { null }
        return Item(
            path, line, Markdown.stripTaskMeta(line.substring(m.value.length)), date, time,
            done = m.groupValues[2] != " ", recur = Recurrence.of(line),
        )
    }

    /** Lines of [body] that can hold tasks: everything outside ``` code blocks. */
    fun lines(body: String): List<String> {
        var code = false
        return body.lines().filter { l ->
            if (l.trimStart().startsWith("```")) { code = !code; false } else !code
        }
    }

    /** All open tasks; lines inside ``` code blocks are skipped. */
    fun open(notes: List<Note>): List<Item> = notes.flatMap { n ->
        lines(n.body).mapNotNull { l -> parse(n.path, l)?.takeIf { !it.done && it.text.isNotBlank() } }
    }

    fun groupOf(d: LocalDate?, today: LocalDate): Group = when {
        d == null -> Group.NoDate
        d < today -> Group.Overdue
        d == today -> Group.Today
        d <= today.plusDays(7) -> Group.Soon
        else -> Group.Later
    }

    /** Non-empty groups in [order], each sorted by date, time, note, text. */
    fun grouped(items: List<Item>, today: LocalDate, order: List<Group> = Group.entries): List<Pair<Group, List<Item>>> {
        val sorted = items.sortedWith(
            compareBy<Item, LocalDate?>(nullsLast()) { it.date }
                .thenBy(nullsLast()) { it.time?.padStart(5, '0') }
                .thenBy { titleOf(it.path).lowercase() }
                .thenBy { it.text.lowercase() },
        )
        val by = sorted.groupBy { groupOf(it.date, today) }
        return order.mapNotNull { g -> by[g]?.let { g to it } }
    }

    /** The line itself after ticking or unticking it (same rule as in the editor, without a new 🔁 occurrence). */
    fun toggled(line: String) = Markdown.toggleTaskLine(line, 0).split("\n").last()
}
