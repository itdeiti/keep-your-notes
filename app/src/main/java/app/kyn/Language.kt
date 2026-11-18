package app.kyn

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * App language through Android's per-app language setting (Android 13+): the system remembers it,
 * applies it to every activity, widget and notification, and also offers it in
 * Settings → Apps → KYN → Language. The available languages are the values-<lang>/ folders
 * (generated locale config), so a new translation shows up here without code changes.
 * Android 12 has no per-app language: the app follows the device language there.
 */
object AppLanguage {
    val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Languages the app ships, e.g. [en, de]. */
    fun available(ctx: Context): List<Locale> {
        if (!supported) return emptyList()
        val list = LocaleConfig(ctx).supportedLocales ?: return emptyList()
        return (0 until list.size()).map { list[it] }
    }

    /** Language chosen for the app, or null for "like the device". */
    fun current(ctx: Context): Locale? {
        if (!supported) return null
        val l = ctx.getSystemService(LocaleManager::class.java).applicationLocales
        return if (l.isEmpty) null else l[0]
    }

    /** null = follow the device. Open activities are recreated by the system. */
    fun set(ctx: Context, locale: Locale?) {
        if (!supported) return
        ctx.getSystemService(LocaleManager::class.java).applicationLocales =
            if (locale == null) LocaleList.getEmptyLocaleList() else LocaleList(locale)
    }
}

/** Settings section: language chips (device + every shipped translation, each in its own language). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageSettings(number: Int) {
    val ctx = LocalContext.current
    Text(stringResource(R.string.settings_language_title, number), style = MaterialTheme.typography.titleMedium)
    if (!AppLanguage.supported) {
        Text(
            stringResource(R.string.settings_language_device_only),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    val available = remember { AppLanguage.available(ctx) }
    var current by remember { mutableStateOf(AppLanguage.current(ctx)?.language) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = current == null,
            onClick = { current = null; AppLanguage.set(ctx, null) },
            label = { Text(stringResource(R.string.settings_language_device)) },
        )
        for (l in available) FilterChip(
            selected = current == l.language,
            onClick = { current = l.language; AppLanguage.set(ctx, l) },
            label = { Text(l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }) },
        )
    }
}
