package org.koitharu.kotatsu.extensions.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

fun getExternalExtensionLanguageDisplayName(langCode: String): String {
	return when (langCode.lowercase(Locale.ROOT)) {
		"all" -> "Multi"
		else -> runCatching { Locale.forLanguageTag(langCode).getDisplayLanguage(Locale.getDefault()) }
			.getOrNull()
			?.takeIf { it.isNotBlank() }
			?: langCode.uppercase(Locale.ROOT)
	}
}

fun registerExternalExtensionPackageObserver(
	context: Context,
	scope: CoroutineScope,
	onPackageChanged: suspend () -> Unit,
): BroadcastReceiver {
	val receiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			scope.launch { onPackageChanged() }
		}
	}
	ContextCompat.registerReceiver(
		context,
		receiver,
		IntentFilter().apply {
			addAction(Intent.ACTION_PACKAGE_ADDED)
			addAction(Intent.ACTION_PACKAGE_REPLACED)
			addAction(Intent.ACTION_PACKAGE_REMOVED)
			addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
			addDataScheme("package")
		},
		ContextCompat.RECEIVER_EXPORTED,
	)
	return receiver
}

data class ProcessedExternalExtensions<SuccessT, ErrorT, SourceT, WrappedSourceT>(
	val successful: List<SuccessT>,
	val failed: List<ErrorT>,
	val sourceById: Map<Long, SourceT>,
	val wrappedSourceById: Map<Long, WrappedSourceT>,
	val untrustedPackages: List<String>,
)

fun <ResultT, SuccessT, ErrorT, SourceT, CatalogueSourceT : SourceT, WrappedSourceT> processExternalExtensionResults(
	results: List<ResultT>,
	successOf: (ResultT) -> SuccessT?,
	errorOf: (ResultT) -> ErrorT?,
	untrustedPackageNameOf: (ResultT) -> String?,
	successSources: (SuccessT) -> List<SourceT>,
	successPackageName: (SuccessT) -> String,
	successIsNsfw: (SuccessT) -> Boolean,
	sourceId: (SourceT) -> Long,
	asCatalogueSource: (SourceT) -> CatalogueSourceT?,
	catalogueSourceName: (CatalogueSourceT) -> String,
	buildWrappedSource: (CatalogueSourceT, String, Boolean, Boolean) -> WrappedSourceT,
	onError: (ErrorT) -> Unit = {},
	onUntrusted: (String) -> Unit = {},
): ProcessedExternalExtensions<SuccessT, ErrorT, SourceT, WrappedSourceT> {
	val successful = mutableListOf<SuccessT>()
	val failed = mutableListOf<ErrorT>()
	val sourceById = linkedMapOf<Long, SourceT>()
	val catalogueSources = mutableListOf<Triple<CatalogueSourceT, String, Boolean>>()
	val untrustedPackages = mutableListOf<String>()

	results.forEach { result ->
		when {
			successOf(result) != null -> {
				val success = requireNotNull(successOf(result))
				successful += success
				successSources(success).forEach { source ->
					sourceById[sourceId(source)] = source
					asCatalogueSource(source)?.let {
						catalogueSources += Triple(it, successPackageName(success), successIsNsfw(success))
					}
				}
			}
			errorOf(result) != null -> {
				val error = requireNotNull(errorOf(result))
				failed += error
				onError(error)
			}
			untrustedPackageNameOf(result) != null -> {
				val pkg = requireNotNull(untrustedPackageNameOf(result))
				untrustedPackages += pkg
				onUntrusted(pkg)
			}
		}
	}

	val nameCount = catalogueSources.groupingBy { catalogueSourceName(it.first) }.eachCount()
	val wrappedSourceById = linkedMapOf<Long, WrappedSourceT>()
	catalogueSources.forEach { (catalogueSource, pkgName, isNsfw) ->
		wrappedSourceById[sourceId(catalogueSource)] = buildWrappedSource(
			catalogueSource,
			pkgName,
			isNsfw,
			(nameCount[catalogueSourceName(catalogueSource)] ?: 0) > 1,
		)
	}

	return ProcessedExternalExtensions(successful, failed, sourceById, wrappedSourceById, untrustedPackages)
}

