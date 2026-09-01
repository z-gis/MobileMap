package com.zys.mobilemap.ui.dialog

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zys.mobilemap.R
import com.zys.mobilemap.media.MediaPlacemark
import com.zys.mobilemap.media.MediaStore
import com.zys.mobilemap.util.AppDirectories
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拍照标识管理对话框（拍照菜单"设置"入口）：
 * 列出数据库全部拍照点位，支持重命名、可见性开关（隐藏后地图不渲染）、
 * 缩放至点位、删除（连同附件文件一并删除）。数据变更后经回调刷新媒体标注图层。
 */
object MediaManageDialog {

    fun show(activity: Activity, onChanged: () -> Unit, onZoomTo: (MediaPlacemark) -> Unit) {
        val store = MediaStore.getInstance(activity)
        val placemarks = store.loadPlacemarks().toMutableList()

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_media_manage, null)
        val emptyView = view.findViewById<TextView>(R.id.media_manage_empty)
        val list = view.findViewById<RecyclerView>(R.id.media_manage_list)
        list.layoutManager = LinearLayoutManager(activity)

        val refreshEmpty = {
            emptyView.visibility = if (placemarks.isEmpty()) View.VISIBLE else View.GONE
        }
        refreshEmpty()

        lateinit var adapter: PlacemarkAdapter
        var dialog: AlertDialog? = null
        adapter = PlacemarkAdapter(
            placemarks,
            onRename = { placemark ->
                showRenameDialog(activity, placemark) { newName ->
                    store.updatePlacemarkName(placemark.id, newName)
                    placemark.name = newName
                    // 同步刷新列表行显示（地图标签经 onChanged 重载图层生效）
                    adapter.notifyDataSetChanged()
                    onChanged()
                }
            },
            onVisibleChanged = { placemark, visible ->
                store.updatePlacemarkVisible(placemark.id, visible)
                onChanged()
            },
            onZoomTo = { placemark ->
                dialog?.dismiss()
                onZoomTo(placemark)
            },
            onDelete = { placemark ->
                showDeleteConfirmDialog(activity, placemark) {
                    store.deletePlacemark(placemark.id)
                    File(AppDirectories.getMediaDir(activity), placemark.id.toString())
                        .deleteRecursively()
                    placemarks.remove(placemark)
                    adapter.notifyDataSetChanged()
                    refreshEmpty()
                    onChanged()
                }
            }
        )
        list.adapter = adapter

        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 重命名对话框：预填当前名称，空名称不保存。 */
    private fun showRenameDialog(activity: Activity, placemark: MediaPlacemark, onRenamed: (String) -> Unit) {
        val edit = EditText(activity).apply {
            setText(placemark.name)
            isSingleLine = true
        }
        val container = FrameLayout(activity).apply {
            val horizontal = (16 * activity.resources.displayMetrics.density).toInt()
            val vertical = (8 * activity.resources.displayMetrics.density).toInt()
            setPadding(horizontal, vertical, horizontal, 0)
            addView(edit, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(activity)
            .setTitle("修改名称")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isNotEmpty()) {
                    onRenamed(newName)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 删除确认：点位记录与附件文件将一并删除。 */
    private fun showDeleteConfirmDialog(activity: Activity, placemark: MediaPlacemark, onConfirmed: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("删除拍照标识")
            .setMessage("删除后该点位及其全部照片文件将一并删除，是否继续？")
            .setPositiveButton("删除") { _, _ -> onConfirmed() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 点位列表适配器：名称/副标题（时间·照片数）+ 可见性开关 + 缩放/删除按钮。 */
    private class PlacemarkAdapter(
        private val placemarks: List<MediaPlacemark>,
        private val onRename: (MediaPlacemark) -> Unit,
        private val onVisibleChanged: (MediaPlacemark, Boolean) -> Unit,
        private val onZoomTo: (MediaPlacemark) -> Unit,
        private val onDelete: (MediaPlacemark) -> Unit
    ) : RecyclerView.Adapter<PlacemarkAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.item_media_name)
            val subtitle: TextView = view.findViewById(R.id.item_media_subtitle)
            val visibleSwitch: SwitchCompat = view.findViewById(R.id.item_media_visible)
            val zoomButton: ImageView = view.findViewById(R.id.item_media_zoom)
            val deleteButton: ImageView = view.findViewById(R.id.item_media_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_placemark, parent, false))

        override fun getItemCount(): Int = placemarks.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val placemark = placemarks[position]
            holder.name.text = placemark.name
            val count = placemark.attachments.size
            holder.subtitle.text = formatTime(placemark.createdTime) +
                    if (count > 0) "　·　${count}张照片" else "　·　无照片"

            // 先设状态再挂监听，避免回显触发写库
            holder.visibleSwitch.setOnCheckedChangeListener(null)
            holder.visibleSwitch.isChecked = placemark.visible
            holder.visibleSwitch.setOnCheckedChangeListener { _, isChecked ->
                onVisibleChanged(placemark, isChecked)
            }

            holder.itemView.setOnClickListener { onRename(placemark) }
            holder.zoomButton.setOnClickListener { onZoomTo(placemark) }
            holder.deleteButton.setOnClickListener { onDelete(placemark) }
        }

        private fun formatTime(time: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }
}
