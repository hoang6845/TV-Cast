package com.tvchromecast.screenmirroringplus.ui.intro

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

class IntroButtonSparkleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val clipPath = Path()
    private val bounds = RectF()
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val cornerRadius = resources.getDimension(com.intuit.sdp.R.dimen._12sdp)
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    private var progress = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode && !animator.isStarted) {
            animator.start()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        drawShine(canvas)
        drawSparkles(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun drawShine(canvas: Canvas) {
        val bandWidth = width * 0.28f
        val centerX = -bandWidth + (width + bandWidth * 2f) * progress
        shinePaint.shader = LinearGradient(
            centerX - bandWidth,
            0f,
            centerX + bandWidth,
            height.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(30, 255, 255, 255),
                Color.argb(120, 255, 255, 255),
                Color.argb(35, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.32f, 0.5f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(bounds, shinePaint)
        shinePaint.shader = null
    }

    private fun drawSparkles(canvas: Canvas) {
        drawSparkle(canvas, width * 0.2f, height * 0.34f, 0f, resources.getDimension(com.intuit.sdp.R.dimen._3sdp))
        drawSparkle(canvas, width * 0.72f, height * 0.26f, 0.28f, resources.getDimension(com.intuit.sdp.R.dimen._4sdp))
        drawSparkle(canvas, width * 0.86f, height * 0.68f, 0.52f, resources.getDimension(com.intuit.sdp.R.dimen._3sdp))
    }

    private fun drawSparkle(canvas: Canvas, baseX: Float, baseY: Float, offset: Float, radius: Float) {
        val wave = ((sin((progress + offset) * TWO_PI) + 1f) / 2f).coerceIn(0f, 1f)
        val alpha = (35 + wave * 170).toInt()
        val drift = resources.getDimension(com.intuit.sdp.R.dimen._2sdp) * sin((progress + offset) * TWO_PI)
        val x = baseX + drift
        val y = baseY

        sparklePaint.color = Color.argb(alpha, 255, 255, 255)
        sparklePaint.strokeWidth = resources.getDimension(com.intuit.sdp.R.dimen._1sdp)
        canvas.drawLine(x - radius, y, x + radius, y, sparklePaint)
        canvas.drawLine(x, y - radius, x, y + radius, sparklePaint)

        sparklePaint.color = Color.argb((alpha * 0.5f).toInt(), 255, 255, 255)
        sparklePaint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, radius * 0.26f, sparklePaint)
        sparklePaint.style = Paint.Style.STROKE
    }

    companion object {
        private val TWO_PI = (PI * 2).toFloat()
    }
}
