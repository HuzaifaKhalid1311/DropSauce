package org.haziffe.dropsauce.core

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.room.InvalidationTracker
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import okhttp3.internal.platform.PlatformRegistry
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.dialog
import org.acra.config.httpSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.sender.HttpSender
import org.conscrypt.Conscrypt
import org.haziffe.dropsauce.BuildConfig
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.db.MangaDatabase
import org.haziffe.dropsauce.core.os.AppValidator
import org.haziffe.dropsauce.core.os.RomCompat
import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.core.util.ext.processLifecycleScope
import org.haziffe.dropsauce.local.data.LocalStorageChanges
import org.haziffe.dropsauce.local.data.index.LocalMangaIndex
import org.haziffe.dropsauce.local.domain.model.LocalManga
import org.haziffe.dropsauce.parsers.util.suspendlazy.getOrNull
import org.haziffe.dropsauce.settings.work.WorkScheduleManager
import java.security.Security
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
open class BaseApp : Application(), Configuration.Provider {

	@Inject
	lateinit var databaseObserversProvider: Provider<Set<@JvmSuppressWildcards InvalidationTracker.Observer>>

	@Inject
	lateinit var activityLifecycleCallbacks: Set<@JvmSuppressWildcards ActivityLifecycleCallbacks>

	@Inject
	lateinit var database: Provider<MangaDatabase>

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var workerFactory: HiltWorkerFactory

	@Inject
	lateinit var appValidator: AppValidator

	@Inject
	lateinit var workScheduleManager: WorkScheduleManager

	@Inject
	lateinit var localMangaIndexProvider: Provider<LocalMangaIndex>

	@Inject
	@LocalStorageChanges
	lateinit var localStorageChanges: MutableSharedFlow<LocalManga?>

	override val workManagerConfiguration: Configuration
		get() = Configuration.Builder()
			.setWorkerFactory(workerFactory)
			.build()

	override fun onCreate() {
		super.onCreate()
		PlatformRegistry.applicationContext = this // TODO replace with OkHttp.initialize
		if (ACRA.isACRASenderServiceProcess()) {
			return
		}
		AppCompatDelegate.setDefaultNightMode(settings.theme)
		// TLS 1.3 support for Android < 10
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			Security.insertProviderAt(Conscrypt.newProvider(), 1)
		}
		setupActivityLifecycleCallbacks()
		processLifecycleScope.launch {
			ACRA.errorReporter.putCustomData("isOriginalApp", appValidator.isOriginalApp.getOrNull().toString())
			ACRA.errorReporter.putCustomData("isMiui", RomCompat.isMiui.getOrNull().toString())
		}
		processLifecycleScope.launch(Dispatchers.Default) {
			setupDatabaseObservers()
			localStorageChanges.collect(localMangaIndexProvider.get())
		}
		workScheduleManager.init()
	}

	override fun attachBaseContext(base: Context) {
		super.attachBaseContext(base)
		if (ACRA.isACRASenderServiceProcess()) {
			return
		}
		initAcra {
			buildConfigClass = BuildConfig::class.java
			reportFormat = StringFormat.JSON
			
			dialog {
				text = getString(R.string.crash_text)
				title = getString(R.string.error_occurred)
				positiveButtonText = getString(R.string.close)
				resIcon = R.drawable.ic_alert_outline
				resTheme = android.R.style.Theme_Material_Light_Dialog_Alert
			}
		}
	}

	@WorkerThread
	private fun setupDatabaseObservers() {
		val tracker = database.get().invalidationTracker
		databaseObserversProvider.get().forEach {
			tracker.addObserver(it)
		}
	}

	private fun setupActivityLifecycleCallbacks() {
		activityLifecycleCallbacks.forEach {
			registerActivityLifecycleCallbacks(it)
		}
	}
}
