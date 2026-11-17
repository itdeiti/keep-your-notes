package app.notizen

import app.notizen.Clipper.Share
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipperTest {
    @Test fun bareLinkIsPage() {
        assertEquals(Share.Page("https://ex.com/a", "Titel"), Clipper.parseShare("https://ex.com/a", "Titel"))
    }

    @Test fun titleThenLinkIsPage() {
        assertEquals(Share.Page("https://ex.com/a", "Ein Artikel"), Clipper.parseShare("Ein Artikel\nhttps://ex.com/a", null))
    }

    @Test fun chromeSelectionIsQuote() {
        val s = Clipper.parseShare("“Der wichtige Satz.”\nhttps://ex.com/a#:~:text=Der%20wichtige", "Seite")
        assertEquals(Share.Quote("Der wichtige Satz.", "https://ex.com/a#:~:text=Der%20wichtige", "Seite"), s)
        val c = Clipper.quote(s as Share.Quote)
        assertEquals("Seite", c.title)
        assertEquals("> Der wichtige Satz.\n\nQuelle: [Seite](https://ex.com/a#:~:text=Der%20wichtige)\n", c.body)
        assertEquals(listOf("source: \"https://ex.com/a#:~:text=Der%20wichtige\""), c.front)
    }

    @Test fun textWithoutLink() {
        assertEquals(Share.Plain("Hallo", null), Clipper.parseShare("Hallo", null))
        assertEquals(Share.Quote("Hallo", null, null), Clipper.parseShare("Hallo", null, selection = true))
    }

    @Test fun pageToMarkdown() {
        val html = """
            <html><head><title>Kaffee kochen | Blog</title></head><body>
            <nav><a href="/">Home</a> <a href="/x">Menü</a></nav>
            <article>
              <h1>Kaffee kochen</h1>
              <p>Guter Kaffee braucht <strong>frische Bohnen</strong> und <em>Geduld</em>.<sup><a href="#fn1">[1]</a></sup> Mehr bei
                 <a href="/tipps">den Tipps</a>. Das ist ein langer Absatz, damit Readability ihn als Inhalt erkennt,
                 mit vielen Wörtern, Kommas, und noch mehr Text, der den Inhalt eines echten Artikels simuliert.</p>
              <h2>Zutaten</h2>
              <ul><li>Bohnen<ul><li>gemahlen</li></ul></li><li>Wasser</li></ul>
              <ol><li>Mahlen</li><li>Brühen</li></ol>
              <blockquote><p>Kaffee ist Liebe.</p></blockquote>
              <pre><code>temp = 94</code></pre>
              <img src="/bild.jpg" alt="Tasse">
              <table><tr><th>Sorte</th><th>Gramm</th></tr><tr><td>Arabica</td><td>18</td></tr></table>
              <p>Noch ein Absatz mit genug Text, damit der Algorithmus zufrieden ist, und noch ein paar Kommas, hier, da, dort.</p>
            </article>
            <aside>Werbung</aside>
            <script>alert(1)</script>
            </body></html>
        """.trimIndent()
        val c = Clipper.fromHtml("https://blog.example.com/kaffee", html)
        val b = c.body
        println(b)
        assertTrue(b.startsWith("Quelle: [blog.example.com](https://blog.example.com/kaffee)"))
        assertTrue(b.contains("**frische Bohnen**"))
        assertTrue(b.contains("*Geduld*"))
        assertTrue(b.contains("[den Tipps](https://blog.example.com/tipps)"))
        assertTrue(b.contains("## Zutaten"))
        assertTrue(b.contains("- Bohnen\n\t- gemahlen\n- Wasser"))
        assertTrue(b.contains("1. Mahlen\n2. Brühen"))
        assertTrue(b.contains("> Kaffee ist Liebe."))
        assertTrue(b.contains("```\ntemp = 94\n```"))
        assertTrue(b.contains("![Tasse](https://blog.example.com/bild.jpg)"))
        assertTrue(b.contains("| Sorte | Gramm |\n| --- | --- |\n| Arabica | 18 |"))
        assertFalse(b.contains("Werbung"))
        assertFalse(b.contains("alert"))
        assertFalse(b.contains("Menü"))
        assertTrue(b.contains("[1](https://blog.example.com/kaffee#fn1)"))
    }
}

