package org.koitharu.kotatsu.main.ui.welcome

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.local.ui.restore.RestoreDialogFragment
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.ActivityOnboardingBinding
import org.koitharu.kotatsu.kotatsumigration.domain.KotatsuMigrationManager
import org.koitharu.kotatsu.kotatsumigration.ui.showExtensionInstallPromptDialog
import org.koitharu.kotatsu.kotatsumigration.ui.showKotatsuMigrationCompleteDialog
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingActivity : BaseActivity<ActivityOnboardingBinding>() {

    private val viewModel by viewModels<WelcomeViewModel>()

    @Inject
    lateinit var migrationManager: KotatsuMigrationManager

    private var permissionStates by mutableStateOf(OnboardingPermissions(false, false, false))

    // Bumped whenever sign-in or a restore succeeds, so the sync slide moves on by itself.
    private var syncStepDone by mutableIntStateOf(0)

    private val restoreTachiyomiLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.restoreBackup(uri)
    }

    private val restoreDropSauceLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) RestoreDialogFragment.show(supportFragmentManager, uri)
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.handleGoogleSignInResult(result.data) }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshPermissionStates() }

    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissionStates() }

    private val batteryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshPermissionStates() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityOnboardingBinding.inflate(layoutInflater))
        WindowCompat.setDecorFitsSystemWindows(window, false)

        viewModel.onGoogleSignInLaunch.observeEvent(this) { intent ->
            googleSignInLauncher.launch(intent)
        }
        viewModel.onGoogleSignInCompleted.observeEvent(this) { success ->
            Toast.makeText(
                this,
                if (success) R.string.sync_completed else R.string.sync_error,
                Toast.LENGTH_LONG,
            ).show()
            if (success) syncStepDone++
        }
        viewModel.onBackupRestored.observeEvent(this) { result ->
            val text = if (result.error != null) {
                result.error.getDisplayMessage(resources)
            } else {
                syncStepDone++
                getString(R.string.data_restored_success)
            }
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            // Tachiyomi/Mihon backup referenced extensions that aren't installed — prompt to install.
            result.report?.missingSources?.let { showExtensionInstallPromptDialog(it) }
        }
        // A restored Kotatsu-fork (DropSauce) backup auto-migrates in the background; surface it here.
        migrationManager.onStarted.observeEvent(this) {
            Toast.makeText(this, R.string.kotatsu_migration_started, Toast.LENGTH_SHORT).show()
        }
        migrationManager.onCompleted.observeEvent(this) { summary ->
            showKotatsuMigrationCompleteDialog(summary)
        }
        // A DropSauce restore runs in a background service (with its own progress notification),
        // so the step counts as done once it has started.
        supportFragmentManager.setFragmentResultListener(RestoreDialogFragment.RESULT_STARTED, this) { _, _ ->
            syncStepDone++
        }

        refreshPermissionStates()
        setupComposeContent()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStorageSummary()
        refreshPermissionStates()
        clearAmoledIfLightMode()
    }

    override fun onApplyWindowInsets(
        v: android.view.View,
        insets: androidx.core.view.WindowInsetsCompat,
    ): androidx.core.view.WindowInsetsCompat = insets

    private fun setupComposeContent() {
        viewBinding.composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        viewBinding.composeView.setContent {
            DropSauceTheme {
                val theme by viewModel.selectedTheme.collectAsState()
                val colorScheme by viewModel.selectedColorScheme.collectAsState()
                val amoled by viewModel.isAmoledEnabled.collectAsState()
                val storage by viewModel.storageSummary.collectAsState()
                val loading by viewModel.isLoading.collectAsState()
                OnboardingScreen(
                    selectedTheme = theme,
                    selectedColorScheme = colorScheme,
                    isAmoledEnabled = amoled,
                    storageSummary = storage,
                    isLoading = loading,
                    permissions = permissionStates,
                    syncStepDone = syncStepDone,
                    actions = OnboardingActions(
                        onThemeChange = { mode ->
                            viewModel.setTheme(mode)
                            AppCompatDelegate.setDefaultNightMode(mode)
                        },
                        onColorSchemeChange = { name ->
                            val scheme = ColorScheme.entries.find { it.name == name }
                            if (scheme != null && viewModel.selectedColorScheme.value != scheme) {
                                viewModel.setColorScheme(scheme)
                                recreate()
                            }
                        },
                        onAmoledChange = { enabled ->
                            viewModel.setAmoledTheme(enabled)
                            recreate()
                        },
                        onAmoledReset = { viewModel.setAmoledTheme(false) },
                        onSelectDestination = { router.showDirectorySelectDialog() },
                        onPermissionInstall = ::requestInstallPermission,
                        onPermissionNotifications = ::requestNotificationsPermission,
                        onPermissionBattery = ::requestBatteryOptimizationPermission,
                        onSignInGoogle = { viewModel.launchGoogleSignIn() },
                        onRestoreDropSauce = {
                            restoreDropSauceLauncher.launch(arrayOf("application/*", "*/*"))
                        },
                        onRestoreTachiyomi = {
                            restoreTachiyomiLauncher.launch(arrayOf("application/*", "*/*"))
                        },
                        onOpenGithub = {
                            openExternalLink(getString(R.string.url_github), getString(R.string.source_code))
                        },
                        onOpenDiscord = {
                            openExternalLink(getString(R.string.url_discord_web), getString(R.string.discord))
                        },
                        onVisitWebsite = {
                            openExternalLink(
                                getString(R.string.url_dropsauce_website),
                                getString(R.string.onboarding_visit_website),
                            )
                        },
                        onFinish = {
                            viewModel.completeOnboarding()
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        },
                    ),
                )
            }
        }
    }

    private fun refreshPermissionStates() {
        permissionStates = OnboardingPermissions(
            hasInstall = hasInstallPermission(),
            hasNotifications = hasNotificationPermission(),
            hasBattery = isIgnoringBatteryOptimizations(),
        )
    }

    private fun clearAmoledIfLightMode() {
        if (!viewModel.isAmoledEnabled.value) return
        val isCurrentlyDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val isDark = when (viewModel.selectedTheme.value) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> isCurrentlyDark
        }
        if (!isDark) viewModel.setAmoledTheme(false)
    }

    private fun hasInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return packageManager.canRequestPackageInstalls()
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            installPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            )
        }.onFailure {
            Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotificationPermission()) return
        notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestBatteryOptimizationPermission() {
        if (isIgnoringBatteryOptimizations()) return
        runCatching {
            batteryPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openExternalLink(url: String, title: String) {
        if (!router.openExternalBrowser(url, title)) {
            Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
        }
    }
}
