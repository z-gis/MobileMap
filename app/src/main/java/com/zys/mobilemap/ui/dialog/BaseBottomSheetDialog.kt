package com.zys.mobilemap.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.zys.mobilemap.R
import com.google.android.material.R as MaterialR

/**
 * BottomSheet 弹层基类：统一负责布局填充、圆角背景与可手势调整的高度档位，
 * 子类经 [sheetView] 取根视图绑定控件。
 * 使用方法：子类构造传入布局资源 id，再调用 `show()`。
 *
 * 高度行为由基类统一配置（见 [applyHeightSteps]），子类不要再覆盖 `setOnShowListener`：
 * 打开即展开态显示全部内容，下拉收到约四成屏高的收起态（地图重新可见），
 * 继续下拉关闭，上拉回到展开态。
 *
 * 子类布局契约：根布局与其内列表/网格的高度一律用 `wrap_content`（内容驱动），
 * 不要按行数算出固定像素高度写死，否则弹层高度被锁死、手势档位失效。
 */
open class BaseBottomSheetDialog(private val resource: Int) : BottomSheetDialogFragment() {

    /** 弹层根视图：[onCreateDialog] 中填充，子类于同一回调内取用 */
    protected var sheetView: View? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        val view = layoutInflater.inflate(resource, null)
        sheetView = view
        dialog.setContentView(view)

        // 圆角背景与高度档位须在 onShow 后配置：design_bottom_sheet 容器在 show 之前尚未就绪，
        // 内容实测高度也要等布局完成才有值
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(MaterialR.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.bg_bottom_sheet_rounded)
                view.post { applyHeightSteps(bottomSheet, view) }
            }
        }

        return dialog
    }

    /**
     * 按内容实测高度配置手势档位，须在布局完成后（post）调用。
     *
     * 收起态高度取屏高 [PEEK_HEIGHT_RATIO] 与内容实测高度的较小者，展开态为内容全高：
     * 两档拉开差距后，下拉先收到收起态、继续下拉才关闭，上拉回到展开态。
     * 内容高于屏幕时容器被父布局限制，超出部分由内容自身（列表/网格）滚动。
     */
    private fun applyHeightSteps(bottomSheet: FrameLayout, content: View) {
        val collapsedHeight = (resources.displayMetrics.heightPixels * PEEK_HEIGHT_RATIO).toInt()
        BottomSheetBehavior.from(bottomSheet).apply {
            isHideable = true
            isDraggable = true
            // 不能取 peekHeight = 内容全高：那会让收起态与展开态重合，
            // 下拉直接跳过收起态关闭弹层，手势无法在两档间调整高度
            peekHeight = if (content.height in 1..collapsedHeight) content.height else collapsedHeight
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    /**
     * 弹层内可滚动列表的高度上限（像素）：占屏高 [LIST_MAX_HEIGHT_RATIO]。
     *
     * 子类把它赋给 [com.zys.mobilemap.control.MaxHeightRecyclerView.maxHeightPx]：
     * 内容少时列表按 wrap_content 自适应，内容多时到上限后内部滚动，
     * 既不会把弹层撑到遮满全屏，也不会因超出屏高而裁掉列表底部。
     */
    protected fun listMaxHeightPx(): Int =
        (resources.displayMetrics.heightPixels * LIST_MAX_HEIGHT_RATIO).toInt()

    companion object {
        /** 收起态（peekHeight）高度占屏高比例：与展开态拉开差距才形成可手势切换的档位 */
        private const val PEEK_HEIGHT_RATIO = 0.4f

        /** 弹层内列表高度上限占屏高比例：留出标题栏与部分地图可见区域 */
        private const val LIST_MAX_HEIGHT_RATIO = 0.6f
    }
}
