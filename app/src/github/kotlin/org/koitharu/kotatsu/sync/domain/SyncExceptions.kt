package org.koitharu.kotatsu.sync.domain

import java.io.IOException

/** Thrown when the user must (re)authorize Google Drive access interactively. */
class SyncSignInRequiredException(
	message: String = "Google Drive authorization is required",
) : IOException(message)

/** Thrown for Drive REST API errors. */
class SyncApiException(
	val code: Int,
	message: String,
) : IOException(message)

/**
 * Thrown when the remote snapshot was written by a newer app version whose format this build can't
 * safely read. We abort rather than risk clobbering newer data with an older format — the user must
 * update the app. This is non-recoverable until then, so syncing shouldn't keep retrying.
 */
class SyncSchemaException(
	val remoteVersion: Int,
) : IOException("Sync data was created by a newer version of the app (format v$remoteVersion). Please update.")
