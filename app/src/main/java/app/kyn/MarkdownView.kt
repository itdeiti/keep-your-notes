package app.kyn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/* Read mode of the editor: rendered Markdown with tappable checkboxes and links. */

/** Markdown table: header bold, equal columns, cells wrap; separator row is skipped. */
@Composable
private fun MdTable(rows: List<String>, c: MdColors, onTap: () -> Unit, onLink: (String) -> Unit, modifier: Modifier) {
    val data = rows.filterIndexed { i, _ -> i != 1 }.map { Markdown.cells(it) }
    val cols = data.maxOf { it.size }
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.border(1.dp, line, RoundedCornerShape(6.dp))) {
        data.forEachIndexed { r, cells ->
            if (r > 0) HorizontalDivider(color = line)
            Row(Modifier.fillMaxWidth().then(if (r == 0) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)) {
                for (ci in 0 until cols) {
                    MdText(
                        Markdown.render(cells.getOrElse(ci) { "" }, c),
                        MaterialTheme.typography.bodyMedium.copy(fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal),
                        onTap, onLink,
                        Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Read view: rendered lines, tappable checkboxes. Lines with Meld Encrypt sections show them locked or
 * decrypted ([SecretLine]); the decrypted text is only displayed, never put into [body].
 * [readOnly]: checkboxes cannot be ticked (decrypted content cannot be written back).
 */
@Composable
internal fun MarkdownView(
    body: String, c: MdColors, onToggle: (Int) -> Unit, onEdit: (Int) -> Unit, onLink: (String) -> Unit, modifier: Modifier,
    readOnly: Boolean = false,
    onEditSecret: (suspend (MeldCrypto.Inline, String) -> Boolean)? = null,
) {
    val base = MaterialTheme.typography.bodyLarge
    val lines = body.split("\n")
    // table blocks: header row followed by a |---| separator
    val tableAt = mutableMapOf<Int, Int>() // start line -> end (exclusive)
    var k = 0
    while (k < lines.size) {
        if (Markdown.isTableRow(lines[k]) && k + 1 < lines.size && Markdown.TABLE_SEP.matches(lines[k + 1])) {
            var e = k + 2
            while (e < lines.size && Markdown.isTableRow(lines[e])) e++
            tableAt[k] = e; k = e
        } else k++
    }
    Column(modifier) {
        var skipUntil = -1
        lines.forEachIndexed { i, line ->
          if (i < skipUntil) return@forEachIndexed
          val tableEnd = tableAt[i]
          if (tableEnd != null) {
              skipUntil = tableEnd
              val edit = { onEdit(tableEnd - 1) }
              MdTable(lines.subList(i, tableEnd), c, edit, onLink, Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = edit))
              return@forEachIndexed
          }
          val secrets = MeldCrypto.findInline(line)
          if (secrets.isNotEmpty()) {
              SecretLine(line, secrets, c, onLink, Modifier.fillMaxWidth().padding(vertical = 4.dp), onEditSecret)
              return@forEachIndexed
          }
          val edit = { onEdit(i) }
          Box(Modifier.fillMaxWidth().clickable(onClick = edit)) {
            val task = Markdown.TASK.find(line)
            val head = Markdown.HEADING.find(line)
            val bullet = Markdown.BULLET.find(line)
            val lead = line.takeWhile { it == ' ' || it == '\t' }
            val indent = (lead.count { it == ' ' } + lead.count { it == '\t' } * 4).coerceAtMost(16) * 6
            when {
                task != null -> Row(Modifier.padding(start = indent.dp), verticalAlignment = Alignment.CenterVertically) {
                    val done = task.groupValues[2] != " "
                    Checkbox(checked = done, onCheckedChange = if (readOnly) null else { _ -> onToggle(i) })
                    MdText(
                        Markdown.render(line.substring(task.value.length), c),
                        base.copy(
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            color = if (done) c.marker else MaterialTheme.colorScheme.onSurface,
                        ),
                        edit, onLink,
                    )
                }
                head != null -> MdText(
                    Markdown.render(line.substring(head.value.length), c),
                    when (head.groupValues[1].length) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }.copy(fontWeight = FontWeight.Bold),
                    edit, onLink, Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                bullet != null -> Row(Modifier.padding(start = indent.dp, top = 2.dp)) {
                    val mark = bullet.groupValues[1]
                    Text(if (mark[0].isDigit()) "$mark " else "•  ", style = base, color = c.marker)
                    MdText(Markdown.render(line.substring(bullet.value.length), c), base, edit, onLink)
                }
                line.startsWith(">") -> MdText(
                    Markdown.render(line.removePrefix(">").trimStart(), c),
                    base.copy(fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.secondary),
                    edit, onLink, Modifier.padding(start = 12.dp, top = 2.dp),
                )
                line.trim() == "---" -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                line.isBlank() -> Spacer(Modifier.height(10.dp))
                else -> MdText(Markdown.render(line, c), base, edit, onLink)
            }
          }
        }
    }
}

/**
 * Rendered text whose links react to taps; a tap anywhere else calls [onTap].
 * Own gesture handling instead of LinkAnnotation, because the surrounding line/table/view
 * is itself clickable (tap = edit) and must not swallow or race with link taps.
 */
@Composable
private fun MdText(text: AnnotatedString, style: TextStyle, onTap: () -> Unit, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tapNow by rememberUpdatedState(onTap)
    val linkNow by rememberUpdatedState(onLink)
    val hasLinks = remember(text) { text.getStringAnnotations(Markdown.LINK_TAG, 0, text.length).isNotEmpty() }
    val tap = if (!hasLinks) modifier else modifier.pointerInput(text) {
        detectTapGestures { pos ->
            val l = layout
            val line = l?.getLineForVerticalPosition(pos.y)
            val inside = l != null && line != null && pos.x >= l.getLineLeft(line) && pos.x <= l.getLineRight(line)
            val off = if (inside) l!!.getOffsetForPosition(pos) else -1
            val hit = if (off < 0) null else text.getStringAnnotations(Markdown.LINK_TAG, off, off + 1).firstOrNull()
            if (hit != null) linkNow(hit.item) else tapNow()
        }
    }
    Text(text, tap, style = style, onTextLayout = { layout = it })
}

/** Allowed links open outside the app, other schemes are refused, everything else is looked up as a note. */
internal fun openLink(ctx: android.content.Context, vm: AppVM, raw: String) {
    when (val t = Markdown.linkTarget(raw)) {
        is Markdown.Target.Web -> try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(ctx, ctx.getString(R.string.mdview_no_app), Toast.LENGTH_SHORT).show()
        }
        is Markdown.Target.Blocked -> Toast.makeText(ctx, ctx.getString(R.string.mdview_link_blocked), Toast.LENGTH_SHORT).show()
        is Markdown.Target.Note -> if (!vm.openNote(t.name)) Toast.makeText(ctx, ctx.getString(R.string.mdview_note_not_found, t.name), Toast.LENGTH_SHORT).show()
    }
}
