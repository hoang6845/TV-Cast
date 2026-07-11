package hoang.dqm.codebase.base.adapter

import android.animation.Animator
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.IntRange
import androidx.annotation.NonNull
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import hoang.dqm.codebase.base.adapter.animation.AlphaInAnimation
import hoang.dqm.codebase.base.adapter.animation.AnimationType
import hoang.dqm.codebase.base.adapter.animation.ItemAnimator
import hoang.dqm.codebase.base.adapter.animation.ScaleInAnimation
import hoang.dqm.codebase.base.adapter.animation.SlideInBottomAnimation
import hoang.dqm.codebase.base.adapter.animation.SlideInLeftAnimation
import hoang.dqm.codebase.base.adapter.animation.SlideInRightAnimation
import hoang.dqm.codebase.databinding.EmptyViewBinding
import hoang.dqm.codebase.data.ItemList
import hoang.dqm.codebase.databinding.ShimmerLayoutBinding
import hoang.dqm.codebase.utils.BindingReflex
import kotlin.apply
import kotlin.collections.getOrNull
import kotlin.collections.isNotEmpty
import kotlin.collections.isNullOrEmpty
import kotlin.collections.toMutableList
import kotlin.jvm.javaClass
import kotlin.let

abstract class BaseRecyclerViewItemShimmerAdapter<T, VB : ViewBinding> :
    RecyclerView.Adapter<BaseViewHolder<VB>>() {

    private var binding: VB? = null
    var dataList: MutableList<ItemList<T>> = mutableListOf()
        internal set
    private var mRecyclerView: RecyclerView? = null
    private var mLastPosition = -1
    var recyclerView: RecyclerView
        set(value) {
            mRecyclerView = value
        }
        get() {
            checkNotNull(mRecyclerView) {
                "Please get it after onAttachedToRecyclerView()"
            }
            return mRecyclerView!!
        }

    val context: Context
        get() {
            return recyclerView.context
        }

    var setOnClickItemListener: ((ItemList<T>, position: Int) -> Unit)? = null
    private var onLongClickItemRecyclerViewAdapter: ((ItemList<T>, position: Int) -> Unit)? = null
    var timeClick: Long = 0

    val isDoubleClick: Boolean
        get() {
            if (System.currentTimeMillis() - timeClick > 100) {
                timeClick = System.currentTimeMillis()
                return false
            }
            return true
        }

    var isAnimationFirstOnly = true
    var animationEnable: Boolean = false
    var itemAnimation: ItemAnimator? = null
        set(value) {
            animationEnable = true
            field = value
        }

    /******************************* RecyclerView Method ****************************************/

    fun setOnLongClickItemRecyclerView(listener: (ItemList<T>, position: Int) -> Unit) {
        onLongClickItemRecyclerViewAdapter = listener
    }

    fun setOnClickItemRecyclerView(listener: (ItemList<T>, position: Int) -> Unit) {
        setOnClickItemListener = listener
    }

    fun setItemAnimation(animationType: AnimationType) {
        itemAnimation = when (animationType) {
            AnimationType.AlphaIn -> AlphaInAnimation()
            AnimationType.ScaleIn -> ScaleInAnimation()
            AnimationType.SlideInBottom -> SlideInBottomAnimation()
            AnimationType.SlideInLeft -> SlideInLeftAnimation()
            AnimationType.SlideInRight -> SlideInRightAnimation()
        }
    }

    private fun runAnimator(holder: RecyclerView.ViewHolder) {
        if (animationEnable) {
            if (!isAnimationFirstOnly || holder.layoutPosition > mLastPosition) {
                val animation: ItemAnimator = itemAnimation ?: AlphaInAnimation()
                animation.animator(holder.itemView).apply {
                    startItemAnimator(this, holder)
                }
                mLastPosition = holder.layoutPosition
            }
        }
    }

    protected open fun startItemAnimator(anim: Animator, holder: RecyclerView.ViewHolder) {
        anim.start()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder<VB> {
        return when (viewType){
            RecyclerViewType.TYPE_DATA.value ->BaseViewHolder(reflexViewBinding(parent)).apply {
                bindViewClickListener(this, viewType)
            }
            RecyclerViewType.TYPE_PLACEHOLDER.value -> BaseViewHolder(ShimmerLayoutBinding.inflate( getLayoutInflater(parent.context), parent, false ) as VB )
            else -> BaseViewHolder( EmptyViewBinding.inflate( getLayoutInflater(parent.context), parent, false ) as VB )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun reflexViewBinding(parent: ViewGroup): VB {
        return try {
            BindingReflex.reflexViewBinding(
                javaClass, getLayoutInflater(parent.context), parent, false
            )
        } catch (e: Exception) {
            e.printStackTrace()
            EmptyViewBinding.inflate(getLayoutInflater(parent.context), parent, false) as VB
        }
    }

    private fun getLayoutInflater(context: Context): LayoutInflater {
        return LayoutInflater.from(context)
    }

    /**
     * @param viewType -> check viewType handle click header...
     */
    open fun bindViewClickListener(viewHolder: BaseViewHolder<VB>, _viewType: Int) {
        viewHolder.itemView.setOnClickListener {  _ ->
            if (!isCheckClickItem()) return@setOnClickListener
            val position = viewHolder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) {
                return@setOnClickListener
            }
            getItem(position)?.let {
                setOnClickItemListener?.invoke(it, position)
            }
        }
        viewHolder.itemView.setOnLongClickListener {
            val position = viewHolder.bindingAdapterPosition
            getItem(position)?.let {
                onLongClickItemRecyclerViewAdapter?.invoke(it, position)
            }
            true
        }
    }

    open fun setAllData(data: List<ItemList<T>>) {
        this.dataList = data.toMutableList()
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: BaseViewHolder<VB>, position: Int) {
        try {
            when (dataList[position]){
                is ItemList.DataItem<T> -> {
                    bindData(holder.binding, (dataList[position] as ItemList.DataItem<T>).item, position)
                }

                ItemList.Placeholder -> {
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getItemCount(): Int {
        return dataList.size
    }

    protected abstract fun bindData(binding: VB, item: T, position: Int)

    open fun getItem(@IntRange(from = 0) position: Int): ItemList<T> {
        return dataList[position]
    }

    open fun getItemOrNull(@IntRange(from = 0) position: Int): ItemList<T>? {
        return dataList.getOrNull(position)
    }

    open fun getItemPosition(item: ItemList<T>?): Int {
        return if (item != null && dataList.isNotEmpty()) dataList.indexOf(item) else -1
    }

    /**
     * change data
     * Change a location data
     */
    open fun setData(@IntRange(from = 0) index: Int, data: ItemList<T>) {
        if (index >= this.dataList.size) {
            return
        }
        this.dataList[index] = data as ItemList<T>
        notifyItemChanged(index)
    }


    /**
     * add one new data in to certain location
     * @param position
     */
    open fun addData(@IntRange(from = 0) position: Int, data: ItemList<T>) {
        this.dataList.add(position, data)
        notifyItemInserted(position)
        compatibilityDataSizeChanged(1)
    }

    /**
     * add one new data
     */
    open fun addData(data: ItemList<T>) {
        this.dataList.add(data)
        notifyItemInserted(this.dataList.size)
        compatibilityDataSizeChanged(1)
    }

    /**
     * add new data in to certain location
     * @param position the insert position
     * @param newData  the new data collection
     */
    open fun addData(@IntRange(from = 0) position: Int, newData: Collection<ItemList<T>>) {
        this.dataList.addAll(position, newData)
        notifyItemRangeInserted(position, newData.size)
        compatibilityDataSizeChanged(newData.size)
    }

    open fun addMoreData(@NonNull newData: Collection<ItemList<T>>) {
        this.dataList.addAll(newData)
        notifyItemRangeInserted(this.dataList.size - newData.size, newData.size)
        compatibilityDataSizeChanged(newData.size)
    }

    open fun setList(list: Collection<ItemList<T>>?) {
        mLastPosition = -1
        if (list !== this.dataList) {
            this.dataList.clear()
            if (!list.isNullOrEmpty()) {
                this.dataList.addAll(list)
            }
        } else {
            if (list.isNotEmpty()) {
                val newList = kotlin.collections.ArrayList(list)
                this.dataList.clear()
                this.dataList.addAll(newList)
            } else {
                this.dataList.clear()
            }
        }
        notifyDataSetChanged()
    }

    open fun addDataAny(data: List<ItemList<T>>) {
        clearData()
        addData(data)
    }

    open fun addData(data: Collection<ItemList<T>>) {
        mLastPosition = -1
        val newData: MutableList<ItemList<T>> = mutableListOf()
        newData.addAll(data)
        val diffResult = DiffUtil.calculateDiff(DiffUtilCallBack(dataList, newData))
        diffResult.dispatchUpdatesTo(this)
        dataList.clear()
        dataList.addAll(newData)
        notifyItemRangeInserted(this.dataList.size - newData.size, newData.size)
        compatibilityDataSizeChanged(newData.size)
    }

    open fun addAllData(data: Collection<ItemList<T>>) {
        mLastPosition = -1
        val newData: MutableList<ItemList<T>> = mutableListOf()
        newData.addAll(data)
        dataList.clear()
        dataList.addAll(newData)
        notifyItemRangeInserted(0, data.size)
    }

    /**
     * remove the item associated with the specified position of adapter
     *
     * @param position
     */
    @Deprecated("Please use removeAt()", replaceWith = ReplaceWith("removeAt(position)"))
    open fun remove(@IntRange(from = 0) position: Int) {
        removeAt(position)
    }

    /**
     * remove the item associated with the specified position of adapter
     *
     * @param position
     */
    open fun removeAt(@IntRange(from = 0) position: Int) {
        if (position >= dataList.size) {
            return
        }
        this.dataList.removeAt(position)
        notifyItemRemoved(position)
        compatibilityDataSizeChanged(0)
        notifyItemRangeChanged(position, this.dataList.size - position)
    }

    open fun remove(data: ItemList<T>) {
        val index = this.dataList.indexOf(data)
        if (index == -1) {
            return
        }
        removeAt(index)
    }

    open fun removeData(data: ItemList<T>) {
        val index = this.dataList.indexOf(data)
        if (index == -1) {
            return
        }
        removeAt(index)
    }

    open fun clearData() {
        mLastPosition = -1
        dataList.clear()
        notifyDataSetChanged()
    }

    /**
     * compatible getEmptyViewCount may change
     *
     * @param size Need compatible data size
     */
    protected fun compatibilityDataSizeChanged(size: Int) {
        if (this.dataList.size == size) {
            notifyDataSetChanged()
        }
    }

    private var timeClickItem = 0L

    protected fun isCheckClickItem(): Boolean {
        if (System.currentTimeMillis() - timeClickItem > 100L) {
            timeClickItem = System.currentTimeMillis()
            return true
        }
        return false
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getItemViewType(position: Int): Int {
        return getItemViewType(position, dataList)
    }

    protected open fun getItemViewType(position: Int, list: List<ItemList<T>>): Int {
        return when (list[position]) {
            is ItemList.DataItem<T> -> RecyclerViewType.TYPE_DATA.value
            is ItemList.Placeholder -> RecyclerViewType.TYPE_PLACEHOLDER.value
        }
    }

    override fun onViewAttachedToWindow(holder: BaseViewHolder<VB>) {
        super.onViewAttachedToWindow(holder)
        runAnimator(holder)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        mRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        mRecyclerView = null
    }

    fun hasEmptyView(): Boolean {
        return dataList.isEmpty()
    }

}

