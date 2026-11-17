package app.notizen

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Conflict copies from [GitSync]: "Einkauf (Konflikt Handy 2026-11-17 1432).md" next to "Einkauf.md".
 * The server version stays in the original; the copy holds what the phone had.
 */
object Conflicts {
    private const val CHANNEL = "sync"
    private const val NOTIF_ID = 4711
    private val COPY = Regex("""^(.*) \(Konflikt Handy \d{4}-\d{2}-\d{2} \d{4}\)(\.[^./]+)?$""")

    fun isCopy(path: String) = COPY.matches(path)

    /** "inbox/Einkauf (Konflikt Handy …).md" -> "inbox/Einkauf.md", null if not a conflict copy. */
    fun original(path: String): String? = COPY.find(path)?.let { it.groupValues[1] + it.groupValues[2] }

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Synchronisierung", NotificationManager.IMPORTANCE_DEFAULT)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** Tell the user that [copies] were created; tapping shows the conflicts in the list. */
    fun notify(ctx: Context, copies: Collection<String>) {
        if (copies.isEmpty()) return
        val names = copies.mapNotNull { original(it) }.map { titleOf(it) }.distinct()
        val open = PendingIntent.getActivity(
            ctx, NOTIF_ID,
            Intent(ctx, MainActivity::class.java).setAction("conflicts").putExtra("conflicts", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (names.size == 1) "Konflikt: ${names[0]}" else "${names.size} Konflikte beim Synchronisieren")
            .setContentText("Die Handy-Version liegt als Kopie daneben. Tippen zum Prüfen.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Diese Notizen wurden gleichzeitig woanders geändert: ${names.joinToString()}. " +
                    "Die Version vom Server ist in der Notiz, die vom Handy liegt als Kopie daneben.",
            ))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
        }
    }

    /**
     * Keep the phone version: the copy's content replaces the original, the copy goes away.
     * Returns the original's path, or null if [copy] is not a conflict copy.
     */
    fun keepCopy(root: File, copy: String): String? {
        val orig = original(copy) ?: return null
        val src = File(root, copy)
        if (!src.exists()) return null
        File(root, orig).apply { parentFile?.mkdirs() }.writeBytes(src.readBytes())
        src.delete()
        return orig
    }

    /** Keep the server version: just delete the copy. */
    fun discardCopy(root: File, copy: String): Boolean = isCopy(copy) && File(root, copy).delete()
}
