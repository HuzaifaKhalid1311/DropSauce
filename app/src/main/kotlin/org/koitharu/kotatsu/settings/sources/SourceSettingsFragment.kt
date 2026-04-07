package org.koitharu.kotatsu.settings.sources

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.core.content.ContextCompat
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import dagger.hilt.android.AndroidEntryPoint
import eu.kanade.tachiyomi.source.ConfigurableSource
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.AppRouter
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
		viewModel.getCurrentMihonPackageName()?.let { pkg ->
			screen.removePreferenceRecursively("$KEY_UNINSTALL_PREF$pkg")
		}
		val mihonSource = repo.mihonSource as? ConfigurableSource
		if (mihonSource != null) {
			try {
				mihonSource.setupPreferenceScreen(screen)
			} catch (e: Throwable) {
				android.util.Log.e("SourceSettingsFragment", "Failed to setup Mihon preferences", e)
			}
		}
		addMihonLanguageToggles(repo, screen)
	}

	private fun addMihonLanguageToggles(repo: MihonMangaRepository, screen: PreferenceScreen) {
		val pkgName = repo.source.pkgName
		val siblings = viewModel.getSiblingMihonSources()
		if (siblings.size <= 1) return

		val category = PreferenceCategory(requireContext()).apply {
			key = KEY_MIHON_LANGUAGE_TOGGLES
			title = getString(R.string.languages)
			isIconSpaceReserved = false
		}
		screen.addPreference(category)
		val sortedSiblings = siblings.sortedBy { it.language }
		SwitchPreferenceCompat(requireContext()).apply {
			key = "lang_toggle_all_$pkgName"
			title = getString(R.string.enable_all_languages)
			isPersistent = false
			isChecked = sortedSiblings.all { viewModel.isMihonSourceLangEnabled(pkgName, it.language) }
			isIconSpaceReserved = false
			order = -1
			onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
				val enabled = newValue as Boolean
				sortedSiblings.forEach { source ->
					viewModel.setMihonSourceLangEnabled(pkgName, source.language, enabled)
				}
				for (i in 0 until category.preferenceCount) {
					val pref = category.getPreference(i) as? SwitchPreferenceCompat ?: continue
					if (pref.key.startsWith("lang_toggle_${pkgName}_")) {
						pref.isChecked = enabled
					}
				}
				true
			}
		}.also { category.addPreference(it) }

		for ((index, source) in sortedSiblings.withIndex()) {
			val lang = source.language
			val langDisplayName = getExternalExtensionLanguageDisplayName(lang)
			SwitchPreferenceCompat(requireContext()).apply {
				key = "lang_toggle_${pkgName}_$lang"
				title = langDisplayName
				isPersistent = false
				isChecked = viewModel.isMihonSourceLangEnabled(pkgName, lang)
				isIconSpaceReserved = false
				order = index + 1
				onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
					viewModel.setMihonSourceLangEnabled(pkgName, lang, newValue as Boolean)
					val allPref = category.findPreference<SwitchPreferenceCompat>("lang_toggle_all_$pkgName")
					allPref?.isChecked = sortedSiblings.all { item ->
						if (item.language == lang) {
							newValue as Boolean
						} else {
							viewModel.isMihonSourceLangEnabled(pkgName, item.language)
						}
					}
					true
				}
			}.also { category.addPreference(it) }
		}

		val uninstallPref = Preference(requireContext()).apply {
			key = "$KEY_UNINSTALL_PREF$pkgName"
			title = SpannableString(getString(R.string.uninstall)).apply {
				setSpan(
					ForegroundColorSpan(ContextCompat.getColor(requireContext(), com.google.android.material.R.color.design_default_color_error)),
					0,
					length,
					0,
				)
			}
			icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_delete)
			isIconSpaceReserved = false
			order = Int.MAX_VALUE
			summary = repo.source.displayName
			onPreferenceClickListener = Preference.OnPreferenceClickListener {
				showUninstallDialog(pkgName, repo.source.displayName)
				true
			}
		}
		uninstallPref.icon?.setTint(ContextCompat.getColor(requireContext(), com.google.android.material.R.color.design_default_color_error))
		screen.addPreference(uninstallPref)
	}

	companion object {

		private const val KEY_MIHON_LANGUAGE_TOGGLES = "mihon_language_toggles"
		private const val KEY_UNINSTALL_PREF = "uninstall_extension_"

		fun newInstance(source: MangaSource) = SourceSettingsFragment().withArgs(1) {
			putString(AppRouter.KEY_SOURCE, source.name)
		}
	}

	private fun showUninstallDialog(pkgName: String, displayName: String) {
		androidx.appcompat.app.AlertDialog.Builder(requireContext())
			.setTitle(R.string.uninstall)
			.setMessage(displayName)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.uninstall) { _, _ ->
				startActivity(
					android.content.Intent(
						if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
							android.content.Intent.ACTION_DELETE
						} else {
							@Suppress("DEPRECATION")
							android.content.Intent.ACTION_UNINSTALL_PACKAGE
						},
						android.net.Uri.fromParts("package", pkgName, null),
					),
				)
			}
			.show()
	}
}
