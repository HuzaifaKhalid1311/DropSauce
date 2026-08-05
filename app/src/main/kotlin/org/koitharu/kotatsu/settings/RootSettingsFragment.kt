package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.github.AppUpdateRepository
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.settings.search.SettingsSearchMenuProvider
import org.koitharu.kotatsu.settings.search.SettingsSearchViewModel
import org.koitharu.kotatsu.settings.about.AboutSettingsFragment
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.sources.ExtensionsSettingsFragment
import org.koitharu.kotatsu.settings.tracker.TrackerSettingsFragment
import org.koitharu.kotatsu.sync.ui.SyncSettingsFragment
import javax.inject.Inject

/**
 * Redesigned settings landing screen — Compose-based, modeled after PixelPlayer's settings.
 * Uses M3 LargeTopAppBar (collapses on scroll) and a single rounded SettingsGroup with the
 * dynamic-corner-radius pattern for the 9 section cards.
 *
 * Hides the host SettingsActivity's MaterialToolbar while attached, since we draw our own
 * top bar inside Compose. Sub-screens still use the activity's toolbar.
 */
@AndroidEntryPoint
class RootSettingsFragment : BaseComposeSettingsFragment(R.string.settings) {

	@Inject
	lateinit var appUpdateRepository: AppUpdateRepository

	private val searchViewModel: SettingsSearchViewModel by activityViewModels()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val update by appUpdateRepository.observeAvailableUpdate().collectAsState()
				RootSettingsContent(
					appVersion = BuildConfig.VERSION_NAME,
					updateAvailable = update != null,
					onSectionClick = { section -> openSection(section) },
					onUpdateClick = { router.openAppUpdate() },
					onOpenGithub = ::openGithub,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		// The search icon lives on the activity toolbar and is only present while the
		// root settings screen is shown (tied to this fragment's view lifecycle).
		requireActivity().addMenuProvider(
			SettingsSearchMenuProvider(searchViewModel),
			viewLifecycleOwner,
		)
	}

	private fun openGithub() {
		router.openExternalBrowser(getString(R.string.url_github), getString(R.string.source_code))
	}

	private fun openSection(section: SettingsSection) {
		val activity = activity as? SettingsActivity ?: return
		activity.openFragment(section.fragmentClass, null, isFromRoot = true)
	}
}

private enum class SettingsSection(
	val titleRes: Int,
	val iconRes: Int,
	val paletteKey: String,
	val summaryRes: IntArray,
	val fragmentClass: Class<out Fragment>,
	val tintIcon: Boolean = true,
) {
	SYNC(
		R.string.google_drive_sync, R.drawable.ic_gdrive, "sync",
		intArrayOf(R.string.sync_sign_in, R.string.sync_frequency),
		SyncSettingsFragment::class.java,
	),
	APPEARANCE(
		R.string.appearance, R.drawable.ic_appearance, "appearance",
		intArrayOf(R.string.theme, R.string.list_mode, R.string.language),
		AppearanceSettingsFragment::class.java,
	),
	EXTENSIONS(
		R.string.extensions, R.drawable.ic_manga_source, "extensions",
		intArrayOf(R.string.manage_extensions, R.string.nsfw_filter, R.string.sort_order),
		ExtensionsSettingsFragment::class.java,
	),
	READER(
		R.string.reader_settings, R.drawable.ic_book_page, "reader",
		intArrayOf(R.string.read_mode, R.string.scale_mode, R.string.switch_pages),
		ReaderSettingsFragment::class.java,
	),
	STORAGE(
		R.string.storage_and_network, R.drawable.ic_usage, "storage",
		intArrayOf(R.string.storage_usage, R.string.proxy, R.string.prefetch_content),
		StorageAndNetworkSettingsFragment::class.java,
	),
	DOWNLOADS(
		R.string.downloads, R.drawable.ic_download, "downloads",
		intArrayOf(R.string.manga_save_location, R.string.downloads_wifi_only),
		DownloadsSettingsFragment::class.java,
	),
	BACKUP(
		R.string.backup_restore, R.drawable.ic_backup_restore, "backup",
		intArrayOf(R.string.restore_backup),
		BackupSettingsFragment::class.java,
	),
	TRACKER(
		R.string.check_for_new_chapters, R.drawable.ic_feed, "tracker",
		intArrayOf(R.string.track_sources, R.string.notifications_settings),
		TrackerSettingsFragment::class.java,
	),
	SERVICES(
		R.string.services, R.drawable.ic_services, "services",
		intArrayOf(R.string.suggestions, R.string.tracking),
		ServicesSettingsFragment::class.java,
	),
	ABOUT(
		R.string.about, R.drawable.ic_info_outline, "about",
		IntArray(0),
		AboutSettingsFragment::class.java,
	),
}

