package com.tvchromecast.screenmirroringplus.ui.iptv_fragment

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.databinding.ItemIptvChannelBinding
import com.tvchromecast.screenmirroringplus.model.entity.Channel
import hoang.dqm.codebase.utils.loadImageSketch

class IPTVChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onFavouriteClick: (Channel, Boolean) -> Unit
) : ListAdapter<Channel, IPTVChannelAdapter.ChannelViewHolder>(DiffCallback) {

    private var selectedChannelId: String? = null

    fun submitChannels(channels: List<Channel>, selectedId: String?) {
        val previousSelectedId = selectedChannelId
        val selectionChanged = previousSelectedId != selectedId
        selectedChannelId = selectedId
        submitList(channels.toList())
        if (selectionChanged) {
            notifySelectionChanged(previousSelectedId)
            notifySelectionChanged(selectedId)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemIptvChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id == selectedChannelId)
    }

    override fun onBindViewHolder(
        holder: ChannelViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }

        val item = getItem(position)
        if (payloads.contains(PAYLOAD_FAVOURITE)) {
            holder.bindFavourite(item.isFavourite)
        }
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelected(item.id == selectedChannelId)
        }
    }

    private fun notifySelectionChanged(channelId: String?) {
        if (channelId == null) return
        val position = currentList.indexOfFirst { it.id == channelId }
        if (position != -1) {
            notifyItemChanged(position, PAYLOAD_SELECTION)
        }
    }

    inner class ChannelViewHolder(
        private val binding: ItemIptvChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Channel, selected: Boolean) {
            bindSelected(selected)
            binding.tvChannelName.text = item.name
            binding.tvLogoFallback.text = item.initials()
            bindFavourite(item.isFavourite)

            val logo = item.logo
            binding.tvLogoFallback.isVisible = logo.isNullOrBlank()
            binding.ivChannelLogo.isVisible = logo.isNullOrBlank().not()
            if (logo.isNullOrBlank()) {
                binding.ivChannelLogo.setImageDrawable(null)
            } else {
                binding.ivChannelLogo.loadImageSketch(logo, isFull = true)
            }

            binding.root.setOnClickListener { onClick(item) }
            binding.btnFavourite.setOnClickListener {
                val nextFavourite = binding.btnFavourite.isSelected.not()
                bindFavourite(nextFavourite)
                onFavouriteClick(item, nextFavourite)
            }
        }

        fun bindSelected(selected: Boolean) {
            binding.root.isSelected = selected
        }

        fun bindFavourite(isFavourite: Boolean) {
            binding.btnFavourite.isSelected = isFavourite
            binding.btnFavourite.setImageResource(
                if (isFavourite) R.drawable.ic_movie_star else R.drawable.ic_iptv_star_outline
            )
            binding.btnFavourite.imageTintList = ColorStateList.valueOf(
                Color.parseColor(if (isFavourite) "#D4A642" else "#FFFFFFFF")
            )
            binding.btnFavourite.alpha = if (isFavourite) 1f else 0.72f
            binding.btnFavourite.contentDescription = binding.root.context.getString(
                if (isFavourite) {
                    R.string.text_remove_from_favorites
                } else {
                    R.string.text_add_to_favorites
                }
            )
        }
    }

    private fun Channel.initials(): String {
        return name
            .split(" ")
            .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
            .take(2)
            .joinToString("")
            .ifBlank { name.firstOrNull()?.uppercaseChar()?.toString().orEmpty() }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: Channel, newItem: Channel): Any? {
            val onlyFavouriteChanged = oldItem.isFavourite != newItem.isFavourite &&
                oldItem.copy(isFavourite = newItem.isFavourite) == newItem
            return if (onlyFavouriteChanged) PAYLOAD_FAVOURITE else null
        }
    }

    companion object {
        private const val PAYLOAD_FAVOURITE = "payload_favourite"
        private const val PAYLOAD_SELECTION = "payload_selection"
    }
}
