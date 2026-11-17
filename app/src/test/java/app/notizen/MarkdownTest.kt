package app.notizen

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class MarkdownTest {
    private fun typed(before: String, insert: String): Pair<TextFieldValue, TextFieldValue> {
        val old = TextFieldValue(before, TextRange(before.length))
        val t = before + insert
        return old to TextFieldValue(t, TextRange(t.length))
    }

    @Test fun enterContinuesChecklist() {
        val (o, n) = typed("- [ ] Milch", "\n")
        assertEquals("- [ ] Milch\n- [ ] ", Markdown.continueList(o, n).text)
    }

    @Test fun enterAfterDoneTaskGivesOpenTask() {
        val (o, n) = typed("  - [x] Milch", "\n")
        assertEquals("  - [x] Milch\n  - [ ] ", Markdown.continueList(o, n).text)
    }

    @Test fun enterContinuesNumbers() {
        val (o, n) = typed("1. eins", "\n")
        assertEquals("1. eins\n2. ", Markdown.continueList(o, n).text)
    }

    @Test fun enterOnEmptyItemEndsList() {
        val (o, n) = typed("- [ ] Milch\n- [ ] ", "\n")
        val r = Markdown.continueList(o, n)
        assertEquals("- [ ] Milch\n", r.text)
        assertEquals(TextRange(12), r.selection)
    }

    @Test fun setDueMakesTaskWithReminder() {
        val v = TextFieldValue("Zahnarzt anrufen", TextRange(3))
        val r = Markdown.setDue(v, LocalDate.of(2026, 11, 20), LocalTime.of(9, 30))
        assertEquals("- [ ] Zahnarzt anrufen 📅 2026-11-20 ⏰ 2026-11-20 09:30", r.text)
    }

    @Test fun setDueReplacesOldDate() {
        val v = TextFieldValue("a\n- [ ] Steuer 📅 2026-01-01 ⏰ 2026-01-01 08:00\nb", TextRange(5))
        val r = Markdown.setDue(v, LocalDate.of(2026, 12, 1), null)
        assertEquals("a\n- [ ] Steuer 📅 2026-12-01\nb", r.text)
    }

    @Test fun toggleTaskAddsAndRemovesDoneDate() {
        val done = Markdown.toggleTaskLine("x\n- [ ] Brot 📅 2026-11-20", 1)
        assertEquals("x\n- [x] Brot 📅 2026-11-20 ✅ ${LocalDate.now()}", done)
        assertEquals("x\n- [ ] Brot 📅 2026-11-20", Markdown.toggleTaskLine(done, 1))
    }

    @Test fun headingCycles() {
        var v = TextFieldValue("Titel", TextRange(2))
        v = Markdown.cycleHeading(v); assertEquals("# Titel", v.text); assertEquals(TextRange(4), v.selection)
        v = Markdown.cycleHeading(v); assertEquals("## Titel", v.text)
        v = Markdown.cycleHeading(v); v = Markdown.cycleHeading(v); assertEquals("Titel", v.text)
    }

    @Test fun tagsAndStrip() {
        assertEquals(setOf("einkauf", "haus/garten"), Markdown.tags("Liste #einkauf und #haus/garten, nicht #1 oder a#b"))
        assertEquals("Brot", Markdown.stripTaskMeta("Brot 📅 2026-11-20 ⏰ 2026-11-20 09:00"))
    }

    @Test fun frontmatterRoundTrip() {
        val text = "---\ncreated: 2026-11-17T14:32\npinned: true\n---\n# Hallo\nText"
        val (front, body) = Vault.parse(text)
        assertEquals(listOf("created: 2026-11-17T14:32", "pinned: true"), front)
        assertEquals("# Hallo\nText", body)
        assertEquals(text, Vault.serialize(front, body))
    }
}
