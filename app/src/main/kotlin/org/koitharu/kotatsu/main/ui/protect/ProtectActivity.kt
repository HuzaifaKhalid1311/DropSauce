package org.koitharu.kotatsu.main.ui.protect

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationRequest.Biometric
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.registerForAuthenticationResult
import androidx.core.view.WindowInsetsCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.databinding.ActivityProtectBinding
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

@AndroidEntryPoint
class ProtectActivity :
	BaseActivity<ActivityProtectBinding>(),
	AuthenticationResultCallback {

	@Inject
	lateinit var protectHelper: AppProtectHelper

	@Inject
	lateinit var settings: AppSettings

	private val biometricPrompt = registerForAuthenticationResult(resultCallback = this)
	private var isAutoPromptPending = true
	private var isPromptShowing = false
	private val isPinMode get() = settings.isAppPasswordSet

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		setContentView(ActivityProtectBinding.inflate(layoutInflater))
		viewBinding.composeView.setContent {
			DropSauceTheme {
				ProtectScreen(
					isPinMode = isPinMode,
					onVerifyPin = { pin ->
						settings.verifyAppPassword(pin).also { if (it) unlockAndFinish() }
					},
					onBiometric = { startUnlockFlow() },
					onCancel = { finishAffinity() },
				)
			}
		}
	}

	override fun onStart() {
		super.onStart()
		// Biometric mode auto-prompts; PIN mode waits for input instead (the field takes focus itself).
		if (!isPinMode && isAutoPromptPending) {
			isAutoPromptPending = false
			viewBinding.root.post { startUnlockFlow() }
		}
	}

	override fun onStop() {
		super.onStop()
		// Re-arm the auto-prompt when we were backgrounded for any reason other than the prompt
		// itself (device-credential fallback runs in its own activity), so coming back re-asks.
		if (!isPromptShowing) {
			isAutoPromptPending = true
		}
	}

	// Let Compose handle the insets itself (systemBarsPadding); don't consume them here.
	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onAuthResult(result: AuthenticationResult) {
		isPromptShowing = false
		if (result.isSuccess()) {
			unlockAndFinish()
		}
	}

	private fun unlockAndFinish() {
		protectHelper.unlock()
		@Suppress("DEPRECATION")
		overridePendingTransition(0, 0)
		finish()
	}

	private fun startUnlockFlow(): Boolean {
		if (BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) != BIOMETRIC_SUCCESS) {
			finishAffinity()
			return false
		}
		val request = AuthenticationRequest.biometricRequest(
			getString(R.string.app_name),
			Biometric.Fallback.DeviceCredential,
		) {
				setMinStrength(Biometric.Strength.Class2)
				setIsConfirmationRequired(false)
			}
		isPromptShowing = true
		biometricPrompt.launch(request)
		return true
	}

	companion object
}
