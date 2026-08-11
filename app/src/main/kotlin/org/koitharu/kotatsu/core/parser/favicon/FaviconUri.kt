package org.koitharu.kotatsu.core.parser.favicon

import android.net.Uri
import org.koitharu.kotatsu.parsers.model.MangaSource

const val URI_SCHEME_FAVICON = "favicon"

/** Marks a favicon uri that names an extension package directly instead of a source. */
const val FAVICON_PACKAGE_PREFIX = "PKG_"

fun MangaSource.faviconUri(): Uri = Uri.fromParts(URI_SCHEME_FAVICON, name, null)

/**
 * The icon of an installed extension package, straight from its APK. For callers that know the
 * package but cannot rely on the source being resolvable — the extension store lists packages
 * before (or without) the extension list having produced a source for them.
 */
fun extensionPackageFaviconUri(pkgName: String): Uri =
	Uri.fromParts(URI_SCHEME_FAVICON, FAVICON_PACKAGE_PREFIX + pkgName, null)