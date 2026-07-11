package hoang.dqm.codebase.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import hoang.dqm.codebase.R

class CradleBottomBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val SVG_WIDTH  = 360f
    private val SVG_HEIGHT = 82f

    // Shadow params
    private val SHADOW_RADIUS = 20f
    private val SHADOW_DY     = -8f  // âm → shadow lên trên
    // Padding top = bán kính shadow + |offset lên trên| để shadow không bị cắt
    private val SHADOW_TOP_PADDING = (SHADOW_RADIUS + Math.abs(SHADOW_DY)).toInt()

    private val surfaceColor = resolveAttrColor(R.attr.myColorSurface)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = surfaceColor
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.FILL
        setShadowLayer(SHADOW_RADIUS, 0f, SHADOW_DY, resolveShadowColor(surfaceColor))
    }

    private val leftPath  = Path()
    private val rightPath = Path()
    private val matrix    = Matrix()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val resolvedWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            SVG_WIDTH.toInt() else widthSize
        // Chiều cao = tỉ lệ SVG + padding top cho shadow
        val svgHeight = (resolvedWidth * SVG_HEIGHT / SVG_WIDTH).toInt()
        val resolvedHeight = svgHeight + SHADOW_TOP_PADDING
        setMeasuredDimension(resolvedWidth, resolvedHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPaths(w.toFloat(), h.toFloat())
    }

    private fun rebuildPaths(w: Float, h: Float) {
        // Vùng vẽ thực sự bắt đầu từ SHADOW_TOP_PADDING, không phải 0
        val drawH = h - SHADOW_TOP_PADDING
        buildRawPaths()
        matrix.setRectToRect(
            RectF(0f, 0f, SVG_WIDTH, SVG_HEIGHT),
            RectF(0f, SHADOW_TOP_PADDING.toFloat(), w, h),
            Matrix.ScaleToFit.FILL
        )
        leftPath.transform(matrix)
        rightPath.transform(matrix)
    }

    private fun buildRawPaths() {
        // LEFT
        leftPath.reset()
        leftPath.moveTo(0f, 25.921f)
        leftPath.lineTo(0f, 81.7508f)
        leftPath.lineTo(180f, 81.7508f)
        leftPath.cubicTo(179.752f, 71.2827f, 180f, 52.8389f, 179.752f, 52.8389f)
        leftPath.cubicTo(152.154f, 52.8389f, 143.503f, 37.8845f, 136.055f, 19.4407f)
        leftPath.cubicTo(130.097f, 4.68571f, 120.662f, 0f, 111.228f, 0f)
        leftPath.lineTo(27.3103f, 0f)
        leftPath.cubicTo(8.64f, 0f, 0f, 18.4438f, 0f, 25.921f)
        leftPath.close()

        // RIGHT
        rightPath.reset()
        rightPath.moveTo(360f, 25.921f)
        rightPath.lineTo(360f, 81.7508f)
        rightPath.lineTo(179f, 81.7508f)
        rightPath.cubicTo(179.25f, 71.2827f, 179f, 52.8389f, 179.25f, 52.8389f)
        rightPath.cubicTo(207.001f, 52.8389f, 215.699f, 37.8845f, 223.189f, 19.4407f)
        rightPath.cubicTo(229.181f, 4.68571f, 238.668f, 0f, 248.154f, 0f)
        rightPath.lineTo(332.538f, 0f)
        rightPath.cubicTo(351.312f, 0f, 360f, 18.4438f, 360f, 25.921f)
        rightPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(leftPath, shadowPaint)
        canvas.drawPath(rightPath, shadowPaint)
        canvas.drawPath(leftPath, barPaint)
        canvas.drawPath(rightPath, barPaint)
    }

    private fun resolveShadowColor(surfaceColor: Int): Int {
        val luminance = ColorUtils.calculateLuminance(surfaceColor)
        return if (luminance > 0.5f) {
            Color.argb(60, 0, 0, 0)
        } else {
            Color.argb(80, 255, 255, 255)
        }
    }

    private fun resolveAttrColor(attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, Color.WHITE)
        ta.recycle()
        return color
    }
}