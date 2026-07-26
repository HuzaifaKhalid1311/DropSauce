package org.koitharu.kotatsu.mihon.model

import kotlinx.serialization.Serializable

/**
 * Authoritative metadata for an extension repo, read from its `repo.json` (`meta`). [fingerprint] is
 * the repo's signingKeyFingerprint — an installed extension whose signature matches it belongs to
 * this repo, which is how we attribute installed (incl. previously-installed) extensions to a repo
 * without recording anything at install time.
 */
@Serializable
data class ExternalRepoInfo(
	val url: String,
	val name: String,
	val shortName: String? = null,
	val fingerprint: String,
	val website: String? = null,
	val discord: String? = null,
) {
	val displayName: String
		get() = shortName?.takeIf { it.isNotBlank() } ?: name
}
