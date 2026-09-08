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
class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onColorChangedListener: ((Int) -> Unit)? = null

    private val colorPanel: ColorPanelView
    private val brightnessSlider: BrightnessSliderView
    private val alphaSlider: AlphaSliderView

    private val sliderMarginPx: Int
    private val sliderHeightPx: Int

    init {
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
        // （局部量不叫 width/height：否则会遮蔽下面 apply 里 LayoutParams 的同名属性）
        val totalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val side = totalWidth - paddingLeft - paddingRight
        (colorPanel.layoutParams as LayoutParams).apply {
            width = side
            height = side
        }
        val totalHeight = side + paddingTop + paddingBottom + 2 * (sliderMarginPx + sliderHeightPx)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(totalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY)
        )
    }

    /**
     * 当前选中颜色（含透明度）。
     * 赋值时同步主色板选标与两条滑条位置，不触发 [onColorChangedListener]（用于回显初值）。
     */
    var color: Int
        get() = alphaSlider.color
        set(value) {
            val hsv = FloatArray(3)
            Color.colorToHSV(value, hsv)
            colorPanel.setSelection(hsv[0], hsv[1])
            brightnessSlider.updateBaseColor(Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
            brightnessSlider.value = hsv[2]
            alphaSlider.updateBaseColor(brightnessSlider.color)
            alphaSlider.value = Color.alpha(value) / 255f
        }
}
