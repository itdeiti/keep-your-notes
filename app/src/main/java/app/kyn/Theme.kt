package app.kyn

import android.app.UiModeManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Material You colors from the wallpaper, light or dark per [ThemeMode], text scaled per [FontSize].
 * Used by every activity.
 */
@Composable
fun KynTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // isSystemInDarkTheme() already reflects the app's own night mode set by ThemeMode.apply
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    val d = LocalDensity.current
    // on top of the device's font size, so accessibility settings still apply
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * FontSize.current.factor)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Text size of the app's own screens. The home-screen widgets are drawn by the launcher and follow the device. */
enum class FontSize(@StringRes val label: Int, val factor: Float) {
    Small(R.string.font_small, 0.85f),
    Normal(R.string.font_normal, 1f),
    Large(R.string.font_large, 1.15f),
    Huge(R.string.font_huge, 1.3f);

    companion object {
        /** Compose state, so open screens resize right away. */
        var current by mutableStateOf(Normal)
            private set

        private fun sp(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

        fun load(ctx: Context) {
            current = runCatching { valueOf(sp(ctx).getString("font", Normal.name)!!) }.getOrDefault(Normal)
        }

        fun save(ctx: Context, f: FontSize) {
            sp(ctx).edit().putString("font", f.name).apply()
            current = f
        }
    }
}

/**
 * Light/dark for this app only, independent of the device. Set through the system's per-app
 * night mode, so system bars, date/time pickers and all activities follow without own plumbing.
 * The home-screen widgets are drawn by the launcher and keep following the device.
 */
enum class ThemeMode(@StringRes val label: Int, private val night: Int) {
    System(R.string.theme_system, UiModeManager.MODE_NIGHT_AUTO),
    Light(R.string.theme_light, UiModeManager.MODE_NIGHT_NO),
    Dark(R.string.theme_dark, UiModeManager.MODE_NIGHT_YES);

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
