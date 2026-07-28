package org.koitharu.kotatsu.lnreader

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.koitharu.kotatsu.lnreader.js.JsHost
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.lnreader.model.LnPlugin
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks installed LNReader novel plugins on disk and loads them into [JsHost] on demand.
 *
 * Shaped like [org.koitharu.kotatsu.mihon.MihonExtensionManager], including the static
 * [activeInstance] handle, because the `MangaSource(name)` resolver is neither suspending nor
 * DI-aware and still has to map `"LN_<id>"` back to a live source.
 *
 * Deliberately not built on `ExternalExtensionManagerFacade`: that is organised around
 * PackageManager broadcasts, numeric Long ids and per-package language variants, none of which apply
 * here, and hashing string plugin ids into Longs to fit it would be a latent collision bug.
 */
@Singleton
class LnPluginManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val jsHost: JsHost,
) {

	private val loadMutex = Mutex()
	private val loaded = HashSet<String>()

	@Volatile
	private var isInitialized = false

	private val state = MutableStateFlow<List<LnMangaSource>>(emptyList())

	val sources: StateFlow<List<LnMangaSource>> = state

	private val root: File
		get() = File(context.filesDir, DIR_PLUGINS)

	init {
		activeInstance = this
	}

	/**
	 * Scans the plugin directory and publishes what it finds.
	 *
	 * A directory scan ONLY — it must never boot the JS realm, because `MangaRepository.Factory`
	 * reaches this from a synchronous path where a WebView would land in cold start (and break unit
	 * tests).
	 */
	fun initialize() {
		if (isInitialized) return
		isInitialized = true
		scan()
	}

	private fun scan() {
		state.value = root.listFiles { file: File -> file.isDirectory }
			?.mapNotNull { dir -> readPlugin(dir)?.let(::LnMangaSource) }
			?.sortedBy { it.displayName.lowercase() }
			.orEmpty()
	}

	fun getAll(): List<LnMangaSource> = state.value

	fun getById(pluginId: String): LnMangaSource? = state.value.find { it.pluginId == pluginId }

	/** Loads [pluginId]'s code into the JS realm if it is not there yet. Safe to call repeatedly. */
	suspend fun ensureLoaded(pluginId: String) {
		if (pluginId in loaded) return
		loadMutex.withLock {
			if (pluginId in loaded) return
			val code = codeFile(pluginId).takeIf { it.isFile }?.readText()
				?: error("Plugin $pluginId is not installed")
			jsHost.install(pluginId, code)
			loaded.add(pluginId)
		}
	}

	/**
	 * Persists [rawCode] as [pluginId] and returns the metadata the plugin reported. Evaluating it
	 * first means a plugin that throws on load is never written to disk.
	 *
	 * A plugin's exported object carries no `iconUrl` — LNReader's build script writes that into the
	 * index, not the code — and some plugins omit `lang`, so the store row is the authority for both.
	 * [storeId] is kept so the catalog can name the provider.
	 */
	suspend fun install(
		pluginId: String,
		rawCode: String,
		iconUrl: String = "",
		lang: String = "",
		storeId: String? = null,
	): LnPlugin {
		val reported = LnPlugin.fromJson(jsHost.install(pluginId, rawCode))
		val metadata = reported.copy(
			iconUrl = reported.iconUrl.ifEmpty { iconUrl },
			lang = reported.lang.ifEmpty { lang },
			storeId = storeId,
		)
		loadMutex.withLock {
			val dir = File(root, pluginId)
			dir.mkdirs()
			// Stage then rename, like MihonExtensionLoader.installPrivateExtensionFile, so an
			// interrupted write cannot leave a half-truncated plugin behind.
			val staged = File(dir, "$FILE_CODE.new")
			staged.writeText(rawCode)
			if (!staged.renameTo(codeFile(pluginId))) {
				staged.delete()
				error("Could not write plugin $pluginId")
			}
			File(dir, FILE_MANIFEST).writeText(metadata.toJson().toString())
			loaded.add(pluginId)
		}
		scan()
		return metadata
	}

	suspend fun uninstall(pluginId: String) {
		loadMutex.withLock {
			loaded.remove(pluginId)
			File(root, pluginId).deleteRecursively()
		}
		jsHost.uninstall(pluginId)
		scan()
	}

	fun isInstalled(pluginId: String): Boolean = codeFile(pluginId).isFile

	private fun codeFile(pluginId: String) = File(File(root, pluginId), FILE_CODE)

	private fun readPlugin(dir: File): LnPlugin? {
		val manifest = File(dir, FILE_MANIFEST)
		if (!manifest.isFile || !File(dir, FILE_CODE).isFile) return null
		return runCatching { LnPlugin.fromJson(JSONObject(manifest.readText())) }
			.onFailure { Log.w(TAG, "Bad manifest in ${dir.name}", it) }
			.getOrNull()
	}

	companion object {

		private const val TAG = "LnPluginManager"
		private const val DIR_PLUGINS = "lnplugins"
		private const val FILE_CODE = "index.js"
		private const val FILE_MANIFEST = "plugin.json"

		@Volatile
		private var activeInstance: LnPluginManager? = null

		/**
		 * Whether any plugin is installed, as a plain directory check. Deliberately static: injecting the
		 * manager into a main-thread caller (e.g. WorkScheduleManager during app startup) would build
		 * JsHost and its OkHttp client there, which the network module asserts against.
		 */
		fun hasInstalledPlugins(context: Context): Boolean =
			File(context.filesDir, DIR_PLUGINS).listFiles { file: File -> file.isDirectory }
				?.any { File(it, FILE_CODE).isFile } == true

		/**
		 * The plugin that serves [host], for tagging its network requests with a source. Static for the
		 * same reason as [hasInstalledPlugins]: an OkHttp interceptor must not build the DI graph.
		 *
		 * By host because the plugin realm shares one global `fetch`, so a request carries no plugin id.
		 */
		fun findBySiteHost(host: String): LnMangaSource? = activeInstance?.run {
			initialize()
			val target = host.removePrefix("www.")
			getAll().firstOrNull { source ->
				val site = source.plugin.site.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return@firstOrNull false
				// Either direction: plugins list either the bare domain or a www/subdomain of it, while
				// requests go to both that and sibling hosts (cdn, api).
				target == site || target.endsWith(".$site") || site.endsWith(".$target")
			}
		}

		/** Resolves a stored `"LN_<id>"` source name without DI. Null when nothing is installed yet. */
		fun getByName(name: String): LnMangaSource? = activeInstance?.run {
			// The resolver can run before anything called initialize() (e.g. a DB row mapped during
			// cold start), and the scan is a cheap directory listing guarded by isInitialized.
			initialize()
			getById(name.removePrefix("LN_"))
		}
	}
}
