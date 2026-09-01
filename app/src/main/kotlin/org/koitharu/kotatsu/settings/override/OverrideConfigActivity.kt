package org.koitharu.kotatsu.settings.override

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.consumeAll
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.databinding.ActivityOverrideEditBinding
import org.koitharu.kotatsu.parsers.model.Manga
import androidx.core.text.parseAsHtml
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.picker.ui.PageImagePickContract
import com.google.android.material.R as materialR

@AndroidEntryPoint
class OverrideConfigActivity : BaseActivity<ActivityOverrideEditBinding>(), View.OnClickListener,
	ActivityResultCallback<Uri?> {

	private val viewModel: OverrideConfigViewModel by viewModels()

	private var originalTitle: String? = null
	private var originalDescription: String? = null

	private val pickCoverFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument(), this)
	private val pickPageLauncher = registerForActivityResult(PageImagePickContract(), this)

	// The photo picker grants temporary read access (no persistable permission), which lasts long
	// enough for the cover to be copied into app storage when the override is saved.
	private val pickCoverGalleryLauncher = registerForActivityResult(
		ActivityResultContracts.PickVisualMedia(),
	) { uri ->
		if (uri != null) {
			viewModel.updateCover(uri.toString())
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityOverrideEditBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.buttonPickGallery.setOnClickListener(this)
		viewBinding.buttonPickFile.setOnClickListener(this)
		viewBinding.buttonPickPage.setOnClickListener(this)
		viewBinding.buttonPickUrl.setOnClickListener(this)
		viewBinding.buttonResetCover.setOnClickListener(this)
		viewBinding.layoutName.setEndIconOnClickListener(this)
		viewBinding.layoutDescription.setEndIconOnClickListener(this)
		viewBinding.editName.doAfterTextChanged { updateOriginalNamePreview() }
		viewBinding.editDescription.doAfterTextChanged { updateDescriptionResetState() }
		viewModel.data.filterNotNull().observe(this, ::onDataChanged)
		viewModel.onSaved.observeEvent(this) { onDataSaved() }
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)
		viewModel.onError.observeEvent(this, ::onError)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		viewBinding.root.setPadding(
			barsInsets.left,
			barsInsets.top,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAll(typeMask)
	}

	override fun onActivityResult(result: Uri?) {
		if (result != null) {
			if (result.host?.startsWith(packageName) != true) {
				contentResolver.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
			viewModel.updateCover(result.toString())
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_done -> viewModel.save(
				title = viewBinding.editName.text?.toString()?.trim(),
				description = viewBinding.editDescription.text?.toString()?.trim(),
			)

			materialR.id.text_input_end_icon -> {
				// Both fields share the same end-icon id; the parent layout tells them apart.
				when (v.parent?.parent) {
					viewBinding.layoutDescription -> viewBinding.editDescription.text?.clear()
					else -> if (isCustomNameTyped()) viewBinding.editName.text?.clear()
				}
			}

			R.id.button_reset_cover -> viewModel.updateCover(null)
			R.id.button_pick_gallery -> {
				if (!pickCoverGalleryLauncher.tryLaunch(
						PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
					)
				) {
					Snackbar.make(
						viewBinding.imageViewCover,
						R.string.operation_not_supported,
						Snackbar.LENGTH_SHORT,
					).show()
				}
			}

			R.id.button_pick_file -> {
				if (!pickCoverFileLauncher.tryLaunch(arrayOf("image/*"))) {
					Snackbar.make(
						viewBinding.imageViewCover,
						R.string.operation_not_supported,
						Snackbar.LENGTH_SHORT,
					).show()
				}
			}

			R.id.button_pick_page -> {
				val manga = viewModel.data.value?.first
				pickPageLauncher.launch(manga)
			}

			R.id.button_pick_url -> showCoverUrlDialog()
		}
	}

	private fun showCoverUrlDialog() {
		val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
			setHint(R.string.url)
			inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
			setText(viewModel.data.value?.second?.coverUrl?.takeIf { it.isHttpUrl() })
		}
		val padding = resources.getDimensionPixelOffset(R.dimen.margin_normal)
		val container = android.widget.FrameLayout(this).apply {
			setPadding(padding, padding / 2, padding, 0)
			addView(editText)
		}
		com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
			.setTitle(R.string.pick_cover_url)
			.setView(container)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val url = editText.text?.toString()?.trim().orEmpty()
				if (url.isHttpUrl()) {
					viewModel.updateCover(url)
				} else {
					Snackbar.make(viewBinding.imageViewCover, R.string.invalid_url, Snackbar.LENGTH_SHORT).show()
				}
			}
			.show()
	}

	private fun onDataChanged(data: Pair<Manga, MangaOverride>) {
		val (manga, override) = data
		originalTitle = manga.title
		viewBinding.imageViewCover.setImageAsync(override.coverUrl.ifNullOrEmpty { manga.coverUrl }, manga)
		viewBinding.layoutName.placeholderText = manga.title
		if (viewBinding.editName.tag == null) {
			viewBinding.editName.setText(override.title)
			viewBinding.editName.tag = true  // Sentinel: field has been initialised; don't overwrite user edits on re-emit.
		}
		// Left empty when there is no override, exactly like the name field: empty means "use the
		// source's own description", so a later source update still comes through.
		originalDescription = manga.description?.parseAsHtml()?.toString()?.trim()
		viewBinding.layoutDescription.placeholderText = originalDescription
		if (viewBinding.editDescription.tag == null) {
			viewBinding.editDescription.setText(override.description)
			viewBinding.editDescription.tag = true
		}
		val hasCustomCover = !override.coverUrl.isNullOrEmpty()
		viewBinding.buttonResetCover.isEnabled = hasCustomCover
		viewBinding.layoutOriginalCover.isVisible = hasCustomCover
		if (hasCustomCover) {
			viewBinding.imageViewOriginalCover.setImageAsync(manga.coverUrl, manga)
		}
		updateOriginalNamePreview()
		updateDescriptionResetState()
	}

	private fun updateDescriptionResetState() {
		val hasCustom = !viewBinding.editDescription.text?.toString()?.trim().isNullOrEmpty()
		setEndIconEnabled(viewBinding.layoutDescription, hasCustom)
		viewBinding.textViewOriginalDescription.isVisible = hasCustom && !originalDescription.isNullOrEmpty()
	}

	private fun updateOriginalNamePreview() {
		val original = originalTitle?.trim().orEmpty()
		val current = viewBinding.editName.text?.toString()?.trim().orEmpty()
		val changed = isCustomNameTyped(original, current)
		setNameResetEnabled(changed)
		viewBinding.textViewOriginalName.isVisible = changed
		if (changed) {
			viewBinding.textViewOriginalName.text = getString(
				R.string.inline_preference_pattern,
				getString(R.string.original_name),
				original,
			)
		}
	}

	private fun isCustomNameTyped(
		original: String = originalTitle?.trim().orEmpty(),
		current: String = viewBinding.editName.text?.toString()?.trim().orEmpty(),
	): Boolean = original.isNotEmpty() && current.isNotEmpty() && current != original

	private fun setNameResetEnabled(isEnabled: Boolean) = setEndIconEnabled(viewBinding.layoutName, isEnabled)

	private fun setEndIconEnabled(layout: com.google.android.material.textfield.TextInputLayout, isEnabled: Boolean) {
		layout.findViewById<View>(materialR.id.text_input_end_icon)?.let { icon ->
			icon.isEnabled = isEnabled
			icon.alpha = if (isEnabled) 1f else DISABLED_ICON_ALPHA
		}
	}

	private fun onError(e: Throwable) {
		viewBinding.textViewError.text = e.getDisplayMessage(resources)
		viewBinding.textViewError.isVisible = true
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.buttonDone.isEnabled = !isLoading
		viewBinding.editName.isEnabled = !isLoading
		viewBinding.editDescription.isEnabled = !isLoading
		if (isLoading) {
			viewBinding.textViewError.isVisible = false
		}
	}

	private fun onDataSaved() {
		setResult(RESULT_OK)
		finish()
	}

	private companion object {
		const val DISABLED_ICON_ALPHA = 0.38f
	}
}
