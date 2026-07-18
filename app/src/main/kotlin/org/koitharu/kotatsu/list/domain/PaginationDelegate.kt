package org.koitharu.kotatsu.list.domain

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class PaginationDelegate(private val pageSize: Int = DEFAULT_PAGE_SIZE) {

	val limit = MutableStateFlow(pageSize)
	private val isReady = AtomicBoolean(false)

	fun requestMoreItems() {
		if (isReady.compareAndSet(true, false)) {
			limit.value += pageSize
		}
	}

	fun onQueryLaunched() {
		isReady.set(false)
	}

	fun onContentReady() {
		isReady.set(true)
	}

	companion object {
		const val DEFAULT_PAGE_SIZE = 16
	}
}
