package app.notizen

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RecurrenceTest {
    private val today = LocalDate.of(2026, 11, 17) // Tuesday
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun parsesRules() {
        assertEquals("täglich", Recurrence.parse("every day")!!.label())
        assertEquals("alle 2 Wochen", Recurrence.parse("every 2 weeks")!!.label())
        assertEquals("werktags", Recurrence.parse("every weekday")!!.label())
        assertEquals("Mo, Fr", Recurrence.parse("every monday, friday")!!.label())
        assertEquals("Mo, Mi", Recurrence.parse("every week on Monday and Wednesday")!!.label())
        assertEquals("monatlich ab Erledigung", Recurrence.parse("every month when done")!!.label())
        assertNull(Recurrence.parse("every blue moon"))
        assertNull(Recurrence.parse("daily"))
    }

    @Test fun nextDates() {
        assertEquals(d("2026-11-24"), Recurrence.parse("every week")!!.next(d("2026-11-17")))
        assertEquals(d("2027-02-28"), Recurrence.parse("every month")!!.next(d("2027-01-31")))
        assertEquals(d("2026-11-23"), Recurrence.parse("every weekday")!!.next(d("2026-11-20"))) // Fri -> Mon
        assertEquals(d("2026-11-20"), Recurrence.parse("every monday, friday")!!.next(d("2026-11-17")))
        // every 2 weeks on monday: from Mon 16.11. skip the next week
        assertEquals(d("2026-11-30"), Recurrence.parse("every 2 weeks on monday")!!.next(d("2026-11-16")))
    }

    @Test fun tickInsertsNextOccurrenceAbove() {
        val text = "# Haushalt\n  - [ ] Müll 📅 2026-11-17 ⏰ 2026-11-17 07:00 🔁 every week\nEnde"
        val out = Markdown.toggleTaskLine(text, 1, today)
        assertEquals(
            "# Haushalt\n" +
                "  - [ ] Müll 📅 2026-11-24 ⏰ 2026-11-24 07:00 🔁 every week\n" +
                "  - [x] Müll 📅 2026-11-17 ⏰ 2026-11-17 07:00 🔁 every week ✅ 2026-11-17\n" +
                "Ende",
            out,
        )
        // untick the done one: the created occurrence goes away again
        assertEquals(text, Markdown.toggleTaskLine(out, 2, today))
    }

    @Test fun whenDoneCountsFromToday() {
        val l = "- [ ] Filter wechseln 📅 2026-10-01 🔁 every month when done"
        assertEquals("- [ ] Filter wechseln 📅 2026-12-17 🔁 every month when done", Recurrence.nextLine(l, today))
    }

    @Test fun normalTaskUnchanged() {
        assertEquals("- [x] Milch ✅ 2026-11-17", Markdown.toggleTaskLine("- [ ] Milch", 0, today))
        assertNull(Recurrence.nextLine("- [ ] Milch 📅 2026-11-17", today))
    }

    @Test fun stripAndOverview() {
        assertEquals("Müll", Markdown.stripTaskMeta("Müll 📅 2026-11-17 🔁 every week"))
        val t = Tasks.parse("a.md", "- [ ] Müll 📅 2026-11-17 🔁 every week")!!
        assertEquals("wöchentlich", t.recur)
        assertEquals("- [x] Müll 📅 2026-11-17 🔁 every week ✅ ${LocalDate.now()}", Tasks.toggled(t.line))
    }

    @Test fun setRecurrenceAddsDateAndReplacesRule() {
        val v = TextFieldValue("Müll raus", TextRange(3))
        val a = Markdown.setRecurrence(v, "every week", today)
        assertEquals("- [ ] Müll raus 📅 2026-11-17 🔁 every week", a.text)
        val b = Markdown.setRecurrence(a, "every day", today)
        assertEquals("- [ ] Müll raus 📅 2026-11-17 🔁 every day", b.text)
        assertEquals("- [ ] Müll raus 📅 2026-11-17", Markdown.setRecurrence(b, null, today).text)
    }
}
