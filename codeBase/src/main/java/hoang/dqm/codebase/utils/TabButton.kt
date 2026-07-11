package hoang.dqm.codebase.utils

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class TabButton @JvmOverloads constructor(
    context: Context,
    val item: TabBarItem,
    val index: Int,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // ── Data ──────────────────────────────────────────────────────────────────

    enum class DisplayMode { ICON_ONLY, TEXT_ONLY, ICON_WITH_TEXT }

    data class TabBarItem(
        val title: String = "",
        val icon: Int = 0,
        val selectedIcon: Int = 0,
        val unselectedTextColor: Int = Color.BLACK,
        val selectedTextColor: Int =  Color.BLACK
    ) {
        val displayMode: DisplayMode
            get() = when {
                icon != 0 && title.isNotBlank() -> DisplayMode.ICON_WITH_TEXT
                icon != 0 -> DisplayMode.ICON_ONLY
                else -> DisplayMode.TEXT_ONLY
            }
    }

    // ── Public ────────────────────────────────────────────────────────────────

    var onClickListener: ((Int) -> Unit)? = null
    val displayMode: DisplayMode = item.displayMode

    val titleTextWidth: Int
        get() {
            titleLabel.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            return titleLabel.measuredWidth
        }

    // ── Private ───────────────────────────────────────────────────────────────

    // pillView: the visible gradient background — smaller than the full tab width
    private val pillView: View
    private val contentRow: LinearLayout
    private val iconImageView: ImageView
    private val titleLabel: TextView

    private val unselectedIconColor = Color.BLACK

    internal val pillGapH = if (displayMode == DisplayMode.ICON_WITH_TEXT) 0 else dp(2)
    private val pillGapV = if (displayMode == DisplayMode.ICON_WITH_TEXT) 0 else dp(2)
    private val cornerRadiusPx = (dp(22) - pillGapV).toFloat()

    private var isCurrentlySelected = false

    init {
        clipToPadding = false
        clipChildren = false

        // Fixed height — prevents FrameLayout from stretching to parent height
        minimumHeight = dp(44)

        // ── pillView ──────────────────────────────────────────────────────────
        pillView = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#037EB9"),
                    Color.parseColor("#045DCC")
                )
            ).also { it.cornerRadius = cornerRadiusPx }
            alpha = 0f   // hidden until selected
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).also {
                it.marginStart = pillGapH
                it.marginEnd   = pillGapH
                it.topMargin   = pillGapV
                it.bottomMargin = pillGapV
            }
        }
        addView(pillView)

        // ── contentRow: icon + title centred over the pill ────────────────────
        contentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                .apply { gravity = Gravity.CENTER }
            isClickable = false; isFocusable = false
        }

        // Icon
        iconImageView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).also {
                if (displayMode == DisplayMode.ICON_WITH_TEXT) it.marginStart = dp(12)
            }
            if (item.icon != 0) setImageResource(item.icon)
            imageTintList = tintList(unselectedIconColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = false; isFocusable = false
        }
        if (displayMode != DisplayMode.TEXT_ONLY) {
            contentRow.addView(iconImageView)
        }
