package app.notizen

import android.app.UiModeManager
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Material You colors from the wallpaper, light or dark per [ThemeMode]. Used by every activity. */
@Composable
fun NotizenTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // isSystemInDarkTheme() already reflects the app's own night mode set by ThemeMode.apply
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Light/dark for this app only, independent of the device. Set through the system's per-app
 * night mode, so system bars, date/time pickers and all activities follow without own plumbing.
 * The home-screen widgets are drawn by the launcher and keep following the device.
 */
enum class ThemeMode(val label: String, private val night: Int) {
    System("Wie Gerät", UiModeManager.MODE_NIGHT_AUTO),
    Light("Hell", UiModeManager.MODE_NIGHT_NO),
    Dark("Dunkel", UiModeManager.MODE_NIGHT_YES);

    companion object {
        private fun sp(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

        fun load(ctx: Context): ThemeMode =
            runCatching { valueOf(sp(ctx).getString("theme", System.name)!!) }.getOrDefault(System)

        /** Save and switch; open activities are recreated by the system. */
        fun save(ctx: Context, m: ThemeMode) {
            sp(ctx).edit().putString("theme", m.name).apply()
            apply(ctx, m)
        }

        /** Re-apply at app start (cheap, no change if the system already has it). */
        fun apply(ctx: Context, m: ThemeMode = load(ctx)) {
            ctx.getSystemService(UiModeManager::class.java).setApplicationNightMode(m.night)
        }
    }
}
