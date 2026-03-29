package org.koitharu.kotatsu.settings.sources.catalog

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionInstaller @Inject constructor(
	@ApplicationContext private val context: Context,
	@BaseHttpClient private val client: OkHttpClient,
) {
	suspend fun installExtension(url: String, pkgName: String) {
		withContext(Dispatchers.IO) {
			try {
				val request = Request.Builder().url(url).build()
				val response = client.newCall(request).execute()
				val body = response.body
				if (!response.isSuccessful || body == null) {
					throw Exception("Failed to download APK: ${response.code}")
				}

				val apkDir = File(context.cacheDir, "extensions")
				if (!apkDir.exists()) apkDir.mkdirs()
				val apkFile = File(apkDir, "$pkgName.apk")
				
				body.byteStream().use { input ->
					apkFile.outputStream().use { output ->
						input.copyTo(output)
					}
				}

				installApk(apkFile, pkgName)
			} catch (e: Exception) {
				Log.e(TAG, "Installation failed", e)
				throw e
			}
		}
	}

	private fun installApk(apkFile: File, pkgName: String) {
		val packageInstaller = context.packageManager.packageInstaller
		val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
		params.setAppPackageName(pkgName)
		
		val sessionId = packageInstaller.createSession(params)
		val session = packageInstaller.openSession(sessionId)

		session.use { s ->
			apkFile.inputStream().use { input ->
				s.openWrite("package", 0, apkFile.length()).use { output ->
					input.copyTo(output)
				}
			}
			
			val intent = Intent("org.koitharu.kotatsu.EXTENSION_INSTALL_RESULT")
			intent.setPackage(context.packageName)
			val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
			} else {
				PendingIntent.FLAG_UPDATE_CURRENT
			}
			val pendingIntent = PendingIntent.getBroadcast(
				context,
				sessionId,
				intent,
				flags
			)
			s.commit(pendingIntent.intentSender)
		}
	}

	private companion object {
		const val TAG = "ExtensionInstaller"
	}
}
