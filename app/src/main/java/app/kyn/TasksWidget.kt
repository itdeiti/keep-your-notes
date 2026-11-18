package app.kyn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * Task list widget: every open task of the vault (or of the folders, tags and notes chosen in its
 * settings), grouped like the task overview (group order set in its settings) and sorted by due date. The checkbox ticks the task
 * without opening the app, the text opens the note, ＋ opens the quick task dialog.
 */
class TasksWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val app = ctx.applicationContext
        val pr = goAsync()
        App.scope.launch {
            try { update(app, mgr, ids) } catch (_: Exception) {} finally { pr.finish() }
        }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) = WidgetFilter.delete(ctx, ids)

    companion object {
        /** More rows make the widget slow to load and are never scrolled to. */
        private const val MAX_TASKS = 150

        /** Blocking, run off the main thread. Called by [NotesWidget.refresh] whenever notes change. */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TasksWidget::class.java))
            if (ids.isNotEmpty()) runCatching { update(ctx, mgr, ids) }
        }

        private fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val all by lazy { if (Vault.accessible(ctx)) Vault.all(ctx) else emptyList() }
            for (id in ids) {
                val f = WidgetFilter.load(ctx, id)
                val locked = WidgetFilter.hidden(ctx, id)
                val tasks = if (locked) emptyList() else f.tasks(all).take(MAX_TASKS)
                runCatching { mgr.updateAppWidget(id, views(ctx, f, tasks, locked)) }
            }
        }

        /** "📅 18.11. ⏰ 09:00  ·  🔁 weekly  ·  📄 Aufgaben" – due date, reminder time, recurrence and the note it is in. */
        private fun meta(ctx: Context, t: Tasks.Item): String = listOfNotNull(
            t.date?.let { d ->
                "📅 " + Dates.dayMonth(ctx, d) +
                    (t.time?.let { " ⏰ " + runCatching { Dates.time(ctx, LocalTime.parse(it.padStart(5, '0'))) }.getOrDefault(it) } ?: "")
            },
            t.recur?.let { "🔁 " + it.label(ctx.resources) },
            "📄 " + titleOf(t.path),
        ).joinToString("  ·  ")

        private fun views(ctx: Context, f: WidgetFilter, tasks: List<Tasks.Item>, locked: Boolean): RemoteViews {
            val today = LocalDate.now()
            val px = ctx.resources.displayMetrics.density
            val fg = ctx.getColor(R.color.widget_item_fg)
            val dim = (fg and 0x00FFFFFF) or (0x99 shl 24)
            val red = ctx.getColor(R.color.widget_overdue)
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(2)
            for ((g, list) in Tasks.grouped(tasks, today, f.order.groups)) {
                val size = list.size + 1 // header + tasks form one card
                val head = RemoteViews(ctx.packageName, R.layout.widget_item_title).apply {
                    setTextViewText(R.id.wc_text, ctx.getString(g.label) + "  " + list.size)
                    setTextColor(R.id.wc_text, if (g == Tasks.Group.Overdue) red else fg)
                    setInt(R.id.wc_root, "setBackgroundResource", NotesWidget.bg(0, size))
                }
                items.addItem(("group:" + g.name).hashCode().toLong(), head)
                list.forEachIndexed { i, t ->
                    val row = RemoteViews(ctx.packageName, R.layout.widget_item_task_meta).apply {
                        // own line for the date: a long task text can no longer push it out of the row
                        setTextViewText(R.id.wc_text, t.text)
                        setTextViewText(R.id.wc_meta, meta(ctx, t))
                        setTextColor(R.id.wc_meta, if (g == Tasks.Group.Overdue) red else dim)
                        setCompoundButtonChecked(R.id.wc_check, false)
                        setOnCheckedChangeResponse(
                            R.id.wc_check,
                            RemoteViews.RemoteResponse.fromFillInIntent(Intent().putExtra("toggle", t.path).putExtra("line", t.line)),
                        )
                        setOnClickFillInIntent(R.id.wc_body, Intent().putExtra("open", t.path))
                        setInt(R.id.wc_root, "setBackgroundResource", NotesWidget.bg(i + 1, size))
                        if (i == list.size - 1) // room for the gap below the card (inset in wcard_bottom)
                            setViewPadding(R.id.wc_root, (2 * px).toInt(), 0, (12 * px).toInt(), (12 * px).toInt())
                    }
                    items.addItem(t.key.hashCode().toLong(), row)
                }
            }
            val v = RemoteViews(ctx.packageName, R.layout.widget_tasks)
            v.setRemoteAdapter(R.id.wt_items, items.build())
            v.setEmptyView(R.id.wt_items, R.id.wt_empty)
            v.setTextViewText(R.id.wt_title, f.title.ifBlank { ctx.getString(R.string.widget_tasks_title) })
            v.setTextViewText(
                R.id.wt_empty,
                ctx.getString(
                    when {
                        locked -> R.string.widget_locked
                        f.restricted -> R.string.widget_tasks_empty_filtered
                        else -> R.string.widget_tasks_empty
                    },
                ),
            )
            v.setPendingIntentTemplate(
                R.id.wt_items,
                PendingIntent.getActivity(
                    ctx, 20,
                    Intent(ctx, WidgetAction::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ),
            )
            v.setOnClickPendingIntent(R.id.wt_new, TaskWidget.newTask(ctx))
            v.setOnClickPendingIntent(
                R.id.wt_title,
                PendingIntent.getActivity(
                    ctx, 21,
                    Intent(ctx, MainActivity::class.java).setAction("tasks").putExtra("tasks", true),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            return v
        }
    }
}
