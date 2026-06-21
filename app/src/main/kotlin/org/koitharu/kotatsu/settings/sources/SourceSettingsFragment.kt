package org.koitharu.kotatsu.settings.sources

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.parser.EmptyMangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.mihon.MihonMangaRepository
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.EditTextSettingsItem
import org.koitharu.kotatsu.settings.compose.InfoSettingsItem
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.MultiSelectSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem

@AndroidEntryPoint
class SourceSettingsFragment : BaseComposeSettingsFragment(0) {

	private val viewModel: SourceSettingsViewModel by viewModels()

	// Preference managers/screens are kept alive while the screen is shown — the bridged
	// Preference objects (read by MihonPreferenceRow) hold no separate references. One entry per
	// language variant that has been opened.
	private val mihonPms = mutableListOf<PreferenceManager>()
	private val mihonScreens = mutableListOf<PreferenceScreen>()

	// Built lazily per language and cached, so a 30-language source only builds the screens the
	// user actually views, and switching back is instant.
	private val variantCache = mutableMapOf<String, SourceVariant>()

	override fun onResume() {
		super.onResume()
		val ctx = context ?: return
		(activity as? org.koitharu.kotatsu.settings.SettingsActivity)
			?.setSectionTitle(viewModel.source.getTitle(ctx))
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		val repo = viewModel.repository
		val isValidSource = repo !is EmptyMangaRepository
		val mihonSource = (repo as? MihonMangaRepository)?.source
		val uninstallPkg = mihonSource?.pkgName

		// Language variants of this logical source (same package + name). >1 => show radio picker.
		val siblings = viewModel.getSiblingMihonSources()
			.sortedBy { it.languageDisplayName.lowercase() }
		val isMulti = siblings.size > 1
		val languageOptions = if (isMulti) {
			siblings.map { LanguageOption(it.language, it.languageDisplayName) }
		} else {
			emptyList()
		}
		val initialLang = if (isMulti) {
			viewModel.getActiveLanguage(siblings) ?: siblings.first().language
		} else {
			null
		}

		val variantProvider: (String?) -> SourceVariant = { lang ->
			if (lang != null && isMulti) {
				variantCache.getOrPut(lang) {
					val src = siblings.first { it.language == lang }
					buildVariant(src.catalogueSource, src.name)
				}
			} else {
				variantCache.getOrPut(DEFAULT_VARIANT_KEY) {
					if (mihonSource != null) {
						buildVariant(mihonSource.catalogueSource, mihonSource.name)
					} else {
						buildVariant(null, viewModel.source.name)
					}
				}
			}
		}

		setContent {
			DropSauceTheme {
				SourceSettingsScreen(
					isValidSource = isValidSource,
					languageOptions = languageOptions,
					initialLang = initialLang,
					variantProvider = variantProvider,
					uninstallPkg = uninstallPkg,
					onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
					onOpenBrowser = { url -> openBrowser(url) },
					onUninstall = { pkg -> uninstallExtension(pkg) },
					onLanguageSelected = { lang ->
						viewModel.setActiveLanguage(lang)
						requireActivity().recreate()
					},
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
		mihonScreens.clear()
		mihonPms.clear()
		variantCache.clear()
		super.onDestroyView()
	}

	private fun buildVariant(catalogueSource: CatalogueSource?, sourceName: String): SourceVariant {
		val prefsName = SourceSettings.getStorageName(sourceName)
		val prefs = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
		val sections = buildMihonSections(catalogueSource, prefsName)
		val browserUrl = (catalogueSource as? HttpSource)?.baseUrl?.takeIf { it.isNotBlank() }
		return SourceVariant(sections = sections, sourcePrefs = prefs, openBrowserUrl = browserUrl)
	}

	@SuppressLint("RestrictedApi")
	private fun buildMihonSections(catalogueSource: CatalogueSource?, prefsName: String): List<PreferenceSection> {
		val configurable = catalogueSource as? ConfigurableSource ?: return emptyList()
		val ctx = requireContext()
		val pm = PreferenceManager(ctx).apply { sharedPreferencesName = prefsName }
		val screen = pm.createPreferenceScreen(ctx)
		try {
			configurable.setupPreferenceScreen(screen)
		} catch (e: Throwable) {
			Log.e("SourceSettingsFragment", "Failed to setup Mihon preferences", e)
		}
		mihonPms += pm
		mihonScreens += screen
		return buildSections(screen)
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
		private const val DEFAULT_VARIANT_KEY = " default"

		fun newInstance(source: MangaSource) = SourceSettingsFragment().withArgs(1) {
			putString(AppRouter.KEY_SOURCE, source.name)
		}
	}
}

private data class PreferenceSection(val title: String?, val preferences: List<Preference>)

/** A language choice for the radio picker. [displayName] is the native autonym. */
private data class LanguageOption(val lang: String, val displayName: String)

/** Everything that depends on which language variant is currently selected. */
private class SourceVariant(
	val sections: List<PreferenceSection>,
	val sourcePrefs: SharedPreferences,
	val openBrowserUrl: String?,
)

@Composable
private fun SourceSettingsScreen(
	isValidSource: Boolean,
	languageOptions: List<LanguageOption>,
	initialLang: String?,
	variantProvider: (String?) -> SourceVariant,
	uninstallPkg: String?,
	onBack: () -> Unit,
	onOpenBrowser: (String) -> Unit,
	onUninstall: (String) -> Unit,
	onLanguageSelected: (String) -> Unit,
) {
	// The active language drives an in-place reload of the whole screen.
	var selectedLang by remember { mutableStateOf(initialLang) }
	val variant = remember(selectedLang) { variantProvider(selectedLang) }
	val sourcePrefs = variant.sourcePrefs

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

		// Dynamic extension-provided preferences (reload with the selected language).
		variant.sections.forEach { section ->
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

		// Language picker (single-select radio buttons) — only for multi-language sources.
		// Placed below the extension's own settings.
		if (languageOptions.isNotEmpty()) {
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			item {
				LanguageRadioGroup(
					options = languageOptions,
					selectedLang = selectedLang,
					onSelect = { lang ->
						selectedLang = lang
						onLanguageSelected(lang)
					},
				)
			}
		}

		if (variant.openBrowserUrl != null || uninstallPkg != null) {
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			item {
				SettingsGroup {
					val browserUrl = variant.openBrowserUrl
					if (browserUrl != null) {
						item { pos ->
							ActionSettingsItem(
								title = stringResource(R.string.open_in_browser),
								subtitle = browserUrl,
								icon = R.drawable.ic_open_external,
								shape = pos.shape,
								onClick = { onOpenBrowser(browserUrl) },
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
private fun LanguageRadioGroup(
	options: List<LanguageOption>,
	selectedLang: String?,
	onSelect: (String) -> Unit,
) {
	SettingsGroup(title = stringResource(R.string.language)) {
		options.forEach { option ->
			item { pos ->
				val isSelected = option.lang == selectedLang
				SettingsItem(
					title = option.displayName,
					shape = pos.shape,
					onClick = { onSelect(option.lang) },
					trailing = {
						RadioButton(
							selected = isSelected,
							onClick = { onSelect(option.lang) },
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
