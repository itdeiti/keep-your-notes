package app.notizen

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    override fun onCreate() {
        super.onCreate()
        // JGit looks for ~/.gitconfig; Android has no home directory.
        System.setProperty("user.home", filesDir.absolutePath)
        Reminders.createChannel(this)
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

/** Keep-style widget: scrollable note cards, tap opens the note for editing. */
class NotesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val app = ctx.applicationContext
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { update(app, mgr, ids) } catch (_: Exception) {} finally { pr.finish() }
        }
    }

    companion object {
        private val plain = MdColors(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)

        /** Call after notes changed (save, sync, delete). Blocking, run off the main thread. */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NotesWidget::class.java))
            if (ids.isNotEmpty()) runCatching { update(ctx, mgr, ids) }
        }

        private fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val notes = if (Environment.isExternalStorageManager()) Vault.all(ctx)
                .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.modified })
                .take(40) else emptyList()
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            for (n in notes) {
                val item = RemoteViews(ctx.packageName, R.layout.widget_list_item)
                item.setTextViewText(R.id.wi_title, (if (n.pinned) "📌 " else "") + n.title)
                val text = Markdown.preview(n.body, plain, n.title, maxLines = 6).text
                item.setTextViewText(R.id.wi_text, text)
                item.setViewVisibility(R.id.wi_text, if (text.isBlank()) View.GONE else View.VISIBLE)
                item.setOnClickFillInIntent(R.id.wi_root, Intent().putExtra("edit", n.path))
                items.addItem(n.path.hashCode().toLong(), item)
            }
            val v = RemoteViews(ctx.packageName, R.layout.widget_list)
            v.setRemoteAdapter(R.id.wl_items, items.build())
            v.setEmptyView(R.id.wl_items, R.id.wl_empty)
            v.setPendingIntentTemplate(
                R.id.wl_items,
                PendingIntent.getActivity(
                    ctx, 10,
                    Intent(ctx, MainActivity::class.java).setAction("edit-from-widget").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ),
            )
            v.setOnClickPendingIntent(R.id.wl_new, QuickWidget.launch(ctx, "note", 11))
            v.setOnClickPendingIntent(R.id.wl_list, QuickWidget.launch(ctx, "list", 12))
            v.setOnClickPendingIntent(
                R.id.wl_title,
                PendingIntent.getActivity(ctx, 13, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            ids.forEach { mgr.updateAppWidget(it, v) }
        }
    }
}
