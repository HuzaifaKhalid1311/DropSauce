package org.koitharu.kotatsu.mihon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.mihon.compat.KotoInjektBridge
import org.koitharu.kotatsu.mihon.model.MihonLoadResult
import org.koitharu.kotatsu.mihon.util.ChildFirstPathClassLoader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonExtensionLoader @Inject constructor(
	@ApplicationContext private val applicationContext: Context,
	private val injektBridge: Lazy<KotoInjektBridge>,
) {

	companion object {
		private const val EXTENSION_FEATURE = "tachiyomi.extension"
		private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
		private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
		private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
		const val LIB_VERSION_MIN = 1.2
		const val LIB_VERSION_MAX = 1.9
	}

	suspend fun loadExtensions(context: Context): List<MihonLoadResult> = withContext(Dispatchers.IO) {
		injektBridge.get().initialize()
		getInstalledPackages(context.packageManager)
			.filter(::isPackageAnExtension)
			.map { pkgInfo -> async { loadExtension(context, pkgInfo) } }
			.awaitAll()
	}

	private fun loadExtension(context: Context, pkgInfo: PackageInfo): MihonLoadResult {
		val appInfo = pkgInfo.applicationInfo
			?: return MihonLoadResult.Error(pkgInfo.packageName, "No ApplicationInfo")
		val metaData = appInfo.metaData
			?: return MihonLoadResult.Error(pkgInfo.packageName, "No manifest metadata")
		val versionName = pkgInfo.versionName
			?: return MihonLoadResult.Error(pkgInfo.packageName, "No version name")
		val libVersion = parseLibVersion(versionName)
			?: return MihonLoadResult.Error(pkgInfo.packageName, "Invalid lib version: $versionName")
		if (libVersion < LIB_VERSION_MIN || libVersion > LIB_VERSION_MAX) {
			return MihonLoadResult.Error(
				pkgName = pkgInfo.packageName,
				message = "Incompatible lib version: $libVersion",
			)
		}
		val sourceClassNames = metaData.getString(METADATA_SOURCE_CLASS)
			?: metaData.getString(METADATA_SOURCE_FACTORY)
			?: return MihonLoadResult.Error(pkgInfo.packageName, "No source class metadata")
		val classLoader = runCatching {
			ChildFirstPathClassLoader(
				appInfo.sourceDir,
				appInfo.nativeLibraryDir,
				context.classLoader,
			)
		}.getOrElse {
			return MihonLoadResult.Error(pkgInfo.packageName, "Failed to create class loader", it)
		}
		val sources = runCatching {
			loadSources(sourceClassNames, classLoader)
		}.getOrElse {
			return MihonLoadResult.Error(pkgInfo.packageName, "Failed to load sources", it)
		}
		if (sources.isEmpty()) {
			return MihonLoadResult.Error(pkgInfo.packageName, "No sources loaded")
		}
		return MihonLoadResult.Success(
			pkgName = pkgInfo.packageName,
			appName = appInfo.loadLabel(context.packageManager).toString(),
			versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
			versionName = versionName,
			libVersion = libVersion,
			lang = extractLanguage(pkgInfo.packageName),
			isNsfw = metaData.getBoolean(METADATA_NSFW, false),
			sources = sources,
		)
	}

	private fun loadSources(sourceClassNames: String, classLoader: ClassLoader): List<Source> {
		return sourceClassNames
			.split(';', ':', ',')
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.flatMap { className ->
				val instance = classLoader.loadClass(className).getDeclaredConstructor().newInstance()
				when (instance) {
					is Source -> listOf(instance)
					is SourceFactory -> instance.createSources().toList()
					else -> emptyList()
				}
			}
	}

	private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
		val appInfo = pkgInfo.applicationInfo ?: return false
		val metaData = appInfo.metaData
		val pkgName = pkgInfo.packageName
		val hasFeature = pkgInfo.reqFeatures?.any { it.name == EXTENSION_FEATURE } == true
		val hasSource = metaData?.containsKey(METADATA_SOURCE_CLASS) == true ||
			metaData?.containsKey(METADATA_SOURCE_FACTORY) == true
		val hasPackageName = pkgName.contains(".extension") ||
			pkgName.startsWith("eu.kanade.tachiyomi.") ||
			pkgName.startsWith("org.keiyoushi.") ||
			pkgName.startsWith("app.mihon.")
		return hasFeature || (hasPackageName && hasSource)
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
