package app.kyn

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Open tasks with "⏰ date time" (or only "📅 date", then at the default time)
 * anywhere in the vault become exact alarms.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    private val OPEN = Regex("""^\s*[-*+] \[ \] (.*)$""")

    data class Task(val path: String, val line: String, val text: String, val at: Long) {
        val id get() = "$path|$line".hashCode()
    }

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun tasks(ctx: Context): List<Task> {
        val def = Prefs.load(ctx).defaultTime
        // same lines as the task list and widget (no code blocks), so no reminder for a task shown nowhere
        return Vault.all(ctx).flatMap { n -> Tasks.lines(n.body).mapNotNull { parse(n.path, it, def) } }
    }

    internal fun parse(path: String, line: String, def: String): Task? {
        val m = OPEN.find(line) ?: return null
        val r = Tasks.REMIND.find(line)
        val d = Tasks.DUE.find(line)
        val (date, time) = when {
            r != null -> r.groupValues[1] to r.groupValues[2].ifEmpty { def }
            d != null -> d.groupValues[1] to def
            else -> return null
        }
        val at = runCatching {
            LocalDateTime.parse("${date}T${time.padStart(5, '0')}")
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return Task(path, line, Markdown.stripTaskMeta(m.groupValues[1]), at)
    }

    private fun alarmIntent(ctx: Context, id: Int, t: Task?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("app.kyn.REMIND")
        if (t != null) i.putExtra("id", id).putExtra("path", t.path).putExtra("line", t.line).putExtra("text", t.text)
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun rescan(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val prefs = ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        val old = prefs.getStringSet("ids", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        val now = System.currentTimeMillis()
        val ids = mutableSetOf<Int>()
        for (t in tasks(ctx).filter { it.at > now }) {
            ids += t.id
            val pi = alarmIntent(ctx, t.id, t)
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.at, pi)
        }
        for (id in old - ids) am.cancel(alarmIntent(ctx, id, null))
        prefs.edit().putStringSet("ids", ids.map { it.toString() }.toSet()).apply()
    }

    fun notify(ctx: Context, id: Int, path: String, line: String, text: String) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java).setAction("open/$id").putExtra("open", path), flags,
        )
        val done = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, DoneReceiver::class.java).setAction("done/$id")
                .putExtra("id", id).putExtra("path", path).putExtra("line", line), flags,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(text.ifBlank { ctx.getString(R.string.reminder_due) })
            .setContentText(titleOf(path))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, ctx.getString(R.string.reminder_done), done)
            .let { AppLock.hideOnLockScreen(ctx, it, CHANNEL, ctx.getString(R.string.reminder_due)) }
            .build()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
        }
    }

    /** "Erledigt" from the notification. */
    suspend fun complete(ctx: Context, path: String, line: String) = toggle(ctx, path, line, onlyOpen = true)

    /** Tick or untick the task [line] in [path], commit, push later, refresh alarms and widget. */
    suspend fun toggle(ctx: Context, path: String, line: String, onlyOpen: Boolean = false) {
        val open = Markdown.TASK.find(line)?.groupValues?.get(2) == " "
        if (onlyOpen && !open) return
        // under the git lock: a running sync (merge, conflict copies) must not overwrite the tick
        val written = GitSync.exclusive {
            val f = Vault.safeFile(Vault.dir(ctx), path) ?: return@exclusive false
            if (!f.exists()) return@exclusive false
            val text = f.readText()
            val idx = text.split("\n").indexOfFirst { it.trimEnd('\r') == line }
            if (idx < 0) return@exclusive false
            f.writeText(Markdown.toggleTaskLine(text, idx))
            true
        }
        if (!written) return
        // widgets and alarms first, so the tick shows right away; the commit may wait for a running sync
        runCatching { NotesWidget.refresh(ctx) }
        runCatching { rescan(ctx) }
        val what = Markdown.stripTaskMeta(line.substringAfter("] "))
        GitSync.commit(ctx, ctx.getString(if (open) R.string.commit_done else R.string.commit_reopened, what))
        SyncWorker.enqueue(ctx)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Reminders.notify(
            ctx, i.getIntExtra("id", 0),
            i.getStringExtra("path") ?: return, i.getStringExtra("line") ?: return, i.getStringExtra("text") ?: "",
        )
    }
}

class DoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getIntExtra("id", 0)
        val path = i.getStringExtra("path") ?: return
        val line = i.getStringExtra("line") ?: return
        val app = ctx.applicationContext
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotificationManagerCompat.from(app).cancel(id)
                Reminders.complete(app, path, line)
            } catch (_: Exception) {
            } finally { pr.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val app = ctx.applicationContext
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { Reminders.rescan(app); NotesWidget.refresh(app) } catch (_: Exception) {} finally { pr.finish() }
        }
    }
}
