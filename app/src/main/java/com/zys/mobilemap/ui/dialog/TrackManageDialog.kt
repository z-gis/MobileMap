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
import com.zys.mobilemap.db.MapDataStore
import com.zys.mobilemap.db.TrackRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轨迹管理对话框（轨迹菜单"管理"入口，参照拍照标识管理）：
 * 列出数据库全部轨迹，支持重命名、可见性开关（隐藏后地图不渲染）、
 * 缩放至轨迹、删除。数据变更后经回调刷新轨迹图层。
 */
object TrackManageDialog {

    fun show(activity: Activity, onChanged: () -> Unit, onZoomTo: (TrackRecord) -> Unit) {
        val store = MapDataStore.getInstance(activity)
        val tracks = store.loadTracks().toMutableList()

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_track_manage, null)
        val emptyView = view.findViewById<TextView>(R.id.track_manage_empty)
        val list = view.findViewById<RecyclerView>(R.id.track_manage_list)
        list.layoutManager = LinearLayoutManager(activity)

        val refreshEmpty = {
            emptyView.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        }
        refreshEmpty()

        lateinit var adapter: TrackAdapter
        var dialog: AlertDialog? = null
        adapter = TrackAdapter(
            tracks,
            onRename = { track ->
                showRenameDialog(activity, track) { newName ->
                    store.updateTrackName(track.id, newName)
                    adapter.notifyDataSetChanged()
                    onChanged()
                }
            },
            onVisibleChanged = { track, visible ->
                store.updateTrackVisible(track.id, visible)
                onChanged()
            },
            onZoomTo = { track ->
                dialog?.dismiss()
                onZoomTo(track)
            },
            onDelete = { track ->
                showDeleteConfirmDialog(activity, track) {
                    store.deleteTrack(track.id)
                    tracks.remove(track)
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
    private fun showRenameDialog(activity: Activity, track: TrackRecord, onRenamed: (String) -> Unit) {
        val edit = EditText(activity).apply {
            setText(track.name)
            isSingleLine = true
        }
        val container = FrameLayout(activity).apply {
            val density = activity.resources.displayMetrics.density
            val horizontal = (PADDING_H_DP * density).toInt()
            val vertical = (PADDING_V_DP * density).toInt()
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

    /** 删除确认：轨迹记录及其坐标点将一并删除。 */
    private fun showDeleteConfirmDialog(activity: Activity, track: TrackRecord, onConfirmed: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("删除轨迹")
            .setMessage("删除后该轨迹及其全部坐标点将一并删除，是否继续？")
            .setPositiveButton("删除") { _, _ -> onConfirmed() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 轨迹列表适配器：名称/副标题（时间·点数）+ 可见性开关 + 缩放/删除按钮。 */
    private class TrackAdapter(
        private val tracks: List<TrackRecord>,
        private val onRename: (TrackRecord) -> Unit,
        private val onVisibleChanged: (TrackRecord, Boolean) -> Unit,
        private val onZoomTo: (TrackRecord) -> Unit,
        private val onDelete: (TrackRecord) -> Unit
    ) : RecyclerView.Adapter<TrackAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.item_track_name)
            val subtitle: TextView = view.findViewById(R.id.item_track_subtitle)
            val visibleSwitch: SwitchCompat = view.findViewById(R.id.item_track_visible)
            val zoomButton: ImageView = view.findViewById(R.id.item_track_zoom)
            val deleteButton: ImageView = view.findViewById(R.id.item_track_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_track, parent, false))

        override fun getItemCount(): Int = tracks.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val track = tracks[position]
            holder.name.text = track.name
            holder.subtitle.text = "${formatTime(track.createdTime)}　·　${track.points.size}个点"

            // 先设状态再挂监听，避免回显触发写库
            holder.visibleSwitch.setOnCheckedChangeListener(null)
            holder.visibleSwitch.isChecked = track.visible
            holder.visibleSwitch.setOnCheckedChangeListener { _, isChecked ->
                onVisibleChanged(track, isChecked)
            }

            holder.itemView.setOnClickListener { onRename(track) }
            holder.zoomButton.setOnClickListener { onZoomTo(track) }
            holder.deleteButton.setOnClickListener { onDelete(track) }
        }

        /** 副标题时间格式；onBindViewHolder 恒在主线程，可复用同一实例（SimpleDateFormat 非线程安全） */
        private val timeFormat = SimpleDateFormat(TIME_PATTERN, Locale.getDefault())

        private fun formatTime(time: Long): String = timeFormat.format(Date(time))
    }

    /** 重命名对话框输入框的左右/上内边距（dp） */
    private const val PADDING_H_DP = 16
    private const val PADDING_V_DP = 8

    /** 轨迹创建时间的副标题格式 */
    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm"
}
