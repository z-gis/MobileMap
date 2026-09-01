package com.zys.mobilemap.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet

/**
 * 透明度滑条（移植自 ColorPicker-master 的 AlphaSliderView）：
 * 轨道为全透明 → 基色（不透明），滑块位置即 alpha 分量；
 * 轨道底衬绘制棋盘格以便观察透明程度。
 */
class AlphaSliderView : ColorSliderView {

    private val checkerDarkPaint = Paint().apply { color = Color.parseColor("#CFCFCF") }
    private val checkerLightPaint = Paint().apply { color = Color.WHITE }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            super(context, attrs, defStyleAttr)

    override fun resolveValue(color: Int): Float = Color.alpha(color) / 255f

    override fun configurePaint(colorPaint: Paint) {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        colorPaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            Color.HSVToColor(0, hsv), Color.HSVToColor(255, hsv), Shader.TileMode.CLAMP
        )
    }

    override fun assembleColor(): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        return Color.HSVToColor((currentValue * 255).toInt(), hsv)
    }

    override fun drawTrackUnderlay(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float
    ) {
        // 棋盘格底衬：透明区域可见灰白格子
        val size = bottom - top
        var x = left
        var dark = false
        while (x < right) {
            val end = minOf(x + size, right)
            canvas.drawRect(x, top, end, bottom, if (dark) checkerDarkPaint else checkerLightPaint)
            x = end
            dark = !dark
        }
    }
}
