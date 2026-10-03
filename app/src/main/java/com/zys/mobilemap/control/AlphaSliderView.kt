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
class AlphaSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ColorSliderView(context, attrs, defStyleAttr) {

    /** 棋盘格底衬画笔：深格 / 浅格交替，透明区域凭此观察透明程度 */
    private val checkerDarkPaint = Paint().apply { color = CHECKER_DARK_COLOR }
    private val checkerLightPaint = Paint().apply { color = Color.WHITE }

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
        return Color.HSVToColor((value * 255).toInt(), hsv)
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

    companion object {
        /** 棋盘格深格颜色（等同 #CFCFCF，直接给常量免去每次构造解析色串） */
        private const val CHECKER_DARK_COLOR = 0xFFCFCFCF.toInt()
    }
}
