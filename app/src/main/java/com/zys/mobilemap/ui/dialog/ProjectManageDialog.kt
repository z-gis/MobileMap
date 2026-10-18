package com.zys.mobilemap.ui.dialog

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zys.mobilemap.R
import com.zys.mobilemap.project.ProjectManager
import java.io.File

/**
 * 工程管理对话框（0.4.0.0，主界面「工程」入口，仿轨迹管理）：
 * 列出 projects 目录全部 .gpkg 工程，点击打开（替换工作库并刷新叠加层）；
 * 右侧 ⋮ 按钮弹出操作菜单（导出分享 / 复制 / 重命名 / 删除）；右上角新建工程。
 * 打开/新建/删除当前工程后工作库内容已切换，经 [onChanged] 回调刷新地图叠加层。
 */
object ProjectManageDialog {

    fun show(activity: Activity, onChanged: () -> Unit) {
        val pm = ProjectManager.get(activity)

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_project_manage, null)
        val emptyView = view.findViewById<TextView>(R.id.project_manage_empty)
        val list = view.findViewById<RecyclerView>(R.id.project_manage_list)
        list.layoutManager = LinearLayoutManager(activity)

        val infos = pm.listProjects().toMutableList()
        val refreshEmpty = { emptyView.visibility = if (infos.isEmpty()) View.VISIBLE else View.GONE }
        refreshEmpty()

        lateinit var adapter: ProjectAdapter
        var dialog: AlertDialog? = null

        fun refreshList() {
            infos.clear()
            infos.addAll(pm.listProjects())
            adapter.notifyDataSetChanged()
            refreshEmpty()
        }

