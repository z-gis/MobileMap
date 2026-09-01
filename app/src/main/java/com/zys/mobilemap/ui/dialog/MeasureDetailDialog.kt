package com.zys.mobilemap.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.zys.mobilemap.R
import com.google.android.material.R as MaterialR

/**
 * 测量详情 BottomSheet：点击已保存测量几何后弹出（与要素详情弹层样式一致）。
 * 标题为测量名称，中间为样式编辑区（颜色色块 + 线宽步进），底部按键：缩放至/删除/保存。
 *
 * 颜色以 ARGB Int 存取（与 measurement 表 color 列一致）；
 * 保存经 [onSave] 写库并重载测量图层，删除经 [onDelete] 走宿主删除确认。
 */
class MeasureDetailDialog(
    private val title: String,
    private val typeText: String,
    private val geometryText: String?,
    /** 点测量无线要素，隐藏线宽步进 */
    private val widthEditable: Boolean,
    private val initialColor: Int,
    private val initialWidth: Int,
    private val onZoom: (() -> Unit)?,
    private val onSave: ((color: Int, width: Int) -> Unit)?,
    private val onDelete: (() -> Unit)?
) : BaseBottomSheetDialog(R.layout.dialog_measure_detail) {

    private var color = initialColor
    private var lineWidth = initialWidth

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        val sheet = sheetView ?: return dialog

        // 覆盖基类 onShowListener：圆角背景 + 初始展开态（内容全量显示）
        dialog.setOnShowListener { d ->
            val bottomSheet = (d as BottomSheetDialog)
                .findViewById<FrameLayout>(MaterialR.id.design_bottom_sheet)
            bottomSheet?.let { bs ->
                bs.setBackgroundResource(R.drawable.bg_bottom_sheet_rounded)
                sheet.post {
                    BottomSheetBehavior.from(bs).apply {
                        peekHeight = sheet.height
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
            }
        }

        sheet.findViewById<TextView>(R.id.measure_detail_tv_title).text = title
        sheet.findViewById<TextView>(R.id.measure_detail_tv_close).setOnClickListener { dismiss() }

        sheet.findViewById<TextView>(R.id.measure_detail_tv_type).text = typeText
        val tvGeometry = sheet.findViewById<TextView>(R.id.measure_detail_tv_geometry)
        if (geometryText.isNullOrEmpty()) {
            tvGeometry.visibility = View.GONE
        } else {
            tvGeometry.text = geometryText
        }

        // 样式区：颜色色块（选色器）+ 线宽步进
        val ivColor = sheet.findViewById<ImageView>(R.id.measure_detail_iv_color)
        ivColor.setBackgroundColor(color)
        ivColor.setOnClickListener {
            ColorPickerDialog(requireContext(), color) { picked ->
                color = picked
                ivColor.setBackgroundColor(picked)
            }.create().show()
        }

        if (!widthEditable) {
            sheet.findViewById<View>(R.id.measure_detail_ll_width_row).visibility = View.GONE
        } else {
            lineWidth = lineWidth.coerceIn(WIDTH_MIN, WIDTH_MAX)
            val tvWidth = sheet.findViewById<TextView>(R.id.measure_detail_tv_width_value)
            tvWidth.text = lineWidth.toString()
            sheet.findViewById<TextView>(R.id.measure_detail_btn_width_minus).setOnClickListener {
                lineWidth = (lineWidth - 1).coerceIn(WIDTH_MIN, WIDTH_MAX)
                tvWidth.text = lineWidth.toString()
            }
            sheet.findViewById<TextView>(R.id.measure_detail_btn_width_plus).setOnClickListener {
                lineWidth = (lineWidth + 1).coerceIn(WIDTH_MIN, WIDTH_MAX)
                tvWidth.text = lineWidth.toString()
            }
        }

        // 底部按键：缩放至（关闭弹层后移动相机）/ 删除（宿主确认）/ 保存
        sheet.findViewById<Button>(R.id.measure_detail_btn_zoom).setOnClickListener {
            onZoom?.invoke()
            dismiss()
        }
        sheet.findViewById<Button>(R.id.measure_detail_btn_delete).setOnClickListener {
            onDelete?.invoke()
            dismiss()
        }
        sheet.findViewById<Button>(R.id.measure_detail_btn_save).setOnClickListener {
            onSave?.invoke(color, lineWidth)
            dismiss()
        }
        return dialog
    }

    companion object {
        private const val WIDTH_MIN = 1
        private const val WIDTH_MAX = 10
    }
}
