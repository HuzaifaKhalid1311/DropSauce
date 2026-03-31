package org.haziffe.dropsauce.mihon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import android.os.Bundle
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.haziffe.dropsauce.mihon.compat.KotoInjektBridge
import org.haziffe.dropsauce.mihon.model.MihonExtensionInfo
import org.haziffe.dropsauce.mihon.model.MihonLoadResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonExtensionLoader @Inject constructor(
	@ApplicationContext private val applicationContext: Context,
	private val injektBridge: Lazy<KotoInjektBridge>,
) {

	companion object {
		private const val TAG = "MihonExtensionLoader"
		private const val EXTENSION_FEATURE = "tachiyomi.extension"
		private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
		private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
		private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
		const val LIB_VERSION_MIN = 1.2
		const val LIB_VERSION_MAX = 1.9

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

	suspend fun loadExtensions(context: Context): List<MihonLoadResult> = withContext(Dispatchers.IO) {
		injektBridge.get().initialize()
		getInstalledPackages(context.packageManager)
			.filter(::isPackageAnExtension)
			.map { pkgInfo -> async { loadExtension(context, pkgInfo) } }
			.awaitAll()
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
				PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS,
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
	fun getInstalledExtensions(context: Context): List<MihonExtensionInfo> {
		val pkgManager = context.packageManager
		return getInstalledPackages(pkgManager)
			.filter(::isPackageAnExtension)
			.mapNotNull { pkgInfo -> extractExtensionInfo(pkgInfo, pkgManager) }
	}

	private fun extractExtensionInfo(pkgInfo: PackageInfo, pkgManager: PackageManager): MihonExtensionInfo? {
		val appInfo = pkgInfo.applicationInfo ?: return null
		val metaData = appInfo.metaData ?: return null
		val versionName = pkgInfo.versionName ?: return null
		val libVersion = parseLibVersion(versionName) ?: return null
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
		)
	}

	private fun loadExtension(context: Context, pkgInfo: PackageInfo): MihonLoadResult {
		val appInfo = pkgInfo.applicationInfo
			?: return buildLoggedError(pkgInfo.packageName, "No ApplicationInfo")
		val metaData = appInfo.metaData
			?: return buildLoggedError(pkgInfo.packageName, "No manifest metadata")
		val versionName = pkgInfo.versionName
			?: return buildLoggedError(pkgInfo.packageName, "No version name")
		val libVersion = parseLibVersion(versionName)
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
			appName = appInfo.loadLabel(context.packageManager).toString(),
			versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
			versionName = versionName,
			libVersion = libVersion,
			lang = extractLanguage(pkgInfo.packageName),
			isNsfw = readNsfwFlag(metaData),
			sources = sources,
		)
	}

	private fun loadSources(pkgName: String, sourceClassNames: String, classLoader: ClassLoader): List<Source> {
		return normalizeSourceClassNames(pkgName, sourceClassNames)
			.flatMap { className ->
				val instance = classLoader.loadClass(className).getDeclaredConstructor().newInstance()
				when (instance) {
					is Source -> listOf(instance)
					is SourceFactory -> instance.createSources().toList()
					else -> emptyList()
				}
			}
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
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			packageManager.getInstalledPackages(
				PackageManager.PackageInfoFlags.of(
					(PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS).toLong(),
				),
			)
		} else {
			packageManager.getInstalledPackages(PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS)
		}
	}
}