class FilterTest {
    private fun note(path: String, tags: String, body: String = "x", pinned: Boolean = false, t: Long = 0) =
        Note(path, listOfNotNull("tags: [$tags]", if (pinned) "pinned: true" else null), body, t)

    private val notes = listOf(
        note("wissen/Pasta.md", "kochen, rezept", t = 3),
        note("wissen/Kuchen.md", "kochen", body = "Mehl #backen", t = 2),
        note("bereiche/Steuer.md", "finanzen", t = 1),
        note("bereiche/Zahnarzt.md", "gesundheit", pinned = true, t = 0),
    )

    private fun titles(tag: String?, q: String) = filterNotes(notes, tag, q).map { it.title }

    @Test fun hashSearchMatchesTagsByPart() {
        assertEquals(listOf("Pasta", "Kuchen"), titles(null, "#ko"))
        assertEquals(listOf("Kuchen"), titles(null, "#back")) // inline #tag in text counts too
        assertEquals(emptyList<String>(), titles(null, "#xyz"))
    }

    @Test fun plainSearchIsFullText() {
        assertEquals(listOf("Kuchen"), titles(null, "mehl"))
        assertEquals(listOf("Steuer"), titles(null, "steu"))
    }

    @Test fun selectedTagAndPinnedFirst() {
        assertEquals(listOf("Pasta", "Kuchen"), titles("kochen", ""))
        assertEquals(listOf("Zahnarzt", "Pasta", "Kuchen", "Steuer"), titles(null, ""))
        assertEquals(listOf("Zahnarzt", "Pasta", "Kuchen", "Steuer"), titles(null, "#"))
    }
}

class PropsTest {
    private val front = listOf(
        "created: 2026-11-17T14:32",
        "source: \"https://ex.com/a#b\"",
        "aliases:",
        "  - Eins",
        "  - Zwei",
        "tags: [kochen]",
        "due: 2026-12-01",
        "ort: Berlin",
    )

    @Test fun parsesScalarsListsAndQuotes() {
        val p = props(front).associateBy { it.key }
        assertEquals("2026-11-17T14:32", p["created"]!!.value)
        assertEquals("https://ex.com/a#b", p["source"]!!.value)
        assertEquals(Prop("aliases", "Eins, Zwei", true), p["aliases"])
        assertTrue(isDateProp(p["created"]!!))
        assertTrue(isDateProp(p["due"]!!))
        assertFalse(isDateProp(p["ort"]!!))
    }

    @Test fun setKeepsPositionAndRemoves() {
        val f = withProp(front, "aliases", "Eins, Drei", list = true)
        assertEquals("aliases: [Eins, Drei]", f[2])
        assertEquals("tags: [kochen]", f[3]) // list lines of the old value are gone
        assertEquals("due: 2027-01-05", withProp(f, "due", "2027-01-05")[4])
        assertEquals(listOf("created: 2026-11-17T14:32"), withProp(listOf("created: 2026-11-17T14:32", "ort: x"), "ort", null))
        assertEquals("notiz: \"Achtung: wichtig\"", withProp(emptyList(), "notiz", "Achtung: wichtig").single())
        assertEquals("Achtung: wichtig", props(listOf("notiz: \"Achtung: wichtig\"")).single().value)
        assertEquals("mein_feld", cleanKey(" mein feld! "))
    }
}

class FolderTest {
    @Test fun sortsParaFirstAndCleans() {
        assertEquals(
            listOf("inbox", "projekte", "projekte/umzug", "bereiche", "wissen", "archiv", "Clippings", "zeug"),
            Vault.sortFolders(listOf("zeug", "archiv", "Clippings", "projekte/umzug", "wissen", "inbox", "bereiche", "projekte")),
        )
        assertEquals("projekte/Umzug 2027", Vault.cleanFolder(" projekte / Umzug 2027/ "))
        assertEquals("a/b", Vault.cleanFolder("a/../.git/b"))
    }

    @Test fun listsRealFolders() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        listOf("projekte/umzug", ".git/objects", "wissen", "Clippings").forEach { java.io.File(root, it).mkdirs() }
        assertEquals(
            listOf("inbox", "projekte", "projekte/umzug", "bereiche", "wissen", "archiv", "Clippings"),
            Vault.folders(root),
        )
        root.deleteRecursively()
    }
}