//        contentRow.addView(iconImageView)

        // Title
        titleLabel = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                when (displayMode) {
                    DisplayMode.ICON_WITH_TEXT -> { it.marginStart = dp(8); it.marginEnd = dp(16) }
                    DisplayMode.TEXT_ONLY -> { it.marginStart = dp(14); it.marginEnd = dp(14) }
                    else -> {}
                }
            }
            text = item.title
            setTextColor(item.unselectedTextColor)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            isClickable = false; isFocusable = false
            alpha = if (displayMode == DisplayMode.ICON_WITH_TEXT) 0f else 1f
            visibility = if (displayMode == DisplayMode.ICON_ONLY) GONE else VISIBLE
        }
        contentRow.addView(titleLabel)
        addView(contentRow)

        // Touch feedback on whole tab area
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> animate().alpha(0.7f).setDuration(100).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().alpha(1f).setDuration(100).start()
            }
            false
        }
        setOnClickListener { onClickListener?.invoke(index) }
        Log.d("check mode", "$displayMode: ")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setSelected(selected: Boolean, animated: Boolean) {
        val wasSelected = isCurrentlySelected
        isCurrentlySelected = selected
        val animate = animated && selected != wasSelected

        if (item.selectedIcon != 0)
            iconImageView.setImageResource(if (selected) item.selectedIcon else item.icon)

        when (displayMode) {
            DisplayMode.ICON_ONLY -> animateIconOnly(selected, animate)
            DisplayMode.TEXT_ONLY -> animateTextOnly(selected, animate)
            DisplayMode.ICON_WITH_TEXT -> animateIconWithText(selected, animate)
        }
    }

    fun animateWidthTo(targetPx: Int, durationMs: Long = 350) {
        val from = layoutParams.width.takeIf { it > 0 } ?: targetPx
        ValueAnimator.ofInt(from, targetPx).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                lp.width = it.animatedValue as Int
                lp.height = dp(44)
                layoutParams = lp
                constrainTitleWidth(it.animatedValue as Int)
            }
            start()
        }
    }

    private fun constrainTitleWidth(buttonWidth: Int) {
        val horizontalMargins = when (displayMode) {
            DisplayMode.TEXT_ONLY -> dp(14) * 2 + pillGapH * 2
            DisplayMode.ICON_WITH_TEXT -> dp(12) + dp(24) + dp(8) + dp(16)
            else -> 0
        }
        titleLabel.maxWidth = (buttonWidth - horizontalMargins).coerceAtLeast(dp(20))
    }

    fun setWidthInstant(px: Int) {
        lp.width = px
        lp.height = dp(44)
        layoutParams = lp
        constrainTitleWidth(px)
    }

    // ── Per-mode animation ────────────────────────────────────────────────────

    private fun animateIconOnly(selected: Boolean, animate: Boolean) {
        if (animate) {
            AnimatorSet().apply { playTogether(pillAlphaAnim(selected), iconTintAnim(selected)); start() }
        } else {
            pillView.alpha = if (selected) 1f else 0f
            iconImageView.imageTintList = tintList(if (selected) Color.WHITE else unselectedIconColor)
        }
    }

    private fun animateTextOnly(selected: Boolean, animate: Boolean) {
        val toColor = if (selected) item.selectedTextColor else item.unselectedTextColor
        if (animate) {
            val fromColor = if (selected) item.unselectedTextColor else item.selectedTextColor
            val textAnim = ValueAnimator.ofArgb(fromColor, toColor).apply {
                duration = 280
                addUpdateListener { titleLabel.setTextColor(it.animatedValue as Int) }
            }
            AnimatorSet().apply { playTogether(pillAlphaAnim(selected), textAnim); start() }
        } else {
            pillView.alpha = if (selected) 1f else 0f
            titleLabel.setTextColor(toColor)
        }
    }

    private fun animateIconWithText(selected: Boolean, animate: Boolean) {
        val titleTarget = if (selected) 1f else 0f
        if (animate) {
            val titleFade = ObjectAnimator.ofFloat(titleLabel, "alpha", titleLabel.alpha, titleTarget).apply { duration = 280 }
            AnimatorSet().apply { playTogether(pillAlphaAnim(selected), iconTintAnim(selected), titleFade); start() }
        } else {
            pillView.alpha = if (selected) 1f else 0f
            iconImageView.imageTintList = tintList(if (selected) Color.WHITE else unselectedIconColor)
            titleLabel.alpha = titleTarget
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun pillAlphaAnim(selected: Boolean) =
        ObjectAnimator.ofFloat(pillView, "alpha", pillView.alpha, if (selected) 1f else 0f).apply { duration = 280 }

    private fun iconTintAnim(selected: Boolean) =
        ValueAnimator.ofArgb(
            if (selected) unselectedIconColor else Color.WHITE,
            if (selected) Color.WHITE else unselectedIconColor
        ).apply {
            duration = 280
            addUpdateListener { iconImageView.imageTintList = tintList(it.animatedValue as Int) }
        }

    private fun tintList(color: Int) = ColorStateList.valueOf(color)

    private val lp get() = layoutParams

    fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}