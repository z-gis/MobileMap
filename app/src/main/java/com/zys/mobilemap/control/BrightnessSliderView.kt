package com.zys.mobilemap.control

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet

/**
 * 亮度（深度）滑条（移植自 ColorPicker-master 的 BrightnessSliderView）：
 * 轨道为黑 → 基色（当前色相/饱和度下的纯色），滑块位置即 HSV 的 V 分量。
 */
class BrightnessSliderView : ColorSliderView {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            super(context, attrs, defStyleAttr)

    override fun resolveValue(color: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[2]
    }

    override fun configurePaint(colorPaint: Paint) {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        hsv[2] = 0f
        val startColor = Color.HSVToColor(hsv)
        hsv[2] = 1f
        val endColor = Color.HSVToColor(hsv)
        colorPaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f, startColor, endColor, Shader.TileMode.CLAMP
        )
    }

    override fun assembleColor(): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        hsv[2] = currentValue
        return Color.HSVToColor(hsv)
    }
}
