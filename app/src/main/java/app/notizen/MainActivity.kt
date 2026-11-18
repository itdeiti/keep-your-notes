package app.notizen

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!AppLock.enabled(this)) AppLock.locked = false
        vm.handleIntent(intent)
        setContent {
            Theme {
                // Overlay instead of replacing the app: the editor keeps its state (and pending
                // results like dictation) while locked; hidden from touch and accessibility.
                Box(Modifier.fillMaxSize()) {
                    Box(if (AppLock.locked) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) { App(vm) }
                    if (AppLock.locked) PinScreen(onBack = { moveTaskToBack(true) })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLock.onForeground(this)
        // no note content in the recent-apps preview / screenshots while a PIN is set
        if (AppLock.enabled(this)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onStop() {
        super.onStop()
        vm.saveEditor()
        if (!isChangingConfigurations) AppLock.onBackground()
    }
}

@Composable
private fun Theme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun App(vm: AppVM) {
    when (vm.screen) {
        Screen.List -> ListScreen(vm)
        Screen.Tasks -> TasksScreen(vm)
        Screen.Edit -> EditorScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
    }
}
