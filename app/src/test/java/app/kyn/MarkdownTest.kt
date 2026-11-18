package app.kyn

import androidx.compose.ui.graphics.Color
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
        assertEquals(TextRange(22), r.selection)
    }

    @Test fun setDueReplacesOldDate() {
        val v = TextFieldValue("a\n- [ ] Steuer 📅 2026-01-01 ⏰ 2026-01-01 08:00\nb", TextRange(5))
        val r = Markdown.setDue(v, LocalDate.of(2026, 12, 1), null)
        assertEquals("a\n- [ ] Steuer 📅 2026-12-01\nb", r.text)
        assertEquals(TextRange(14), r.selection) // after "Steuer"
    }

    @Test fun setDueOnEmptyCheckboxKeepsOneBoxAndCursorBeforeDate() {
        val v = TextFieldValue("- [ ] ", TextRange(6))
        val r = Markdown.setDue(v, LocalDate.of(2026, 11, 17), LocalTime.of(9, 0))
        assertEquals("- [ ]  📅 2026-11-17 ⏰ 2026-11-17 09:00", r.text)
        assertEquals(TextRange(6), r.selection)
        // typing now lands between box and date
        assertEquals("- [ ] Brot 📅 2026-11-17 ⏰ 2026-11-17 09:00", Markdown.insert(r, "Brot").text)
    }

    @Test fun setDueOnBulletTurnsItIntoTask() {
        val r = Markdown.setDue(TextFieldValue("  - Milch", TextRange(3)), LocalDate.of(2026, 11, 17), null)
        assertEquals("  - [ ] Milch 📅 2026-11-17", r.text)
        assertEquals(TextRange(13), r.selection)
    }

    @Test fun checkboxOnEmptyLineCursorAfterBox() {
        val r = Markdown.toggleTask(TextFieldValue("Brot\n", TextRange(5)))
        assertEquals("Brot\n- [ ] ", r.text)
        assertEquals(TextRange(11), r.selection)
        val r2 = Markdown.toggleTask(TextFieldValue("Brot", TextRange(4)))
        assertEquals("- [ ] Brot", r2.text)
        assertEquals(TextRange(10), r2.selection)
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

    @Test fun tagsInFrontmatter() {
        val front = listOf("created: 2026-11-17T10:00", "tags:", "  - alt", "  - zwei", "pinned: true")
        assertEquals(setOf("alt", "zwei"), frontTags(front))
        val f2 = withTags(front, listOf("kochen", "neu-tag"))
        assertEquals(listOf("created: 2026-11-17T10:00", "pinned: true", "tags: [kochen, neu-tag]"), f2)
        assertEquals(setOf("kochen", "neu-tag"), frontTags(f2))
        assertEquals(listOf("created: 2026-11-17T10:00", "pinned: true"), withTags(f2, emptyList()))
        assertEquals("mein-tag", cleanTag(" #Mein Tag! "))
        assertEquals("haus/garten", cleanTag("Haus/Garten"))
    }

    // ---------- links in the read view ----------

    private val colors = MdColors(Color.Gray, Color.Blue, Color.Green, Color.LightGray, Color.Gray)

    private fun links(line: String) = Markdown.render(line, colors).let { r ->
        r.getStringAnnotations(Markdown.LINK_TAG, 0, r.length).map { r.text.substring(it.start, it.end) to it.item }
    }

    @Test fun markdownLinkIsTappable() {
        assertEquals(listOf("Seite" to "https://example.com/a"), links("siehe [Seite](https://example.com/a) hier"))
        assertEquals("siehe Seite hier", Markdown.render("siehe [Seite](https://example.com/a) hier", colors).text)
    }

    @Test fun angleBracketLinkTargetMayContainSpaces() {
        assertEquals(listOf("Plan" to "Projekte/Mein Plan.md"), links("[Plan](<Projekte/Mein Plan.md>)"))
    }

    @Test fun bareUrlIsTappableWithoutTrailingPunctuation() {
        assertEquals(listOf("https://example.com/x?a=1" to "https://example.com/x?a=1"), links("Link: https://example.com/x?a=1."))
        assertEquals(listOf("www.example.de" to "www.example.de"), links("(www.example.de)"))
    }

    @Test fun urlWithFragmentIsNoTag() {
        assertEquals(listOf("https://x.de/p#abschnitt" to "https://x.de/p#abschnitt"), links("https://x.de/p#abschnitt"))
    }

    @Test fun wikiLinkPointsToNote() {
        assertEquals(listOf("Alias" to "wiki:Andere Notiz"), links("[[Andere Notiz|Alias]]"))
    }

    @Test fun previewHasNoTappableLinks() {
        val r = Markdown.render("[a](https://x.de) https://y.de", colors, links = false)
        assertEquals(0, r.getStringAnnotations(Markdown.LINK_TAG, 0, r.length).size)
    }

    @Test fun linkTargets() {
        assertEquals(Markdown.Target.Web("https://www.x.de"), Markdown.linkTarget("www.x.de"))
        assertEquals(Markdown.Target.Web("mailto:a@b.de"), Markdown.linkTarget("mailto:a@b.de"))
        assertEquals(Markdown.Target.Note("Andere Notiz"), Markdown.linkTarget("wiki:Andere Notiz#Kapitel"))
        assertEquals(Markdown.Target.Note("Ordner/Mein Plan"), Markdown.linkTarget("Ordner/Mein%20Plan.md"))
    }

    @Test fun dangerousSchemesAreBlocked() {
        for (u in listOf("content://x/y", "intent://scan#Intent;end", "file:///sdcard/a", "javascript:alert(1)"))
            assertEquals(Markdown.Target.Blocked(u), Markdown.linkTarget(u))
        assertEquals(Markdown.Target.Web("HTTPS://x.de"), Markdown.linkTarget("HTTPS://x.de"))
        assertEquals(Markdown.Target.Web("tel:+491234"), Markdown.linkTarget("tel:+491234"))
    }
}
