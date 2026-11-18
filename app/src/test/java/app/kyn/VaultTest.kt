package app.kyn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class VaultTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun parseWithAndWithoutFrontmatter() {
        assertEquals(listOf("created: 2026-11-17T14:32", "tags: [a]") to "Text\nZeile 2",
            Vault.parse("---\ncreated: 2026-11-17T14:32\ntags: [a]\n---\nText\nZeile 2"))
        assertEquals(emptyList<String>() to "Nur Text", Vault.parse("Nur Text"))
        // Windows line endings from other editors
        assertEquals(listOf("a: 1") to "x", Vault.parse("---\r\na: 1\r\n---\r\nx"))
    }

    @Test fun unterminatedFrontmatterIsBody() {
        val text = "---\nkein Ende\nText"
        assertEquals(emptyList<String>() to text, Vault.parse(text))
    }

    @Test fun horizontalRuleInBodyStaysBody() {
        val (front, body) = Vault.parse("---\na: 1\n---\noben\n---\nunten")
        assertEquals(listOf("a: 1"), front)
        assertEquals("oben\n---\nunten", body)
    }

    @Test fun serializeRoundTrip() {
        val front = listOf("created: 2026-11-17T14:32", "pinned: true")
        val body = "- [ ] Milch\n\nText"
        assertEquals(front to body, Vault.parse(Vault.serialize(front, body)))
        assertEquals(body, Vault.serialize(emptyList(), body))
    }

    @Test fun tagsInAllForms() {
        assertEquals(setOf("a", "b c"), frontTags(listOf("tags: [a, \"b c\"]")))
        assertEquals(setOf("x", "y"), frontTags(listOf("tags:", "  - x", "  - \"#y\"", "ort: z")))
        assertEquals(setOf("solo"), frontTags(listOf("tags: solo")))
        assertEquals(emptySet<String>(), frontTags(listOf("ort: z")))
        assertEquals(listOf("ort: z", "tags: [a, b]"), withTags(listOf("tags:", "  - x", "ort: z"), listOf("a", "b")))
        assertEquals(listOf("ort: z"), withTags(listOf("tags: [a]", "ort: z"), emptyList()))
    }

    @Test fun uniquePathAndWriteRead() {
        val root = tmp.root
        assertEquals("inbox/Einkauf.md", Vault.uniquePath(root, "inbox", "Einkauf"))
        Vault.write(root, "inbox/Einkauf.md", listOf("pinned: true"), "- [ ] Milch")
        assertEquals("inbox/Einkauf 2.md", Vault.uniquePath(root, "inbox", "Einkauf"))
        assertEquals("EinKauf.md", Vault.uniquePath(root, "", "Ein/Kauf?")) // no folders or odd characters from titles
        val n = Vault.read(root, File(root, "inbox/Einkauf.md"))
        assertEquals("inbox/Einkauf.md", n.path)
        assertEquals("inbox", n.folder)
        assertEquals("Einkauf", n.title)
        assertTrue(n.pinned)
        assertEquals("- [ ] Milch", n.body)
    }
}

class RemindersTest {
    private fun at(s: String) = LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun reminderWithTime() {
        val t = Reminders.parse("a.md", "- [ ] Zahnarzt 📅 2026-11-20 ⏰ 2026-11-20 14:30", "09:00")!!
        assertEquals(at("2026-11-20T14:30"), t.at)
        assertEquals("Zahnarzt", t.text)
    }

    @Test fun dueDateOnlyUsesDefaultTime() {
        assertEquals(at("2026-11-20T07:15"), Reminders.parse("a.md", "- [ ] Müll 📅 2026-11-20", "7:15")!!.at)
    }

    @Test fun otherTimeFormats() {
        assertEquals(at("2026-11-20T08:05"), Reminders.parse("a.md", "* [ ] X ⏰ 2026-11-20T8:05", "09:00")!!.at)
        assertEquals(at("2026-11-20T09:00"), Reminders.parse("a.md", "  - [ ] X ⏰ 2026-11-20", "09:00")!!.at)
    }

    @Test fun ignoredLines() {
        assertNull(Reminders.parse("a.md", "- [x] Erledigt 📅 2026-11-20", "09:00"))
        assertNull(Reminders.parse("a.md", "- [ ] Ohne Datum", "09:00"))
        assertNull(Reminders.parse("a.md", "Text 📅 2026-11-20", "09:00"))
        assertNull(Reminders.parse("a.md", "- [ ] Kaputt 📅 2026-02-30", "09:00"))
        assertNull(Reminders.parse("a.md", "- [ ] Kaputte Zeit ⏰ 2026-11-20 25:00", "09:00"))
    }
}