@Composable
private fun RootSettingsContent(
	appVersion: String,
	updateAvailable: Boolean,
	onSectionClick: (SettingsSection) -> Unit,
	onUpdateClick: () -> Unit,
	onOpenGithub: () -> Unit,
) {
	val ctx = LocalContext.current
	SettingsScaffold {
		if (updateAvailable) {
			item {
				UpdateBanner(onClick = onUpdateClick)
			}
			item { Spacer(Modifier.height(12.dp).fillMaxWidth()) }
		}
		item {
			SettingsGroup {
				// SettingsGroup's DSL block is NOT @Composable — @Composable calls
				// (stringResource, MaterialTheme.colorScheme, …) only happen inside
				// each `item { pos -> ... }` body which IS @Composable.
				SettingsSection.values().forEach { section ->
					item { pos ->
						val subtitle = if (section == SettingsSection.ABOUT) {
							appVersion
						} else {
							section.summaryRes.joinToString { ctx.getString(it) }
						}
						SettingsItem(
							title = stringResource(section.titleRes),
							subtitle = subtitle,
							icon = section.iconRes,
							iconColors = CategoryPalette.forKey(section.paletteKey),
							tintIcon = section.tintIcon,
							shape = pos.shape,
							onClick = { onSectionClick(section) },
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item { GithubStarNote(onOpenGithub = onOpenGithub) }
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

/** Closing note on the settings landing screen: a nudge to star the repo, with "GitHub" as a link. */
@Composable
private fun GithubStarNote(onOpenGithub: () -> Unit) {
	val message = stringResource(R.string.github_star_note)
	val link = stringResource(R.string.github)
	val linkStart = message.indexOf(link).coerceAtLeast(0)
	PlainInfoSettingsItem(
		text = buildAnnotatedString {
			append(message.substring(0, linkStart))
			withLink(
				LinkAnnotation.Clickable(
					tag = "github",
					styles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary)),
					linkInteractionListener = { onOpenGithub() },
				),
			) { append(link) }
			append(message.substring((linkStart + link.length).coerceAtMost(message.length)))
		},
		icon = R.drawable.ic_star_rate,
	)
}

/**
 * Non-removable banner shown at the top of Settings whenever an app update is available.
 * Tapping it opens the update page. There is intentionally no dismiss affordance.
 */
@Composable
private fun UpdateBanner(onClick: () -> Unit) {
	val cs = MaterialTheme.colorScheme
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(24.dp),
		color = cs.primaryContainer,
		onClick = onClick,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(R.drawable.ic_app_update),
				contentDescription = null,
				tint = cs.onPrimaryContainer,
				modifier = Modifier.size(28.dp),
			)
			Spacer(Modifier.width(16.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.app_update_available),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					color = cs.onPrimaryContainer,
				)
				Text(
					text = stringResource(R.string.update),
					style = MaterialTheme.typography.bodySmall,
					color = cs.onPrimaryContainer.copy(alpha = 0.8f),
				)
			}
			Spacer(Modifier.width(8.dp))
			Icon(
				painter = painterResource(R.drawable.ic_arrow_forward),
				contentDescription = null,
				tint = cs.onPrimaryContainer,
				modifier = Modifier.size(22.dp),
			)
		}
	}
}
