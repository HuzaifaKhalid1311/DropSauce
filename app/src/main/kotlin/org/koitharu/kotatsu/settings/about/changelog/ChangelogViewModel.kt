package org.koitharu.kotatsu.settings.about.changelog

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.jsoup.internal.StringUtil
import org.koitharu.kotatsu.core.github.AppUpdateRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import javax.inject.Inject

@HiltViewModel
class ChangelogViewModel @Inject constructor(
	private val appUpdateRepository: AppUpdateRepository,
) : BaseViewModel() {

	val changelog = MutableStateFlow<String?>(null)

	init {
		launchLoadingJob(Dispatchers.Default) {
			val versions = appUpdateRepository.getAvailableVersions()
			val stringJoiner = StringUtil.StringJoiner("\n\n\n")
			for (version in versions) {
				stringJoiner.add(version.description.formatChangelogDescription())
			}
			changelog.value = stringJoiner.complete()
		}
	}

	private fun String.formatChangelogDescription(): String {
		var text = replace(Regex("(?<!\\n)\\nIf this is your first"), "\n\nIf this is your first")
		// installation instructions belong after the changelog, not before it
		installationSection.find(text)?.let { match ->
			text = text.removeRange(match.range).trimEnd() + "\n\n\n" + match.value.trim()
		}
		return text.tidyInstructions()
	}

	private fun String.tidyInstructions(): String = replace(listItem) { match ->
		match.groupValues[1] + match.groupValues[2].uppercase()
	}.replace(Regex("\"\\s*$", RegexOption.MULTILINE), "")

	private companion object {

		val installationSection = Regex(
			"^#{1,6}\\s*Installation Instructions\\b.*?(?=^#{1,6}\\s|\\z)",
			setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
		)

		val listItem = Regex("^(\\s*(?:\\d+[.)]|[-*+])\\s+)([a-z])", RegexOption.MULTILINE)
	}
}
