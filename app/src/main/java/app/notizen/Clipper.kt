package app.notizen

import net.dankito.readability4j.extended.Readability4JExtended
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

/** Turns shared text, links and web pages into Markdown notes. */
object Clipper {
    const val FOLDER = "Clippings"
    private val URL_RE = Regex("""https?://[^\s"<>“”]+""")
    private const val UA =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

    sealed interface Share {
        /** Only a link was shared: fetch and convert the whole page. */
        data class Page(val url: String, val title: String?) : Share
        /** Selected text, optionally with its source link. */
        data class Quote(val text: String, val url: String?, val title: String?) : Share
        /** Plain text from some other app. */
        data class Plain(val text: String, val title: String?) : Share
    }

    data class Clip(val title: String, val front: List<String>, val body: String)

    fun parseShare(text: String, subject: String?, selection: Boolean = false): Share {
        val t = text.trim()
        val sub = subject?.trim()?.takeIf { it.isNotEmpty() }
        val url = URL_RE.find(t)?.value?.trimEnd(')', '.', ',', ';')
        if (url == null) return if (selection) Share.Quote(t, null, sub) else Share.Plain(t, sub)
        val rest = t.replace(url, "").trim().trim('"', '“', '”', '„', '«', '»', '\'').trim()
        val sub2 = sub?.takeIf { it != url }
        return when {
            rest.isEmpty() || rest == sub2 -> Share.Page(url, sub2)
            // Chrome shares a selection as "“text”" plus a link to the highlighted passage.
            "#:~:text=" in url || selection -> Share.Quote(rest, url, sub2)
            // Many apps share "Title\nURL".
            '\n' !in rest && rest.length <= 120 -> Share.Page(url, sub2 ?: rest)
            else -> Share.Quote(rest, url, sub2)
        }
    }

    private fun host(url: String) = runCatching { URI(url).host.removePrefix("www.") }.getOrNull() ?: url
    private fun yaml(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun sourceLine(url: String, title: String? = null) = "Quelle: [${title ?: host(url)}]($url)"

    fun quote(q: Share.Quote): Clip {
        val body = q.text.lines().joinToString("\n") { "> $it".trimEnd() } +
            (q.url?.let { "\n\n" + sourceLine(it, q.title) } ?: "") + "\n"
        val title = q.title ?: q.text.lineSequence().first().take(50)
        return Clip(title, listOfNotNull(q.url?.let { "source: ${yaml(it)}" }), body)
    }

    fun failed(p: Share.Page, e: Throwable) = Clip(
        p.title ?: host(p.url),
        listOf("source: ${yaml(p.url)}"),
        sourceLine(p.url) + "\n\n⚠ Seite konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}\n",
    )

    /** Blocking: download, keep the article part, convert to Markdown. */
    fun page(p: Share.Page): Clip {
        val res = Jsoup.connect(p.url).userAgent(UA).timeout(20_000).followRedirects(true).execute()
        return fromHtml(res.url().toString(), res.body(), p.title)
    }

    fun fromHtml(url: String, html: String, fallbackTitle: String? = null): Clip {
        val article = Readability4JExtended(url, html).parse()
        val content = article.articleContent ?: Jsoup.parse(html, url).body()
        val title = article.title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle ?: host(url)
        val front = buildList {
            add("source: ${yaml(url)}")
            article.byline?.trim()?.takeIf { it.isNotEmpty() && it.length < 100 }?.let { add("author: ${yaml(it)}") }
        }
        val md = HtmlToMd.convert(content).removePrefix("# $title").trimStart()
        return Clip(title, front, sourceLine(url) + "\n\n" + md + "\n")
    }
}

/** Minimal HTML → Markdown for article content. */
object HtmlToMd {
    private val SKIP = setOf("script", "style", "noscript", "svg", "nav", "aside", "form", "button", "iframe", "input", "select", "template")
    private val BLOCK = setOf(
        "p", "div", "section", "article", "main", "header", "footer", "figure", "figcaption", "details", "summary",
        "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "pre", "hr", "table", "dl", "dt", "dd",
    ) + SKIP

    fun convert(root: Element): String =
        blocks(root).joinToString("\n\n").replace(Regex("\n{3,}"), "\n\n").trim()

