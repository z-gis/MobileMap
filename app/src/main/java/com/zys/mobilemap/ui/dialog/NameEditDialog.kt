package com.zys.mobilemap.ui.dialog

import android.app.Activity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog

/**
 * 单行名称输入对话框（轨迹/测量重命名、拍照分组新建与重命名共用）。
 *
 * 口径统一：确定时名称去首尾空白，为空则不回调（等同取消），由各调用方自行保存并刷新。
 * 命名与鸿蒙端同名组件 `entry/src/main/ets/components/NameEditDialog.ets` 对齐。
 */
object NameEditDialog {

    /**
     * @param hint 输入框占位文案（如「轨迹名称」「分组名称」）
     * @param onConfirm 确定且名称非空时回调（已 trim）
     */
    fun show(
        activity: Activity,
        title: String,
        initial: String,
        hint: String,
        onConfirm: (String) -> Unit
    ) {
        val edit = EditText(activity).apply {
            setText(initial)
            setSelection(text?.length ?: 0)
            isSingleLine = true
            this.hint = hint
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
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) onConfirm(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 输入框容器内边距（dp） */
    private const val PADDING_H_DP = 20
    private const val PADDING_V_DP = 12
}
