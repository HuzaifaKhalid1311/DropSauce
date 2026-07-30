package eu.kanade.tachiyomi.source

/**
 * Tsundoku source-api compatibility contract. DropSauce keeps extension-owned request pacing;
 * these values are exposed so APKs implementing the interface can load unchanged.
 */
interface RateLimited {
	val minimumDelayMillis: Long

	val recommendedDelayMillis: Long
		get() = minimumDelayMillis

	val recommendedPermits: Int
		get() = 1
}
