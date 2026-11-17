package app.notizen

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.handleIntent(intent)
        setContent { Theme { App(vm) } }
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
