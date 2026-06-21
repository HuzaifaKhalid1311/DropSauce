package org.koitharu.kotatsu.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat

@SuppressLint("CustomX509TrustManager")
class ScopedTrustManager(
	private val delegate: javax.net.ssl.X509ExtendedTrustManager?,
	private val bypassHost: String?
) : javax.net.ssl.X509ExtendedTrustManager() {

	override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
		delegate?.checkClientTrusted(chain, authType)
	}

	override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
		delegate?.checkServerTrusted(chain, authType)
	}

	override fun getAcceptedIssuers(): Array<X509Certificate> {
		return delegate?.acceptedIssuers ?: emptyArray()
	}

	override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {
		delegate?.checkClientTrusted(chain, authType, socket)
	}

	override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {
		delegate?.checkClientTrusted(chain, authType, engine)
	}

	override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {
		val host = getHost(socket)
		if (BuildConfig.DEBUG && !bypassHost.isNullOrEmpty() && host != null &&
			(host.equals(bypassHost, ignoreCase = true) || host.endsWith(".$bypassHost", ignoreCase = true))) {
			return
		}
		delegate?.checkServerTrusted(chain, authType, socket)
	}

	override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {
		val host = engine?.peerHost
		if (BuildConfig.DEBUG && !bypassHost.isNullOrEmpty() && host != null &&
			(host.equals(bypassHost, ignoreCase = true) || host.endsWith(".$bypassHost", ignoreCase = true))) {
			return
		}
		delegate?.checkServerTrusted(chain, authType, engine)
	}

	private fun getHost(socket: java.net.Socket?): String? {
		if (socket is javax.net.ssl.SSLSocket) {
			runCatching { return socket.handshakeSession?.peerHost }.getOrNull()
				?: runCatching { return socket.session.peerHost }.getOrNull()
		}
		return socket?.inetAddress?.hostName
	}
}

private fun showSslWarningNotification(context: Context, host: String) {
	val channelId = "ssl_bypass_channel"
	val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
		val channel = NotificationChannel(
			channelId,
			"SSL Bypass Warning",
			NotificationManager.IMPORTANCE_HIGH
		).apply {
			description = "Warning that SSL certificate verification is bypassed for a host"
		}
		notificationManager.createNotificationChannel(channel)
	}
	val notification = NotificationCompat.Builder(context, channelId)
		.setSmallIcon(android.R.drawable.stat_sys_warning)
		.setContentTitle("SSL Verification Bypassed")
		.setContentText("Certificate check disabled for $host")
		.setOngoing(true)
		.setPriority(NotificationCompat.PRIORITY_HIGH)
		.build()
	notificationManager.notify(1001, notification)
}

private fun cancelSslWarningNotification(context: Context) {
	val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
	notificationManager.cancel(1001)
}

@SuppressLint("CustomX509TrustManager")
fun OkHttpClient.Builder.disableCertificateVerification(context: Context, bypassHost: String?) = also { builder ->
	if (!BuildConfig.DEBUG) return@also
	runCatching {
		val trustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
		trustManagerFactory.init(null as java.security.KeyStore?)
		val defaultTrustManager = trustManagerFactory.trustManagers.first { it is X509TrustManager } as X509TrustManager
		val extendedTrustManager = defaultTrustManager as? javax.net.ssl.X509ExtendedTrustManager

		val trustManager = ScopedTrustManager(extendedTrustManager, bypassHost)
		val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
		sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())
		val sslSocketFactory: SSLSocketFactory = sslContext.socketFactory
		builder.sslSocketFactory(sslSocketFactory, trustManager)

		val defaultVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
		builder.hostnameVerifier { hostname, session ->
			if (!bypassHost.isNullOrEmpty() && (hostname.equals(bypassHost, ignoreCase = true) || hostname.endsWith(".$bypassHost", ignoreCase = true))) {
				true
			} else {
				defaultVerifier.verify(hostname, session)
			}
		}

		if (!bypassHost.isNullOrEmpty()) {
			showSslWarningNotification(context, bypassHost)
		} else {
			cancelSslWarningNotification(context)
		}
	}.onFailure {
		it.printStackTraceDebug()
	}
}

fun OkHttpClient.Builder.installExtraCertificates(context: Context) = also { builder ->
	val certificatesBuilder = HandshakeCertificates.Builder()
		.addPlatformTrustedCertificates()
	val assets = context.assets.list("").orEmpty()
	for (path in assets) {
		if (path.endsWith(".pem")) {
			val cert = loadCert(context, path) ?: continue
			certificatesBuilder.addTrustedCertificate(cert)
		}
	}
	val certificates = certificatesBuilder.build()
	builder.sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
}

private fun loadCert(context: Context, path: String): X509Certificate? = runCatching {
	val cf = CertificateFactory.getInstance("X.509")
	context.assets.open(path, AssetManager.ACCESS_STREAMING).use {
		cf.generateCertificate(it)
	} as X509Certificate
}.onFailure { e ->
	e.printStackTraceDebug()
}.onSuccess {
	if (BuildConfig.DEBUG) {
		Log.i("ExtraCerts", "Loaded cert $path")
	}
}.getOrNull()
