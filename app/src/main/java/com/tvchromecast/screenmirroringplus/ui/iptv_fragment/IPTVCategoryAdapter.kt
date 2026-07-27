package com.tvchromecast.screenmirroringplus.ui.iptv_fragment

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.databinding.ItemIptvCategoryBinding

class IPTVCategoryAdapter(
    private val onClick: (IPTVCategoryItem) -> Unit
) : ListAdapter<IPTVCategoryItem, IPTVCategoryAdapter.CategoryViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemIptvCategoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CategoryViewHolder(
        private val binding: ItemIptvCategoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: IPTVCategoryItem) {
            val context = binding.root.context
            binding.tvCategoryName.text = item.name
            binding.tvCategoryCount.text = context.getString(
                R.string.text_channels_count,
                item.channelCount
            )
            binding.tvCategoryInitial.text = item.name.firstOrNull()
                ?.uppercaseChar()
                ?.toString()
                .orEmpty()

            binding.root.setOnClickListener { onClick(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<IPTVCategoryItem>() {
        override fun areItemsTheSame(
            oldItem: IPTVCategoryItem,
            newItem: IPTVCategoryItem
        ): Boolean = oldItem.name.equals(newItem.name, ignoreCase = true)

        override fun areContentsTheSame(
            oldItem: IPTVCategoryItem,
            newItem: IPTVCategoryItem
        ): Boolean = oldItem == newItem
    }
}
