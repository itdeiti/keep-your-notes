package app.kyn

import android.content.res.Resources
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Recurring tasks in the Obsidian Tasks format: `- [ ] Müll 📅 2026-11-20 🔁 every week`.
 * Ticking one inserts the next occurrence above it with all dates moved, like the Tasks plugin.
 *
 * Understood: every [N] day(s)/week(s)/month(s)/year(s), every weekday, every monday[, friday],
 * every [N] week(s) on monday[, friday], each with an optional "when done" (next date counts from
 * the day it was ticked instead of from the due date).
 */
object Recurrence {
    /** The rule text after 🔁, up to the next emoji. */
    val RULE = Regex("""🔁 ?([A-Za-z0-9][A-Za-z0-9, ]*)""")
    private val DATES = Regex("""(📅|⏳|🛫|⏰) ?(\d{4}-\d{2}-\d{2})""")
    private val DAYS = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )
    private val WORKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    data class Rule(val n: Int, val unit: ChronoUnit, val days: Set<DayOfWeek> = emptySet(), val whenDone: Boolean = false) {
        /** First date after [base] that matches. */
        fun next(base: LocalDate): LocalDate {
            if (days.isEmpty()) return base.plus(n.toLong(), unit)
            val week0 = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            var d = base.plusDays(1)
            while (true) {
                val weeks = ChronoUnit.WEEKS.between(week0, d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
                if (d.dayOfWeek in days && weeks % n == 0L) return d
                d = d.plusDays(1)
            }
        }

        /** Label for the UI in the app's language ("weekly", "alle 2 Wochen Mo., Fr." …). */
        fun label(res: Resources): String {
            val loc = res.configuration.locales[0]
            val base = when {
                days == WORKDAYS && n == 1 -> res.getString(R.string.recur_weekdays)
                days.isNotEmpty() -> res.getQuantityString(
                    R.plurals.recur_every_n_weeks_on, n, n,
                    days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, loc) },
                )
                n == 1 -> res.getString(
                    when (unit) {
                        ChronoUnit.DAYS -> R.string.recur_daily
                        ChronoUnit.WEEKS -> R.string.recur_weekly
                        ChronoUnit.MONTHS -> R.string.recur_monthly
                        else -> R.string.recur_yearly
                    },
                )
                else -> res.getQuantityString(
                    when (unit) {
                        ChronoUnit.DAYS -> R.plurals.recur_every_n_days
                        ChronoUnit.WEEKS -> R.plurals.recur_every_n_weeks
                        ChronoUnit.MONTHS -> R.plurals.recur_every_n_months
                        else -> R.plurals.recur_every_n_years
                    },
                    n, n,
                )
            }
            return if (whenDone) res.getString(R.string.recur_when_done, base) else base
        }
    }

    /** Rule of a task line, or null if it has none or it is not understood. */
    fun of(line: String): Rule? = RULE.find(line)?.let { parse(it.groupValues[1]) }

    fun parse(raw: String): Rule? {
        var s = raw.trim().lowercase().replace(Regex("""\s+"""), " ")
        if (!s.startsWith("every ")) return null
        s = s.removePrefix("every ")
        val whenDone = s.endsWith(" when done")
        s = s.removeSuffix(" when done").trim()
        if (s == "weekday") return Rule(1, ChronoUnit.WEEKS, WORKDAYS, whenDone)
        val m = Regex("""^(?:(\d+) )?(day|week|month|year)s?(?: on (.+))?$""").find(s)
        if (m != null) {
            val n = m.groupValues[1].ifEmpty { "1" }.toInt().takeIf { it > 0 } ?: return null
            val unit = when (m.groupValues[2]) {
                "day" -> ChronoUnit.DAYS; "week" -> ChronoUnit.WEEKS; "month" -> ChronoUnit.MONTHS; else -> ChronoUnit.YEARS
            }
            val on = m.groupValues[3]
            if (on.isEmpty()) return Rule(n, unit, whenDone = whenDone)
            if (unit != ChronoUnit.WEEKS) return null
            return days(on)?.let { Rule(n, unit, it, whenDone) }
        }
        return days(s)?.let { Rule(1, ChronoUnit.WEEKS, it, whenDone) }
    }

    private fun days(s: String): Set<DayOfWeek>? {
        val parts = s.split(Regex("""\s*(?:,|\band\b)\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.map { p -> DAYS.entries.firstOrNull { p.startsWith(it.key) }?.value ?: return null }.toSet()
    }

    /**
     * The next occurrence of the open task [line] (without ✅), or null if it does not recur.
     * Dates (📅 ⏳ 🛫 ⏰) move by the same number of days; the reference is 📅, else ⏳, 🛫, ⏰.
     */
    fun nextLine(line: String, today: LocalDate): String? {
        val rule = of(line) ?: return null
        val dates = DATES.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
        val ref = listOf("📅", "⏳", "🛫", "⏰").firstNotNullOfOrNull { k ->
            dates[k]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        } ?: return line
        val shift = ChronoUnit.DAYS.between(ref, rule.next(if (rule.whenDone) today else ref))
        return DATES.replace(line) { m ->
            val d = runCatching { LocalDate.parse(m.groupValues[2]) }.getOrNull() ?: return@replace m.value
            m.value.replace(m.groupValues[2], d.plusDays(shift).toString())
        }
    }

    /** Rules offered in the editor (Tasks plugin syntax). */
    val CHOICES = listOf("every day", "every weekday", "every week", "every 2 weeks", "every month", "every 3 months", "every year")
}
