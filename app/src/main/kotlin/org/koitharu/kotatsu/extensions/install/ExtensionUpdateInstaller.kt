package org.koitharu.kotatsu.extensions.install

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.settings.sources.catalog.EXTENSION_APK_PREFIX
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionInstallMode
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreManager
import org.koitharu.kotatsu.settings.sources.catalog.ExternalExtensionRepoRepository
import org.koitharu.kotatsu.settings.sources.catalog.StoreHealth
import org.koitharu.kotatsu.settings.sources.catalog.isNewerThan
import java.io.File
import java.io.IOException

/**
 * Downloads and installs a newer build of one already-installed extension, in place, without
 * routing the user through the extension manager.
 *
 * This is deliberately narrower than the catalog screen's installer: an in-place update always
 * comes from the store that already owns the package, so there is no store-replacement prompt and
 * no uninstall round-trip to handle — only the three install back-ends.
 *
 * Must be created before the host fragment/activity is started, since it registers activity results.
 */
class ExtensionUpdateInstaller @AssistedInject constructor(
	@Assisted caller: ActivityResultCaller,
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
	private val storeManager: ExtensionStoreManager,
	private val repoRepository: ExternalExtensionRepoRepository,
	private val shizukuInstaller: ShizukuExtensionInstaller,
	private val extensionLoader: MihonExtensionLoader,
	private val extensionManager: MihonExtensionManager,
	@BaseHttpClient private val httpClient: OkHttpClient,
) {

	private var pendingResult: CompletableDeferred<Boolean>? = null

	private val installLauncher = caller.registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) { result ->
		resume(result.resultCode == Activity.RESULT_OK)
	}

	private val permissionLauncher = caller.registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		val apk = pendingApk
		if (apk != null && canInstallPackages()) {
			launchSystemInstaller(apk)
		} else {
			resume(false)
		}
	}

	private var pendingApk: File? = null

	/**
	 * @return one of [Result]. On [Result.SUCCESS] the extension list has already been reloaded, so
	 * whatever observes the installed extensions updates itself.
	 */
	suspend fun installUpdate(packageName: String): Result {
		val mode = if (settings.isPrivateInstallEnabled) {
			ExtensionInstallMode.SANDBOX
		} else {
			ExtensionInstallMode.SYSTEM
		}
		val owner = storeManager.owner(mode, packageName) ?: return Result.NO_UPDATE
		val state = storeManager.state(owner.id)?.takeIf { it.health == StoreHealth.AVAILABLE }
			?: return Result.NO_UPDATE
		val local = extensionLoader.getInstalledExtensions(
			context,
			privateMode = mode == ExtensionInstallMode.SANDBOX,
		).firstOrNull { it.pkgName == packageName } ?: return Result.NO_UPDATE
		val entry = state.catalog.firstOrNull {
			it.packageName == packageName && it.isNewerThan(local)
		} ?: return Result.NO_UPDATE

		val apk = File(context.cacheDir, "$EXTENSION_APK_PREFIX$packageName.apk")
		try {
			withContext(Dispatchers.IO) {
				download(repoRepository.resolveApkUrl(owner.indexUrl, entry.apkName), apk)
			}
			if (!isValidExtensionApk(apk, packageName)) {
				return Result.INVALID
			}
			val installed = when {
				mode == ExtensionInstallMode.SANDBOX -> MihonExtensionLoader.installPrivateExtensionFile(
					context = context,
					file = apk,
					expectedPackageName = packageName,
				)

				settings.isShizukuInstallerEnabled ->
					shizukuInstaller.install(apk, packageName) is ShizukuExtensionInstaller.InstallResult.Success

				else -> installWithSystemInstaller(apk)
			}
			if (!installed) {
				return Result.FAILED
			}
			storeManager.setOwner(mode, packageName, owner.id)
			extensionManager.loadExtensions()
			return Result.SUCCESS
		} catch (_: IOException) {
			return Result.DOWNLOAD_FAILED
		} finally {
			pendingApk = null
			apk.delete()
		}
	}

	private fun download(url: String, destination: File) {
		val tmp = File(destination.parentFile, "${destination.name}.tmp")
		try {
			val request = Request.Builder().url(url).get().build()
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					throw IOException("HTTP ${response.code}")
				}
				response.body.byteStream().use { input ->
					tmp.outputStream().buffered().use { output ->
						input.copyTo(output)
					}
				}
			}
			if (!tmp.renameTo(destination)) {
				tmp.copyTo(destination, overwrite = true)
			}
		} finally {
			tmp.delete()
		}
	}

	/** Refuses anything that is not a real, signed Mihon extension for the expected package. */
	private fun isValidExtensionApk(apk: File, expectedPackage: String): Boolean {
		if (!apk.isFile) return false
		val archive = getArchivePackageInfo(apk) ?: return false
		return archive.packageName == expectedPackage &&
			MihonExtensionLoader.isPackageAnExtensionStatic(archive) &&
			MihonExtensionLoader.getSignatures(archive).isNotEmpty()
	}

	@Suppress("DEPRECATION")
	private fun getArchivePackageInfo(apk: File): PackageInfo? {
		val flags = PackageManager.GET_META_DATA or
			PackageManager.GET_CONFIGURATIONS or
			PackageManager.GET_SIGNATURES or
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
		return context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
	}

	private suspend fun installWithSystemInstaller(apk: File): Boolean {
		val deferred = CompletableDeferred<Boolean>()
		pendingResult?.cancel()
		pendingResult = deferred
		pendingApk = apk
		withContext(Dispatchers.Main) {
			if (canInstallPackages()) {
				launchSystemInstaller(apk)
			} else {
				requestInstallPackagesPermission()
			}
		}
		return try {
			deferred.await()
		} finally {
			pendingResult = null
		}
	}

	private fun resume(value: Boolean) {
		pendingResult?.complete(value)
	}

	@Suppress("DEPRECATION")
	@SuppressLint("RequestInstallPackagesPolicy")
	private fun launchSystemInstaller(apk: File) {
		val apkUri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", apk)
		val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
			.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			.setDataAndType(apkUri, MIME_APK)
			.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
			.putExtra(Intent.EXTRA_RETURN_RESULT, true)
		findPackageInstallerPackage(intent)?.let(intent::setPackage)
		grantInstallerUriPermissions(intent, apkUri)
		try {
			installLauncher.launch(intent)
		} catch (_: ActivityNotFoundException) {
			resume(false)
		} catch (_: SecurityException) {
			resume(false)
		}
	}

	@Suppress("DEPRECATION")
	private fun grantInstallerUriPermissions(intent: Intent, apkUri: Uri) {
		val targets = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
		for (resolveInfo in targets) {
			val targetPackage = resolveInfo.activityInfo?.packageName ?: continue
			context.grantUriPermission(targetPackage, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
	}

	@Suppress("DEPRECATION")
	private fun findPackageInstallerPackage(intent: Intent): String? =
		context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
			.firstOrNull { resolveInfo ->
				val targetPackage = resolveInfo.activityInfo?.packageName ?: return@firstOrNull false
				targetPackage.contains("packageinstaller", ignoreCase = true) ||
					targetPackage.contains("package.installer", ignoreCase = true)
			}?.activityInfo?.packageName

	private fun canInstallPackages(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
		context.packageManager.canRequestPackageInstalls()

	private fun requestInstallPackagesPermission() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			resume(false)
			return
		}
		try {
			permissionLauncher.launch(
				Intent(
					Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
					"package:${context.packageName}".toUri(),
				),
			)
		} catch (_: ActivityNotFoundException) {
			resume(false)
		}
	}

	enum class Result {
		SUCCESS,

		/** The store no longer offers a newer build — nothing to do. */
		NO_UPDATE,
		DOWNLOAD_FAILED,

		/** The downloaded file is not a signed extension for this package. */
		INVALID,
		FAILED,
	}

	@AssistedFactory
	interface Factory {

		fun create(caller: ActivityResultCaller): ExtensionUpdateInstaller
	}

	private companion object {

		const val MIME_APK = "application/vnd.android.package-archive"
	}
}
