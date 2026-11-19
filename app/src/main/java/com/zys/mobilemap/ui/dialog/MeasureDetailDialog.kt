package com.zys.mobilemap.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.zys.mobilemap.R
import java.util.Locale

/**
 * 测量/轨迹详情 BottomSheet：点击已保存的测量或轨迹后弹出。
 * 标题栏含名称 + 操作文字键（重命名/分享/删除/关闭），中间为样式编辑区（颜色色块 + 线宽步进），
 * 其下为「备注：内容」行（整行可点），底部仅保留「保存」按键。
 *
 * 颜色以 ARGB Int 存取（与 measurement 表 color 列一致）；
 * 保存经 [onSave] 写库并重载图层，删除经 [onDelete] 走宿主删除确认。
 * [onShare] 为空时分享键隐藏，仅轨迹详情传入时展示。
 * [onRename] 为空时不展示重命名键；改名落库成功后宿主按 tag 取回本弹层并调
 * [updateTitle] 就地刷新标题（不重建弹层，用户已改的色/线宽不丢）。
 * [onRemark] 为空（轨迹详情）时整行隐藏；备注框由本弹层弹出，确认后就地刷新本行并交宿主写库。
 */
class MeasureDetailDialog(
    private val title: String,
    private val typeText: String,
    private val geometryText: String?,
    /** 点测量无线要素，隐藏线宽步进 */
    private val widthEditable: Boolean,
    private val initialColor: Int,
    private val initialWidth: Int,
    /** 备注行初值（空串显示占位）；与 [onRemark] 成对传入 */
    private val initialRemark: String = "",
    private val onSave: ((color: Int, width: Int) -> Unit)?,
    private val onDelete: (() -> Unit)?,
    /** 分享回调（轨迹详情专用，导出 shp 数据包）；为空时不展示分享键 */
    private val onShare: (() -> Unit)? = null,
    /** 重命名回调；为空时不展示重命名键 */
    private val onRename: (() -> Unit)? = null,
    /** 备注保存回调（备注文本）；为空时底部备注行隐藏（仅测量详情传入，轨迹不传） */
    private val onRemark: ((remark: String) -> Unit)? = null
) : BaseBottomSheetDialog(R.layout.dialog_measure_detail) {

    private var color = initialColor
    private var lineWidth = initialWidth

    /** 弹层当前显示名：重命名后由 [updateTitle] 就地改写，重建弹层用的也是它 */
    private var currentTitle: String = title

    /** 标题栏名称控件：弹层存活期间可被 [updateTitle] 就地刷新；弹层未创建/已销毁时为 null */
    private var titleView: TextView? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        val sheet = sheetView ?: return dialog

        // 圆角背景与可手势调整的高度档位由基类统一配置（见 BaseBottomSheetDialog）
        val tv = sheet.findViewById<TextView>(R.id.measure_detail_tv_title)
        tv.text = currentTitle
        titleView = tv
        sheet.findViewById<TextView>(R.id.measure_detail_tv_close).setOnClickListener { dismiss() }

        // 标题栏操作键（文字键，回调为空的键隐藏）
        val btnRename = sheet.findViewById<TextView>(R.id.measure_detail_btn_rename)
        if (onRename != null) {
            btnRename.visibility = View.VISIBLE
            btnRename.setOnClickListener { onRename.invoke() }
        } else {
            btnRename.visibility = View.GONE
        }

        val btnShare = sheet.findViewById<TextView>(R.id.measure_detail_btn_share)
        if (onShare != null) {
            btnShare.visibility = View.VISIBLE
            btnShare.setOnClickListener {
                onShare.invoke()
                dismiss()
            }
        } else {
            btnShare.visibility = View.GONE
        }

        val btnDelete = sheet.findViewById<TextView>(R.id.measure_detail_btn_delete)
        if (onDelete != null) {
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener {
                onDelete.invoke()
                dismiss()
            }
        } else {
            btnDelete.visibility = View.GONE
        }

        // 基本信息
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
            val tvWidth = sheet.findViewById<TextView>(R.id.measure_detail_tv_width_value)
            // 步进统一经此函数限幅并回显，避免加/减两个监听各写一遍
            fun applyWidth(value: Int) {
                lineWidth = value.coerceIn(WIDTH_MIN, WIDTH_MAX)
                tvWidth.text = String.format(Locale.getDefault(), "%d", lineWidth)
            }
            applyWidth(lineWidth)
            sheet.findViewById<TextView>(R.id.measure_detail_btn_width_minus)
                .setOnClickListener { applyWidth(lineWidth - 1) }
            sheet.findViewById<TextView>(R.id.measure_detail_btn_width_plus)
                .setOnClickListener { applyWidth(lineWidth + 1) }
        }

        // 备注行（保存键上方）：整行可点多行备注框，确认后就地刷新本行并交宿主写库；
        // 备注框叠在本弹层之上，本弹层不关闭（用户已改的色/线宽不丢）
        val remarkRow = sheet.findViewById<View>(R.id.measure_detail_ll_remark)
        if (onRemark == null) {
            remarkRow.visibility = View.GONE
        } else {
            remarkRow.visibility = View.VISIBLE
            val tvRemark = sheet.findViewById<TextView>(R.id.measure_detail_tv_remark)
            var remarkText = initialRemark
            // 空态用占位灰字，避免和真备注混读
            fun renderRemark(text: String) {
                if (text.isEmpty()) {
                    tvRemark.text = REMARK_EMPTY
                    tvRemark.setTextColor(REMARK_EMPTY_COLOR.toColorInt())
                } else {
                    tvRemark.text = text
                    tvRemark.setTextColor(REMARK_TEXT_COLOR.toColorInt())
                }
            }
            renderRemark(remarkText)
            remarkRow.setOnClickListener {
                RemarkEditDialog.show(requireActivity(), "测量备注", remarkText) { saved ->
                    remarkText = saved
                    renderRemark(saved)
                    onRemark.invoke(saved)
                }
            }
        }

        // 底部保存按键
        sheet.findViewById<Button>(R.id.measure_detail_btn_save).setOnClickListener {
            onSave?.invoke(color, lineWidth)
            dismiss()
        }
        return dialog
    }

    companion object {
        private const val WIDTH_MIN = 1
        private const val WIDTH_MAX = 10

        /** 备注行的空态占位（同 PhotoSheetDialog） */
        private const val REMARK_EMPTY = "未填写"
        private const val REMARK_EMPTY_COLOR = "#999999"
        private const val REMARK_TEXT_COLOR = "#212121"
    }

    /**
     * 就地刷新标题栏名称（重命名后调用）：不重建弹层，避免用户已改的色/线宽丢失。
     * [currentTitle] 同步更新，弹层因配置变更重建时亦回显新名。
     */
    fun updateTitle(newTitle: String) {
        currentTitle = newTitle
        titleView?.text = newTitle
    }

    override fun onDestroyView() {
        // 视图销毁即释放控件引用，防止 updateTitle 落到旧视图树
        titleView = null
        super.onDestroyView()
    }
}
