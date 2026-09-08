package com.zys.mobilemap.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 颜色设置主面板：横向为色相（左红→右红一周），纵向为饱和度（上纯色→下白），
 * 选标为十字线 + 圆圈（参考 ColorPicker-master 的色轮选标样式）。
 * 拖动选标选择色相与饱和度，回调 [onColorSelected] 输出基色（亮度 1、不透明），
 * 亮度与透明度由 [BrightnessSliderView]、[AlphaSliderView] 两条滑条分别控制。
 */
class ColorPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 用户拖动选标回调：基色（亮度 1、不透明） */
    var onColorSelected: ((Int) -> Unit)? = null

    private var hue = 0f
    private var saturation = 1f

    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 选标半径（像素）：构造时按屏幕密度换算，保证各机型视觉一致 */
    private val selectorRadiusPx: Float

    init {
        val density = resources.displayMetrics.density
        selectorRadiusPx = SELECTOR_RADIUS_DP * density
        selectorPaint.style = Paint.Style.STROKE
        selectorPaint.strokeWidth = SELECTOR_STROKE_WIDTH_DP * density
        selectorPaint.color = Color.BLACK
    }

    /** 设置选标位置（程序化设置，不回调） */
    fun setSelection(hue: Float, saturation: Float) {
        this.hue = hue.coerceIn(0f, 360f)
        this.saturation = saturation.coerceIn(0f, 1f)
        invalidate()
    }

    /** 当前基色（亮度 1、不透明）：仅用于向外回调，最终色由两条滑条继续叠加亮度/透明度 */
    private val baseColor: Int
        get() = Color.HSVToColor(floatArrayOf(hue, saturation, 1f))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 横向色相谱（满饱和满亮度）
        huePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f, HUE_COLORS, null, Shader.TileMode.CLAMP
        )
        // 纵向透明→白色叠加：上端饱和度 1（纯色，深色），下端饱和度 0（白，浅色）
        satPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(), 0x00FFFFFF, Color.WHITE, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), huePaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), satPaint)

        // 十字选标：位置与取值同向映射（右→色相增大，上→饱和度增大）
        val cx = hue / 360f * width
        val cy = (1f - saturation) * height
        canvas.drawLine(cx - selectorRadiusPx, cy, cx + selectorRadiusPx, cy, selectorPaint)
        canvas.drawLine(cx, cy - selectorRadiusPx, cx, cy + selectorRadiusPx, selectorPaint)
        canvas.drawCircle(cx, cy, selectorRadiusPx * 0.66f, selectorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                applyTouch(event)
            }
            MotionEvent.ACTION_MOVE -> applyTouch(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun applyTouch(event: MotionEvent) {
        if (width <= 0 || height <= 0) return
        hue = (event.x / width * 360f).coerceIn(0f, 360f)
        saturation = (1f - event.y / height).coerceIn(0f, 1f)
        invalidate()
        onColorSelected?.invoke(baseColor)
    }

    companion object {
        /** 选标半径 / 线宽（dp） */
        private const val SELECTOR_RADIUS_DP = 9f
        private const val SELECTOR_STROKE_WIDTH_DP = 2.5f

        /** 横向色相谱取样点：红→黄→绿→青→蓝→品红→红（首尾同为红以接成一周） */
        private val HUE_COLORS = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
            Color.BLUE, Color.MAGENTA, Color.RED
        )
    }
}