class ExternalExtensionManagerRuntime<ResultT, SuccessT, ErrorT, SourceT, WrappedSourceT>(
	private val context: Context,
	private val scope: CoroutineScope,
) {
	private val _installedExtensions = MutableStateFlow<List<SuccessT>>(emptyList())
	val installedExtensions: StateFlow<List<SuccessT>> = _installedExtensions.asStateFlow()

	private val _failedExtensions = MutableStateFlow<List<ErrorT>>(emptyList())
	val failedExtensions: StateFlow<List<ErrorT>> = _failedExtensions.asStateFlow()

	private val _isLoading = MutableStateFlow(false)
	val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

	private val sourceCache = mutableMapOf<Long, SourceT>()
	private val wrappedSourceCache = mutableMapOf<Long, WrappedSourceT>()
	@Volatile
	private var isPackageObserverRegistered = false

	fun initialize(loadAction: suspend () -> Unit) {
		registerPackageObserver(loadAction)
		scope.launch { loadAction() }
	}

	suspend fun loadExtensions(
		loadResults: suspend (Context) -> List<ResultT>,
		processResults: (List<ResultT>) -> ProcessedExternalExtensions<SuccessT, ErrorT, SourceT, WrappedSourceT>,
	) {
		if (_isLoading.value) return
		_isLoading.value = true
		try {
			sourceCache.clear()
			wrappedSourceCache.clear()
			val processed = processResults(loadResults(context))
			sourceCache.putAll(processed.sourceById)
			wrappedSourceCache.putAll(processed.wrappedSourceById)
			_installedExtensions.value = processed.successful
			_failedExtensions.value = processed.failed
		} finally {
			_isLoading.value = false
		}
	}

	fun getSourceById(sourceId: Long): SourceT? = sourceCache[sourceId]
	fun getWrappedSourceById(sourceId: Long): WrappedSourceT? = wrappedSourceCache[sourceId]
	fun getWrappedSources(): List<WrappedSourceT> = wrappedSourceCache.values.toList()
	fun getSourceCount(): Int = sourceCache.size
	fun hasExtensions(): Boolean = installedExtensions.value.isNotEmpty()

	private fun registerPackageObserver(loadAction: suspend () -> Unit) {
		if (isPackageObserverRegistered) return
		registerExternalExtensionPackageObserver(context, scope, loadAction)
		isPackageObserverRegistered = true
	}
}

class ExternalExtensionManagerFacade<ResultT, SuccessT, ErrorT, SourceT, CatalogueT : SourceT, WrappedSourceT>(
	context: Context,
	scope: CoroutineScope,
	private val loadResults: suspend (Context) -> List<ResultT>,
	private val successOf: (ResultT) -> SuccessT?,
	private val errorOf: (ResultT) -> ErrorT?,
	private val untrustedPackageNameOf: (ResultT) -> String?,
	private val successSources: (SuccessT) -> List<SourceT>,
	private val successPackageName: (SuccessT) -> String,
	private val successIsNsfw: (SuccessT) -> Boolean,
	private val successCatalogueSources: (SuccessT) -> List<CatalogueT>,
	private val sourceId: (SourceT) -> Long,
	private val asCatalogueSource: (SourceT) -> CatalogueT?,
	private val catalogueSourceName: (CatalogueT) -> String,
	private val catalogueSourceLang: (CatalogueT) -> String,
	private val buildWrappedSource: (CatalogueT, String, Boolean, Boolean) -> WrappedSourceT,
	private val sourceNamePrefix: String,
	private val errorPackageName: (ErrorT) -> String,
	private val errorMessage: (ErrorT) -> String,
) {
	private val runtime = ExternalExtensionManagerRuntime<ResultT, SuccessT, ErrorT, SourceT, WrappedSourceT>(context, scope)

	val installedExtensions: StateFlow<List<SuccessT>> = runtime.installedExtensions
	val failedExtensions: StateFlow<List<ErrorT>> = runtime.failedExtensions
	val isLoading: StateFlow<Boolean> = runtime.isLoading

	fun initialize() {
		runtime.initialize(::loadExtensions)
	}

	suspend fun loadExtensions() {
		runtime.loadExtensions(loadResults) { results ->
			processExternalExtensionResults(
				results = results,
				successOf = successOf,
				errorOf = errorOf,
				untrustedPackageNameOf = untrustedPackageNameOf,
				successSources = successSources,
				successPackageName = successPackageName,
				successIsNsfw = successIsNsfw,
				sourceId = sourceId,
				asCatalogueSource = asCatalogueSource,
				catalogueSourceName = catalogueSourceName,
				buildWrappedSource = buildWrappedSource,
				onError = { error -> android.util.Log.e("ExternalExtensionManager", "${errorPackageName(error)}: ${errorMessage(error)}") },
			)
		}
	}

	fun getCatalogueSources(): List<CatalogueT> = installedExtensions.value.flatMap(successCatalogueSources)
	fun getWrappedSources(): List<WrappedSourceT> = runtime.getWrappedSources()
	fun getSourceById(sourceId: Long): SourceT? = runtime.getSourceById(sourceId)
	fun getCatalogueSourceById(sourceId: Long): CatalogueT? = runtime.getSourceById(sourceId)?.let(asCatalogueSource)
	fun getWrappedSourceById(sourceId: Long): WrappedSourceT? = runtime.getWrappedSourceById(sourceId)
	fun getWrappedSourceByName(name: String): WrappedSourceT? {
		if (!name.startsWith(sourceNamePrefix)) return null
		val sourceId = name.substringAfter(sourceNamePrefix).toLongOrNull() ?: return null
		return getWrappedSourceById(sourceId)
	}
	fun getSourcesByLanguage(): Map<String, List<CatalogueT>> = getCatalogueSources().groupBy(catalogueSourceLang)
	fun getSourceCount(): Int = runtime.getSourceCount()
	fun hasExtensions(): Boolean = runtime.hasExtensions()
}
