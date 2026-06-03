package org.koitharu.kotatsu.settings.sources

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import dagger.hilt.android.AndroidEntryPoint
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.getTitleWithInlineLanguage
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.parser.EmptyMangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.koitharu.kotatsu.mihon.MihonMangaRepository
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.EditTextSettingsItem
import org.koitharu.kotatsu.settings.compose.InfoSettingsItem
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.MultiSelectSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem

@AndroidEntryPoint
class SourceSettingsFragment : BaseComposeSettingsFragment(0) {

	private val viewModel: SourceSettingsViewModel by viewModels()

	private var mihonPm: PreferenceManager? = null
	private var mihonScreen: PreferenceScreen? = null

	override fun onResume() {
		super.onResume()
		updateSectionTitle()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		val repo = viewModel.repository
		val isValidSource = repo !is EmptyMangaRepository
		val prefsName = SourceSettings.getStorageName(viewModel.source.name)
		val sourcePrefs = requireContext().getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
		val mihonSections = buildMihonSections(prefsName)
		val openBrowserUrl = ((repo as? MihonMangaRepository)?.mihonSource as? HttpSource)
			?.baseUrl?.takeIf { it.isNotBlank() }
		val uninstallPkg = (repo as? MihonMangaRepository)?.source?.pkgName
		val languageSelection = buildLanguageSelection()

		setContent {
			DropSauceTheme {
				SourceSettingsScreen(
					sourcePrefs = sourcePrefs,
					isValidSource = isValidSource,
					mihonSections = mihonSections,
					languageSelection = languageSelection,
					openBrowserUrl = openBrowserUrl,
					uninstallPkg = uninstallPkg,
					onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
					onOpenBrowser = { url -> openBrowser(url) },
					onUninstall = { pkg -> uninstallExtension(pkg) },
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(view, this))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(view))
	}

	override fun onDestroyView() {
		mihonScreen = null
		mihonPm = null
		super.onDestroyView()
	}

	@SuppressLint("RestrictedApi")
	private fun buildMihonSections(prefsName: String): List<PreferenceSection> {
		val repo = viewModel.repository as? MihonMangaRepository ?: return emptyList()
		val mihonSource = repo.mihonSource as? ConfigurableSource ?: return emptyList()
		val ctx = requireContext()
		val pm = PreferenceManager(ctx).apply { sharedPreferencesName = prefsName }
		val screen = pm.createPreferenceScreen(ctx)
		try {
			mihonSource.setupPreferenceScreen(screen)
		} catch (e: Throwable) {
			Log.e("SourceSettingsFragment", "Failed to setup Mihon preferences", e)
		}
		mihonPm = pm
		mihonScreen = screen
		return buildSections(screen)
	}

	private fun buildLanguageSelection(): LanguageSelection? {
		val repo = viewModel.repository as? MihonMangaRepository ?: return null
		val pkgName = repo.source.pkgName
		val siblings = viewModel.getSiblingMihonSources().sortedBy { it.language }
		if (siblings.size <= 1) return null
		val langs = siblings.map { it.language }
		return LanguageSelection(
			languages = langs.map { lang ->
				val source = siblings.first { it.language == lang }
				LanguageEntry(
					lang = lang,
					displayName = getExternalExtensionLanguageDisplayName(lang),
					sourceName = source.name,
				)
			},
			selectedLanguage = viewModel.getSelectedMihonSourceLang(pkgName, langs),
			setSelectedLanguage = ::setSelectedLanguage,
		)
	}

	private fun updateSectionTitle() {
		val ctx = context ?: return
		(activity as? SettingsActivity)?.setSectionTitle(viewModel.source.getTitleWithInlineLanguage(ctx))
	}

	private fun setSelectedLanguage(entry: LanguageEntry) {
		val repo = viewModel.repository as? MihonMangaRepository ?: return
		viewModel.setSelectedMihonSourceLang(repo.source.pkgName, entry.lang)
		if (entry.sourceName == viewModel.source.name) {
			updateSectionTitle()
			return
		}
		if (shouldReturnToSourceOnLanguageChange()) {
			finishWithSelectedSource(entry.sourceName)
			return
		}
		(activity as? SettingsActivity)?.replaceCurrentFragment(
			SourceSettingsFragment::class.java,
			newInstance(entry.sourceName).arguments,
		)
	}

	private fun openBrowser(url: String) {
		val repo = viewModel.repository as? MihonMangaRepository ?: return
		router.openBrowser(url = url, source = repo.source, title = repo.source.displayName)
	}

	private fun uninstallExtension(packageName: String) {
		val uri = Uri.fromParts("package", packageName, null)
		val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			Intent.ACTION_DELETE
		} else {
			@Suppress("DEPRECATION")
			Intent.ACTION_UNINSTALL_PACKAGE
		}
		startActivity(Intent(action, uri))
	}

