package com.zys.mobilemap.control

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * 带高度上限的 RecyclerView：内容不足上限时按 wrap_content 自适应，
 * 超过上限时锁定为上限、由列表内部滚动。
 *
 * 用于 BottomSheet 弹层：弹层高度必须由内容驱动，才能形成可手势调整的档位
 * （见 [com.zys.mobilemap.ui.dialog.BaseBottomSheetDialog]）；但普通 RecyclerView 的
 * wrap_content 放在 LinearLayout 里时，拿到的是"父可用全高"的 AT_MOST 约束，
 * 并不扣除标题栏已占的部分，内容一多就会测得过高 —— 弹层被父布局限制到屏高后，
 * 列表底部那段被裁到屏幕外，滚到底也看不全。
 *
 * 这里显式给出上限，测量结果确定，不依赖父布局的权重分配行为。
 * 上限按屏高比例由宿主设置（见 [maxHeightPx]），不要写死像素值。
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /** 高度上限（像素）；<= 0 表示不限制，完全按 wrap_content 测量 */
    var maxHeightPx: Int = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val limitedHeightSpec = if (maxHeightPx > 0) {
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        } else {
            heightSpec
        }
        super.onMeasure(widthSpec, limitedHeightSpec)
    }
}
