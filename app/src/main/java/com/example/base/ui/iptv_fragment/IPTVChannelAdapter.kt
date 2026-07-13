package com.example.base.ui.iptv_fragment

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.base.databinding.ItemIptvChannelBinding
import com.example.base.model.entity.Channel
import hoang.dqm.codebase.utils.loadImageSketch

class IPTVChannelAdapter(
    private val onClick: (Channel) -> Unit
) : ListAdapter<Channel, IPTVChannelAdapter.ChannelViewHolder>(DiffCallback) {

    private var selectedChannelId: String? = null

    fun submitChannels(channels: List<Channel>, selectedId: String?) {
        val selectionChanged = selectedChannelId != selectedId
        selectedChannelId = selectedId
        submitList(channels.toList())
        if (selectionChanged) {
            notifyDataSetChanged()
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

    inner class ChannelViewHolder(
        private val binding: ItemIptvChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Channel, selected: Boolean) {
            binding.root.isSelected = selected
            binding.tvChannelName.text = item.name
            binding.tvLogoFallback.text = item.initials()

            val logo = item.logo
            binding.tvLogoFallback.isVisible = logo.isNullOrBlank()
            binding.ivChannelLogo.isVisible = logo.isNullOrBlank().not()
            if (logo.isNullOrBlank()) {
                binding.ivChannelLogo.setImageDrawable(null)
            } else {
                binding.ivChannelLogo.loadImageSketch(logo, isFull = true)
            }

            binding.root.setOnClickListener { onClick(item) }
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
    }
}
