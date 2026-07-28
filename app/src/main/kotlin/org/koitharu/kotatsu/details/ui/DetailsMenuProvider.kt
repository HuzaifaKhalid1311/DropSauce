package org.koitharu.kotatsu.details.ui

import android.app.Activity
import android.net.Uri
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.core.util.ext.toFileNameSafe
import org.koitharu.kotatsu.local.data.isEpub

class DetailsMenuProvider(
	private val activity: FragmentActivity,
	private val viewModel: DetailsViewModel,
	private val snackbarHost: View,
	private val appShortcutManager: AppShortcutManager,
) : MenuProvider, ActivityResultCallback<ActivityResult> {

	private val activityForResultLauncher = activity.registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
		this,
	)

	/** Registered eagerly alongside [activityForResultLauncher] — both must exist before STARTED. */
	private val exportEpubLauncher = activity.registerForActivityResult(
		ActivityResultContracts.CreateDocument(MIME_EPUB),
	) { uri -> if (uri != null) exportEpubTo(uri) }

	private val router: AppRouter
		get() = activity.router

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_details, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		val manga = viewModel.manga.value
		menu.findItem(R.id.action_share).isVisible = manga != null && AppRouter.isShareSupported(manga)
		menu.findItem(R.id.action_save).isVisible = manga?.source != null && manga.source != LocalMangaSource
		menu.findItem(R.id.action_delete).isVisible = manga?.source == LocalMangaSource
		menu.findItem(R.id.action_browser).isVisible = manga?.publicUrl?.isHttpUrl() == true
		menu.findItem(R.id.action_alternatives).isVisible = manga?.source != LocalMangaSource
		menu.findItem(R.id.action_shortcut).isVisible = ShortcutManagerCompat.isRequestPinShortcutSupported(activity)
		menu.findItem(R.id.action_scrobbling).isVisible = viewModel.isScrobblingAvailable
		menu.findItem(R.id.action_online).isVisible = viewModel.remoteManga.value != null
		menu.findItem(R.id.action_stats).isVisible = viewModel.isStatsAvailable.value
		// Novels and local books only — there is nothing to put in an epub for an image manga.
		menu.findItem(R.id.action_export_epub).isVisible = manga?.isEpub == true
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		val manga = viewModel.getMangaOrNull() ?: return false
		when (menuItem.itemId) {
			R.id.action_share -> {
				router.showShareDialog(manga)
			}

			R.id.action_delete -> {
				buildAlertDialog(activity) {
					setTitle(R.string.delete_manga)
					setMessage(activity.getString(R.string.text_delete_local_manga, manga.title))
					setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteLocal() }
					setNegativeButton(android.R.string.cancel, null)
				}.show()
			}

			R.id.action_save -> {
				router.showDownloadDialog(manga, snackbarHost)
			}

			R.id.action_browser -> {
				router.openBrowser(url = manga.publicUrl, source = manga.source, title = manga.title)
			}

			R.id.action_online -> {
				router.openDetails(viewModel.remoteManga.value ?: return false)
			}

			R.id.action_related -> {
				router.openSearch(manga.title)
			}

			R.id.action_alternatives -> {
				router.openAlternatives(manga)
			}

			R.id.action_stats -> {
				router.showStatisticSheet(manga)
			}

			R.id.action_scrobbling -> {
				router.showScrobblingSelectorSheet(manga, null)
			}

			R.id.action_shortcut -> {
				activity.lifecycleScope.launch {
					if (!appShortcutManager.requestPinShortcut(manga)) {
						Snackbar.make(snackbarHost, R.string.operation_not_supported, Snackbar.LENGTH_SHORT)
							.show()
					}
				}
			}

			R.id.action_export_epub -> {
				if (viewModel.getLocalEpubFile() == null) {
					Snackbar.make(snackbarHost, R.string.export_epub_nothing, Snackbar.LENGTH_LONG).show()
				} else {
					exportEpubLauncher.launch("${manga.title.toFileNameSafe()}.epub")
				}
			}

			R.id.action_edit_override -> {
				// Pass the pristine source manga so the editor always shows the true original
				// title/cover, independent of any previously saved override.
				val original = viewModel.getSourceMangaOrNull() ?: manga
				val intent = AppRouter.overrideEditIntent(activity, original)
				activityForResultLauncher.launch(intent)
			}

			else -> return false
		}
		return true
	}

	/**
	 * A downloaded novel is already a valid EPUB (written by `LocalNovelEpubOutput`), so exporting is a
	 * stream copy into whatever the user picked — no rebuild, and nothing is re-fetched.
	 */
	private fun exportEpubTo(destination: Uri) {
		val source = viewModel.getLocalEpubFile() ?: return
		activity.lifecycleScope.launch {
			val result = runCatching {
				withContext(Dispatchers.IO) {
					checkNotNull(activity.contentResolver.openOutputStream(destination)) {
						"Cannot open $destination for writing"
					}.use { output -> source.inputStream().use { it.copyTo(output) } }
				}
			}
			val message = if (result.isSuccess) R.string.export_epub_done else R.string.export_epub_failed
			Snackbar.make(snackbarHost, message, Snackbar.LENGTH_SHORT).show()
		}
	}

	override fun onActivityResult(result: ActivityResult) {
		if (result.resultCode == Activity.RESULT_OK) {
			viewModel.reload()
		}
	}

	private companion object {
		const val MIME_EPUB = "application/epub+zip"
	}
}
