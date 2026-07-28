package org.koitharu.kotatsu.settings.sources.catalog.stores

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemExtensionStoreBinding
import org.koitharu.kotatsu.databinding.ItemExtensionStoreNoteBinding
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreKind
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreState
import org.koitharu.kotatsu.settings.sources.catalog.StoreHealth
import org.koitharu.kotatsu.settings.sources.catalog.extensionStoreDisplayLabels
import org.koitharu.kotatsu.settings.sources.catalog.extensionStoreKind

class ExtensionStoresAdapter(
	private val listener: Listener,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

	private val items = ArrayList<ExtensionStoreState>()
	private var labels = emptyMap<String, String>()

	init {
		setHasStableIds(true)
	}

	@SuppressLint("NotifyDataSetChanged")
	fun submitList(value: List<ExtensionStoreState>) {
		items.clear()
		items.addAll(value)
		labels = extensionStoreDisplayLabels(value.map { it.store })
		notifyDataSetChanged()
	}

	fun move(fromIndex: Int, toIndex: Int): Boolean {
		if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) return false
		items.add(toIndex, items.removeAt(fromIndex))
		notifyItemMoved(fromIndex, toIndex)
		return true
	}

	fun itemAt(position: Int): ExtensionStoreState? = items.getOrNull(position)

	override fun getItemId(position: Int): Long =
		if (position == items.size) NOTE_ITEM_ID else items[position].store.id.hashCode().toLong()

	// The note is always the last row, so with no stores at all it lands at the top by itself.
	override fun getItemCount(): Int = items.size + 1

	override fun getItemViewType(position: Int): Int = if (position == items.size) TYPE_NOTE else TYPE_STORE

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		return if (viewType == TYPE_NOTE) {
			NoteHolder(ItemExtensionStoreNoteBinding.inflate(inflater, parent, false))
		} else {
			Holder(ItemExtensionStoreBinding.inflate(inflater, parent, false))
		}
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		(holder as? Holder)?.bind(items[position])
	}

	class NoteHolder(binding: ItemExtensionStoreNoteBinding) : RecyclerView.ViewHolder(binding.root)

	inner class Holder(
		private val binding: ItemExtensionStoreBinding,
	) : RecyclerView.ViewHolder(binding.root) {

		@SuppressLint("ClickableViewAccessibility")
		fun bind(item: ExtensionStoreState) {
			binding.textTitle.text = labels[item.store.id] ?: item.store.displayName
			val kind = item.catalog.extensionStoreKind()
			binding.textKind.isVisible = kind != null
			if (kind != null) {
				binding.textKind.setText(
					when (kind) {
						ExtensionStoreKind.MANGA -> R.string.store_kind_manga
						ExtensionStoreKind.NOVEL -> R.string.store_kind_novel
						ExtensionStoreKind.MIXED -> R.string.store_kind_mixed
					},
				)
			}
			val color = when (item.health) {
				StoreHealth.AVAILABLE -> ContextCompat.getColor(binding.root.context, R.color.common_green)
				StoreHealth.UNAVAILABLE -> com.google.android.material.color.MaterialColors.getColor(
					binding.root,
					androidx.appcompat.R.attr.colorError,
				)
				StoreHealth.CHECKING -> com.google.android.material.color.MaterialColors.getColor(
					binding.root,
					com.google.android.material.R.attr.colorOnSurfaceVariant,
				)
			}
			binding.storeStatus.backgroundTintList = ColorStateList.valueOf(color)
			binding.storeStatus.contentDescription = binding.root.context.getString(
				when (item.health) {
					StoreHealth.AVAILABLE -> R.string.store_available
					StoreHealth.UNAVAILABLE -> R.string.store_unavailable
					StoreHealth.CHECKING -> R.string.loading_
				},
			)
			binding.buttonEdit.isVisible = true
			binding.buttonRetry.isVisible = item.health == StoreHealth.UNAVAILABLE
			binding.buttonWebsite.isVisible = !item.store.website.isNullOrBlank()
			binding.buttonDiscord.isVisible = !item.store.discord.isNullOrBlank()
			binding.buttonEdit.setOnClickListener { listener.onEdit(item) }
			binding.buttonCopy.setOnClickListener { listener.onCopy(item) }
			binding.buttonRetry.setOnClickListener { listener.onRetry() }
			binding.buttonWebsite.setOnClickListener { item.store.website?.let(listener::onOpenLink) }
			binding.buttonDiscord.setOnClickListener { item.store.discord?.let(listener::onOpenLink) }
			binding.buttonRemove.setOnClickListener { listener.onRemove(item) }
			binding.dragHandle.setOnTouchListener { _, event ->
				event.actionMasked == MotionEvent.ACTION_DOWN && listener.onDrag(this)
			}
		}
	}

	private companion object {
		const val TYPE_STORE = 0
		const val TYPE_NOTE = 1
		const val NOTE_ITEM_ID = -1L
	}

	interface Listener {
		fun onEdit(item: ExtensionStoreState)
		fun onCopy(item: ExtensionStoreState)
		fun onRetry()
		fun onOpenLink(url: String)
		fun onRemove(item: ExtensionStoreState)
		fun onDrag(holder: RecyclerView.ViewHolder): Boolean
	}
}
