package app.notizen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TasksTest {
    private val today = LocalDate.of(2026, 11, 17)
    private fun note(path: String, body: String) = Note(path, emptyList(), body, 0)

    @Test fun parsesDueDateAndStripsMeta() {
        val t = Tasks.parse("a.md", "- [ ] Zahnarzt anrufen 📅 2026-11-20 ⏰ 2026-11-20 09:00")!!
        assertEquals("Zahnarzt anrufen", t.text)
        assertEquals(LocalDate.of(2026, 11, 20), t.date)
        assertEquals("09:00", t.time)
    }

    @Test fun reminderDateIsUsedWithoutDueDate() {
        val t = Tasks.parse("a.md", "  * [ ] Müll ⏰ 2026-11-18 7:30")!!
        assertEquals(LocalDate.of(2026, 11, 18), t.date)
        assertEquals("7:30", t.time)
    }

    @Test fun noDateAndInvalidDate() {
        assertNull(Tasks.parse("a.md", "- [ ] Milch")!!.date)
        assertNull(Tasks.parse("a.md", "- [ ] Kaputt 📅 2026-13-40")!!.date)
        assertNull(Tasks.parse("a.md", "- Milch"))
    }

    @Test fun openSkipsDoneEmptyAndCodeBlocks() {
        val body = """
            - [ ] offen
            - [x] fertig ✅ 2026-11-01
            - [ ]
            ```
            - [ ] im Code
            ```
              - [ ] eingerückt
        """.trimIndent()
        assertEquals(listOf("offen", "eingerückt"), Tasks.open(listOf(note("n.md", body))).map { it.text })
    }

    @Test fun groupsInOrder() {
        val items = listOf(
            "- [ ] später 📅 2026-12-24",
            "- [ ] ohne",
            "- [ ] heute spät ⏰ 2026-11-17 18:00",
            "- [ ] heute früh ⏰ 2026-11-17 8:00",
            "- [ ] gestern 📅 2026-11-16",
            "- [ ] in einer Woche 📅 2026-11-24",
        ).map { Tasks.parse("n.md", it)!! }
        val g = Tasks.grouped(items, today)
        assertEquals(
            listOf(Tasks.Group.Overdue, Tasks.Group.Today, Tasks.Group.Soon, Tasks.Group.Later, Tasks.Group.NoDate),
            g.map { it.first },
        )
        assertEquals(listOf("heute früh", "heute spät"), g[1].second.map { it.text })
    }

    @Test fun toggledLineRoundTrips() {
        val l = "- [ ] Milch 📅 2026-11-20"
        val done = Tasks.toggled(l)
        assertTrue(done.startsWith("- [x] Milch 📅 2026-11-20 ✅ "))
        assertEquals(l, Tasks.toggled(done))
    }
}
