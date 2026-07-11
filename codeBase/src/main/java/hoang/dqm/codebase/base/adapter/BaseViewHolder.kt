package hoang.dqm.codebase.base.adapter

import android.content.Context
import android.util.SparseArray
import android.view.View
import androidx.annotation.IdRes
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

open class BaseViewHolder<out VB : ViewBinding>(val binding: VB) :
    RecyclerView.ViewHolder(binding.root) {

    open fun getContext(): Context {
        return itemView.context
    }

    /**
     * Views indexed with their IDs
     */
    private val views: SparseArray<View> = SparseArray()

    open fun <V : View> getView(@IdRes viewId: Int): V {
        val view = getViewOrNull<V>(viewId)
        checkNotNull(view) { "No view found with id $viewId" }
        return view
    }

    @Suppress("UNCHECKED_CAST")
    open fun <V : View> getViewOrNull(@IdRes viewId: Int): V? {
        val view = views.get(viewId)
        if (view == null) {
            itemView.findViewById<V>(viewId)?.let {
                views.put(viewId, it)
                return it
            }
        }
        return view as? V
    }

    open fun <V : View> Int.findView(): V? {
        return itemView.findViewById(this)
    }

    fun isViewVisible(): Boolean {
        val parentRecyclerView = itemView.parent as? RecyclerView ?: return false
        val location = IntArray(2)
        itemView.getLocationOnScreen(location)
        val itemTop = location[1]
        val itemBottom = itemTop + itemView.height

        parentRecyclerView.getLocationOnScreen(location)
        val recyclerTop = location[1]
        val recyclerBottom = recyclerTop + parentRecyclerView.height

        return itemBottom > recyclerTop && itemTop < recyclerBottom
    }
}
