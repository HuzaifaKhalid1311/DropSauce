package org.koitharu.kotatsu.mihon

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.extensions.runtime.ExternalExtensionManagerFacade
import org.koitharu.kotatsu.mihon.model.MihonLoadResult
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonExtensionManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val loader: MihonExtensionLoader,
) {

	companion object {
		@Volatile
		private var activeInstance: MihonExtensionManager? = null

		fun getByName(name: String): MihonMangaSource? = activeInstance?.getMihonMangaSourceByName(name)
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	private val facade = ExternalExtensionManagerFacade<
		MihonLoadResult,
		MihonLoadResult.Success,
		MihonLoadResult.Error,
		Source,
		CatalogueSource,
		MihonMangaSource,
	>(
		context = context,
		scope = scope,
		loadResults = loader::loadExtensions,
		successOf = { it as? MihonLoadResult.Success },
		errorOf = { it as? MihonLoadResult.Error },
		untrustedPackageNameOf = { (it as? MihonLoadResult.Untrusted)?.pkgName },
		successSources = { it.sources },
		successPackageName = { it.pkgName },
		successIsNsfw = { it.isNsfw },
		successCatalogueSources = { it.catalogueSources },
		sourceId = { it.id },
		asCatalogueSource = { it as? CatalogueSource },
		catalogueSourceName = { it.name },
		catalogueSourceLang = { it.lang },
		buildWrappedSource = { catalogueSource, pkgName, isNsfw, hasLanguageSuffix ->
			MihonMangaSource(catalogueSource, pkgName, isNsfw, hasLanguageSuffix)
		},
		sourceNamePrefix = "MIHON_",
		errorPackageName = { it.pkgName },
		errorMessage = { it.message },
		errorThrowable = { it.exception },
	)

	val installedExtensions: StateFlow<List<MihonLoadResult.Success>> = facade.installedExtensions
	val failedExtensions: StateFlow<List<MihonLoadResult.Error>> = facade.failedExtensions
	val isLoading: StateFlow<Boolean> = facade.isLoading

	init {
		activeInstance = this
	}

	fun initialize() {
		facade.initialize()
	}

	suspend fun loadExtensions() {
		facade.loadExtensions()
	}

	fun getCatalogueSources(): List<CatalogueSource> = facade.getCatalogueSources()

	fun getMihonMangaSources(): List<MihonMangaSource> = facade.getWrappedSources()

	fun getSourceById(sourceId: Long): Source? = facade.getSourceById(sourceId)

	fun getCatalogueSourceById(sourceId: Long): CatalogueSource? = facade.getCatalogueSourceById(sourceId)

	fun getMihonMangaSourceById(sourceId: Long): MihonMangaSource? = facade.getWrappedSourceById(sourceId)

	fun getMihonMangaSourceByName(name: String): MihonMangaSource? = facade.getWrappedSourceByName(name)

	fun getSourcesByLanguage(): Map<String, List<CatalogueSource>> = facade.getSourcesByLanguage()

	fun getSourceCount(): Int = facade.getSourceCount()

	fun hasExtensions(): Boolean = facade.hasExtensions()
}
