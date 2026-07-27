package com.tvchromecast.screenmirroringplus.utils

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.Log
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import hoang.dqm.codebase.R
import hoang.dqm.codebase.base.adapter.RecyclerViewType
import hoang.dqm.codebase.base.application.getBaseApplication
import kotlin.apply
import kotlin.collections.map
import kotlin.collections.toIntArray

fun AppCompatTextView.setGradient(
    listColor: IntArray = intArrayOf(
        R.color.pink_fd8aff, R.color.blue_00b2ff
    )
) {
    post {
        try {
            this.text = this.text
            val width = paint.measureText(this.text.toString())
            val listParseColor = listColor.map {
                ContextCompat.getColor(getBaseApplication(), it)
            }.toIntArray()
            val shader = LinearGradient(
                0f, 0f, width, this.textSize, listParseColor, null, Shader.TileMode.CLAMP
            )

            this.paint.shader = shader
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}

fun RecyclerView.setUpAdapterGridAds(
    adapter: RecyclerView.Adapter<*>, context: Context?, spanCount: Int
) {
    val layoutManager = GridLayoutManager(context, spanCount).apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter.getItemViewType(position)) {
                    RecyclerViewType.TYPE_AD_FULL.value -> 2 // FULL_ITEMS
                    else -> 1 // Data item & FULL_ONE_ITEM & Placeholder
                }
            }
        }
    }

    this.layoutManager = layoutManager
    this.adapter = adapter

    setRecycledViewPool(RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(RecyclerViewType.TYPE_AD_FULL.value, 2)
        setMaxRecycledViews(RecyclerViewType.TYPE_AD_ONE.value, 4)
    })
}

fun RecyclerView.setUpAdapterLinearAds(
    adapter: RecyclerView.Adapter<*>, context: Context?, orientation: Int = RecyclerView.VERTICAL
) {
    val layoutManager = LinearLayoutManager(context, orientation, false)

    this.layoutManager = layoutManager
    this.adapter = adapter

    setRecycledViewPool(RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(RecyclerViewType.TYPE_AD_FULL.value, 2)
        setMaxRecycledViews(RecyclerViewType.TYPE_AD_ONE.value, 4)
    })
}

fun View.animateClose(onEnd: () -> Unit) {
    val endX = this.width.toFloat()
    val endY = -this.height.toFloat()
    val screenWidth = resources.displayMetrics.widthPixels.toFloat()

    Log.d("check animate", "animateClose: $endY")
    this.animate()
        .scaleX(0f)
        .scaleY(0f)
        .translationX(endX)
        .translationY(endY)
        .setDuration(250)
        .withEndAction { onEnd() }
        .start()
}

fun View.animateCloseTo(target: View, onEnd: () -> Unit) {
    val rootLocation = IntArray(2)
    val targetLocation = IntArray(2)

    // Lấy vị trí trên màn hình
    this.getLocationOnScreen(rootLocation)
    target.getLocationOnScreen(targetLocation)
    val screenWidth = resources.displayMetrics.heightPixels.toFloat()

    val deltaX = targetLocation[0] - rootLocation[0]
    val deltaY = targetLocation[1] - rootLocation[1]
    Log.d("check animate", "animateClose: $deltaX $deltaY")

    this.animate()
        .scaleX(0.2f)
        .scaleY(0.2f)
        .translationX(deltaX.toFloat())
        .translationY(-screenWidth)
        .setDuration(3200)
        .withEndAction { onEnd() }
        .start()
}

fun View.gone() {
    visibility = View.GONE
}

fun View.visible() {
    visibility = View.VISIBLE
}

fun View.invisible() {
    visibility = View.INVISIBLE
}


