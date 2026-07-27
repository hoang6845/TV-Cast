package com.tvchromecast.screenmirroringplus.ui.iptv_fragment

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.databinding.ItemIptvFilterOptionBinding

class IPTVFilterOptionAdapter(
    private var selectedValue: String?,
    private val onClick: (IPTVFilterOption) -> Unit
) : ListAdapter<IPTVFilterOption, IPTVFilterOptionAdapter.FilterViewHolder>(DiffCallback) {

    fun setSelectedValue(value: String?) {
        selectedValue = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilterViewHolder {
        val binding = ItemIptvFilterOptionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FilterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FilterViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FilterViewHolder(
        private val binding: ItemIptvFilterOptionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: IPTVFilterOption) {
            val context = binding.root.context
            val selected = item.value.equals(selectedValue, ignoreCase = true)

            binding.tvFilterName.text = item.label
            binding.tvFilterCount.text = context.getString(
                R.string.text_channels_count,
                item.channelCount
            )
            binding.ivFilterCheck.isVisible = selected
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<IPTVFilterOption>() {
        override fun areItemsTheSame(
            oldItem: IPTVFilterOption,
            newItem: IPTVFilterOption
        ): Boolean = oldItem.value.equals(newItem.value, ignoreCase = true)

        override fun areContentsTheSame(
            oldItem: IPTVFilterOption,
            newItem: IPTVFilterOption
        ): Boolean = oldItem == newItem
    }
}