    private fun blocks(el: Element): List<String> {
        val res = mutableListOf<String>()
        val para = StringBuilder()
        fun flush() {
            val t = para.toString().lines().joinToString("\n") { it.trim() }.trim()
            if (t.isNotEmpty()) res += t
            para.clear()
        }
        for (n in el.childNodes()) {
            if (n is Element && n.tagName() in BLOCK) { flush(); res += block(n) } else para.append(inline(n))
        }
        flush()
        return res
    }

    private fun block(e: Element): List<String> = when (val tag = e.tagName()) {
        in SKIP -> emptyList()
        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            val t = inlineChildren(e).trim().replace("\n", " ")
            if (t.isEmpty()) emptyList() else listOf("#".repeat(tag[1].digitToInt()) + " " + t)
        }
        "ul", "ol" -> listOf(list(e, 0)).filter { it.isNotBlank() }
        "blockquote" -> listOf(blocks(e).joinToString("\n\n").lines().joinToString("\n") { "> $it".trimEnd() })
        "pre" -> listOf("```\n" + e.wholeText().trimEnd('\n') + "\n```")
        "hr" -> listOf("---")
        "table" -> listOf(table(e)).filter { it.isNotBlank() }
        else -> blocks(e)
    }

    private fun list(e: Element, depth: Int): String {
        val ordered = e.tagName() == "ol"
        val out = mutableListOf<String>()
        e.children().filter { it.tagName() == "li" }.forEachIndexed { i, li ->
            val marker = if (ordered) "${i + 1}. " else "- "
            val text = li.childNodes().filterNot { it is Element && it.tagName() in setOf("ul", "ol") }
                .joinToString("") { inline(it) }.replace(Regex("\\s*\n\\s*"), " ").trim()
            out += "\t".repeat(depth) + marker + text
            li.children().filter { it.tagName() in setOf("ul", "ol") }.forEach { out += list(it, depth + 1) }
        }
        return out.joinToString("\n")
    }

    private fun table(e: Element): String {
        val rows = e.select("tr").map { tr ->
            tr.children().filter { it.tagName() in setOf("td", "th") }
                .map { inlineChildren(it).trim().replace("\n", " ").replace("|", "\\|") }
        }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return ""
        val w = rows.maxOf { it.size }
        fun row(r: List<String>) = "| " + (r + List(w - r.size) { "" }).joinToString(" | ") + " |"
        return (listOf(row(rows[0]), "|" + " --- |".repeat(w)) + rows.drop(1).map(::row)).joinToString("\n")
    }

    private fun inlineChildren(e: Element) = e.childNodes().joinToString("") { inline(it) }

    private fun wrap(mark: String, inner: String): String {
        val t = inner.trim()
        if (t.isEmpty()) return inner
        val lead = if (inner.first().isWhitespace()) " " else ""
        val trail = if (inner.last().isWhitespace()) " " else ""
        return "$lead$mark$t$mark$trail"
    }

    private fun inline(n: Node): String = when (n) {
        is TextNode -> n.text().replace(Regex("\\s+"), " ")
        is Element -> when (n.tagName()) {
            in SKIP -> ""
            "br" -> "\n"
            "strong", "b" -> wrap("**", inlineChildren(n))
            "em", "i" -> wrap("*", inlineChildren(n))
            "del", "s", "strike" -> wrap("~~", inlineChildren(n))
            "code", "kbd" -> n.text().takeIf { it.isNotBlank() }?.let { "`$it`" } ?: ""
            "img" -> image(n)
            "a" -> {
                val t = inlineChildren(n).trim().replace("[", "").replace("]", "")
                val href = n.absUrl("href")
                if (t.isEmpty() || href.isEmpty() || !href.startsWith("http")) t else "[$t]($href)"
            }
            else -> if (n.tagName() in BLOCK) " " + blocks(n).joinToString(" ") + " " else inlineChildren(n)
        }
        else -> ""
    }

    private fun image(img: Element): String {
        val src = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
        if (!src.startsWith("http")) return ""
        val alt = img.attr("alt").replace(Regex("[\\[\\]\\n]"), " ").trim()
        return "![$alt]($src)"
    }
}
