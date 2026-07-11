package hoang.dqm.codebase.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import hoang.dqm.codebase.R


class CustomTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val buttons = mutableListOf<TabButton>()
    private var selectedIndex = 0
    var onTabSelected: ((Int) -> Unit)? = null

    private val barPaddingH = dp(0)
    private val minCollapsedWidth = dp(44)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
//        setPadding(barPaddingH, dp(6), barPaddingH, dp(6))
        clipChildren = false
        clipToPadding = false
        background = run {
            val strokeWidth = dp(1)
            val radius = dp(22).toFloat()

            // Layer 0: gradient — đây là phần "stroke"
            val gradientBorder = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.parseColor("#037EB9"),
                    Color.parseColor("#045DCC")
                )
            ).apply {
                cornerRadius = radius
            }

            val innerBackground = GradientDrawable().apply {
                setColor(context.getAttrColor(R.attr.myColorSurface))
                cornerRadius = radius - strokeWidth
            }

            LayerDrawable(arrayOf(gradientBorder, innerBackground)).apply {
                setLayerInset(1, strokeWidth, strokeWidth, strokeWidth, strokeWidth)
            }
        }
        // shadow / elevation
        elevation = dp(8).toFloat()
    }

    fun Context.getAttrColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    fun setup(
        items: List<TabButton.TabBarItem>,
        initialIndex: Int = 0,
        onTabSelected: ((Int) -> Unit)? = null
    ) {
        this.onTabSelected = onTabSelected
        removeAllViews()
        buttons.clear()

        items.forEachIndexed { i, item ->
            val btn = TabButton(context, item, i).apply {
                this.onClickListener = { idx -> selectTab(idx) }
            }
            buttons.add(btn)
            addView(btn)
        }

        post {
            distributeWidths(initialIndex, animated = false)
            buttons.getOrNull(initialIndex)?.setSelected(true, animated = false)
            selectedIndex = initialIndex
        }
    }

    fun selectTab(index: Int, animated: Boolean = true) {
        if (index == selectedIndex) return
        val prev = selectedIndex
        selectedIndex = index

        buttons.getOrNull(prev)?.setSelected(false, animated)
        buttons.getOrNull(index)?.setSelected(true, animated)

        // Only ICON_WITH_TEXT needs width redistribution
        if (buttons.firstOrNull()?.displayMode == TabButton.DisplayMode.ICON_WITH_TEXT) {
            distributeWidths(index, animated)
        }

        onTabSelected?.invoke(index)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun distributeWidths(selectedIdx: Int, animated: Boolean) {
        if (buttons.isEmpty()) return
        val totalAvailable = width - paddingLeft - paddingRight
        if (totalAvailable <= 0) return

        when (buttons.first().displayMode) {

            TabButton.DisplayMode.ICON_ONLY,
            TabButton.DisplayMode.TEXT_ONLY -> {
                val equalWidth = totalAvailable / buttons.size
                buttons.forEach { btn ->
                    if (animated) btn.animateWidthTo(equalWidth)
                    else btn.setWidthInstant(equalWidth)
                }
            }

            TabButton.DisplayMode.ICON_WITH_TEXT -> {
                val selBtn = buttons.getOrNull(selectedIdx) ?: return
                val expandedWidth = (dp(12) + dp(24) + dp(8) + selBtn.titleTextWidth + dp(16))
                    .coerceAtMost(totalAvailable - (buttons.size - 1) * minCollapsedWidth)
                val collapsedWidth =
                    ((totalAvailable - expandedWidth) / (buttons.size - 1).coerceAtLeast(1))
                        .coerceAtLeast(minCollapsedWidth)

                buttons.forEachIndexed { i, btn ->
                    val target = if (i == selectedIdx) expandedWidth else collapsedWidth
                    if (animated) btn.animateWidthTo(target)
                    else btn.setWidthInstant(target)
                }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}