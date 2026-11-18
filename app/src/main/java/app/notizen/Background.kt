package app.notizen

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.os.Environment
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class App : Application() {
    companion object {
        /** Work that must outlive the screen that started it (e.g. the widget dialog closing right away). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()
        // JGit looks for ~/.gitconfig; Android has no home directory.
        System.setProperty("user.home", filesDir.absolutePath)
        Reminders.createChannel(this)
        Conflicts.createChannel(this)
        SyncWorker.schedulePeriodic(this)
    }
}

/** Push as soon as there is network; also a pull every few hours so reminders from the Mac arrive. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        GitSync.sync(applicationContext)
        Reminders.rescan(applicationContext)
        NotesWidget.refresh(applicationContext)
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS).setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(ctx.packageName, R.layout.widget)
        v.setOnClickPendingIntent(R.id.w_note, launch(ctx, "note", 1))
        v.setOnClickPendingIntent(R.id.w_list, launch(ctx, "list", 2))
        v.setOnClickPendingIntent(R.id.w_mic, launch(ctx, "dictate", 3))
        ids.forEach { mgr.updateAppWidget(it, v) }
    }

    companion object {
        fun launch(ctx: Context, kind: String, code: Int): PendingIntent = PendingIntent.getActivity(
        ctx, code,
        Intent(ctx, MainActivity::class.java).setAction("new/$kind").putExtra("new", kind)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Keep-style widget: note cards with tappable checkboxes; tapping text opens the note for editing. */
class NotesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val app = ctx.applicationContext
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { update(app, mgr, ids) } catch (_: Exception) {} finally { pr.finish() }
        }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) = WidgetFilter.delete(ctx, ids)

    companion object {
        private const val MAX_ROWS = 8
        private val plain = MdColors(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)

        /** Call after notes changed (save, sync, delete). Blocking, run off the main thread. */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NotesWidget::class.java))
            if (ids.isNotEmpty()) runCatching { update(ctx, mgr, ids) }
        }

        private sealed interface Row {
            data class Title(val text: String) : Row
            data class Text(val text: String) : Row
            data class Task(val line: String, val text: String, val done: Boolean) : Row
        }

        /** A note as widget rows: title, text blocks and tasks in file order, capped. */
        private fun rows(n: Note, content: WidgetFilter.Content): List<Row> {
            val out = mutableListOf<Row>(Row.Title((if (n.pinned) "📌 " else "") + n.title))
            if (content == WidgetFilter.Content.Titles) return out
            val tasksOnly = content != WidgetFilter.Content.All
            val openOnly = content == WidgetFilter.Content.OpenTasks
            val block = mutableListOf<String>()
            var shown = 0
            var hidden = 0
            fun flush() {
                if (block.isEmpty()) return
                val t = Markdown.preview(block.joinToString("\n"), plain, if (out.size == 1) n.title else "", maxLines = 5).text
                if (t.isNotBlank()) { out += Row.Text(t); shown++ }
                block.clear()
            }
            for (line in n.body.split("\n")) {
                if (shown >= MAX_ROWS) {
                    val t = Markdown.TASK.find(line)
                    val counts = if (t == null) line.isNotBlank() && !tasksOnly else !openOnly || t.groupValues[2] == " "
                    if (counts) hidden++
                    continue
                }
                val m = Markdown.TASK.find(line)
                if (m == null) { if (line.isNotBlank() && !tasksOnly) block += line; continue }
                if (openOnly && m.groupValues[2] != " ") continue
                flush()
                val text = Markdown.render(Markdown.stripTaskMeta(line.substring(m.value.length)), plain, false).text
                val due = Regex("""📅 ?\d{4}-(\d{2})-(\d{2})""").find(line)
                    ?.let { "  ·  ${it.groupValues[2].toInt()}.${it.groupValues[1].toInt()}." } ?: ""
                out += Row.Task(line, text + due, m.groupValues[2] != " ")
                shown++
            }
            flush()
            if (hidden > 0) out += Row.Text("+$hidden weitere")
            return out
        }

        private fun bg(i: Int, size: Int) = when {
            size == 1 -> R.drawable.wcard_single
            i == 0 -> R.drawable.wcard_top
            i == size - 1 -> R.drawable.wcard_bottom
            else -> R.drawable.wcard_mid
        }

        private fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val all = if (Environment.isExternalStorageManager()) Vault.all(ctx)
                .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.modified }) else emptyList()
            for (id in ids) {
                val f = WidgetFilter.load(ctx, id)
                runCatching { mgr.updateAppWidget(id, views(ctx, id, f, f.apply(all).take(30))) }
            }
        }

        private fun views(ctx: Context, id: Int, f: WidgetFilter, notes: List<Note>): RemoteViews {
            val px = ctx.resources.displayMetrics.density
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(3)
            for (n in notes) {
                val rs = rows(n, f.content)
                val edit = Intent().putExtra("edit", n.path)
                rs.forEachIndexed { i, r ->
                    val v = when (r) {
                        is Row.Title -> RemoteViews(ctx.packageName, R.layout.widget_item_title).apply {
                            setTextViewText(R.id.wc_text, r.text)
                            setOnClickFillInIntent(R.id.wc_root, edit)
                        }
                        is Row.Text -> RemoteViews(ctx.packageName, R.layout.widget_item_text).apply {
                            setTextViewText(R.id.wc_text, r.text)
                            setOnClickFillInIntent(R.id.wc_root, edit)
                        }
                        is Row.Task -> RemoteViews(ctx.packageName, R.layout.widget_item_task).apply {
                            setCompoundButtonChecked(R.id.wc_check, r.done)
                            setTextViewText(R.id.wc_text, r.text)
                            setInt(R.id.wc_text, "setPaintFlags",
                                if (r.done) Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG else Paint.ANTI_ALIAS_FLAG)
                            setFloat(R.id.wc_text, "setAlpha", if (r.done) 0.55f else 1f)
                            setOnCheckedChangeResponse(
                                R.id.wc_check,
                                RemoteViews.RemoteResponse.fromFillInIntent(
                                    Intent().putExtra("toggle", n.path).putExtra("line", r.line)
                                ),
                            )
                            setOnClickFillInIntent(R.id.wc_text, edit) // the row root does not get clicks next to a CheckBox
                        }
                    }
                    v.setInt(R.id.wc_root, "setBackgroundResource", bg(i, rs.size))
                    if (i == rs.size - 1) { // room for the gap below the card (inset in wcard_bottom)
                        val start = if (r is Row.Task) 2 else 12
                        val top = if (r is Row.Title) 10 else 0
                        v.setViewPadding(R.id.wc_root, (start * px).toInt(), (top * px).toInt(), (12 * px).toInt(), (12 * px).toInt())
                    }
                    items.addItem("${n.path}#$i".hashCode().toLong(), v)
                }
            }
            val v = RemoteViews(ctx.packageName, R.layout.widget_list)
            v.setRemoteAdapter(R.id.wl_items, items.build())
            v.setEmptyView(R.id.wl_items, R.id.wl_empty)
            v.setTextViewText(R.id.wl_title, f.title.ifBlank { "Notizen" })
            v.setTextViewText(R.id.wl_empty, if (f.restricted || f.content != WidgetFilter.Content.All) "Nichts in dieser Auswahl" else "Noch keine Notizen")
            v.setPendingIntentTemplate(
                R.id.wl_items,
                PendingIntent.getActivity(
                    ctx, 10,
                    Intent(ctx, WidgetAction::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ),
            )
            v.setOnClickPendingIntent(R.id.wl_new, QuickWidget.launch(ctx, "note", 11))
            v.setOnClickPendingIntent(R.id.wl_list, QuickWidget.launch(ctx, "list", 12))
            v.setOnClickPendingIntent(
                R.id.wl_title,
                PendingIntent.getActivity(ctx, 13, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            return v
        }
    }
}

/**
 * Invisible trampoline for clicks in the widget list (a list allows only one target):
 * "toggle" ticks a task without showing anything, "edit" opens the note.
 */
class WidgetAction : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toggle = intent.getStringExtra("toggle")
        val line = intent.getStringExtra("line")
        val edit = intent.getStringExtra("edit")
        val app = applicationContext
        when {
            toggle != null && line != null -> CoroutineScope(Dispatchers.IO).launch {
                runCatching { Reminders.toggle(app, toggle, line) }
                    .onFailure { NotesWidget.refresh(app) } // reset the checkbox if the line moved
            }
            edit != null -> startActivity(
                Intent(this, MainActivity::class.java).putExtra("edit", edit)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
