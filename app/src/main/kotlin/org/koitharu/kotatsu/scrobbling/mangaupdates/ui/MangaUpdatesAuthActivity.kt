package org.koitharu.kotatsu.scrobbling.mangaupdates.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.util.DefaultTextWatcher
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.databinding.ActivityMangaupdatesAuthBinding
import org.koitharu.kotatsu.parsers.util.urlEncoded
import org.koitharu.kotatsu.scrobbling.common.ui.config.ScrobblerConfigActivity
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.encodeCredentials

/** MangaUpdates has no browser sign-in, so the username and password are asked for here. */
class MangaUpdatesAuthActivity : BaseActivity<ActivityMangaupdatesAuthBinding>(),
	View.OnClickListener,
	DefaultTextWatcher,
	TextView.OnEditorActionListener {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityMangaupdatesAuthBinding.inflate(layoutInflater))
		viewBinding.buttonCancel.setOnClickListener(this)
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.editUsername.addTextChangedListener(this)
		viewBinding.editUsername.setOnEditorActionListener(this)
		viewBinding.editPassword.addTextChangedListener(this)
		viewBinding.editPassword.setOnEditorActionListener(this)
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val screenPadding = v.resources.getDimensionPixelOffset(R.dimen.screen_padding)
		val barsInsets = insets.getInsets(typeMask)
		viewBinding.root.updatePadding(top = barsInsets.top)
		viewBinding.dockedToolbarChild.updateLayoutParams<MarginLayoutParams> {
			leftMargin = barsInsets.left
			rightMargin = barsInsets.right
			bottomMargin = barsInsets.bottom
		}
		viewBinding.layoutUsername.updateLayoutParams<MarginLayoutParams> {
			leftMargin = barsInsets.left + screenPadding
			rightMargin = barsInsets.right + screenPadding
		}
		viewBinding.layoutPassword.updateLayoutParams<MarginLayoutParams> {
			leftMargin = barsInsets.left + screenPadding
			rightMargin = barsInsets.right + screenPadding
		}
		return insets.consume(v, typeMask)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_cancel -> finish()
			R.id.button_done -> continueAuth()
		}
	}

	override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent?): Boolean = when (v.id) {
		R.id.edit_username -> {
			viewBinding.editPassword.requestFocus()
			true
		}

		R.id.edit_password -> {
			if (viewBinding.buttonDone.isEnabled) {
				continueAuth()
				true
			} else {
				false
			}
		}

		else -> false
	}

	override fun afterTextChanged(s: Editable?) {
		viewBinding.buttonDone.isEnabled = !viewBinding.editUsername.text.isNullOrBlank()
			&& !viewBinding.editPassword.text.isNullOrEmpty()
	}

	private fun continueAuth() {
		val username = viewBinding.editUsername.text?.toString()?.trim().orEmpty()
		// Passwords are taken exactly as typed: leading or trailing spaces can be part of one
		val password = viewBinding.editPassword.text?.toString().orEmpty()
		val uri = "kotatsu://${ScrobblerConfigActivity.HOST_MANGAUPDATES_AUTH}?code=" +
			encodeCredentials(username, password).urlEncoded()
		// Addressed to the config screen directly, unlike Kitsu's implicit link, so no other app can see the password
		val intent = Intent(this, ScrobblerConfigActivity::class.java)
			.setAction(Intent.ACTION_VIEW)
			.setData(uri.toUri())
		startActivity(intent)
		finishAfterTransition()
	}
}
