package app.kyn

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

class MainActivity : LockedActivity() {
    private val vm: AppVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!AppLock.enabled(this)) AppLock.locked = false
        vm.handleIntent(intent)
        setContent {
            KynTheme {
                LockGate(onBack = { moveTaskToBack(true) }, hint = vm.pendingShareHint) { App(vm) }
                // no screenshots or recents preview as soon as encrypted content is unlocked
                LaunchedEffect(SecretSession.version) { SecretSession.secureWindow(this@MainActivity) }
                // shared text waits for the PIN before anything is written to the vault
                LaunchedEffect(AppLock.locked) { if (!AppLock.locked) vm.runPendingShare() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a new intent arrives before onStart, so decide the lock first
        AppLock.onForeground(this)
        vm.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onStop() {
        vm.saveEditor()
        vm.saveSecret()
        vm.saveSection()
        super.onStop()
    }
}

@Composable
private fun App(vm: AppVM) {
    when (vm.screen) {
        Screen.List -> ListScreen(vm)
        Screen.Tasks -> TasksScreen(vm)
        Screen.Edit -> EditorScreen(vm)
        Screen.Secret -> SecretScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
    }
}
