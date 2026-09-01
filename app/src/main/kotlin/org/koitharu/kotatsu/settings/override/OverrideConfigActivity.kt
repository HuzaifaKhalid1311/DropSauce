package org.koitharu.kotatsu.settings.override

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.text.parseAsHtml
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePaddingRelative
import coil3.ImageLoader
import kotlinx.coroutines.flow.filterNotNull
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.start
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.databinding.ActivityOverrideEditBinding
import org.koitharu.kotatsu.picker.ui.PageImagePickContract
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject

/**
 * Hosts [OverrideEditScreen]. The activity owns only the window: the toolbar with its Save action,
 * the system pickers a cover can come from, and the typed text, which lives here so Save can read
 * it without the screen having to push every keystroke into the view model.
 */
@AndroidEntryPoint
class OverrideConfigActivity : BaseActivity<ActivityOverrideEditBinding>(), ActivityResultCallback<Uri?> {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel: OverrideConfigViewModel by viewModels()

	private val bottomInset = mutableIntStateOf(0)
	private val titleText = mutableStateOf<String?>(null)
	private val descriptionText = mutableStateOf<String?>(null)
	private val errorText = mutableStateOf<String?>(null)

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
		setTitle(R.string.edit)
		viewBinding.composeView.setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		viewBinding.composeView.setContent {
			DropSauceTheme {
				val density = LocalDensity.current
				val data by viewModel.data.collectAsState()
				val isLoading by viewModel.isLoading.collectAsState()
				data?.let { (manga, override) ->
					OverrideEditScreen(
						manga = manga,
						coverUrl = override.coverUrl,
						title = titleText.value.orEmpty(),
						description = descriptionText.value.orEmpty(),
						originalDescription = remember(manga) {
							manga.description?.parseAsHtml()?.toString()?.trim()
						},
						isLoading = isLoading,
						error = errorText.value,
						bottomInset = with(density) { bottomInset.intValue.toDp() },
						imageLoader = coil,
						onTitleChange = { titleText.value = it },
						onDescriptionChange = { descriptionText.value = it },
						onCoverPick = ::onCoverPick,
						onCoverReset = { viewModel.updateCover(null) },
					)
				}
			}
		}
		// Seed the fields from the stored override once; a later re-emission must never overwrite
		// what is being typed.
		viewModel.data.filterNotNull().observe(this) { (_, override) ->
			if (titleText.value == null) {
				titleText.value = override.title.orEmpty()
				descriptionText.value = override.description.orEmpty()
			}
		}
		viewModel.onSaved.observeEvent(this) {
			setResult(RESULT_OK)
			finish()
		}
		viewModel.onError.observeEvent(this) { errorText.value = it.getDisplayMessage(resources) }
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		menuInflater.inflate(R.menu.opt_override_edit, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		R.id.action_done -> {
			errorText.value = null
			viewModel.save(
				title = titleText.value?.trim(),
				description = descriptionText.value?.trim(),
			)
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.appbar.updatePaddingRelative(
			start = bars.start(v),
			top = bars.top,
			end = bars.end(v),
		)
		bottomInset.intValue = bars.bottom
		return insets
	}

	override fun onActivityResult(result: Uri?) {
		if (result != null) {
			if (result.host?.startsWith(packageName) != true) {
				contentResolver.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
			viewModel.updateCover(result.toString())
		}
	}

	private fun onCoverPick(source: CoverSource) {
		when (source) {
			CoverSource.GALLERY -> if (
				!pickCoverGalleryLauncher.tryLaunch(
					PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
				)
			) {
				showNotSupported()
			}

			CoverSource.FILE -> if (!pickCoverFileLauncher.tryLaunch(arrayOf("image/*"))) {
				showNotSupported()
			}

			CoverSource.PAGE -> pickPageLauncher.launch(viewModel.data.value?.first)

			CoverSource.URL -> showCoverUrlDialog()
		}
	}

	private fun showNotSupported() {
		Snackbar.make(viewBinding.composeView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
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
					Snackbar.make(viewBinding.composeView, R.string.invalid_url, Snackbar.LENGTH_SHORT).show()
				}
			}
			.show()
	}
}
