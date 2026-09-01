package com.zys.mobilemap.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 颜色滑条基类（移植自 ColorPicker-master 的 ColorSliderView）：
 * 横向渐变轨道 + 顶部三角滑块。子类实现渐变配置（[configurePaint]）、
 * 数值解析（[resolveValue]）与颜色组装（[assembleColor]）。
 * 仅用户拖动触发 [onColorChanged] 回调，程序化设置不回调（避免联动死循环）。
 */
abstract class ColorSliderView : View {

    /** 用户拖动产生的颜色回调 */
    var onColorChanged: ((Int) -> Unit)? = null

    protected var baseColor: Int = Color.WHITE
    protected var currentValue: Float = 1f

    private val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#BDBDBD")
    }
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    private val selectorPath = Path()
    private val currentSelectorPath = Path()
    private var selectorSize = 0f

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            super(context, attrs, defStyleAttr)

    /** 当前组装出的颜色 */
    val color: Int get() = assembleColor()

    /** 更新基色（重建渐变，滑块位置不变，不回调） */
    fun updateBaseColor(color: Int) {
        baseColor = color
        if (width > 0) configurePaint(colorPaint)
        invalidate()
    }

    /** 直接设置滑块位置 0~1（不回调） */
    fun setValue(value: Float) {
        currentValue = value.coerceIn(0f, 1f)
        invalidate()
    }

    /** 从颜色解析滑块位置（不回调） */
    fun setValueFromColor(color: Int) {
        currentValue = resolveValue(color).coerceIn(0f, 1f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        configurePaint(colorPaint)
        selectorPath.reset()
        selectorSize = h * 0.25f
        selectorPath.moveTo(0f, 0f)
        selectorPath.lineTo(selectorSize * 2, 0f)
        selectorPath.lineTo(selectorSize, selectorSize)
        selectorPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        val left = selectorSize
        val top = selectorSize
        val right = width - selectorSize
        val bottom = height.toFloat()
        drawTrackUnderlay(canvas, left, top, right, bottom)
        canvas.drawRect(left, top, right, bottom, colorPaint)
        canvas.drawRect(left, top, right, bottom, borderPaint)
        selectorPath.offset(currentValue * (width - 2 * selectorSize), 0f, currentSelectorPath)
        canvas.drawPath(currentSelectorPath, selectorPaint)
    }

    /** 轨道底衬（可选）：透明度滑条用棋盘格显示透明效果 */
    protected open fun drawTrackUnderlay(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float
    ) {
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
        val left = selectorSize
        val right = width - selectorSize
        if (right <= left) return
        val x = event.x.coerceIn(left, right)
        currentValue = (x - left) / (right - left)
        invalidate()
        onColorChanged?.invoke(assembleColor())
    }

    /** 从颜色解析滑块数值 0~1 */
    protected abstract fun resolveValue(color: Int): Float

    /** 根据基色构建轨道渐变 */
    protected abstract fun configurePaint(colorPaint: Paint)

    /** 用基色与当前滑块数值组装输出颜色 */
    protected abstract fun assembleColor(): Int
}
