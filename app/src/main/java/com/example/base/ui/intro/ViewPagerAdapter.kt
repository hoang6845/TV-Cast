package com.example.base.ui.intro

import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class ViewPager2Adapter<T>(
    private val items: List<T>,
    private val getLayoutResId: (item: T, position: Int) -> Int,
    private val bindView: (view: View, item: T, position: Int) -> Unit
) : RecyclerView.Adapter<ViewPager2Adapter<T>.PageViewHolder>() {

    private val boundViews = SparseArray<View>()

    inner class PageViewHolder(val root: View) : RecyclerView.ViewHolder(root)

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        return getLayoutResId(items[position], position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(viewType, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        boundViews.put(position, holder.root)
        bindView(holder.root, items[position], position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            boundViews.remove(position)
        }
        super.onViewRecycled(holder)
    }

    fun getBoundView(position: Int): View? {
        return boundViews[position]
    }
}