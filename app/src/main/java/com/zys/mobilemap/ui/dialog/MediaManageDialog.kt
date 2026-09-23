package com.zys.mobilemap.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zys.mobilemap.R
import com.zys.mobilemap.control.MaxHeightRecyclerView
import com.zys.mobilemap.media.MediaGroup
import com.zys.mobilemap.media.MediaPackage
import com.zys.mobilemap.media.MediaPlacemark
import com.zys.mobilemap.media.MediaStore
import com.zys.mobilemap.util.AppDirectories
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 采集数据管理 BottomSheet（拍照菜单"管理"入口）：
 * 按分组分区列出全部拍照点位，分组头（名称/数量/整组可见性开关/重命名/删除/折叠）
 * + 组内点位行（点击编辑名称与分组、可见性开关、缩放至、删除），含"未分组"虚拟组；
 * 标题栏"新建分组"。数据变更后经 [onChanged] 回调刷新媒体标注图层。
 *
 * 分组可见性开关直接批量写入组内各点位的 visible（渲染层只看点位自身 visible）。
 *
 * 回调经构造参数传入（同 [FeatureDetailDialog]）：宿主界面（MainActivity）已声明 configChanges，
 * 旋转不重建，故回调不会因配置变更丢失。
 */
class MediaManageDialog(
    private val onChanged: () -> Unit,
    private val onZoomTo: (MediaPlacemark) -> Unit
) : BaseBottomSheetDialog(R.layout.dialog_media_manage) {

    private lateinit var store: MediaStore
    private lateinit var listView: MaxHeightRecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: GroupAdapter

    /** 全部分组（真实分组，未分组为虚拟组不在此列） */
    private val groups = mutableListOf<MediaGroup>()

    /** 全部点位 */
    private val placemarks = mutableListOf<MediaPlacemark>()

    /** 折叠的分组 id 集合（含 [MediaStore.UNGROUPED_ID]） */
    private val collapsed = mutableSetOf<Long>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        val sheet = sheetView ?: return dialog
        store = MediaStore.getInstance(requireContext())

        emptyView = sheet.findViewById(R.id.media_manage_empty)
        listView = sheet.findViewById(R.id.media_manage_list)
        listView.layoutManager = LinearLayoutManager(requireContext())
        // 列表高度须由内容驱动（wrap_content）才能形成手势档位，但必须给上限：
        // 无上限时点位过多会测得超过屏高，弹层被父布局截断后列表底部滚不到
        listView.maxHeightPx = listMaxHeightPx()

        sheet.findViewById<ImageView>(R.id.media_manage_add_group).setOnClickListener {
            MediaEditDialog.promptGroupName(requireActivity(), "新建分组", "") { name ->
                // promptGroupName 只负责收集名称，写库由调用方完成（与点位编辑对话框的“+”一致）；
                // 漏掉 insertGroup 会导致点确定后库里根本没新分组，reloadData 重读自然毫无变化
                store.insertGroup(name, System.currentTimeMillis())
                reloadData()
            }
        }

        adapter = GroupAdapter()
        listView.adapter = adapter
        reloadData()

        // 圆角背景与可手势调整的高度档位由基类统一配置（见 BaseBottomSheetDialog）
        return dialog
    }

    /** 从库重载分组与点位，重建扁平化行并刷新空态。 */
    private fun reloadData() {
        groups.clear()
        groups.addAll(store.loadGroups())
        placemarks.clear()
        placemarks.addAll(store.loadPlacemarks())

        val rows = buildRows()
        adapter.submit(rows)

        val hasData = placemarks.isNotEmpty() || groups.isNotEmpty()
        emptyView.visibility = if (hasData) View.GONE else View.VISIBLE
        listView.visibility = if (hasData) View.VISIBLE else View.GONE
    }

    /**
     * 构建扁平化行序列：真实分组（按创建时间升序）在前，"未分组"虚拟组在后；
     * 每组先加分组头，未折叠时追加组内点位行。
     */
    private fun buildRows(): List<Any> {
        val rows = mutableListOf<Any>()
        for (group in groups) {
            val members = placemarks.filter { it.groupId == group.id }
            val isCollapsed = collapsed.contains(group.id)
            rows.add(HeaderRow(group.id, group.name, members.size, allVisible(members), isCollapsed, false))
            if (!isCollapsed) rows.addAll(members)
        }
        val ungrouped = placemarks.filter { it.groupId == MediaStore.UNGROUPED_ID }
        val ungroupedCollapsed = collapsed.contains(MediaStore.UNGROUPED_ID)
        rows.add(
            HeaderRow(
                MediaStore.UNGROUPED_ID, "未分组", ungrouped.size,
                allVisible(ungrouped), ungroupedCollapsed, true
            )
        )
        if (!ungroupedCollapsed) rows.addAll(ungrouped)
        return rows
    }

    /** 组内点位是否全部可见（空组视为不可见，开关呈关闭态）。 */
    private fun allVisible(members: List<MediaPlacemark>): Boolean =
        members.isNotEmpty() && members.all { it.visible }

    // ---- 分组操作 ----

    /** 整组可见性开关：批量写组内各点位 visible，刷新地图。 */
    private fun onGroupVisibleChanged(groupId: Long, visible: Boolean) {
        store.setGroupPlacemarksVisible(groupId, visible)
        placemarks.filter { it.groupId == groupId }.forEach { it.visible = visible }
        reloadData()
        onChanged()
    }

    /** 重命名分组。 */
    private fun onGroupRename(group: MediaGroup) {
        MediaEditDialog.promptGroupName(requireActivity(), "重命名分组", group.name) { name ->
            store.updateGroupName(group.id, name)
            reloadData()
        }
    }

    /** 删除分组：组内点位不删除，移到"未分组"。 */
    private fun onGroupDelete(group: MediaGroup) {
        AlertDialog.Builder(requireActivity())
            .setTitle("删除分组")
            .setMessage("删除分组\"${group.name}\"后，组内拍照标识不会被删除，将移到\"未分组\"。是否继续？")
            .setPositiveButton("删除") { _, _ ->
                store.deleteGroup(group.id)
                collapsed.remove(group.id)
                reloadData()
                onChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 分享分组：打包组内全部点位（坐标/照片）为 .mphoto 数据包，经系统分享面板发出。 */
    private fun onGroupShare(groupId: Long, groupName: String) {
        val members = placemarks.filter { it.groupId == groupId }
        // 未分组为虚拟组：分享包不带分组名，导入后点位仍归未分组（不新建“未分组”分组）
        val pkgGroup = if (groupId == MediaStore.UNGROUPED_ID) null else groupName
        MediaPackage.shareGroup(requireContext(), pkgGroup, members)
    }

    /** 折叠/展开分组。 */
    private fun onToggleCollapse(groupId: Long) {
        if (!collapsed.remove(groupId)) collapsed.add(groupId)
        reloadData()
    }

    // ---- 点位操作 ----

    /** 点击点位行：编辑名称与所属分组。 */
    private fun onPlacemarkEdit(placemark: MediaPlacemark) {
        MediaEditDialog.show(requireActivity(), placemark) { name, groupId ->
            placemark.name = name
            placemark.groupId = groupId
            reloadData()
            onChanged()
        }
    }

    /** 点位可见性开关。 */
    private fun onPlacemarkVisibleChanged(placemark: MediaPlacemark, visible: Boolean) {
        store.updatePlacemarkVisible(placemark.id, visible)
        placemark.visible = visible
        reloadData()
        onChanged()
    }

    /** 缩放至点位：关闭弹层后由宿主执行相机跳转。 */
    private fun onPlacemarkZoom(placemark: MediaPlacemark) {
        dismiss()
        onZoomTo(placemark)
    }

    /** 删除点位：连同附件文件目录一并删除。 */
    private fun onPlacemarkDelete(placemark: MediaPlacemark) {
        AlertDialog.Builder(requireActivity())
            .setTitle("删除拍照标识")
            .setMessage("删除后该点位及其全部照片文件将一并删除，是否继续？")
            .setPositiveButton("删除") { _, _ ->
                store.deletePlacemark(placemark.id)
                File(AppDirectories.getMediaDir(requireContext()), placemark.id.toString())
                    .deleteRecursively()
                placemarks.remove(placemark)
                reloadData()
                onChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 分组头行数据。 */
    private class HeaderRow(
        val groupId: Long,
        val name: String,
        val count: Int,
        val allVisible: Boolean,
        val collapsed: Boolean,
        val ungrouped: Boolean
    )

    /**
     * 分组分区适配器：分组头（[HeaderRow]）与点位行（[MediaPlacemark]）两种视图类型。
     * 行数据由 [submit] 整体替换后 notifyDataSetChanged（管理弹窗数据量小，无需 DiffUtil）。
     */
    private inner class GroupAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val rows = mutableListOf<Any>()

        /** 点位行副标题时间格式；onBindViewHolder 恒在主线程，可复用同一实例（SimpleDateFormat 非线程安全） */
        private val timeFormat = SimpleDateFormat(TIME_PATTERN, Locale.getDefault())

        fun submit(newRows: List<Any>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is HeaderRow) TYPE_HEADER else TYPE_ITEM

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(inflater.inflate(R.layout.item_media_group, parent, false))
            } else {
                ItemHolder(inflater.inflate(R.layout.item_media_placemark, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is HeaderRow -> bindHeader(holder as HeaderHolder, row)
                is MediaPlacemark -> bindItem(holder as ItemHolder, row)
            }
        }

        private fun bindHeader(holder: HeaderHolder, row: HeaderRow) {
            holder.name.text = row.name
            holder.count.text = if (row.count > 0) "${row.count}个拍照标识" else "无拍照标识"
            // 折叠箭头：展开指向下（-90°），折叠指向右（180°）
            holder.arrow.rotation = if (row.collapsed) 180f else -90f
            holder.arrow.setOnClickListener { onToggleCollapse(row.groupId) }
            holder.name.setOnClickListener { onToggleCollapse(row.groupId) }

            // 分享：组内全部点位打包为数据包分享（未分组也可分享，导入后仍归未分组）
            holder.share.setOnClickListener { onGroupShare(row.groupId, row.name) }

            // 未分组为虚拟组：不可重命名/删除
            holder.rename.visibility = if (row.ungrouped) View.GONE else View.VISIBLE
            holder.delete.visibility = if (row.ungrouped) View.GONE else View.VISIBLE
            if (!row.ungrouped) {
                val group = groups.firstOrNull { it.id == row.groupId }
                holder.rename.setOnClickListener { group?.let { onGroupRename(it) } }
                holder.delete.setOnClickListener { group?.let { onGroupDelete(it) } }
            }

            // 先设状态再挂监听，避免回显触发批量写库
            holder.visibleSwitch.setOnCheckedChangeListener(null)
            holder.visibleSwitch.isChecked = row.allVisible
            holder.visibleSwitch.isEnabled = row.count > 0
            holder.visibleSwitch.setOnCheckedChangeListener { _, isChecked ->
                onGroupVisibleChanged(row.groupId, isChecked)
            }
        }

        private fun bindItem(holder: ItemHolder, placemark: MediaPlacemark) {
            holder.name.text = placemark.name
            val count = placemark.attachments.size
            holder.subtitle.text = formatTime(placemark.createdTime) +
                    if (count > 0) "　·　${count}张照片" else "　·　无照片"

            holder.visibleSwitch.setOnCheckedChangeListener(null)
            holder.visibleSwitch.isChecked = placemark.visible
            holder.visibleSwitch.setOnCheckedChangeListener { _, isChecked ->
                onPlacemarkVisibleChanged(placemark, isChecked)
            }

            holder.itemView.setOnClickListener { onPlacemarkEdit(placemark) }
            holder.zoomButton.setOnClickListener { onPlacemarkZoom(placemark) }
            holder.deleteButton.setOnClickListener { onPlacemarkDelete(placemark) }
        }

        private fun formatTime(time: Long): String = timeFormat.format(Date(time))
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val arrow: ImageView = view.findViewById(R.id.item_group_arrow)
        val name: TextView = view.findViewById(R.id.item_group_name)
        val count: TextView = view.findViewById(R.id.item_group_count)
        val visibleSwitch: SwitchCompat = view.findViewById(R.id.item_group_visible)
        val share: ImageView = view.findViewById(R.id.item_group_share)
        val rename: TextView = view.findViewById(R.id.item_group_rename)
        val delete: ImageView = view.findViewById(R.id.item_group_delete)
    }

    private class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.item_media_name)
        val subtitle: TextView = view.findViewById(R.id.item_media_subtitle)
        val visibleSwitch: SwitchCompat = view.findViewById(R.id.item_media_visible)
        val zoomButton: ImageView = view.findViewById(R.id.item_media_zoom)
        val deleteButton: ImageView = view.findViewById(R.id.item_media_delete)
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        /** 点位创建时间的副标题格式 */
        private const val TIME_PATTERN = "yyyy-MM-dd HH:mm"
    }
}
