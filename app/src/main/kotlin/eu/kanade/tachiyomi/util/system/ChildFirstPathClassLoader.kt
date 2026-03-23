package eu.kanade.tachiyomi.util.system

import dalvik.system.PathClassLoader
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.Enumeration

class ChildFirstPathClassLoader(
	dexPath: String,
	librarySearchPath: String?,
	parent: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, parent) {

	private val systemClassLoader: ClassLoader? = getSystemClassLoader()

	override fun loadClass(name: String, resolve: Boolean): Class<*> {
		var loadedClass = findLoadedClass(name)

		val systemLoader = systemClassLoader
		if (loadedClass == null && systemLoader != null) {
			loadedClass = runCatching {
				systemLoader.loadClass(name)
			}.getOrNull()
		}

		if (loadedClass == null) {
			loadedClass = runCatching {
				findClass(name)
			}.getOrElse {
				super.loadClass(name, resolve)
			}
		}

		if (resolve) {
			resolveClass(loadedClass)
		}

		return loadedClass
	}

	override fun getResource(name: String): URL? {
		return systemClassLoader?.getResource(name)
			?: findResource(name)
			?: super.getResource(name)
	}

	override fun getResources(name: String): Enumeration<URL> {
		val systemUrls = systemClassLoader?.getResources(name)
		val localUrls = findResources(name)
		val parentUrls = parent?.getResources(name)
		val urls = buildList {
			while (systemUrls?.hasMoreElements() == true) {
				add(systemUrls.nextElement())
			}
			while (localUrls?.hasMoreElements() == true) {
				add(localUrls.nextElement())
			}
			while (parentUrls?.hasMoreElements() == true) {
				add(parentUrls.nextElement())
			}
		}

		return object : Enumeration<URL> {
			private val iterator = urls.iterator()

			override fun hasMoreElements(): Boolean = iterator.hasNext()

			override fun nextElement(): URL = iterator.next()
		}
	}

	override fun getResourceAsStream(name: String): InputStream? {
		return try {
			getResource(name)?.openStream()
		} catch (_: IOException) {
			null
		}
	}
}
