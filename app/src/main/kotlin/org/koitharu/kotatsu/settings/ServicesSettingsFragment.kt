package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.ui.ScrobblerAuthHelper
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.discord.DiscordSettingsFragment
import javax.inject.Inject

@AndroidEntryPoint
class ServicesSettingsFragment : BaseComposeSettingsFragment(R.string.services) {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var scrobblerAuthHelper: ScrobblerAuthHelper

	private val scrobblerSummary = MutableStateFlow<Map<ScrobblerService, String?>>(emptyMap())

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val summaries by scrobblerSummary.asStateFlow().collectAsState()
				ServicesScreen(
					scrobblerSummaries = summaries,
					onOpenSuggestions = {
						(activity as? SettingsActivity)?.openFragment(
							SuggestionsSettingsFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
					onOpenStatistics = router::openStatistic,
					onScrobblerClick = ::handleScrobblerClick,
					onOpenDiscord = {
						(activity as? SettingsActivity)?.openFragment(
							DiscordSettingsFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
	}

	override fun onResume() {
		super.onResume()
		refreshScrobblers()
	}

	private fun refreshScrobblers() {
		ScrobblerService.values().forEach { svc -> refreshScrobblerSummary(svc) }
	}

	private fun refreshScrobblerSummary(service: ScrobblerService) {
		val current = scrobblerSummary.value.toMutableMap()
		if (!scrobblerAuthHelper.isAuthorized(service)) {
			current[service] = getString(R.string.disabled)
			scrobblerSummary.value = current
			return
		}
		val cached = scrobblerAuthHelper.getCachedUser(service)?.nickname
		if (cached != null) {
			current[service] = getString(R.string.logged_in_as, cached)
			scrobblerSummary.value = current
			return
		}
		current[service] = getString(R.string.loading_)
		scrobblerSummary.value = current
		lifecycleScope.launch {
			val text = withContext(Dispatchers.Default) {
				runCatching {
					val user = scrobblerAuthHelper.getUser(service)
					getString(R.string.logged_in_as, user.nickname)
				}.getOrElse {
					it.printStackTraceDebug()
					it.getDisplayMessage(resources)
				}
			}
			scrobblerSummary.value = scrobblerSummary.value + (service to text)
		}
	}

	private fun handleScrobblerClick(service: ScrobblerService) {
		if (!scrobblerAuthHelper.isAuthorized(service)) {
			confirmScrobblerAuth(service)
		} else {
			router.openScrobblerSettings(service)
		}
	}

	private fun confirmScrobblerAuth(service: ScrobblerService) {
		buildAlertDialog(context ?: return, isCentered = true) {
			setIcon(service.iconResId)
			setTitle(service.titleResId)
			setMessage(context.getString(R.string.scrobbler_auth_intro, context.getString(service.titleResId)))
			setPositiveButton(R.string.sign_in) { _, _ ->
				scrobblerAuthHelper.startAuth(context, service).onFailure {
					Snackbar.make(
						requireView(),
						it.getDisplayMessage(resources),
						Snackbar.LENGTH_LONG,
					).show()
				}
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}
}

@Composable
private fun ServicesScreen(
	scrobblerSummaries: Map<ScrobblerService, String?>,
	onOpenSuggestions: () -> Unit,
	onOpenStatistics: () -> Unit,
	onScrobblerClick: (ScrobblerService) -> Unit,
	onOpenDiscord: () -> Unit,
) {
	val colors = CategoryPalette.forKey("services")
	var suggestionsEnabled by rememberBooleanPref(AppSettings.KEY_SUGGESTIONS, false)
	var relatedManga by rememberBooleanPref(AppSettings.KEY_RELATED_MANGA, true)
	var statsEnabled by rememberBooleanPref(AppSettings.KEY_STATS_ENABLED, true)
	var readingTime by rememberBooleanPref(AppSettings.KEY_READING_TIME, true)
	var syncTrackingProgress by rememberBooleanPref(AppSettings.KEY_SCROBBLING_PROGRESS_SYNC, true)

	val enabledLabel = stringResource(R.string.enabled)
	val disabledLabel = stringResource(R.string.disabled)

	SettingsScaffold {
		item {
			SettingsGroup(title = "General") {
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.suggestions),
						subtitle = if (suggestionsEnabled) enabledLabel else disabledLabel,
						icon = R.drawable.ic_suggestion,
						
						shape = pos.shape,
						onClick = onOpenSuggestions,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.related_manga),
						subtitle = stringResource(R.string.related_manga_summary),
						checked = relatedManga,
						onCheckedChange = { relatedManga = it },
						icon = R.drawable.ic_heart_outline,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					// Combined row: tapping the row opens the statistics page, the trailing
					// switch (split off by a divider) toggles collection on/off.
					SettingsItem(
						title = stringResource(R.string.reading_stats),
						subtitle = if (statsEnabled) enabledLabel else disabledLabel,
						icon = R.drawable.ic_timelapse,
						shape = pos.shape,
						onClick = onOpenStatistics,
						trailing = {
							Row(verticalAlignment = Alignment.CenterVertically) {
								VerticalDivider(modifier = Modifier.height(32.dp))
								Spacer(Modifier.width(14.dp))
								Switch(
									checked = statsEnabled,
									onCheckedChange = { statsEnabled = it },
								)
							}
						},
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.reading_time_estimation),
						subtitle = stringResource(R.string.reading_time_estimation_summary),
						checked = readingTime,
						onCheckedChange = { readingTime = it },
						icon = R.drawable.ic_timer,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			Column {
				SettingsGroup(title = stringResource(R.string.tracking)) {
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.sync_progress_from_tracking),
							subtitle = stringResource(R.string.sync_progress_from_tracking_summary),
							checked = syncTrackingProgress,
							onCheckedChange = { syncTrackingProgress = it },
							icon = R.drawable.ic_sync_alt,
							shape = pos.shape,
						)
					}
				}
				Spacer(Modifier.height(8.dp).fillMaxWidth())
				val services = listOf(
					ScrobblerService.ANILIST to R.drawable.ic_anilist,
					ScrobblerService.KITSU to R.drawable.ic_kitsu,
					ScrobblerService.MAL to R.drawable.ic_mal,
					ScrobblerService.MANGABAKA to R.drawable.ic_mangabaka,
					ScrobblerService.SHIKIMORI to R.drawable.ic_shikimori,
				)
				SettingsGroup {
					services.forEach { (svc, icon) ->
						item { pos ->
							ActionSettingsItem(
								title = stringResource(svc.titleResId),
								subtitle = scrobblerSummaries[svc],
								icon = icon,
								tintIcon = svc != ScrobblerService.MANGABAKA,
								shape = pos.shape,
								onClick = { onScrobblerClick(svc) },
							)
						}
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "External") {
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.discord_rpc),
						subtitle = stringResource(R.string.discord_rpc_summary),
						icon = R.drawable.ic_discord,
						
						shape = pos.shape,
						onClick = onOpenDiscord,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
