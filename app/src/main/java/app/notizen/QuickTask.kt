package app.notizen

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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
     * Only the file write: fast, so the dialog can close right away. Commit, alarms and widget
     * follow in [afterWrite], off the UI path — a commit may wait minutes for a running sync
     * (all git work shares one lock), which used to keep the widget dialog hanging.
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
        runCatching { GitSync.commit(ctx, "Aufgabe: " + Markdown.stripTaskMeta(line.removePrefix("- [ ] "))) }
        SyncWorker.enqueue(ctx) // also commits the file if the commit above failed or the process died
    }

    fun confirmation(date: LocalDate?, time: LocalTime?): String = when {
        date == null -> "Aufgabe gespeichert"
        else -> "Aufgabe gespeichert · " + date.format(DateTimeFormatter.ofPattern("EE d.M.", Locale.GERMAN)) +
            (time?.let { " " + "%02d:%02d".format(it.hour, it.minute) } ?: "")
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
        setContent { NotizenTheme { QuickTaskDialog(onSave = ::save, onCancel = ::finish) } }
    }

    private var saving = false

    private fun save(text: String, date: LocalDate?, time: LocalTime?) {
        if (saving) return
        saving = true
        val app = applicationContext
        App.scope.launch {
            val line = runCatching { QuickTask.write(app, text, date, time) }.getOrNull()
            withContext(Dispatchers.Main) {
                Toast.makeText(app, if (line != null) QuickTask.confirmation(date, time) else "Speichern fehlgeschlagen", Toast.LENGTH_SHORT).show()
                finish()
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
        TimePickerDialog(ctx, { _, h, m -> onSave(text, date, LocalTime.of(h, m)) }, def.hour, def.minute, true).apply {
            setTitle("Erinnerung um")
            setButton(DialogInterface.BUTTON_NEUTRAL, "Ohne Uhrzeit") { _, _ -> onSave(text, date, null) }
            setOnCancelListener { picking = false }
        }.show()
    }

    fun pickDate() {
        val t = LocalDate.now()
        picking = true
        DatePickerDialog(ctx, { _, y, m, d -> pickTime(LocalDate.of(y, m + 1, d)) }, t.year, t.monthValue - 1, t.dayOfMonth).apply {
            setOnCancelListener { picking = false }
            setButton(DialogInterface.BUTTON_NEGATIVE, "Zurück") { _, _ -> picking = false }
        }.show()
    }

    fun quick(date: LocalDate) { picking = true; pickTime(date) }

    if (picking) return
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Neue Aufgabe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text("Was ist zu tun?") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { if (ok) pickDate() }),
                    maxLines = 4,
                )
                Text("Fällig / Erinnerung", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { if (ok) quick(LocalDate.now()) }, label = { Text("Heute") }, enabled = ok)
                    AssistChip(onClick = { if (ok) quick(LocalDate.now().plusDays(1)) }, label = { Text("Morgen") }, enabled = ok)
                    AssistChip(onClick = { if (ok) pickDate() }, label = { Text("Datum …") }, enabled = ok)
                }
            }
        },
        confirmButton = { TextButton(onClick = { pickDate() }, enabled = ok) { Text("Weiter") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text("Abbrechen") }
                TextButton(onClick = { onSave(text, null, null) }, enabled = ok, modifier = Modifier.padding(start = 4.dp)) { Text("Ohne Datum") }
            }
        },
    )
}

/** One-tap widget: opens [QuickTaskActivity]. */
class TaskWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_task)
        val pi = PendingIntent.getActivity(
            ctx, 10,
            Intent(ctx, QuickTaskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        v.setOnClickPendingIntent(R.id.w_task, pi)
        ids.forEach { mgr.updateAppWidget(it, v) }
    }
}
