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
import com.zys.mobilemap.project.Project
import com.zys.mobilemap.project.ProjectManager
import java.io.File

/**
 * 工程管理对话框（0.4.0.0，主界面「工程」入口，仿轨迹管理）：
 * 列出 projects 目录全部 .gpkg 工程，点击打开（替换工作库并刷新叠加层）；
 * 右侧 ⋮ 按钮弹出操作菜单（导出分享 / 复制 / 重命名 / 备份 / 合并 / 删除）；右上角新建工程。
 * 打开/新建/删除/合并当前工程后工作库内容已切换，经 [onChanged] 回调刷新地图叠加层。
 *
 * 合并（[startMerge]）恒以当前工程为目标：源工程只读取不改写，并入前自动备份当前工程，
 * 因此无需「撤销」——反悔就用 backups/ 里的备份覆盖回来。
 */
object ProjectManageDialog {

    /** 合并防抖：整段流程（读源 → 确认 → 备份 → 暂存 → 并表 → 落盘）期间忽略再次点击，仅主线程读写 */
    private var mergeRunning = false

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
        var isOpening = false  // 后台切换进行中：防双击重复打开

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
                // 后台切换：openProject 含 GDAL 全量读取 + JSON 组包，大工程秒级，
                // 主线程同步执行会卡 UI；期间双击防抖（isOpening 标记）
                if (isOpening) return@ProjectAdapter
                isOpening = true
                Toast.makeText(activity, "正在打开工程…", Toast.LENGTH_SHORT).show()
                Thread {
                    val p = pm.openProject(file.absolutePath)
                    activity.runOnUiThread {
                        isOpening = false
                        if (p != null) {
                            Toast.makeText(activity, "已打开工程：${p.name}", Toast.LENGTH_SHORT).show()
                            onChanged()
                            dialog?.dismiss()
                        } else {
                            Toast.makeText(activity, "工程读取失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            },
            onMore = { info -> showActionMenu(activity, pm, info, ::refreshList, onChanged) }
        )
        // 上次合并若被进程中途杀死，暂存照片会留在 cache/merge_tmp；进工程管理时后台清一次（目录 IO 不上主线程）
        Thread { pm.cleanupMergeTempFiles() }.start()

        list.adapter = adapter
        view.findViewById<TextView>(R.id.project_manage_new).setOnClickListener {
            showNewProjectDialog(activity) { name, desc ->
                if (pm.createEmptyProject(name, desc)) {
                    Toast.makeText(activity, "已创建并切换到新工程：$name", Toast.LENGTH_SHORT).show()
                    onChanged()
                    dialog?.dismiss()
                } else {
                    Toast.makeText(activity, "创建失败：名称重复或写入出错", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 操作菜单：分享 / 复制 / 重命名 / 备份 / 合并（当前工程为合并目标，非当前工程为合并源）/
     *  删除（仅非当前工程） */
    private fun showActionMenu(
        activity: Activity,
        pm: ProjectManager,
        info: ProjectManager.ProjectInfo,
        refreshList: () -> Unit,
        onChanged: () -> Unit
    ) {
        val fileName = info.fileName ?: return
        val isCurrent = fileName == pm.currentFileName()
        // 当前工程不可删除（工作库镜像源），其余操作正常
        // 备份：复制 gpkg（带时间戳）到 /sdcard/调查宝/backups/，当前工程先落盘保证备份为最新
        // 合并：目标恒为当前工程——当前工程行选源工程并入，其他工程行把自己并入当前工程
        val actions = if (isCurrent)
            arrayOf("分享", "复制", "重命名", "备份", "合并")
        else
            arrayOf("分享", "复制", "重命名", "备份", "删除", "合并到当前工程")
        AlertDialog.Builder(activity)
            .setTitle(fileName.removeSuffix(".gpkg"))
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    "分享" -> exportAndShare(activity, pm, info)
                    "复制" -> {
                        val newName = uniqueCopyName(pm, fileName.removeSuffix(".gpkg"))
                        if (pm.duplicateProject(fileName, newName)) refreshList()
                    }
                    "重命名" -> showRenameDialog(activity, pm, fileName, refreshList)
                    "合并" -> showMergeSourcePicker(activity, pm, fileName, refreshList, onChanged)
                    "合并到当前工程" -> startMerge(activity, pm, info, refreshList, onChanged)
                    "备份" -> Thread {
                        val dst = if (isCurrent) pm.backupCurrentProject() else
                            pm.backupProjectFile(fileName)
                        activity.runOnUiThread {
                            Toast.makeText(activity, if (dst != null)
                                "已备份：${dst.absolutePath}" else "备份失败", Toast.LENGTH_LONG).show()
                        }
                    }.start()
                    "删除" -> showDeleteConfirmDialog(activity, pm, fileName, refreshList)
                }
            }
            .show()
    }

    /**
     * 当前工程 ⋮ →「合并」：列出其余工程供选择作为合并源。
     * [currentFileName] 只用于防呆（列表中已被切换掉的当前工程不入选项）。
     */
    private fun showMergeSourcePicker(
        activity: Activity, pm: ProjectManager, currentFileName: String,
        refreshList: () -> Unit, onChanged: () -> Unit
    ) {
        val candidates = pm.listProjects().filter {
            it.fileName != currentFileName && it.filePath != null
        }
        if (candidates.isEmpty()) {
            Toast.makeText(activity, "没有其他工程可合并", Toast.LENGTH_SHORT).show()
            return
        }
        val names = candidates.map { it.fileName?.removeSuffix(".gpkg") ?: "" }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("选择并入「${currentFileName.removeSuffix(".gpkg")}」的工程")
            .setItems(names) { _, which ->
                startMerge(activity, pm, candidates[which], refreshList, onChanged)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 合并入口：后台读源工程（GDAL 全量读，大工程秒级，不上主线程）→ 主线程弹确认框列出并入条数
     * → 确认后交 [doMerge] 执行。目标恒为当前工程，[source] 是作为合并源的工程文件。
     */
    private fun startMerge(
        activity: Activity, pm: ProjectManager,
        source: ProjectManager.ProjectInfo,
        refreshList: () -> Unit, onChanged: () -> Unit
    ) {
        if (mergeRunning) {
            Toast.makeText(activity, "正在合并工程，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val sourcePath = source.filePath ?: return
        val sourceName = source.fileName?.removeSuffix(".gpkg") ?: "未命名工程"
        if (source.fileName == pm.currentFileName()) {
            Toast.makeText(activity, "源工程与当前工程相同，无需合并", Toast.LENGTH_SHORT).show()
            return
        }
        val targetName = pm.currentFileName()?.removeSuffix(".gpkg") ?: "当前工程"
        mergeRunning = true
        Toast.makeText(activity, "正在读取源工程…", Toast.LENGTH_SHORT).show()
        Thread {
            val src = pm.readProject(sourcePath)
            activity.runOnUiThread {
                // statsOf 要读当前工程内存模型（同名分组归并判断），与并表一样收口在主线程
                val stats = src?.let { pm.statsOf(it) }
                if (src == null || stats == null) {
                    mergeRunning = false
                    Toast.makeText(activity, "源工程读取失败", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (stats.isEmpty) {
                    mergeRunning = false
                    Toast.makeText(activity, "「$sourceName」没有可合并的数据", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(activity)
                    .setTitle("合并到「$targetName」")
                    .setMessage(
                        "将「$sourceName」的采集数据并入当前工程：\n${stats.describe()}\n\n" +
                                "源工程保持不变；当前工程先自动备份到 backups/。"
                    )
                    .setPositiveButton("合并") { _, _ ->
                        doMerge(activity, pm, src, sourcePath, sourceName, refreshList, onChanged)
                    }
                    .setNegativeButton("取消") { _, _ -> mergeRunning = false }
                    .setOnCancelListener { mergeRunning = false }
                    .show()
            }
        }.start()
    }

    /**
     * 执行合并（线程编排：IO 全部离开主线程，模型并表回主线程，与拍照导入的 IO/Main.immediate 约定一致）
     *  1. 后台：备份当前工程（内部含写队列栅栏，备份即合并前的最新状态）+ 照片本体暂存；
     *  2. 主线程：内存并表并提交增量 DML（[ProjectManager.mergeIntoCurrent]）；
     *  3. 后台：[ProjectManager.saveNow] 栅栏排空增量写，返回时数据已在磁盘；
     *  4. 主线程：提示结果并刷新工程列表与地图叠加层。
     * 备份失败即中止——本操作不可撤销地改写当前工程，宁可不做。
     */
    private fun doMerge(
        activity: Activity, pm: ProjectManager, src: Project, sourcePath: String,
        sourceName: String,
        refreshList: () -> Unit, onChanged: () -> Unit
    ) {
        Toast.makeText(activity, "正在合并工程…", Toast.LENGTH_SHORT).show()
        Thread {
            val backup = pm.backupCurrentProject()
            if (backup == null) {
                finishMerge(activity, null, sourceName, "备份当前工程失败，已取消合并", refreshList, onChanged)
                return@Thread
            }
            val staged = pm.stageMergePhotos(src, sourcePath)
            activity.runOnUiThread {
                val res = pm.mergeIntoCurrent(src, staged)
                if (res == null) {
                    finishMerge(activity, null, sourceName, "合并中止：当前工程不可用", refreshList, onChanged)
                } else {
                    Thread {
                        pm.saveNow()
                        finishMerge(activity, res, sourceName, null, refreshList, onChanged)
                    }.start()
                }
            }
        }.start()
    }

    /** 合并收尾（统一回主线程）：提示并入条数与备份位置，刷新工程列表和地图叠加层，释放防抖标记 */
    private fun finishMerge(
        activity: Activity, res: ProjectManager.MergeResult?, sourceName: String,
        error: String?, refreshList: () -> Unit, onChanged: () -> Unit
    ) {
        activity.runOnUiThread {
            mergeRunning = false
            if (res == null) {
                Toast.makeText(activity, error ?: "合并失败", Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            Toast.makeText(
                activity,
                "已将「$sourceName」并入：${res.describe()}\n合并前备份见 /调查宝/backups/",
                Toast.LENGTH_LONG
            ).show()
            refreshList()
            onChanged()
        }
    }

    /** 直接分享工程 .gpkg 文件本体（当前工程先同步落盘保证最新；gpkg 单文件，QGIS/ArcGIS 可直接读取） */
    private fun exportAndShare(activity: Activity, pm: ProjectManager, info: ProjectManager.ProjectInfo) {
        if (info.fileName == pm.currentFileName()) {
            pm.saveNow() // 同步落盘：分享的是文件本体，异步写会分享到旧内容
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

    /** 删除确认（仅非当前工程可达此入口；当前工程在操作菜单中不显示删除项） */
    private fun showDeleteConfirmDialog(
        activity: Activity, pm: ProjectManager, fileName: String, refreshList: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle("删除工程")
            .setMessage("删除后该工程文件（.gpkg）不可恢复，是否继续？")
            .setPositiveButton("删除") { _, _ ->
                if (pm.deleteProject(fileName)) refreshList()
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
