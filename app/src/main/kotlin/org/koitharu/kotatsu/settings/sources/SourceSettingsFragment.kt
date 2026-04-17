package org.koitharu.kotatsu.settings.sources

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.parser.EmptyMangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.koitharu.kotatsu.mihon.MihonMangaRepository
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.File

@AndroidEntryPoint
class SourceSettingsFragment : BasePreferenceFragment(0) {

	private val viewModel: SourceSettingsViewModel by viewModels()

	override fun onResume() {
		super.onResume()
		context?.let { ctx ->
			setTitle(viewModel.source.getTitle(ctx))
		}
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		val repo = viewModel.repository
		if (repo is MihonMangaRepository) {
			preferenceManager.sharedPreferencesName = "source_${repo.mihonSource.id}"
		} else {
			preferenceManager.sharedPreferencesName = viewModel.source.name.replace(File.separatorChar, '$')
		}
		addPreferencesFromResource(R.xml.pref_source)
		addPreferencesFromRepository(viewModel.repository)
		val isValidSource = viewModel.repository !is EmptyMangaRepository

		findPreference<Preference>(SourceSettings.KEY_SLOWDOWN)?.isVisible = isValidSource
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		tryAddMihonPreferences()
		viewModel.onError.observeEvent(
			viewLifecycleOwner,
			SnackbarErrorObserver(
				listView,
				this,
				exceptionResolver,
				null,
			),
		)
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(listView))
	}

	override fun onDisplayPreferenceDialog(preference: Preference) {
		if (preference.key == SourceSettings.KEY_DOMAIN) {
			if (parentFragmentManager.findFragmentByTag(DomainDialogFragment.DIALOG_FRAGMENT_TAG) != null) {
				return
			}
			val f = DomainDialogFragment.newInstance(preference.key)
			@Suppress("DEPRECATION")
			f.setTargetFragment(this, 0)
			f.show(parentFragmentManager, DomainDialogFragment.DIALOG_FRAGMENT_TAG)
			return
		}
		super.onDisplayPreferenceDialog(preference)
	}

	class DomainDialogFragment : EditTextPreferenceDialogFragmentCompat() {

		override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
			super.onPrepareDialogBuilder(builder)
			builder.setNeutralButton(R.string.reset) { _, _ ->
				resetValue()
			}
		}

		private fun resetValue() {
			val editTextPreference = preference as EditTextPreference
			if (editTextPreference.callChangeListener("")) {
				editTextPreference.text = ""
			}
		}

		companion object {

			const val DIALOG_FRAGMENT_TAG: String = "androidx.preference.PreferenceFragment.DIALOG"

			fun newInstance(key: String) = DomainDialogFragment().withArgs(1) {
				putString(ARG_KEY, key)
			}
		}
	}

	/**
	 * If the source is a Mihon extension implementing ConfigurableSource,
	 * inject its preferences into the current preference screen.
	 * Also adds language enable/disable toggles for multi-language extensions.
	 */
	private fun tryAddMihonPreferences() {
		val repo = viewModel.repository as? MihonMangaRepository ?: return
		val screen = preferenceScreen ?: return
		screen.removePreferenceRecursively(KEY_MIHON_LANGUAGE_TOGGLES)
		screen.removePreferenceRecursively(KEY_MIHON_OPEN_BROWSER)
		screen.removePreferenceRecursively(KEY_MIHON_UNINSTALL_EXTENSION)
		val mihonSource = repo.mihonSource as? ConfigurableSource
		if (mihonSource != null) {
			try {
				mihonSource.setupPreferenceScreen(screen)
			} catch (e: Throwable) {
				android.util.Log.e("SourceSettingsFragment", "Failed to setup Mihon preferences", e)
			}
		}
		addMihonLanguageToggles(repo, screen)
		moveMihonLanguageTogglesToBottom(screen)
		addMihonOpenBrowserPreference(screen, repo)
		addMihonUninstallPreference(screen, repo.source.pkgName)
	}

	private fun addMihonOpenBrowserPreference(screen: PreferenceScreen, repo: MihonMangaRepository) {
		val baseUrl = (repo.mihonSource as? HttpSource)?.baseUrl?.takeIf { it.isNotBlank() } ?: return
		val openBrowserPref = Preference(requireContext()).apply {
			key = KEY_MIHON_OPEN_BROWSER
			title = getString(R.string.open_in_browser)
			icon = ContextCompat.getDrawable(context, R.drawable.ic_open_external)
			summary = baseUrl
			isIconSpaceReserved = true
			onPreferenceClickListener = Preference.OnPreferenceClickListener {
				router.openBrowser(
					url = baseUrl,
					source = repo.source,
					title = repo.source.displayName,
				)
				true
			}
		}
		val maxOrder = (0 until screen.preferenceCount)
			.map { screen.getPreference(it) }
			.maxOfOrNull { it.order } ?: 0
		openBrowserPref.order = maxOrder + 1
		screen.addPreference(openBrowserPref)
	}

	private fun addMihonLanguageToggles(repo: MihonMangaRepository, screen: PreferenceScreen) {
		val pkgName = repo.source.pkgName
		val siblings = viewModel.getSiblingMihonSources().sortedBy { it.language }
		if (siblings.size <= 1) return
		val langs = siblings.map { it.language }

		val category = PreferenceCategory(requireContext()).apply {
			key = KEY_MIHON_LANGUAGE_TOGGLES
			title = getString(R.string.languages)
			isIconSpaceReserved = false
		}
		screen.addPreference(category)

		val allLanguagesToggle = SwitchPreferenceCompat(requireContext()).apply {
			key = "lang_toggle_all_${pkgName}"
			title = getString(R.string.all_languages)
			isPersistent = false
			isChecked = viewModel.areAllMihonSourceLangsEnabled(pkgName, langs)
			isIconSpaceReserved = false
			order = -1
			onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
				val enabled = newValue as Boolean
				viewModel.setMihonSourceLangsEnabled(pkgName, langs, enabled)
				for (i in 0 until category.preferenceCount) {
					val pref = category.getPreference(i) as? SwitchPreferenceCompat ?: continue
					if (pref.key != key) {
						pref.isChecked = enabled
					}
				}
				true
			}
		}
		category.addPreference(allLanguagesToggle)

		for ((index, source) in siblings.withIndex()) {
			val lang = source.language
			val langDisplayName = getExternalExtensionLanguageDisplayName(lang)
			SwitchPreferenceCompat(requireContext()).apply {
				key = "lang_toggle_${pkgName}_$lang"
				title = langDisplayName
				isPersistent = false
				isChecked = viewModel.isMihonSourceLangEnabled(pkgName, lang)
				isIconSpaceReserved = false
				order = index
				onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
					viewModel.setMihonSourceLangEnabled(pkgName, lang, newValue as Boolean)
					allLanguagesToggle.isChecked = viewModel.areAllMihonSourceLangsEnabled(pkgName, langs)
					true
				}
			}.also { category.addPreference(it) }
		}
	}

	private fun moveMihonLanguageTogglesToBottom(screen: PreferenceScreen) {
		val category = screen.findPreference<PreferenceCategory>(KEY_MIHON_LANGUAGE_TOGGLES) ?: return
		val maxOrder = (0 until screen.preferenceCount)
			.map { screen.getPreference(it) }
			.filterNot { it.key == KEY_MIHON_LANGUAGE_TOGGLES }
			.maxOfOrNull { it.order } ?: 0
		category.order = maxOrder + 1
		screen.removePreference(category)
		screen.addPreference(category)
	}

	private fun addMihonUninstallPreference(screen: PreferenceScreen, packageName: String) {
		val errorColor = ContextCompat.getColor(requireContext(), android.R.color.holo_red_light)
		val uninstallPref = Preference(requireContext()).apply {
			key = KEY_MIHON_UNINSTALL_EXTENSION
			title = SpannableString(getString(R.string.uninstall)).apply {
				setSpan(ForegroundColorSpan(errorColor), 0, length, 0)
			}
			icon = ContextCompat.getDrawable(context, R.drawable.ic_delete)
			summary = packageName
			isIconSpaceReserved = true
			onPreferenceClickListener = Preference.OnPreferenceClickListener {
				val uri = Uri.fromParts("package", packageName, null)
				val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					Intent.ACTION_DELETE
				} else {
					@Suppress("DEPRECATION")
					Intent.ACTION_UNINSTALL_PACKAGE
				}
				startActivity(Intent(action, uri))
				true
			}
		}
		val maxOrder = (0 until screen.preferenceCount)
			.map { screen.getPreference(it) }
			.maxOfOrNull { it.order } ?: 0
		uninstallPref.order = maxOrder + 1
		screen.addPreference(uninstallPref)
	}

	companion object {

		private const val KEY_MIHON_LANGUAGE_TOGGLES = "mihon_language_toggles"
		private const val KEY_MIHON_OPEN_BROWSER = "mihon_open_browser"
		private const val KEY_MIHON_UNINSTALL_EXTENSION = "mihon_uninstall_extension"

		fun newInstance(source: MangaSource) = SourceSettingsFragment().withArgs(1) {
			putString(AppRouter.KEY_SOURCE, source.name)
		}
	}
}
