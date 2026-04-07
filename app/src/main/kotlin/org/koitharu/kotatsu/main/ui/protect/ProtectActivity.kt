package org.koitharu.kotatsu.main.ui.protect

import android.content.Context
import android.content.Intent
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
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.databinding.ActivityProtectBinding

@AndroidEntryPoint
class ProtectActivity :
	BaseActivity<ActivityProtectBinding>(),
	View.OnClickListener,
	AuthenticationResultCallback {

	@Inject
	lateinit var protectHelper: AppProtectHelper

	private val biometricPrompt = registerForAuthenticationResult(resultCallback = this)
	private var authInProgress = false
	private var autoPromptShown = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		setContentView(ActivityProtectBinding.inflate(layoutInflater))
		viewBinding.buttonCancel.setOnClickListener(this)
		viewBinding.buttonNext.apply {
			isEnabled = true
			setText(R.string.unlock_app)
			setOnClickListener(this@ProtectActivity)
		}
		viewBinding.layoutPassword.visibility = View.GONE
		viewBinding.textViewSubtitle.setText(R.string.require_unlock)
	}

	override fun onResume() {
		super.onResume()
		if (!autoPromptShown && !authInProgress) {
			autoPromptShown = true
			startUnlockFlow()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		val basePadding = resources.getDimensionPixelOffset(R.dimen.screen_padding)
		viewBinding.root.setPadding(
			barsInsets.left + basePadding,
			barsInsets.top + basePadding,
			barsInsets.right + basePadding,
			barsInsets.bottom + basePadding,
		)
		return WindowInsetsCompat.CONSUMED
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_next -> startUnlockFlow()
			R.id.button_cancel -> finish()
		}
	}

	override fun onAuthResult(result: AuthenticationResult) {
		authInProgress = false
		if (result.isSuccess()) {
			protectHelper.unlock()
			val sourceIntent = intent.getParcelableExtraCompat<Intent>(EXTRA_INTENT)
			if (sourceIntent != null) {
				startActivity(sourceIntent)
			}
			finishAfterTransition()
		}
	}

	private fun startUnlockFlow(): Boolean {
		if (BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) != BIOMETRIC_SUCCESS) {
			return false
		}
		authInProgress = true
		val request = AuthenticationRequest.biometricRequest(
			getString(R.string.app_name),
			Biometric.Fallback.DeviceCredential,
		) {
				setMinStrength(Biometric.Strength.Class2)
				setIsConfirmationRequired(false)
			}
		biometricPrompt.launch(request)
		return true
	}

	companion object {

		private const val EXTRA_INTENT = "src_intent"

		fun newIntent(context: Context, sourceIntent: Intent): Intent {
			return Intent(context, ProtectActivity::class.java)
				.putExtra(EXTRA_INTENT, sourceIntent)
		}
	}
}
