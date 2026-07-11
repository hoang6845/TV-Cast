package hoang.dqm.codebase.utils

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

object RecyclerUtils {

    fun setManager(mContext: Context, rlv: RecyclerView) {
        var layout = GridLayoutManager(mContext, 1)
        layout.orientation = GridLayoutManager.VERTICAL
        rlv.layoutManager = layout
    }

    fun setManager(mContext: Context, rlv: RecyclerView, number: Int, orientai: Int) {
        var layout = GridLayoutManager(mContext, number)
        layout.orientation = orientai
        rlv.layoutManager = layout
    }

    fun setGridManage(m: Context, rlv: RecyclerView) {
        val manager = GridLayoutManager(m, 1)
        manager.orientation = GridLayoutManager.VERTICAL
        rlv.layoutManager = manager
    }

    fun setGridManage(m: Context, rlv: RecyclerView, lin: Int) {
        val manager = GridLayoutManager(m, lin)
        manager.orientation = GridLayoutManager.VERTICAL
        rlv.layoutManager = manager
    }

    fun setGridManageH(m: Context, rlv: RecyclerView) {
        val manager = LinearLayoutManager(m, LinearLayoutManager.HORIZONTAL, false)
        rlv.layoutManager = manager
    }

    fun setGridManageHorizontal(
        m: Context, row: Int, rlv: RecyclerView, adapter: RecyclerView.Adapter<*>
    ) {
        val manager = GridLayoutManager(m, row, GridLayoutManager.HORIZONTAL, false)
        rlv.layoutManager = manager
        rlv.adapter = adapter
    }

    fun setGridManage(m: Context, rlv: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        val manager = LinearLayoutManager(m, LinearLayoutManager.VERTICAL, false)
        rlv.layoutManager = manager
        rlv.adapter = adapter
    }

    fun setGridManageTwo(m: Context, rlv: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        val manager = GridLayoutManager(m, 1)
        manager.orientation = GridLayoutManager.VERTICAL
        rlv.layoutManager = manager
        rlv.adapter = adapter
    }

    fun setGridManage(m: Context, rlv: RecyclerView, lin: Int, adapter: RecyclerView.Adapter<*>) {
        val manager = GridLayoutManager(m, lin)
        manager.orientation = GridLayoutManager.VERTICAL
        rlv.layoutManager = manager
        rlv.adapter = adapter
    }

    fun setLinearLayoutManager(
        context: Context, rlv: RecyclerView, adapter: RecyclerView.Adapter<*>
    ) {
        val manager = object : LinearLayoutManager(context, RecyclerView.VERTICAL, false) {
            override fun scrollVerticallyBy(
                dy: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?
            ): Int {
                val scrollRange: Int = super.scrollVerticallyBy(dy, recycler, state)
                val overScroll = dy - scrollRange
                if (overScroll > 0) {
                    rlv.parent.requestDisallowInterceptTouchEvent(true)
                }
                return scrollRange
            }
        }
        manager.orientation = LinearLayoutManager.VERTICAL
        rlv.layoutManager = manager
        rlv.adapter = adapter
    }

    fun setLinearLayoutManagerH(
        context: Context, rlv: RecyclerView, adapter: RecyclerView.Adapter<*>
    ) {
        val manager = LinearLayoutManager(context)
        manager.orientation = LinearLayoutManager.HORIZONTAL
        rlv.layoutManager = manager
        rlv.adapter = adapter
    }

    fun setMaxHeight(view: RecyclerView, maxItem: Int) {
        setMaxHeight(view, 1, maxItem)
    }

    fun setMaxHeight(view: RecyclerView, minItem: Int, maxItem: Int) {
        view.post(Runnable {
            val layoutManager = view.layoutManager
            val itemCount = layoutManager?.itemCount ?: 0
            if (itemCount <= minItem) {
                return@Runnable
            }
            val layoutParams = view.layoutParams
            val height = layoutManager?.height ?: 0
            layoutParams.height = height * maxItem
            view.layoutParams = layoutParams
        })
    }

    fun setHeightByItem(view: RecyclerView, countItem: Int) {
        view.post(Runnable {
            try {
                val layoutManager = view.layoutManager
                val itemCount = layoutManager?.itemCount ?: 0
                if (itemCount == 0) {
                    return@Runnable
                }
                val layoutParams = view.layoutParams
                val height = layoutManager?.getChildAt(0)?.height ?: 0
                layoutParams.height = height * countItem
                view.layoutParams = layoutParams
            } catch (e: Exception) {
                e.printStackTrace()
            }
        })
    }

    fun setHeight(view: RecyclerView, height: Int) {
        try {
            val layoutParams = view.layoutParams
            if (layoutParams.height == height) return
            layoutParams.height = height
            view.layoutParams = layoutParams
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}

fun RecyclerView.smoothScrollToPositionCentered(position: Int) {
    try {
        if (position == 0 || position == ((adapter?.itemCount) ?: 0) - 1) return
        val layoutManager = this.layoutManager as? LinearLayoutManager ?: return
        val recyclerViewWidth = this.width
        val recyclerViewHeight = this.height
        val view = layoutManager.findViewByPosition(position)
        if (view != null) {
            val itemStartX = view.left
            val itemStartY = view.top
            val itemCenterX = itemStartX + view.width / 2
            val itemCenterY = itemStartY + view.height / 2
            val scrollOffsetX = itemCenterX - recyclerViewWidth / 2
            val scrollOffsetY = itemCenterY - recyclerViewHeight / 2
            this.smoothScrollBy(scrollOffsetX, scrollOffsetY)
        } else {
            layoutManager.scrollToPositionWithOffset(position, recyclerViewHeight / 2)
            post {
                val viewAfterScroll = layoutManager.findViewByPosition(position)
                viewAfterScroll?.let {
                    val itemCenterY = it.top + it.height / 2
                    val scrollOffsetY = itemCenterY - recyclerViewHeight / 2
                    this.smoothScrollBy(0, scrollOffsetY)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

