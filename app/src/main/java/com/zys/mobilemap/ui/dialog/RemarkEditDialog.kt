package com.zys.mobilemap.ui.dialog

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog

/**
 * 多行备注输入对话框（照片点位 / 测量共用）。
 *
 * 与 [NameEditDialog] 的区别：多行输入、允许空（清空备注即存空串，不拦截）。
 * 确定即回调（已 trim，可为空），由各调用方自行保存并刷新。
 * 命名与鸿蒙端同名组件 `entry/src/main/ets/components/RemarkEditDialog.ets` 对齐。
 */
object RemarkEditDialog {

    /**
     * @param onConfirm 确定时回调（已 trim，允许空串）
     */
    fun show(
        activity: Activity,
        title: String,
        initial: String,
        onConfirm: (String) -> Unit
    ) {
        val edit = EditText(activity).apply {
            setText(initial)
            setSelection(text?.length ?: 0)
            hint = "请输入备注（可留空）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(MAX_REMARK_LENGTH))
        }
        val density = activity.resources.displayMetrics.density
        val container = FrameLayout(activity).apply {
            val horizontal = (PADDING_H_DP * density).toInt()
            val vertical = (PADDING_V_DP * density).toInt()
            setPadding(horizontal, vertical, horizontal, 0)
            addView(
                edit, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                onConfirm(edit.text.toString().trim())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 输入框容器内边距（dp） */
    private const val PADDING_H_DP = 20
    private const val PADDING_V_DP = 12

    /** 备注长度上限（与鸿蒙端 MAX_REMARK_LENGTH 同值） */
    private const val MAX_REMARK_LENGTH = 500
}