	private fun finishWithSelectedSource(sourceName: String) {
		requireActivity().setResult(
			Activity.RESULT_OK,
			Intent().putExtra(AppRouter.KEY_SOURCE, sourceName),
		)
		requireActivity().finish()
	}

	private fun shouldReturnToSourceOnLanguageChange(): Boolean {
		return activity?.intent?.getBooleanExtra(AppRouter.KEY_RETURN_TO_SOURCE_ON_LANGUAGE_CHANGE, false) == true
	}

	/** Splits a populated [PreferenceScreen] into sections: each PreferenceCategory becomes its
	 *  own titled section; consecutive top-level leaf preferences are grouped together. */
	private fun buildSections(screen: PreferenceScreen): List<PreferenceSection> {
		val sections = mutableListOf<PreferenceSection>()
		val pending = mutableListOf<Preference>()
		fun flush() {
			if (pending.isNotEmpty()) {
				sections += PreferenceSection(null, pending.toList())
				pending.clear()
			}
		}
		for (i in 0 until screen.preferenceCount) {
			val pref = screen.getPreference(i)
			if (!pref.isVisible) continue
			if (pref is PreferenceGroup) {
				flush()
				val children = (0 until pref.preferenceCount)
					.map { pref.getPreference(it) }
					.filter { it.isVisible }
				if (children.isNotEmpty()) {
					sections += PreferenceSection(pref.title?.toString(), children)
				}
			} else {
				pending += pref
			}
		}
		flush()
		return sections
	}

	companion object {
		fun newInstance(source: MangaSource) = newInstance(source.name)

		private fun newInstance(sourceName: String) = SourceSettingsFragment().withArgs(1) {
			putString(AppRouter.KEY_SOURCE, sourceName)
		}
	}
}

private data class PreferenceSection(val title: String?, val preferences: List<Preference>)

private class LanguageEntry(val lang: String, val displayName: String, val sourceName: String)

private class LanguageSelection(
	val languages: List<LanguageEntry>,
	val selectedLanguage: String?,
	val setSelectedLanguage: (LanguageEntry) -> Unit,
)

