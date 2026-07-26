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
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreState
import org.koitharu.kotatsu.settings.sources.catalog.StoreHealth
import org.koitharu.kotatsu.settings.sources.catalog.extensionStoreDisplayLabels

class ExtensionStoresAdapter(
	private val listener: Listener,
) : RecyclerView.Adapter<ExtensionStoresAdapter.Holder>() {

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

	override fun getItemId(position: Int): Long = items[position].store.id.hashCode().toLong()

	override fun getItemCount(): Int = items.size

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
		Holder(ItemExtensionStoreBinding.inflate(LayoutInflater.from(parent.context), parent, false))

	override fun onBindViewHolder(holder: Holder, position: Int) {
		holder.bind(items[position])
	}

	inner class Holder(
		private val binding: ItemExtensionStoreBinding,
	) : RecyclerView.ViewHolder(binding.root) {

		@SuppressLint("ClickableViewAccessibility")
		fun bind(item: ExtensionStoreState) {
			binding.textTitle.text = labels[item.store.id] ?: item.store.displayName
			val removed = item.health == StoreHealth.REMOVED
			binding.root.alpha = if (removed) 0.55f else 1f
			binding.storeStatus.isVisible = !removed
			if (!removed) {
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
					StoreHealth.REMOVED -> error("Handled above")
				}
				binding.storeStatus.backgroundTintList = ColorStateList.valueOf(color)
			}
			binding.storeStatus.contentDescription = binding.root.context.getString(
				when (item.health) {
					StoreHealth.AVAILABLE -> R.string.store_available
					StoreHealth.UNAVAILABLE -> R.string.store_unavailable
					StoreHealth.REMOVED -> R.string.store_removed
					StoreHealth.CHECKING -> R.string.loading_
				},
			)
			binding.buttonEdit.isVisible = item.store.enabled
			binding.buttonRetry.isVisible = item.health == StoreHealth.UNAVAILABLE
			binding.buttonWebsite.isVisible = !item.store.website.isNullOrBlank()
			binding.buttonDiscord.isVisible = !item.store.discord.isNullOrBlank()
			binding.buttonRemove.isVisible = item.store.enabled
			binding.buttonReadd.isVisible = !item.store.enabled
			binding.buttonEdit.setOnClickListener { listener.onEdit(item) }
			binding.buttonCopy.setOnClickListener { listener.onCopy(item) }
			binding.buttonRetry.setOnClickListener { listener.onRetry() }
			binding.buttonWebsite.setOnClickListener { item.store.website?.let(listener::onOpenLink) }
			binding.buttonDiscord.setOnClickListener { item.store.discord?.let(listener::onOpenLink) }
			binding.buttonRemove.setOnClickListener { listener.onRemove(item) }
			binding.buttonReadd.setOnClickListener { listener.onReAdd(item) }
			binding.dragHandle.setOnTouchListener { _, event ->
				event.actionMasked == MotionEvent.ACTION_DOWN && listener.onDrag(this)
			}
		}
	}

	interface Listener {
		fun onEdit(item: ExtensionStoreState)
		fun onCopy(item: ExtensionStoreState)
		fun onRetry()
		fun onOpenLink(url: String)
		fun onRemove(item: ExtensionStoreState)
		fun onReAdd(item: ExtensionStoreState)
		fun onDrag(holder: RecyclerView.ViewHolder): Boolean
	}
}
