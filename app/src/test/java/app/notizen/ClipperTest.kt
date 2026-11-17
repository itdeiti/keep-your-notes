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