@Composable
private fun SourceSettingsScreen(
	sourcePrefs: SharedPreferences,
	isValidSource: Boolean,
	mihonSections: List<PreferenceSection>,
	languageSelection: LanguageSelection?,
	openBrowserUrl: String?,
	uninstallPkg: String?,
	onBack: () -> Unit,
	onOpenBrowser: (String) -> Unit,
	onUninstall: (String) -> Unit,
) {
	// Recomposition trigger for bridged preferences (they read live values from sourcePrefs).
	var rev by remember { mutableIntStateOf(0) }
	DisposableEffect(sourcePrefs) {
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> rev++ }
		sourcePrefs.registerOnSharedPreferenceChangeListener(listener)
		onDispose { sourcePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
	}

	SettingsScaffold(title = "", onBack = onBack) {
		if (!isValidSource) {
			item {
				SettingsGroup {
					item { pos ->
						InfoSettingsItem(
							title = stringResource(R.string.unsupported_source),
							icon = R.drawable.ic_alert_outline,
							shape = pos.shape,
						)
					}
				}
			}
		}
		if (isValidSource) {
			item {
				SettingsGroup {
					item { pos ->
						var slowdown by rememberSourceBoolean(sourcePrefs, SourceSettings.KEY_SLOWDOWN, false)
						SwitchSettingsItem(
							title = stringResource(R.string.download_slowdown),
							subtitle = stringResource(R.string.download_slowdown_summary),
							checked = slowdown,
							onCheckedChange = { slowdown = it },
							icon = R.drawable.ic_timelapse,
							shape = pos.shape,
						)
					}
				}
			}
		}

		// Dynamic extension-provided preferences.
		mihonSections.forEach { section ->
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			item {
				SettingsGroup(title = section.title) {
					section.preferences.forEach { pref ->
						item { pos ->
							MihonPreferenceRow(pref = pref, shape = pos.shape, rev = rev)
						}
					}
				}
			}
		}

		if (languageSelection != null) {
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			item {
				LanguageSelectionGroup(languageSelection)
			}
		}

		if (openBrowserUrl != null || uninstallPkg != null) {
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			item {
				SettingsGroup {
					if (openBrowserUrl != null) {
						item { pos ->
							ActionSettingsItem(
								title = stringResource(R.string.open_in_browser),
								subtitle = openBrowserUrl,
								icon = R.drawable.ic_open_external,
								shape = pos.shape,
								onClick = { onOpenBrowser(openBrowserUrl) },
							)
						}
					}
					if (uninstallPkg != null) {
						item { pos ->
							ActionSettingsItem(
								title = stringResource(R.string.uninstall),
								subtitle = uninstallPkg,
								icon = R.drawable.ic_delete,
								shape = pos.shape,
								onClick = { onUninstall(uninstallPkg) },
							)
						}
					}
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

@Composable
private fun MihonPreferenceRow(
	pref: Preference,
	shape: androidx.compose.ui.graphics.Shape,
	@Suppress("UNUSED_PARAMETER") rev: Int, // change forces recomposition to re-read live pref values
) {
	val title = pref.title?.toString().orEmpty()
	when (pref) {
		is TwoStatePreference -> {
			var checked by remember(rev) { mutableStateOf(pref.isChecked) }
			SwitchSettingsItem(
				title = title,
				subtitle = pref.summary?.toString(),
				checked = checked,
				onCheckedChange = { newVal ->
					if (pref.callChangeListener(newVal)) {
						pref.isChecked = newVal
						checked = newVal
					}
				},
				shape = shape,
				enabled = pref.isEnabled,
			)
		}

		is ListPreference -> {
			val entries = pref.entries?.map { it.toString() } ?: emptyList()
			val values = pref.entryValues?.map { it.toString() } ?: emptyList()
			ListSettingsItem(
				title = title,
				entries = entries,
				entryValues = values,
				selectedValue = pref.value,
				onValueChange = { newVal -> if (pref.callChangeListener(newVal)) pref.value = newVal },
				shape = shape,
				enabled = pref.isEnabled,
			)
		}

		is MultiSelectListPreference -> {
			val entries = pref.entries?.map { it.toString() } ?: emptyList()
			val values = pref.entryValues?.map { it.toString() } ?: emptyList()
			MultiSelectSettingsItem(
				title = title,
				entries = entries,
				entryValues = values,
				selectedValues = pref.values,
				onValuesChange = { newVals -> if (pref.callChangeListener(newVals)) pref.values = newVals },
				shape = shape,
				enabled = pref.isEnabled,
			)
		}

		is EditTextPreference -> {
			EditTextSettingsItem(
				title = title,
				value = pref.text.orEmpty(),
				onValueChange = { newVal -> if (pref.callChangeListener(newVal)) pref.text = newVal },
				shape = shape,
				enabled = pref.isEnabled,
			)
		}

		else -> {
			ActionSettingsItem(
				title = title,
				subtitle = pref.summary?.toString(),
				shape = shape,
				enabled = pref.isEnabled,
				// Use public API instead of the restricted Preference.performClick(): fire the
				// click listener, then fall back to the preference's intent if it didn't handle it.
				onClick = {
					val handled = pref.onPreferenceClickListener?.onPreferenceClick(pref) == true
					if (!handled) {
						pref.intent?.let { intent -> runCatching { pref.context.startActivity(intent) } }
					}
				},
			)
		}
	}
}

@Composable
private fun LanguageSelectionGroup(selection: LanguageSelection) {
	var selectedLang by remember(selection) {
		mutableStateOf(selection.selectedLanguage ?: selection.languages.firstOrNull()?.lang)
	}
	SettingsGroup(title = stringResource(R.string.languages)) {
		selection.languages.forEach { entry ->
			item { pos ->
				SettingsItem(
					title = entry.displayName,
					onClick = {
						selectedLang = entry.lang
						selection.setSelectedLanguage(entry)
					},
					shape = pos.shape,
					trailing = {
						RadioButton(
							selected = selectedLang == entry.lang,
							onClick = {
								selectedLang = entry.lang
								selection.setSelectedLanguage(entry)
							},
						)
					},
				)
			}
		}
	}
}

@Composable
private fun rememberSourceBoolean(
	prefs: SharedPreferences,
	key: String,
	default: Boolean,
): androidx.compose.runtime.MutableState<Boolean> {
	val state = remember(prefs, key) { mutableStateOf(prefs.getBoolean(key, default)) }
	DisposableEffect(prefs, key) {
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, changedKey ->
			if (changedKey == key) state.value = sp.getBoolean(key, default)
		}
		prefs.registerOnSharedPreferenceChangeListener(listener)
		onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
	}
	return remember(state, prefs) {
		object : androidx.compose.runtime.MutableState<Boolean> {
			override var value: Boolean
				get() = state.value
				set(newValue) {
					if (state.value != newValue) {
						state.value = newValue
						prefs.edit().putBoolean(key, newValue).apply()
					}
				}

			override fun component1() = value
			override fun component2(): (Boolean) -> Unit = { value = it }
		}
	}
}
