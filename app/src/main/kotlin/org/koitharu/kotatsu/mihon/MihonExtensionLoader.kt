package org.koitharu.kotatsu.mihon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import android.os.Bundle
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.Lazy
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.mihon.compat.KotoInjektBridge
import org.koitharu.kotatsu.mihon.model.MihonExtensionInfo
import org.koitharu.kotatsu.mihon.model.MihonLoadResult
import eu.kanade.tachiyomi.util.lang.Hash
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonExtensionLoader @Inject constructor(
	private val injektBridge: Lazy<KotoInjektBridge>,
) {

	companion object {
		private const val TAG = "MihonExtensionLoader"
		private const val EXTENSION_FEATURE = "tachiyomi.extension"
		private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
		private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
		private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
		private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
		private const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"
		/** Languages hidden from the whole app: their source variants are never loaded. */
		val HIDDEN_LANGUAGES = setOf("he", "iw")

		@Suppress("DEPRECATION")
		fun getSignatures(pkgInfo: PackageInfo): List<String> {
			val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				val signingInfo = pkgInfo.signingInfo ?: return emptyList()
				if (signingInfo.hasMultipleSigners()) {
					signingInfo.apkContentsSigners
				} else {
					signingInfo.signingCertificateHistory
				}
			} else {
				pkgInfo.signatures
			}
			return signatures.orEmpty().map { Hash.sha256(it.toByteArray()) }
		}

		/** SHA-256 signatures of one installed package — for repo attribution by fingerprint. */
		@Suppress("DEPRECATION")
		fun getPackageSignatures(context: Context, pkgName: String): List<String> = runCatching {
			val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				PackageManager.GET_SIGNING_CERTIFICATES
			} else {
				PackageManager.GET_SIGNATURES
			}
			getSignatures(context.packageManager.getPackageInfo(pkgName, flags))
		}.getOrDefault(emptyList())
		// Keep the accepted ABI window bounded by Mihon's source-api. Loading a hypothetical newer
		// APK and hoping its missing host symbols are unused turns a clear incompatibility into a
		// delayed NoSuchMethodError.
		const val LIB_VERSION_MIN = 1.4
		const val LIB_VERSION_MAX = 1.6

		private const val PRIVATE_EXTENSION_DIR = "exts"
		private const val PRIVATE_EXTENSION_EXT = "ext"

		fun getPrivateExtensionDir(context: Context): File =
			File(context.filesDir, PRIVATE_EXTENSION_DIR)

		fun isPrivateExtensionInstalled(context: Context, pkgName: String): Boolean =
			File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXT").isFile

		fun hasPrivateExtensions(context: Context): Boolean {
			val dir = getPrivateExtensionDir(context)
			return dir.listFiles()?.any { it.isFile && it.extension == PRIVATE_EXTENSION_EXT } == true
		}

		fun installPrivateExtensionFile(
			context: Context,
			file: File,
			replaceExistingProvider: Boolean = false,
			expectedPackageName: String? = null,
		): Boolean {
			val pkgManager = context.packageManager
			@Suppress("DEPRECATION")
			val flags = PackageManager.GET_META_DATA or
				PackageManager.GET_CONFIGURATIONS or
				PackageManager.GET_SIGNATURES or
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
			val pkgInfo = pkgManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
			if (expectedPackageName != null && pkgInfo.packageName != expectedPackageName) return false
			if (!isPackageAnExtensionStatic(pkgInfo)) return false

			val targetDir = getPrivateExtensionDir(context)
			val target = File(targetDir, "${pkgInfo.packageName}.$PRIVATE_EXTENSION_EXT")
			val staged = File(targetDir, "${pkgInfo.packageName}.new")
			val backup = File(targetDir, "${pkgInfo.packageName}.bak")
			if (!target.exists() && backup.exists() && !backup.renameTo(target)) {
				Log.e(TAG, "Failed to restore the previous private extension.")
				return false
			}
			val currentPkgInfo = if (target.exists()) {
				pkgManager.getPackageArchiveInfo(target.absolutePath, flags)
			} else null

			val newSignatures = getSignatures(pkgInfo)
			if (newSignatures.isEmpty()) {
				Log.e(TAG, "Extension to be installed is not signed.")
				return false
			}
			if (currentPkgInfo != null && !replaceExistingProvider) {
				if (PackageInfoCompat.getLongVersionCode(pkgInfo) < PackageInfoCompat.getLongVersionCode(currentPkgInfo)) {
					Log.e(TAG, "Installed private extension version is higher. Downgrading is not allowed.")
					return false
				}
				val currentSignatures = getSignatures(currentPkgInfo)
				if (!newSignatures.containsAll(currentSignatures)) {
					Log.e(TAG, "Installed private extension signature does not match.")
					return false
				}
			}

			var currentPreserved = false
			return try {
				targetDir.mkdirs()
				staged.delete()
				backup.delete()
				copyAndSetReadOnly(file, staged)
				val stagedInfo = pkgManager.getPackageArchiveInfo(staged.absolutePath, flags)
				if (
					stagedInfo == null ||
					stagedInfo.packageName != pkgInfo.packageName ||
					!isPackageAnExtensionStatic(stagedInfo) ||
					getSignatures(stagedInfo).isEmpty()
				) {
					throw IllegalArgumentException("Staged extension validation failed")
				}
				if (target.exists() && !target.renameTo(backup)) {
					throw IllegalStateException("Failed to preserve installed extension")
				}
				currentPreserved = backup.exists()
				if (!staged.renameTo(target)) {
					throw IllegalStateException("Failed to activate staged extension")
				}
				target.setReadOnly()
				backup.delete()
				Log.i(TAG, "Private extension installed: ${pkgInfo.packageName}")
				true
			} catch (e: Exception) {
				Log.e(TAG, "Failed to copy private extension file.", e)
				if (currentPreserved) {
					target.delete()
					backup.renameTo(target)
					target.setReadOnly()
				}
				false
			} finally {
				staged.delete()
			}
		}

		fun uninstallPrivateExtension(context: Context, pkgName: String) {
			File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXT").delete()
		}

		private fun copyAndSetReadOnly(source: File, target: File) {
			target.parentFile?.mkdirs()
			target.setReadOnly()
			source.inputStream().use { input ->
				target.outputStream().use { output ->
					input.copyTo(output)
				}
			}
		}

		@Suppress("DEPRECATION")
		private val packageFlags: Int
			get() = PackageManager.GET_META_DATA or
				PackageManager.GET_CONFIGURATIONS or
				PackageManager.GET_SIGNATURES or
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0

		internal fun isPackageAnExtensionStatic(pkgInfo: PackageInfo): Boolean {
			val appInfo = pkgInfo.applicationInfo ?: return false
			val metaData = appInfo.metaData
			val hasFeature = pkgInfo.reqFeatures?.any { it.name == EXTENSION_FEATURE } == true
			val hasSource = metaData?.containsKey(METADATA_SOURCE_CLASS) == true ||
				metaData?.containsKey(METADATA_SOURCE_FACTORY) == true
			return hasFeature || hasSource
		}

		internal fun normalizeSourceClassNames(pkgName: String, sourceClassNames: String): List<String> {
			return sourceClassNames
				.split(';', ':', ',')
				.map { it.trim() }
				.filter { it.isNotEmpty() }
				.map { className ->
					if (className.startsWith('.')) {
						pkgName + className
					} else {
						className
					}
				}
		}

		internal fun readNsfwFlag(metaData: Bundle): Boolean {
			if (metaData.getInt(METADATA_CONTENT_WARNING, 0) > 0) return true
			if (!metaData.containsKey(METADATA_NSFW)) {
				return false
			}
			return runCatching {
				parseNsfwFlag(metaData.getInt(METADATA_NSFW))
			}.getOrElse {
				runCatching {
					parseNsfwFlag(metaData.getBoolean(METADATA_NSFW))
				}.getOrElse {
					parseNsfwFlag(metaData.getString(METADATA_NSFW))
				}
			}
		}

		internal fun parseNsfwFlag(value: Any?): Boolean {
			return when (value) {
				is Boolean -> value
				is Int -> value != 0
				is String -> value == "1" || value.equals("true", ignoreCase = true)
				else -> false
			}
		}

		internal fun isSupportedLibVersion(libVersion: Double): Boolean {
			return libVersion in LIB_VERSION_MIN..LIB_VERSION_MAX
		}
	}

	suspend fun loadExtensions(context: Context, privateMode: Boolean = false): List<MihonLoadResult> = withContext(Dispatchers.IO) {
		injektBridge.get().initialize()
		if (privateMode) {
			loadPrivateExtensions(context)
		} else {
			getInstalledPackages(context.packageManager)
				.filter(::isPackageAnExtension)
				.map { pkgInfo -> async { loadExtension(context, pkgInfo, isShared = true) } }
				.awaitAll()
		}
	}

	private suspend fun loadPrivateExtensions(context: Context): List<MihonLoadResult> = coroutineScope {
		val pkgManager = context.packageManager
		val privateDir = getPrivateExtensionDir(context)
		val privateFiles = privateDir.listFiles()
			?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXT }
			?: emptyList()
		if (privateFiles.isEmpty()) return@coroutineScope emptyList()
		privateFiles.map { file ->
			async {
				if (file.canWrite()) file.setReadOnly()
				val pkgInfo = pkgManager.getPackageArchiveInfo(file.absolutePath, packageFlags)
				if (pkgInfo == null || !isPackageAnExtension(pkgInfo)) {
					return@async buildLoggedError(file.nameWithoutExtension, "Failed to read private extension APK")
				}
				pkgInfo.applicationInfo?.fixBasePaths(file.absolutePath)
				loadExtension(context, pkgInfo, isShared = false)
			}
		}.awaitAll()
	}

	/**
	 * Load a single Mihon extension by package name.
	 */
	suspend fun loadExtension(context: Context, packageName: String): MihonLoadResult? = withContext(Dispatchers.IO) {
		injektBridge.get().initialize()
		val pkgManager = context.packageManager
		val pkgInfo = try {
			@Suppress("DEPRECATION")
			pkgManager.getPackageInfo(
				packageName,
				PackageManager.GET_META_DATA or
					PackageManager.GET_CONFIGURATIONS or
					PackageManager.GET_SIGNATURES or
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0,
			)
		} catch (e: PackageManager.NameNotFoundException) {
			null
		} ?: return@withContext null

		if (!isPackageAnExtension(pkgInfo)) {
			return@withContext null
		}
		loadExtension(context, pkgInfo)
	}

	/**
	 * Get list of installed Mihon extensions (metadata only, without loading).
	 */
	fun getInstalledExtensions(context: Context, privateMode: Boolean = false): List<MihonExtensionInfo> {
		val pkgManager = context.packageManager
		return if (privateMode) {
			val privateDir = getPrivateExtensionDir(context)
			privateDir.listFiles()
				?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXT }
				?.mapNotNull { file ->
					if (file.canWrite()) file.setReadOnly()
					val pkgInfo = pkgManager.getPackageArchiveInfo(file.absolutePath, packageFlags) ?: return@mapNotNull null
					pkgInfo.applicationInfo?.fixBasePaths(file.absolutePath)
					if (!isPackageAnExtension(pkgInfo)) return@mapNotNull null
					extractExtensionInfo(pkgInfo, pkgManager, isShared = false)
				}
				.orEmpty()
		} else {
			getInstalledPackages(pkgManager)
				.filter(::isPackageAnExtension)
				.mapNotNull { pkgInfo -> extractExtensionInfo(pkgInfo, pkgManager, isShared = true) }
		}
	}

	private fun extractExtensionInfo(pkgInfo: PackageInfo, pkgManager: PackageManager, isShared: Boolean = true): MihonExtensionInfo? {
		val appInfo = pkgInfo.applicationInfo ?: return null
		val metaData = appInfo.metaData ?: return null
		val versionName = pkgInfo.versionName ?: return null
		val libVersion = readLibVersion(metaData, versionName) ?: return null
		val sourceClassName = metaData.getString(METADATA_SOURCE_CLASS)
			?: metaData.getString(METADATA_SOURCE_FACTORY)
			?: return null
		val lang = extractLanguage(pkgInfo.packageName)
		val appName = try {
			appInfo.loadLabel(pkgManager).toString()
		} catch (e: Exception) {
			pkgInfo.packageName.substringAfterLast('.')
		}
		return MihonExtensionInfo(
			pkgName = pkgInfo.packageName,
			appName = appName,
			versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
			versionName = versionName,
			libVersion = libVersion,
			lang = lang,
			isNsfw = readNsfwFlag(metaData),
			sourceClassName = sourceClassName,
			apkPath = appInfo.sourceDir ?: return null,
			signatures = getSignatures(pkgInfo),
			isShared = isShared,
		)
	}

	private fun loadExtension(context: Context, pkgInfo: PackageInfo, isShared: Boolean = true): MihonLoadResult {
		val appInfo = pkgInfo.applicationInfo
			?: return buildLoggedError(pkgInfo.packageName, "No ApplicationInfo")
		val metaData = appInfo.metaData
			?: return buildLoggedError(pkgInfo.packageName, "No manifest metadata")
		val versionName = pkgInfo.versionName
			?: return buildLoggedError(pkgInfo.packageName, "No version name")
		val libVersion = readLibVersion(metaData, versionName)
			?: return buildLoggedError(pkgInfo.packageName, "Invalid lib version: $versionName")
		if (!isSupportedLibVersion(libVersion)) {
			return buildLoggedError(
				pkgName = pkgInfo.packageName,
				message = "Incompatible lib version: $libVersion",
			)
		}
		val sourceClassNames = metaData.getString(METADATA_SOURCE_CLASS)
			?: metaData.getString(METADATA_SOURCE_FACTORY)
			?: return buildLoggedError(pkgInfo.packageName, "No source class metadata")
		val appName = runCatching {
			appInfo.loadLabel(context.packageManager).toString()
		}.getOrDefault(pkgInfo.packageName)
		// Trust any signed, OS-installed extension regardless of which repo signed it. These are real
		// Android packages: the package installer already took the user's consent at install time and
		// Android enforces same-signer on every update, so pinning one repo's key here only blocks
		// every other repo without adding protection the OS doesn't already give. We still reject
		// unsigned APKs — that's the actual trust boundary.
		// ponytail: signed == trusted. Reintroduce a signing-key allowlist only if private
		// (non-OS-installed, loaded-from-file) extensions are ever supported, since those bypass the
		// installer consent this relies on.
		val signatures = getSignatures(pkgInfo)
		if (signatures.isEmpty()) {
			return buildLoggedError(pkgInfo.packageName, "Extension APK is unsigned")
		}
		val classLoader = runCatching {
			ChildFirstPathClassLoader(
				appInfo.sourceDir,
				appInfo.nativeLibraryDir,
				context.classLoader,
			)
		}.getOrElse {
			Log.e(TAG, "Failed to create class loader for ${pkgInfo.packageName}", it)
			return buildLoggedError(pkgInfo.packageName, "Failed to create class loader", it)
		}
		val sources = runCatching {
			loadSources(pkgInfo.packageName, sourceClassNames, classLoader)
		}.getOrElse {
			Log.e(TAG, "Failed to load sources for ${pkgInfo.packageName}", it)
			return buildLoggedError(pkgInfo.packageName, "Failed to load sources", it)
		}
		if (sources.isEmpty()) {
			return buildLoggedError(pkgInfo.packageName, "No sources loaded")
		}
		logLoadedSources(pkgInfo.packageName, sources)
		return MihonLoadResult.Success(
			pkgName = pkgInfo.packageName,
			appName = appName,
			versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
			versionName = versionName,
			libVersion = libVersion,
			// Mihon derives an installed extension's language from the sources it actually
			// created. SourceFactory APKs may expose several languages even though their package
			// path contains only one segment, so package-name inference loses that information.
			lang = sources.mapNotNull { (it as? CatalogueSource)?.lang }.toSet().let { langs ->
				when (langs.size) {
					0 -> ""
					1 -> langs.first()
					else -> "all"
				}
			},
			isNsfw = readNsfwFlag(metaData),
			sources = sources,
			isShared = isShared,
		)
	}

	private fun loadSources(pkgName: String, sourceClassNames: String, classLoader: ClassLoader): List<Source> {
		return normalizeSourceClassNames(pkgName, sourceClassNames)
			.flatMap { className ->
				val instance = classLoader.loadClass(className).getDeclaredConstructor().newInstance()
				when (instance) {
					is Source -> listOf(instance)
					is SourceFactory -> {
						// Cast through Any? to handle Java SourceFactory implementations whose
						// createSources() may return a list with null elements at runtime despite
						// the non-null Kotlin type, which would otherwise crash every source in
						// the extension.
						@Suppress("UNCHECKED_CAST")
						(instance.createSources() as List<Any?>).filterNotNull().filterIsInstance<Source>()
					}
					// Match Mihon: malformed metadata is a load failure, not a successful
					// extension with a silently missing source.
					else -> error("Unknown source class type: ${instance.javaClass.name}")
				}
			}
			.filterNot { (it as? CatalogueSource)?.lang in HIDDEN_LANGUAGES }
	}

	private fun buildLoggedError(
		pkgName: String,
		message: String,
		exception: Throwable? = null,
	): MihonLoadResult.Error {
		if (exception == null) {
			Log.w(TAG, "$pkgName: $message")
		} else {
			Log.e(TAG, "$pkgName: $message", exception)
		}
		return MihonLoadResult.Error(pkgName, message, exception)
	}

	private fun logLoadedSources(pkgName: String, sources: List<Source>) {
		val summary = sources.joinToString(separator = " | ") { source ->
			when (source) {
				is CatalogueSource -> "id=${source.id},name=${source.name},lang=${source.lang},class=${source.javaClass.name}"
				else -> "id=${source.id},class=${source.javaClass.name}"
			}
		}
		Log.i(TAG, "Loaded extension $pkgName with ${sources.size} source(s): $summary")
	}

	private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
		val appInfo = pkgInfo.applicationInfo ?: return false
		val metaData = appInfo.metaData
		val pkgName = pkgInfo.packageName
		val hasFeature = pkgInfo.reqFeatures?.any { it.name == EXTENSION_FEATURE } == true
		val hasSource = metaData?.containsKey(METADATA_SOURCE_CLASS) == true ||
			metaData?.containsKey(METADATA_SOURCE_FACTORY) == true
		return hasFeature || hasSource
	}

	private fun parseLibVersion(versionName: String): Double? {
		return versionName.substringBeforeLast('.').toDoubleOrNull()
			?: versionName.split('.').take(2).joinToString(".").toDoubleOrNull()
	}

	private fun readLibVersion(metaData: Bundle, versionName: String): Double? =
		metaData.getDouble(METADATA_EXTENSION_LIB, 0.0)
			.takeUnless { it == 0.0 }
			?: parseLibVersion(versionName)

	private fun extractLanguage(packageName: String): String {
		val parts = packageName.split('.')
		val extIndex = parts.indexOfLast { it == "extension" }
		return parts.getOrNull(extIndex + 1)
			?.takeIf { it.isNotBlank() }
			?: parts.lastOrNull()
			?: "all"
	}


	@Suppress("DEPRECATION")
	private fun getInstalledPackages(packageManager: PackageManager): List<PackageInfo> {
		val flags = PackageManager.GET_META_DATA or
			PackageManager.GET_CONFIGURATIONS or
			PackageManager.GET_SIGNATURES or
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			packageManager.getInstalledPackages(
				PackageManager.PackageInfoFlags.of(flags.toLong()),
			)
		} else {
			packageManager.getInstalledPackages(flags)
		}
	}

	private fun ApplicationInfo.fixBasePaths(apkPath: String) {
		if (sourceDir == null) sourceDir = apkPath
		if (publicSourceDir == null) publicSourceDir = apkPath
	}
}
