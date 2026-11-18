package app.kyn

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class QuickTaskTest {
    private val d = LocalDate.of(2026, 11, 18)

    @Test fun lineFormats() {
        assertEquals("- [ ] Arzt anrufen 📅 2026-11-18 ⏰ 2026-11-18 09:05", QuickTask.line(" Arzt\n anrufen ", d, LocalTime.of(9, 5)))
        assertEquals("- [ ] Arzt 📅 2026-11-18", QuickTask.line("Arzt", d, null))
        assertEquals("- [ ] Arzt", QuickTask.line("Arzt", null, null))
    }

    @Test fun lineIsReadByOverviewAndReminders() {
        val t = Tasks.parse(QuickTask.FILE, QuickTask.line("Arzt", d, LocalTime.of(9, 5)))!!
        assertEquals("Arzt", t.text)
        assertEquals(d, t.date)
        assertEquals("09:05", t.time)
    }

    @Test fun appendKeepsBody() {
        assertEquals("- [ ] a\n", QuickTask.append("", "- [ ] a"))
        assertEquals("# Aufgaben\n- [ ] a\n- [ ] b\n", QuickTask.append("# Aufgaben\n- [ ] a\n\n", "- [ ] b"))
    }
}