        adapter = ProjectAdapter(
            infos,
            currentFileName = { pm.currentFileName() },
            onOpen = { info ->
                val file = File(info.filePath ?: return@ProjectAdapter)
                if (!file.exists()) {
                    Toast.makeText(activity, "工程文件不存在", Toast.LENGTH_SHORT).show()
                    refreshList()
                    return@ProjectAdapter
                }
                val p = pm.openProject(file.absolutePath)
                if (p != null) {
                    Toast.makeText(activity, "已打开工程：${p.name}", Toast.LENGTH_SHORT).show()
                    onChanged()
                    dialog?.dismiss()
                } else {
                    Toast.makeText(activity, "工程读取失败", Toast.LENGTH_SHORT).show()
                }
            },
            onMore = { info -> showActionMenu(activity, pm, info, ::refreshList, onChanged, dialog) }
        )
        list.adapter = adapter
        view.findViewById<TextView>(R.id.project_manage_new).setOnClickListener {
            showNewProjectDialog(activity) { name, desc ->
                if (pm.createEmptyProject(name, desc)) {
                    Toast.makeText(activity, "已创建并切换到新工程：$name", Toast.LENGTH_SHORT).show()
                    onChanged()
                    dialog?.dismiss()
                } else {
                    Toast.makeText(activity, "工程创建失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 长按操作菜单：导出分享 / 复制 / 重命名 / 删除 */
    private fun showActionMenu(
        activity: Activity,
        pm: ProjectManager,
        info: ProjectManager.ProjectInfo,
        refreshList: () -> Unit,
        onChanged: () -> Unit,
        dialog: AlertDialog?
    ) {
        val fileName = info.fileName ?: return
        val actions = arrayOf("分享", "复制", "重命名", "删除")
        AlertDialog.Builder(activity)
            .setTitle(fileName.removeSuffix(".gpkg"))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> exportAndShare(activity, pm, info)
                    1 -> {
                        val newName = uniqueCopyName(pm, fileName.removeSuffix(".gpkg"))
                        if (pm.duplicateProject(fileName, newName)) refreshList()
                    }
                    2 -> showRenameDialog(activity, pm, fileName, refreshList)
                    3 -> showDeleteConfirmDialog(activity, pm, fileName, refreshList, onChanged)
                }
            }
            .show()
    }

    /** 直接分享工程 .gpkg 文件本体（当前工程先落盘保证最新；gpkg 单文件，QGIS/ArcGIS 可直接读取） */
    private fun exportAndShare(activity: Activity, pm: ProjectManager, info: ProjectManager.ProjectInfo) {
        if (info.fileName == pm.currentFileName()) {
            pm.saveProject(pm.currentProject) // 当前工程可能有未落盘的镜像变更
        }
        val path = info.filePath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(activity, "工程文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = try {
            FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(activity, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, info.fileName?.removeSuffix(".gpkg") ?: "工程")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "分享工程"))
    }

    /** 重命名工程文件（当前工程会同步改工程名） */
    private fun showRenameDialog(
        activity: Activity, pm: ProjectManager, fileName: String, refreshList: () -> Unit
    ) {
        val edit = EditText(activity).apply {
            setText(fileName.removeSuffix(".gpkg"))
            isSingleLine = true
        }
        val container = FrameLayout(activity).apply {
            val density = activity.resources.displayMetrics.density
            val h = (16 * density).toInt()
            val v = (8 * density).toInt()
            setPadding(h, v, h, 0)
            addView(edit, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity)
            .setTitle("重命名工程")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isNotEmpty() && pm.renameProject(fileName, newName)) refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 删除确认：删除当前工程后由 ProjectManager 置空当前工程，并确保回退到默认工程 */
    private fun showDeleteConfirmDialog(
        activity: Activity, pm: ProjectManager, fileName: String,
        refreshList: () -> Unit, onChanged: () -> Unit
    ) {
        val isCurrent = fileName == pm.currentFileName()
        val msg = if (isCurrent)
            "这是当前工程：删除后地图上的轨迹/测量/拍照将一并清空，并自动新建空「默认工程」。该操作不可恢复，是否继续？"
        else
            "删除后该工程文件（.gpkg）不可恢复，是否继续？"
        AlertDialog.Builder(activity)
            .setTitle("删除工程")
            .setMessage(msg)
            .setPositiveButton("删除") { _, _ ->
                if (pm.deleteProject(fileName)) {
                    if (isCurrent) {
                        // 当前工程被删：重置为空默认工程（数据随删除清空，工作库同步清空）
                        pm.resetToEmptyDefault()
                        onChanged()
                    }
                    refreshList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 新建工程对话框：名称 + 描述（可空） */
    private fun showNewProjectDialog(activity: Activity, onCreate: (String, String) -> Unit) {
        val density = activity.resources.displayMetrics.density
        val h = (16 * density).toInt()
        val v = (8 * density).toInt()
        val nameEdit = EditText(activity).apply {
            hint = "工程名称"
            isSingleLine = true
        }
        val descEdit = EditText(activity).apply {
            hint = "描述（可选）"
            isSingleLine = true
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(h, v, h, 0)
            addView(nameEdit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(descEdit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity)
            .setTitle("新建工程")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                val name = nameEdit.text.toString().trim()
                if (name.isNotEmpty()) onCreate(name, descEdit.text.toString().trim())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 复制命名：「xx 副本」，已存在则递增序号 */
    private fun uniqueCopyName(pm: ProjectManager, base: String): String {
        val existing = pm.listProjects().mapNotNull { it.fileName }.toSet()
        var name = "$base 副本"
        var i = 2
        while (("$name.gpkg") in existing) {
            name = "$base 副本$i"
            i++
        }
        return name
    }

    /** 工程列表适配器：名称（当前工程带蓝底标记）+ 副标题（时间 · 大小），点击打开、右侧按钮弹操作菜单 */
    private class ProjectAdapter(
        private val infos: List<ProjectManager.ProjectInfo>,
        private val currentFileName: () -> String?,
        private val onOpen: (ProjectManager.ProjectInfo) -> Unit,
        private val onMore: (ProjectManager.ProjectInfo) -> Unit
    ) : RecyclerView.Adapter<ProjectAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.item_project_name)
            val subtitle: TextView = view.findViewById(R.id.item_project_subtitle)
            val currentBadge: TextView = view.findViewById(R.id.item_project_current)
            val more: TextView = view.findViewById(R.id.item_project_more)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false))

        override fun getItemCount(): Int = infos.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val info = infos[position]
            holder.name.text = info.fileName?.removeSuffix(".gpkg") ?: ""
            holder.subtitle.text = "${info.displayDate}　·　${info.displaySize}"
            holder.currentBadge.visibility =
                if (info.fileName == currentFileName()) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onOpen(info) }
            holder.more.setOnClickListener { onMore(info) }
        }
    }
}
