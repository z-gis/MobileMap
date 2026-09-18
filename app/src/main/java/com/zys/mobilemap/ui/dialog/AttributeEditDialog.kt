package com.zys.mobilemap.ui.dialog

import android.app.Activity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog

/**
 * 属性编辑对话框（调查模式）：单字段值编辑，保存后回调宿主写回矢量源文件。
 *
 * 由 [FeatureDetailDialog] 属性页点击值一列时弹出；宿主（MainActivity）在 onSave 中
 * 调 GdalVectorReader.updateAttributes 写回并刷新 renderable 的 FEATURE_ATTRS_KEY。
 */
object AttributeEditDialog {

    /**
     * 弹出属性编辑对话框。
     * @param key 属性字段名（作为标题）
     * @param initialValue 当前值
     * @param onSave 保存回调（新值），由宿主负责写回与 UI 刷新
     */
    fun show(
        activity: Activity,
        key: String,
        initialValue: String,
        onSave: (String) -> Unit
    ) {
        val edit = EditText(activity).apply {
            setText(initialValue)
            setSelection(text?.length ?: 0)
            isSingleLine = false
            hint = key
        }
        val container = FrameLayout(activity).apply {
            val horizontal = (20 * activity.resources.displayMetrics.density).toInt()
            val vertical = (12 * activity.resources.displayMetrics.density).toInt()
            setPadding(horizontal, vertical, horizontal, 0)
            addView(
                edit, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(activity)
            .setTitle("编辑 $key")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                onSave(edit.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
