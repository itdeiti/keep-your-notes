package app.kyn

import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quick task from the "Aufgabe" widget: text, then date, then time; appended to Aufgaben.md in the vault root. */
object QuickTask {
    const val FILE = "Aufgaben.md"

    /** Same format as 📅 in the editor: `- [ ] text 📅 date ⏰ date time`. */
    fun line(text: String, date: LocalDate?, time: LocalTime?): String {
        val t = text.replace(Regex("""\s+"""), " ").trim()
        if (date == null) return "- [ ] $t"
        return "- [ ] $t 📅 $date" + (time?.let { " ⏰ $date ${"%02d:%02d".format(it.hour, it.minute)}" } ?: "")
    }

    fun append(body: String, line: String): String =
        if (body.isBlank()) "$line\n" else body.trimEnd() + "\n" + line + "\n"

    private val fileLock = Any()

    /**
     * Only the file write. Runs under the git lock (see [save][QuickTaskActivity]), so a running sync
     * cannot overwrite it; the dialog closes before that, as a sync may take a while.
     * Commit, alarms and widget follow in [afterWrite].
     */
    fun write(ctx: Context, text: String, date: LocalDate?, time: LocalTime?): String {
        val root = Vault.dir(ctx)
        val l = line(text, date, time)
        synchronized(fileLock) {
            val f = File(root, FILE)
            if (f.exists()) {
                val (front, body) = Vault.parse(f.readText())
                Vault.write(root, FILE, front, append(body, l))
            } else {
                Vault.write(root, FILE, listOf("created: ${Vault.nowIso()}"), append("", l))
            }
        }
        return l
    }

    /** Alarm and widget first (no git lock needed), then commit and push. */
    suspend fun afterWrite(ctx: Context, line: String) {
        runCatching { Reminders.rescan(ctx) }
        runCatching { NotesWidget.refresh(ctx) }
        runCatching { GitSync.commit(ctx, ctx.getString(R.string.commit_task, Markdown.stripTaskMeta(line.removePrefix("- [ ] ")))) }
        SyncWorker.enqueue(ctx) // also commits the file if the commit above failed or the process died
    }

    fun confirmation(ctx: Context, date: LocalDate?, time: LocalTime?): String = when {
        date == null -> ctx.getString(R.string.quicktask_saved)
        time == null -> ctx.getString(R.string.quicktask_saved_on, Dates.weekdayDate(ctx, date))
        else -> ctx.getString(R.string.quicktask_saved_at, Dates.weekdayDate(ctx, date), Dates.time(ctx, time))
    }
}

/** Dialog over the home screen, started by [TaskWidget]. */
class QuickTaskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ready = Prefs.load(this).repoUrl.isNotBlank() && Vault.accessible(this) && GitSync.isCloned(this)
        if (!ready) { // not set up yet: open the app (settings)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        setContent { KynTheme { QuickTaskDialog(onSave = ::save, onCancel = ::finish) } }
    }

    private var saving = false

    private fun save(text: String, date: LocalDate?, time: LocalTime?) {
        if (saving) return
        saving = true
        val app = applicationContext
        finish() // the write may wait for a running sync: never keep the dialog open for that
        App.scope.launch {
            val line = runCatching { GitSync.exclusive { QuickTask.write(app, text, date, time) } }.getOrNull()
            withContext(Dispatchers.Main) {
                Toast.makeText(app, if (line != null) QuickTask.confirmation(app, date, time) else app.getString(R.string.quicktask_save_failed), Toast.LENGTH_SHORT).show()
            }
            if (line != null) QuickTask.afterWrite(app, line)
        }
    }
}

@androidx.compose.runtime.Composable
private fun QuickTaskDialog(onSave: (String, LocalDate?, LocalTime?) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = text.isNotBlank()

    fun pickTime(date: LocalDate) {
        val def = runCatching { LocalTime.parse(Prefs.load(ctx).defaultTime.padStart(5, '0')) }.getOrDefault(LocalTime.of(9, 0))
        TimePickerDialog(ctx, { _, h, m -> onSave(text, date, LocalTime.of(h, m)) }, def.hour, def.minute, android.text.format.DateFormat.is24HourFormat(ctx)).apply {
            setTitle(ctx.getString(R.string.quicktask_remind_at))
            setButton(DialogInterface.BUTTON_NEUTRAL, ctx.getString(R.string.quicktask_no_time)) { _, _ -> onSave(text, date, null) }
            setOnCancelListener { picking = false }
        }.show()
    }

    fun pickDate() {
        val t = LocalDate.now()
        picking = true
        DatePickerDialog(ctx, { _, y, m, d -> pickTime(LocalDate.of(y, m + 1, d)) }, t.year, t.monthValue - 1, t.dayOfMonth).apply {
            setOnCancelListener { picking = false }
            setButton(DialogInterface.BUTTON_NEGATIVE, ctx.getString(R.string.quicktask_back)) { _, _ -> picking = false }
        }.show()
    }

    fun quick(date: LocalDate) { picking = true; pickTime(date) }

    if (picking) return
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.quicktask_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.quicktask_placeholder)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { if (ok) pickDate() }),
                    maxLines = 4,
                )
                Text(stringResource(R.string.quicktask_due), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { if (ok) quick(LocalDate.now()) }, label = { Text(stringResource(R.string.quicktask_today)) }, enabled = ok)
                    AssistChip(onClick = { if (ok) quick(LocalDate.now().plusDays(1)) }, label = { Text(stringResource(R.string.quicktask_tomorrow)) }, enabled = ok)
                    AssistChip(onClick = { if (ok) pickDate() }, label = { Text(stringResource(R.string.quicktask_pick_date)) }, enabled = ok)
                }
            }
        },
        confirmButton = { TextButton(onClick = { pickDate() }, enabled = ok) { Text(stringResource(R.string.quicktask_next)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.quicktask_cancel)) }
                TextButton(onClick = { onSave(text, null, null) }, enabled = ok, modifier = Modifier.padding(start = 4.dp)) { Text(stringResource(R.string.quicktask_no_date)) }
            }
        },
    )
}

/** One-tap widget: opens [QuickTaskActivity]. Resizable; at one cell only the icon is shown. */
class TaskWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        fun views(layout: Int) = RemoteViews(ctx.packageName, layout).apply {
            setOnClickPendingIntent(R.id.w_task, newTask(ctx))
        }
        // the launcher picks the layout that fits the current size
        val v = RemoteViews(
            mapOf(
                android.util.SizeF(40f, 40f) to views(R.layout.widget_task_small),
                android.util.SizeF(110f, 40f) to views(R.layout.widget_task),
            ),
        )
        ids.forEach { mgr.updateAppWidget(it, v) }
    }

    companion object {
        /** Opens the quick task dialog; also used by the ＋ of the task list widget. */
        fun newTask(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 10,
            Intent(ctx, QuickTaskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
