package com.zys.mobilemap.ui.dialog

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import com.zys.mobilemap.R
import com.zys.mobilemap.media.MediaGroup
import com.zys.mobilemap.media.MediaPlacemark
import com.zys.mobilemap.media.MediaStore

/**
 * 拍照标识编辑对话框（复用）：一处完成"修改名称 + 选择所属分组"。
 *
 * - 拍照标识管理列表点击点位行 → 编辑既有点位；
 * - 拍照会话点"完成" → 复用本对话框命名并归组。
 *
 * 分组下拉含"未分组"与全部已建分组，右侧"+"可即时新建分组并选中。
 * 确定后名称与分组归属一并写库（[MediaStore.updatePlacemarkName]/[MediaStore.updatePlacemarkGroup]），
 * 再回调 [onSaved] 由宿主刷新地图标注。
 */
object MediaEditDialog {

    /**
     * 弹出点位编辑对话框。
     * @param title 对话框标题（管理列表用"编辑拍照标识"，拍照完成用"完成拍照"）
     * @param onSaved 确定保存后回调（名称, 分组ID）
     */
    fun show(
        activity: Activity,
        placemark: MediaPlacemark,
        title: String = "编辑拍照标识",
        onSaved: (name: String, groupId: Long) -> Unit
    ) {
        val store = MediaStore.getInstance(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_media_edit, null)
        val nameEdit = view.findViewById<EditText>(R.id.media_edit_name)
        val spinner = view.findViewById<Spinner>(R.id.media_edit_group)
        val addButton = view.findViewById<ImageView>(R.id.media_edit_group_add)

        nameEdit.setText(placemark.name)
        nameEdit.setSelection(nameEdit.text?.length ?: 0)

        var groups: List<MediaGroup> = store.loadGroups()
        var selectedGroupId = placemark.groupId

        val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, groupLabels(groups))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(indexOfGroup(groups, selectedGroupId))

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                selectedGroupId = groupIdAt(groups, position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        addButton.setOnClickListener {
            promptGroupName(activity, "新建分组", "") { name ->
                val newId = store.insertGroup(name, System.currentTimeMillis())
                groups = store.loadGroups()
                adapter.clear()
                adapter.addAll(groupLabels(groups))
                adapter.notifyDataSetChanged()
                selectedGroupId = newId
                spinner.setSelection(indexOfGroup(groups, newId))
            }
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val newName = nameEdit.text.toString().trim().ifEmpty { placemark.name }
                store.updatePlacemarkName(placemark.id, newName)
                store.updatePlacemarkGroup(placemark.id, selectedGroupId)
                onSaved(newName, selectedGroupId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 分组名称输入框（新建/重命名分组复用）。空名称不回调。
     */
    fun promptGroupName(
        activity: Activity,
        title: String,
        initial: String,
        onConfirm: (String) -> Unit
    ) {
        val edit = EditText(activity).apply {
            setText(initial)
            setSelection(text?.length ?: 0)
            isSingleLine = true
            hint = "分组名称"
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
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) onConfirm(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Spinner 选项文本：首项"未分组" + 各分组名。 */
    private fun groupLabels(groups: List<MediaGroup>): List<String> =
        listOf("未分组") + groups.map { it.name }

    /** Spinner 位置 → 分组ID（位置 0 为未分组）。 */
    private fun groupIdAt(groups: List<MediaGroup>, position: Int): Long =
        if (position <= 0) MediaStore.UNGROUPED_ID else groups[position - 1].id

    /** 分组ID → Spinner 位置（未分组或找不到时回 0）。 */
    private fun indexOfGroup(groups: List<MediaGroup>, groupId: Long): Int {
        if (groupId == MediaStore.UNGROUPED_ID) return 0
        val idx = groups.indexOfFirst { it.id == groupId }
        return if (idx >= 0) idx + 1 else 0
    }
}
