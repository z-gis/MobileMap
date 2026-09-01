package com.zys.mobilemap.control

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * 颜色选取组合视图（纵向 LinearLayout，参考 ColorPicker-master 的 ColorPickerView 结构）：
 * 主色板 [ColorPanelView]（横向色相 × 纵向饱和度，十字选标）负责颜色设置；
 * 下方两条滑条分别负责深度（亮度 [BrightnessSliderView]）与透明度（[AlphaSliderView]）。
 * 主色板按内容宽度取正方形（尽量占满屏幕宽），颜色变化通过 [onColorChangedListener] 回调。
 */
class ColorPickerView : LinearLayout {

    var onColorChangedListener: ((Int) -> Unit)? = null

    private val colorPanel: ColorPanelView
    private val brightnessSlider: BrightnessSliderView
    private val alphaSlider: AlphaSliderView

    private val sliderMarginPx: Int
    private val sliderHeightPx: Int

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            super(context, attrs, defStyleAttr) {
        orientation = VERTICAL
        val density = resources.displayMetrics.density
        val paddingPx = (8 * density).toInt()
        sliderMarginPx = (16 * density).toInt()
        sliderHeightPx = (24 * density).toInt()
        setPadding(paddingPx, paddingPx, paddingPx, paddingPx)

        colorPanel = ColorPanelView(context)
        brightnessSlider = BrightnessSliderView(context)
        alphaSlider = AlphaSliderView(context)

        // 主色板尺寸在 onMeasure 中按内容宽度确定为正方形
        addView(colorPanel, LayoutParams(0, 0))
        addView(
            brightnessSlider,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sliderHeightPx)
                .apply { topMargin = sliderMarginPx }
        )
        addView(
            alphaSlider,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sliderHeightPx)
                .apply { topMargin = sliderMarginPx }
        )

        // 主色板（色相、饱和度）变化 -> 重建两条滑条渐变 -> 输出最终颜色
        colorPanel.onColorSelected = { base ->
            brightnessSlider.updateBaseColor(base)
            alphaSlider.updateBaseColor(brightnessSlider.color)
            onColorChangedListener?.invoke(alphaSlider.color)
        }
        brightnessSlider.onColorChanged = { color ->
            alphaSlider.updateBaseColor(color)
            onColorChangedListener?.invoke(alphaSlider.color)
        }
        alphaSlider.onColorChanged = { color ->
            onColorChangedListener?.invoke(color)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 主色板为内容宽度的正方形，尽量占满屏幕宽
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val side = width - paddingLeft - paddingRight
        (colorPanel.layoutParams as LayoutParams).let {
            it.width = side
            it.height = side
        }
        val height = side + paddingTop + paddingBottom + 2 * (sliderMarginPx + sliderHeightPx)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    /** 设置当前颜色（保留透明度，不触发回调） */
    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        colorPanel.setSelection(hsv[0], hsv[1])
        brightnessSlider.updateBaseColor(Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
        brightnessSlider.setValue(hsv[2])
        alphaSlider.updateBaseColor(brightnessSlider.color)
        alphaSlider.setValue(Color.alpha(color) / 255f)
    }

    /** 获取当前选中颜色（含透明度） */
    fun getColor(): Int = alphaSlider.color
}
