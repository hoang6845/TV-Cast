package com.tvchromecast.screenmirroringplus.ui.faq

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvchromecast.screenmirroringplus.databinding.ItemFaqBinding

class FaqAdapter : ListAdapter<FaqItem, FaqAdapter.FaqViewHolder>(DiffCallback) {

    private val expandedPositions = mutableSetOf<Int>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FaqViewHolder {
        val binding = ItemFaqBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FaqViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FaqViewHolder, position: Int) {
        holder.bind(getItem(position), expandedPositions.contains(position)) {
            if (expandedPositions.contains(position)) {
                expandedPositions.remove(position)
            } else {
                expandedPositions.add(position)
            }
            notifyItemChanged(position)
        }
    }

    class FaqViewHolder(
        private val binding: ItemFaqBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FaqItem, expanded: Boolean, onToggle: () -> Unit) {
            binding.tvFaqTitle.text = item.title
            binding.tvFaqAnswer.text = item.answerLines.joinToString(separator = "\n")
            binding.tvFaqAnswer.isVisible = expanded
            binding.ivFaqArrow.rotation = if (expanded) 180f else 0f
            binding.root.setOnClickListener { onToggle() }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<FaqItem>() {
        override fun areItemsTheSame(oldItem: FaqItem, newItem: FaqItem): Boolean {
            return oldItem.title == newItem.title
        }

        override fun areContentsTheSame(oldItem: FaqItem, newItem: FaqItem): Boolean {
            return oldItem == newItem
        }
    }
}
