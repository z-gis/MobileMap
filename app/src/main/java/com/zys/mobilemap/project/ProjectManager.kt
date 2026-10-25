package com.zys.mobilemap.project

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.GeoCalc
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工程管理器（0.4.0.5 增量落盘版）
 *
 * 工程 gpkg 是采集数据（轨迹/测量/拍照/样地）的唯一数据本体。
 *
 * 数据流：
 *  - 采集/管理功能改 [currentProject] 内存模型，每次 mutation 同步提交毫秒级增量 DML
 *    （app 端 SQLite 直写 gpkg 业务表，见 [GeoPackageDml]）到后台串行队列；
 *  - 定型写入（停表/拍照/退出）用 [saveNow] 做队列栅栏——只等排队的增量任务，毫秒级返回；
 *  - 整文件重建（JNI GDAL）仅用于：新建工程文件；
 *  - 分享/导出/复制 = 队列栅栏后直接复制 gpkg 文件本体（单文件自包含，QGIS/ArcGIS 可读）；
 *  - 打开工程 = 读 gpkg 替换 [currentProject]；
 *  - 合并工程 = 源工程只读入内存，记录重新取号后并入 [currentProject]（[mergeIntoCurrent]），
 *    仍走各 mutation 的增量 DML 通道落盘，源文件不动。
 *
 * 照片策略：拍照/导入的照片本体增量写入 gpkg BLOB（主照片 photos.photo_data，其余附件
 * photo_att_data 附加表），源文件在入库成功后删除，不再长期保留。
 *
 * 工程文件存储在 /调查宝/projects/<工程名>.gpkg（公共目录，卸载不清除、文件管理器可直接取走）。
 * 内存模型坐标序为 [lat,lon,alt]（与采集/叠加层一致），gpkg 轴序 [lon,lat,alt] 转换在
 * [Project.toJson] 与 [GeoPackageDml.GpkgGeom] 边界完成。
 */
class ProjectManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val gpkgWriter = GeoPackageWriter(appContext)
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var currentProject: Project? = null
        private set

    val projectsDir: File = AppDirectories.getProjectDir(appContext)

    // ---- 启动装载 ----

    /**
     * 确保已装载工程（App 启动时调用一次，幂等）：
     * 上次打开的工程文件仍在 → 读入；否则创建「默认工程」并落盘。
     */
    fun ensureLoaded() {
        if (currentProject != null) return
        val last = prefs.getString(KEY_LAST_PROJECT, null)
        if (last != null && File(last).exists()) {
            val p = gpkgWriter.read(last)
            if (p != null) {
                currentProject = p
                Log.i(TAG, "ensureLoaded: 恢复上次工程 ${p.name}")
                return
            }
            Log.w(TAG, "ensureLoaded: 上次工程读取失败，重建默认工程 $last")
        }
        if (last == null) {
            val defaultFile = File(projectsDir, "$DEFAULT_PROJECT_NAME.gpkg")
            if (defaultFile.exists()) {
                val p = gpkgWriter.read(defaultFile.absolutePath)
                if (p != null) {
                    currentProject = p
                    rememberCurrent(defaultFile.absolutePath)
                    Log.i(TAG, "ensureLoaded: 默认工程文件已存在，直接打开")
                    return
                }
            }
        }
        val p = createProject(DEFAULT_PROJECT_NAME, "自动创建，收纳现有采集数据")
        val filePath = File(projectsDir, "$DEFAULT_PROJECT_NAME.gpkg").absolutePath
        writeProjectFileSync(p, filePath)
        rememberCurrent(null)
        Log.i(TAG, "ensureLoaded: 已创建默认工程")
    }

    /** 当前工程文件名（列表高亮用），无工程返回 null */
    fun currentFileName(): String? {
        val p = currentProject ?: return null
        return sanitizeFileName(p.name ?: "") + ".gpkg"
    }

    // ---- 工程 CRUD ----

    /** 创建新工程（并切换为当前工程；调用方负责随后 [saveProject] 落盘） */
    fun createProject(name: String, description: String): Project {
        val p = Project(name, description)
        currentProject = p
        Log.i(TAG, "createProject: $name")
        return p
    }

    /** 新建空工程并落盘、切换为当前工程，返回成功与否。工程管理对话框「新建」入口。
     *  同步落盘：返回 true 时文件已在磁盘，列表刷新立即可见（异步写会让新工程在列表中「隐身」）。
     *  重名拒绝：同名 .gpkg 已存在时返回 false，防止整文件重建覆盖已有工程数据。 */
    fun createEmptyProject(name: String, description: String): Boolean {
        if (File(projectsDir, sanitizeFileName(name) + ".gpkg").exists()) {
            Log.w(TAG, "createEmptyProject: 重名拒绝 $name")
            return false
        }
        // 先把当前工程最新数据同步落盘，再切换
        currentProject?.let { saveNowProject(it) }
        val p = createProject(name, description)
        val filePath = File(projectsDir, sanitizeFileName(name) + ".gpkg").absolutePath
        val ok = writeProjectFileSync(p, filePath)
        if (ok) {
            rememberCurrent(null)
        }
        return ok
    }

    /** 检查是否有打开的工程 */
    fun hasProject(): Boolean = currentProject != null

    /** 关闭当前工程（同步落盘后置空，防止 close 后立即重开读到半成品/丢数据） */
    fun closeProject() {
        val p = currentProject ?: return
        currentProject = null
        prefs.edit().remove(KEY_LAST_PROJECT).apply()
        saveNowProject(p)
        Log.i(TAG, "closeProject")
    }

    /** 列出所有工程文件 */
    fun listProjects(): List<ProjectInfo> {
        val list = mutableListOf<ProjectInfo>()
        val files = projectsDir.listFiles { _, name -> name.endsWith(".gpkg") }
        if (files != null) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            for (f in files) {
                val info = ProjectInfo()
                info.fileName = f.name
                info.filePath = f.absolutePath
                info.fileSize = f.length()
                info.lastModified = f.lastModified()
                info.displayDate = sdf.format(Date(f.lastModified()))
                info.displaySize = formatFileSize(f.length())
                list.add(info)
            }
        }
        // 按最后修改时间倒序
        list.sortByDescending { it.lastModified }
        return list
    }

    /** 只读读取工程（不切换当前工程，供导出/复制非当前工程用） */
    fun readProject(filePath: String): Project? = gpkgWriter.read(filePath)

    /** 打开工程：当前工程先同步落盘 → 读 gpkg 切换 [currentProject] → 记为最近工程。
     *  同步落盘（增量队列栅栏）防竞态：切回当前工程自身时先排空队列再读文件。
     *  工程名以文件名为准：gpkg 内 name 与文件名不一致（外部拷贝/文件管理器改名所致）时
     *  回填文件名，否则按内部 name 落盘会另生成新文件，同名数据分叉成两份。 */
    fun openProject(filePath: String): Project? {
        currentProject?.let { saveNowProject(it) }
        val p = gpkgWriter.read(filePath)
        if (p != null) {
            val fileBase = File(filePath).nameWithoutExtension
            if (p.name != fileBase) {
                Log.i(TAG, "openProject: 工程名回填 '${p.name}' -> '$fileBase'（与文件名对齐）")
                p.name = fileBase
            }
            currentProject = p
            rememberCurrent(filePath)
            Log.i(TAG, "openProject: $filePath -> ${p.name}")
        } else {
            Log.e(TAG, "openProject: 读取失败 $filePath")
        }
        return p
    }

    /** 异步同步工程元数据（name/description/createTime/updateTime）到 gpkg project_meta。
     *  业务数据已由各 mutation 增量落盘，此处仅元数据；需确定性落盘用 [saveNow]。 */
    fun saveProject(project: Project?): Boolean {
        if (project == null) return false
        project.updateTime = System.currentTimeMillis()
        val name = project.name; val desc = project.description; val srs = project.srs
        val createTime = project.createTime; val updateTime = project.updateTime
        val filePath = File(projectsDir, sanitizeFileName(name ?: "未命名工程") + ".gpkg").absolutePath
        saveExecutor.execute { runCatching { GeoPackageDml.syncMeta(filePath, name, desc, srs, createTime, updateTime) } }
        Log.i(TAG, "saveProject(异步元数据): $name 已提交")
        return true
    }

    /** 当前工程 gpkg 文件绝对路径（由 name 推导，与 [saveNowProject] 同规则）；无当前工程返回 null */
    val currentProjectPath: String?
        get() = currentProject?.let {
            File(projectsDir, sanitizeFileName(it.name ?: "未命名工程") + ".gpkg").absolutePath
        }

    /** 主线程快照 + 后台整文件重建（JNI GDAL）：仅用于新建工程文件 */
    private fun writeProjectFileSync(project: Project, filePath: String): Boolean {
        project.updateTime = System.currentTimeMillis()
        val json = gpkgWriter.encode(project)
        return runCatching {
            saveExecutor.submit(java.util.concurrent.Callable { gpkgWriter.writeEncoded(json, filePath) }).get()
        }.getOrElse { false }
    }

    /**
     * 同步落盘指定工程：先排空增量写队列（栅栏），再同步 project_meta 元数据。
     * 排队的增量任务都是毫秒级 DML，整体不再有 GDAL 整文件重建的秒级阻塞。
     * 目标文件不存在（新建流程外异常缺失）时退回整文件重建。
     */
    fun saveNowProject(project: Project?): Boolean {
        if (project == null) return false
        project.updateTime = System.currentTimeMillis()
        val name = project.name; val desc = project.description; val srs = project.srs
        val createTime = project.createTime; val updateTime = project.updateTime
        val filePath = File(projectsDir, sanitizeFileName(name ?: "未命名工程") + ".gpkg").absolutePath
        if (!File(filePath).exists()) return writeProjectFileSync(project, filePath)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val ok = runCatching {
            saveExecutor.submit(java.util.concurrent.Callable {
                runCatching { GeoPackageDml.syncMeta(filePath, name, desc, srs, createTime, updateTime) }
                    .onFailure { Log.e(TAG, "saveNowProject: 元数据同步失败 $filePath", it) }
                    .isSuccess
            }).get()
        }.getOrElse { false }
        Log.i(TAG, "saveNowProject: 栅栏排空+元数据落盘 cost=${android.os.SystemClock.elapsedRealtime() - t0}ms")
        return ok
    }

    /**
     * 等待后台保存队列排空（栅栏）：串行池提交空任务并阻塞至其执行，此前所有写任务必已完成。
     * 供删除/重命名工程文件前调用，防止队列尾部同路径写任务让已删文件复活。
     */
    private fun awaitSaveQueue() {
        runCatching { saveExecutor.submit({}).get() }
    }

    /**
     * 退出保存：提交元数据同步后**不等待**直接返回。业务数据此前已随 mutation 增量落盘，
     * 退出即刻完成（用户决策：不因保存等待而卡退出）；风险兜底是工程管理的「备份」按钮。
     */
    fun saveOnExit() {
        saveProject(currentProject)
    }

    /**
     * 备份当前工程：先同步落盘保证备份为最新，再把 gpkg 复制到 /sdcard/调查宝/backups/
     * （文件名带时间戳，不进工程列表）。返回备份文件，失败返回 null。
     */
    fun backupCurrentProject(): File? {
        val p = currentProject ?: return null
        if (!saveNowProject(p)) return null
        val src = File(projectsDir, sanitizeFileName(p.name ?: "未命名工程") + ".gpkg")
        if (!src.exists()) return null
        val dir = File("/sdcard/调查宝/backups").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val dst = File(dir, "${sanitizeFileName(p.name ?: "未命名工程")}_$ts.gpkg")
        return try {
            src.copyTo(dst, overwrite = false)
            Log.i(TAG, "backupCurrentProject: ${src.name} -> ${dst.absolutePath}")
            dst
        } catch (e: Exception) {
            Log.e(TAG, "backupCurrentProject: 备份失败", e)
            null
        }
    }

    /** 另存为 */
    fun saveProjectAs(project: Project?, newName: String): Boolean {
        if (project == null) return false
        project.name = newName
        return saveProject(project)
    }

    /**
     * 备份指定工程文件（非当前工程）：复制到 /sdcard/调查宝/backups/（带时间戳）。
     * 返回备份文件，失败返回 null。
     */
    fun backupProjectFile(fileName: String): File? {
        val src = File(projectsDir, fileName)
        if (!src.exists()) return null
        val dir = File("/sdcard/调查宝/backups").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val base = fileName.removeSuffix(".gpkg")
        val dst = File(dir, "${base}_$ts.gpkg")
        return try {
            src.copyTo(dst, overwrite = false)
            Log.i(TAG, "backupProjectFile: $fileName -> ${dst.absolutePath}")
            dst
        } catch (e: Exception) {
            Log.e(TAG, "backupProjectFile: 备份失败", e)
            null
        }
    }

    /** 导出工程到 Download/调查宝/ 供分享：栅栏排空增量队列后直接复制 gpkg 文件本体
     *  （单文件自包含，含照片 BLOB，QGIS/ArcGIS 可读）。调用返回时文件已在磁盘。 */
    fun exportProject(project: Project?): File? {
        if (project == null) return null
        val src = File(projectsDir, sanitizeFileName(project.name ?: "未命名工程") + ".gpkg")
        saveNowProject(project)
        if (!src.exists()) return null
        val exportDir = File("/sdcard/Download/调查宝")
        if (!exportDir.exists()) exportDir.mkdirs()
        val dst = File(exportDir, "${sanitizeFileName(project.name ?: "未命名工程")}_${System.currentTimeMillis()}.gpkg")
        return try {
            src.copyTo(dst, overwrite = false)
            Log.i(TAG, "exportProject: ${dst.absolutePath}")
            dst
        } catch (e: Exception) {
            Log.e(TAG, "exportProject: 导出失败 ${project.name}", e)
            null
        }
    }

    /** 删除工程（API 层防线：当前工程禁止删除，防调用方绕过 UI 的隐藏逻辑） */
    fun deleteProject(fileName: String): Boolean {
        val cur = currentProject
        if (cur != null && fileName == sanitizeFileName(cur.name ?: "") + ".gpkg") {
            Log.w(TAG, "deleteProject: 拒绝删除当前工程 $fileName")
            return false
        }
        // 栅栏：排空后台保存队列，防止队列尾部同路径写任务在删除后把文件复活
        awaitSaveQueue()
        val f = File(projectsDir, fileName)
        val ok = f.delete()
        if (ok) {
            prefs.edit().remove(KEY_LAST_PROJECT).apply()
        }
        Log.i(TAG, "deleteProject: $fileName -> $ok")
        return ok
    }

    /** 重命名工程文件（若重命名的是当前工程，同步改工程名并异步同步元数据）。
     *  先栅栏排空队列再 renameTo：防止队列尾部旧路径写任务把旧名文件复活成「副本」。 */
    fun renameProject(oldFileName: String, newName: String): Boolean {
        awaitSaveQueue()
        val oldFile = File(projectsDir, oldFileName)
        val newFile = File(projectsDir, sanitizeFileName(newName) + ".gpkg")
        val ok = oldFile.renameTo(newFile)
        val cur = currentProject
        if (ok && cur != null &&
                sanitizeFileName(cur.name ?: "") == oldFileName.removeSuffix(".gpkg")) {
            cur.name = newName
            saveProject(cur) // project_meta.name 随文件名对齐（异步元数据同步）
            rememberCurrent(newFile.absolutePath)
        }
        Log.i(TAG, "renameProject: $oldFileName -> $newName $ok")
        return ok
    }

    /** 复制工程：栅栏排空队列后直接复制 gpkg 文件本体（保留照片 BLOB/记录 id，快于整文件重建）。
     *  新工程名在打开时按文件名回填，无需改库内元数据。 */
    fun duplicateProject(fileName: String, newName: String): Boolean {
        val cur = currentProject
        if (cur != null && fileName == sanitizeFileName(cur.name ?: "") + ".gpkg") {
            saveNowProject(cur) // 当前工程先排空增量队列，保证复制到最新
        }
        val src = File(projectsDir, fileName)
        if (!src.exists()) return false
        val dst = File(projectsDir, sanitizeFileName(newName) + ".gpkg")
        return try {
            src.copyTo(dst, overwrite = false)
            Log.i(TAG, "duplicateProject: $fileName -> ${dst.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "duplicateProject: 复制失败 $fileName", e)
            false
        }
    }

    // ---- 工程合并（把一个工程的采集数据并入当前工程）----

    /**
     * 合并数据量统计：合并前作预览（[statsOf]），合并后作结果（[mergeIntoCurrent]），
     * 各字段口径一致 = 并入当前工程的条数。[groups] 只计新建分组（同名分组归并不产生新组）。
     */
    class MergeResult {
        var tracks = 0
        var measures = 0
        var photos = 0
        var attachments = 0
        var groups = 0
        var plots = 0

        /** 源工程无可并数据 */
        val isEmpty: Boolean get() = tracks + measures + photos + plots == 0

        /** 单行文案：轨迹 3 · 测量 1 · 拍照点位 5（照片 7 张）· 新建分组 2 · 样地 4 */
        fun describe(): String = buildString {
            append("轨迹 ").append(tracks)
            append(" · 测量 ").append(measures)
            append(" · 拍照点位 ").append(photos)
            if (attachments > 0) append("（照片 ").append(attachments).append(" 张）")
            if (groups > 0) append(" · 新建分组 ").append(groups)
            append(" · 样地 ").append(plots)
        }
    }

    /**
     * 只读预估并入量（不写库、不改模型），供合并确认对话框展示。
     * [groups] 按 [findGroupForMerge] 口径剔除将被同名归并的组，与 [mergeIntoCurrent] 结果一致。
     */
    fun statsOf(source: Project): MergeResult = MergeResult().apply {
        tracks = source.tracks.size
        measures = source.measures.size
        photos = source.photos.size
        attachments = source.photos.sumOf { it.attachments.size }
        groups = source.mediaGroups.count { findGroupForMerge(it.name) == null }
        plots = source.surveyPlots.size
    }

    /** 分组按名称归并：当前工程已有同名（去空白）分组时沿用该组 */
    private fun findGroupForMerge(name: String?): Project.MediaGroupRecord? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        return currentProject?.mediaGroups?.firstOrNull { it.name?.trim() == n }
    }

    /**
     * 把源工程各拍照点位的照片本体取出并暂存到 cache/merge_tmp/<源fid>_<idx>.jpg，
     * 返回「源点位 fid → 附件序号 → 暂存文件路径」。供 [mergeIntoCurrent] 的附件入库任务搬运
     * （暂存文件入库即删，与拍照流程同源同去向）。
     *
     * 只在后台线程调用：BLOB 读出 + 临时文件写入是本次合并唯一的整段 IO，且与工程写队列无关
     * （读的是源工程文件，目标文件此时不受影响）。
     * 取不到本体的附件不进表：老工程照片文件仍在磁盘时按原路径复制暂存；两边都没有的
     * 保留附件记录（路径不落地，不显示图），与拍照后文件被清走的表现一致。
     */
    fun stageMergePhotos(source: Project, sourceFilePath: String): Map<Long, Map<Long, String>> {
        val staged = LinkedHashMap<Long, MutableMap<Long, String>>()
        if (source.photos.isEmpty()) return staged
        val dir = File(appContext.cacheDir, MERGE_TMP_DIR).apply { mkdirs() }
        for (ph in source.photos) {
            if (ph.attachments.isEmpty()) continue
            val perAtt = LinkedHashMap<Long, String>()
            for (idx in ph.attachments.indices) {
                val bytes = GeoPackageDml.readPhotoBlob(sourceFilePath, ph.id, idx.toLong())
                val src = ph.attachments[idx].path
                val content: ByteArray? = bytes
                    ?: File(src).takeIf { it.isFile && it.length() > 0 }?.let { runCatching { it.readBytes() }.getOrNull() }
                if (content == null) {
                    Log.w(TAG, "stageMergePhotos: fid=${ph.id} idx=$idx 无照片本体，仅并附件记录")
                    continue
                }
                val name = (src.substringAfterLast('/').ifEmpty { "photo_$idx.jpg" })
                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                val out = File(dir, "${ph.id}_$idx$name")
                val stagedPath = runCatching { out.writeBytes(content); out.absolutePath }
                    .onFailure { Log.e(TAG, "stageMergePhotos: 暂存失败 fid=${ph.id} idx=$idx", it) }
                    .getOrNull() ?: continue
                perAtt[idx.toLong()] = stagedPath
            }
            if (perAtt.isNotEmpty()) staged[ph.id] = perAtt
        }
        Log.i(TAG, "stageMergePhotos: 暂存照片 ${staged.values.sumOf { it.size }} 张")
        return staged
    }

    /**
     * 把 [source]（由 [readProject] 在后台读入的另一工程副本）的采集数据并入当前工程。
     * **主线程调用**：只做内存并表 + 提交毫秒级增量 DML，不碰磁盘 IO
     * （照片本体须先经 [stageMergePhotos] 暂存，入库搬运排在串行保存队列内）。
     *
     * 并表规则：
     *  - 记录 id 在当前工程内重新取号（[nextId]），与源工程 id 无关，gpkg fid 不冲突；
     *  - 拍照分组按名称归并（[findGroupForMerge]），点位经「源组 id → 目标组 id」映射挂到对应组；
     *  - 样地走 [saveSurveyPlot]，同小班内重号自动续排；
     *  - 工程名/描述/图层引用（layerRefs）不参与合并——图层属于文档配置，不属采集数据。
     *
     * 完成后由调用方 [saveNow] 栅栏落盘。源工程文件与数据不做任何改动。
     * [source] 即当前工程本身、或无当前工程时返回 null（不产生半并状态）。
     */
    fun mergeIntoCurrent(
        source: Project,
        stagedPhotos: Map<Long, Map<Long, String>> = emptyMap()
    ): MergeResult? {
        val target = currentProject ?: return null
        if (source === target) {
            Log.w(TAG, "mergeIntoCurrent: 源即当前工程，拒绝")
            return null
        }
        val res = MergeResult()

        // ---- 分组先并：后续点位要按名称归并结果拿目标组 id ----
        val groupIdMap = HashMap<Long, Long>()
        for (g in source.mediaGroups) {
            val exist = findGroupForMerge(g.name)
            if (exist != null) {
                groupIdMap[g.id] = exist.id
                continue
            }
            val ng = Project.MediaGroupRecord()
            ng.id = nextId(target.mediaGroups.map { it.id })
            ng.name = g.name
            ng.createdTime = g.createdTime
            ng.visible = g.visible
            target.mediaGroups.add(ng)
            groupIdMap[g.id] = ng.id
            res.groups++
            val gid = ng.id; val nm = ng.name; val ct = ng.createdTime; val vis = ng.visible
            enqueueDml { GeoPackageDml.insertGroup(it, gid, nm, ct, vis) }
        }

        // ---- 轨迹 ----
        for (t in source.tracks) {
            val r = Project.TrackRecord()
            r.id = nextId(target.tracks.map { it.id })
            r.name = t.name
            r.date = t.date
            r.color = t.color
            r.hidden = t.hidden
            r.lineWidth = t.lineWidth
            r.points.addAll(t.points.map { it.copyOf() })
            target.tracks.add(r)
            pushTrackInsert(r)
            res.tracks++
        }

        // ---- 测量 ----
        for (m in source.measures) {
            val r = Project.MeasureRecord()
            r.id = nextId(target.measures.map { it.id })
            r.name = m.name
            r.date = m.date
            r.type = m.type
            r.result = m.result
            r.color = m.color
            r.hidden = m.hidden
            r.closed = m.closed
            r.lineWidth = m.lineWidth
            r.points.addAll(m.points.map { it.copyOf() })
            target.measures.add(r)
            pushMeasureInsert(r)
            res.measures++
        }

        // ---- 拍照点位（附件本体取自暂存目录）----
        for (ph in source.photos) {
            val r = Project.PhotoRecord()
            r.id = nextId(target.photos.map { it.id })
            r.name = ph.name
            r.lat = ph.lat
            r.lon = ph.lon
            r.alt = ph.alt
            r.timestamp = ph.timestamp
            r.visible = ph.visible
            r.groupId = groupIdMap[ph.groupId] ?: Project.PhotoRecord.UNGROUPED_ID
            val perAtt = stagedPhotos[ph.id] ?: emptyMap()
            // 与 attachments 同序的待入库文件：只有暂存文件才交给 addPhotoAttachment（它会删源文件），
            // 无本体时传空串——addPhotoAttachment 读不到字节但照常重写附件 JSON，且不会误删别人的照片
            val srcFiles = mutableListOf<String>()
            for (idx in ph.attachments.indices) {
                val a = ph.attachments[idx]
                val stagedPath = perAtt[idx.toLong()]
                r.attachments.add(Project.PhotoAttachment(a.name, stagedPath ?: a.path, a.time))
                srcFiles.add(stagedPath ?: "")
            }
            target.photos.add(r)
            pushPhotoInsert(r)
            res.photos++
            res.attachments += ph.attachments.size
            if (r.attachments.isNotEmpty()) {
                val attsJson = JSONArray().apply {
                    for (a in r.attachments) put(a.toJson())
                }.toString()
                val mainPath = r.photoPath
                val newId = r.id
                for (idx in r.attachments.indices) {
                    val srcFile = srcFiles[idx]
                    enqueueDml {
                        GeoPackageDml.addPhotoAttachment(it, newId, idx.toLong(), attsJson, mainPath, srcFile)
                    }
                }
            }
        }

        // ---- 样地调查 ----
        for (sp in source.surveyPlots) {
            if (saveSurveyPlot(copySurveyPlot(sp)) != null) res.plots++
        }

        Log.i(TAG, "mergeIntoCurrent: 并入 '${source.name}' -> '${target.name}' ${res.describe()}")
        return res
    }

    /** 样地深拷贝（id 置 0 交由 [saveSurveyPlot] 重新取号；明细行整组带走，行 id 仅在组内有意义） */
    private fun copySurveyPlot(src: Project.SurveyPlot): Project.SurveyPlot {
        val r = Project.SurveyPlot()
        r.name = src.name
        r.plotType = src.plotType
        r.points.addAll(src.points.map { it.copyOf() })
        r.sourcePath = src.sourcePath
        r.sourceFid = src.sourceFid
        r.compartmentNo = src.compartmentNo
        r.plotNo = src.plotNo
        r.averageTreeHeight = src.averageTreeHeight
        r.canopyDensity = src.canopyDensity
        r.cornerCoordinates = src.cornerCoordinates
        r.createdAt = src.createdAt
        for (row in src.rows) {
            val nr = Project.SurveyRow()
            nr.id = row.id
            nr.sortNo = row.sortNo
            nr.treeSpecies = row.treeSpecies
            nr.dbh = row.dbh
            nr.category = row.category
            nr.thinningMethod = row.thinningMethod
            nr.remark = row.remark
            r.rows.add(nr)
        }
        r.photoPaths.addAll(src.photoPaths)
        return r
    }

    /** 清理历史合并遗留的未入库暂存照片（正常路径入库即删；进程中途被杀才留残留） */
    fun cleanupMergeTempFiles() {
        runCatching {
            val dir = File(appContext.cacheDir, MERGE_TMP_DIR)
            val cutoff = System.currentTimeMillis() - 60_000L
            dir.listFiles()?.forEach {
                if (it.isFile && it.lastModified() < cutoff) it.delete()
            }
        }.onFailure { Log.w(TAG, "cleanupMergeTempFiles: 清理失败", it) }
    }

    // ---- 落盘线程池与增量写提交 ----

    /** GeoPackage 写盘线程池（单线程串行）：增量 DML 与整文件重建全部离开主线程；
     *  串行保证写写不并发，任务按提交序落库（mutation 先于 saveNow 栅栏任务执行）。 */
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "gpkg-save").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /**
     * 提交增量写任务：单线程串行队列，路径取自当前工程（rename/delete 前有队列栅栏保护）。
     * 各 mutation 先改内存模型再提交快照参数，主线程只做序列化不碰 IO。
     */
    private fun enqueueDml(op: (String) -> Boolean) {
        val path = currentProjectPath ?: return
        saveExecutor.execute {
            try {
                op(path)
            } catch (e: Exception) {
                Log.e(TAG, "增量写盘异常 $path", e)
            }
        }
    }

    /**
     * 立即同步落盘当前工程（定型类关键写入：轨迹停表入库、拍照入库、退出前兜底）。
     * 语义 = 增量队列栅栏 + 元数据同步：只等排队的毫秒级 DML，调用返回时数据已在磁盘。
     */
    fun saveNow() {
        saveNowProject(currentProject)
    }

    // ---- 记录 ID 分配（gpkg 落表为 fid，须 >0 且集合内唯一）----

    private fun nextId(ids: List<Long>): Long = (ids.maxOrNull() ?: 0L) + 1L

    // ---- 轨迹 ----

    fun hasTracks(): Boolean = currentProject?.tracks?.isNotEmpty() == true

    fun findTrack(id: Long): Project.TrackRecord? =
        currentProject?.tracks?.firstOrNull { it.id == id }

    /** 新增轨迹（[points] 内存序 [lat,lon,alt]），返回分配的记录 id */
    fun addTrack(
        name: String,
        createdTime: Long,
        points: List<DoubleArray>,
        colorHex: String = Project.TrackRecord.DEFAULT_COLOR_HEX,
        lineWidth: Int = Project.TrackRecord.DEFAULT_LINE_WIDTH
    ): Long {
        val p = currentProject ?: return -1
        val r = Project.TrackRecord()
        r.id = nextId(p.tracks.map { it.id })
        r.name = name
        r.date = formatTime(createdTime)
        r.color = colorHex
        r.lineWidth = lineWidth
        r.points.addAll(points)
        p.tracks.add(r)
        pushTrackInsert(r)
        return r.id
    }

    /** 提交轨迹增量插入（停表入库路径，saveNow 栅栏保证定型落盘） */
    private fun pushTrackInsert(t: Project.TrackRecord) {
        val id = t.id; val nm = t.name; val dt = t.date; val cl = t.color
        val hd = t.hidden; val lw = t.lineWidth; val pts = t.points
        enqueueDml { GeoPackageDml.insertTrack(it, id, nm, dt, cl, hd, lw, pts) }
    }

    /** 提交轨迹属性增量更新（可见性/样式/改名共用整行属性更新） */
    private fun pushTrackUpdate(t: Project.TrackRecord) {
        val id = t.id; val nm = t.name; val cl = t.color
        val hd = t.hidden; val lw = t.lineWidth
        enqueueDml { GeoPackageDml.updateTrack(it, id, nm, cl, hd, lw) }
    }

    fun removeTrack(id: Long) {
        val p = currentProject ?: return
        if (p.tracks.removeAll { it.id == id }) enqueueDml { GeoPackageDml.deleteTrack(it, id) }
    }

    fun updateTrackName(id: Long, name: String) {
        findTrack(id)?.let { it.name = name; pushTrackUpdate(it) }
    }

    fun updateTrackVisible(id: Long, visible: Boolean) {
        findTrack(id)?.let { it.hidden = !visible; pushTrackUpdate(it) }
    }

    fun updateTrackStyle(id: Long, colorInt: Int, lineWidth: Int) {
        findTrack(id)?.let {
            it.color = colorToHex(colorInt)
            it.lineWidth = lineWidth
            pushTrackUpdate(it)
        }
    }

    // ---- 测量 ----

    fun findMeasure(id: Long): Project.MeasureRecord? =
        currentProject?.measures?.firstOrNull { it.id == id }

    /** 新增测量（[points] 内存序 [lat,lon,alt]；result 文本按几何自动计算），返回记录 id */
    fun addMeasure(
        name: String,
        type: String,
        colorInt: Int,
        createdTime: Long,
        points: List<DoubleArray>,
        lineWidth: Int = Project.MeasureRecord.DEFAULT_LINE_WIDTH
    ): Long {
        val p = currentProject ?: return -1
        val r = Project.MeasureRecord()
        r.id = nextId(p.measures.map { it.id })
        r.name = name
        r.date = formatTime(createdTime)
        r.type = type
        r.result = measureResultText(type, points)
        r.color = colorToHex(colorInt)
        r.closed = type == Project.MeasureRecord.TYPE_AREA
        r.lineWidth = lineWidth
        r.points.addAll(points)
        p.measures.add(r)
        pushMeasureInsert(r)
        return r.id
    }

    /** 提交测量增量插入 */
    private fun pushMeasureInsert(m: Project.MeasureRecord) {
        val id = m.id; val nm = m.name; val dt = m.date; val ty = m.type; val rs = m.result
        val cl = m.color; val hd = m.hidden; val cd = m.closed; val lw = m.lineWidth; val pts = m.points
        enqueueDml {
            GeoPackageDml.insertMeasure(it, id, nm, dt, ty, rs, cl, hd, cd, lw, pts)
        }
    }

    fun removeMeasure(id: Long) {
        val p = currentProject ?: return
        if (p.measures.removeAll { it.id == id }) enqueueDml { GeoPackageDml.deleteMeasure(it, id) }
    }

    fun updateMeasureStyle(id: Long, colorInt: Int, lineWidth: Int) {
        findMeasure(id)?.let {
            it.color = colorToHex(colorInt)
            it.lineWidth = lineWidth
            val mid = it.id; val mcl = it.color; val mlw = it.lineWidth
            enqueueDml { GeoPackageDml.updateMeasureStyle(it, mid, mcl, mlw) }
        }
    }

    fun updateMeasureName(id: Long, name: String) {
        findMeasure(id)?.let {
            it.name = name
            val mid = it.id; val mn = it.name
            enqueueDml { GeoPackageDml.updateMeasureName(it, mid, mn) }
        }
    }

    // ---- 拍照标识 ----

    fun findPhoto(id: Long): Project.PhotoRecord? =
        currentProject?.photos?.firstOrNull { it.id == id }

    /** 新增拍照点位（首张照片前建会话点位），返回记录 id */
    fun addPhoto(name: String, lat: Double, lon: Double, timestamp: Long): Long {
        val p = currentProject ?: return -1
        val r = Project.PhotoRecord()
        r.id = nextId(p.photos.map { it.id })
        r.name = name
        r.lat = lat
        r.lon = lon
        r.timestamp = timestamp
        p.photos.add(r)
        pushPhotoInsert(r)
        return r.id
    }

    private fun pushPhotoInsert(ph: Project.PhotoRecord) {
        val id = ph.id; val nm = ph.name; val la = ph.lat; val lo = ph.lon; val al = ph.alt
        val ts = ph.timestamp; val vis = ph.visible; val gid = ph.groupId
        enqueueDml { GeoPackageDml.insertPhoto(it, id, nm, la, lo, al, ts, vis, gid) }
    }

    /**
     * 为拍照点位追加一个照片附件：内存模型追加后提交增量落盘——附件整行重写
     * （attachments JSON + photo_path），同时把照片本体读入 gpkg BLOB（主照片 photo_data /
     * 其余附件附加表），库提交成功后删除源文件（照片不再长期保留文件）。
     */
    fun addPhotoAttachment(photoId: Long, name: String, path: String, time: Long) {
        val photo = findPhoto(photoId) ?: return
        photo.attachments.add(Project.PhotoAttachment(name, path, time))
        val idx = photo.attachments.size - 1L
        val attsJson = JSONArray().apply {
            for (a in photo.attachments) put(a.toJson())
        }.toString()
        val mainPath = photo.attachments.firstOrNull()?.path
        enqueueDml { GeoPackageDml.addPhotoAttachment(it, photoId, idx, attsJson, mainPath, path) }
    }

    fun removePhoto(id: Long) {
        val p = currentProject ?: return
        if (p.photos.removeAll { it.id == id }) enqueueDml { GeoPackageDml.deletePhoto(it, id) }
    }

    fun updatePhotoName(id: Long, name: String) {
        findPhoto(id)?.let {
            it.name = name
            enqueueDml { GeoPackageDml.updatePhotoColumns(it, id, name, null, null) }
        }
    }

    fun updatePhotoGroup(id: Long, groupId: Long) {
        findPhoto(id)?.let {
            it.groupId = groupId
            enqueueDml { GeoPackageDml.updatePhotoColumns(it, id, null, groupId, null) }
        }
    }

    fun updatePhotoVisible(id: Long, visible: Boolean) {
        findPhoto(id)?.let {
            it.visible = visible
            enqueueDml { GeoPackageDml.updatePhotoColumns(it, id, null, null, visible) }
        }
    }

    // ---- 拍照分组 ----

    fun listGroups(): List<Project.MediaGroupRecord> =
        currentProject?.mediaGroups?.toList() ?: emptyList()

    fun findGroupByName(name: String): Project.MediaGroupRecord? =
        currentProject?.mediaGroups?.firstOrNull { it.name == name }

    fun addGroup(name: String, createdTime: Long): Long {
        val p = currentProject ?: return -1
        val g = Project.MediaGroupRecord()
        g.id = nextId(p.mediaGroups.map { it.id })
        g.name = name
        g.createdTime = createdTime
        p.mediaGroups.add(g)
        val gid = g.id; val gname = g.name; val gct = g.createdTime; val gvis = g.visible
        enqueueDml { GeoPackageDml.insertGroup(it, gid, gname, gct, gvis) }
        return g.id
    }

    fun renameGroup(id: Long, name: String) {
        currentProject?.mediaGroups?.firstOrNull { it.id == id }
            ?.let {
                it.name = name
                enqueueDml { GeoPackageDml.updateGroup(it, id, name) }
            }
    }

    /** 删除分组：组内点位不删除，移到未分组 */
    fun removeGroup(id: Long) {
        val p = currentProject ?: return
        if (p.mediaGroups.removeAll { it.id == id }) {
            for (photo in p.photos) {
                if (photo.groupId == id) photo.groupId = Project.PhotoRecord.UNGROUPED_ID
            }
            enqueueDml { GeoPackageDml.deleteGroup(it, id) }
        }
    }

    /** 批量设置分组内全部点位可见性（组开关直接作用于组内各点位） */
    fun setGroupPhotosVisible(groupId: Long, visible: Boolean) {
        val p = currentProject ?: return
        var changed = false
        for (photo in p.photos) {
            if (photo.groupId == groupId && photo.visible != visible) {
                photo.visible = visible
                changed = true
            }
        }
        if (changed) enqueueDml { GeoPackageDml.setGroupPhotosVisible(it, groupId, visible) }
    }

    // ---- 样地调查 ----

    fun findSurveyPlot(id: Long): Project.SurveyPlot? =
        currentProject?.surveyPlots?.firstOrNull { it.id == id }

    fun findSurveyPlots(sourcePath: String, sourceFid: Long): List<Project.SurveyPlot> =
        currentProject?.surveyPlots
            ?.filter { it.sourcePath == sourcePath && it.sourceFid == sourceFid }
            ?.sortedBy { it.plotNo }
            ?: emptyList()

    /** 获取下一个样地编号（同小班内最大编号 +1） */
    fun nextSurveyPlotNo(sourcePath: String, sourceFid: Long): Int =
        (findSurveyPlots(sourcePath, sourceFid).maxOfOrNull { it.plotNo } ?: 0) + 1

    /**
     * 保存样地（新增或更新）：id<=0 分配新 id 插入，否则按 id 原位更新（INSERT OR REPLACE 增量落盘）。
     * 返回保存后的样地（含 id），无当前工程返回 null。
     */
    fun saveSurveyPlot(plot: Project.SurveyPlot): Project.SurveyPlot? {
        val p = currentProject ?: return null
        val now = System.currentTimeMillis()
        plot.updatedAt = now
        if (plot.id <= 0) {
            plot.id = nextId(p.surveyPlots.map { it.id })
            if (plot.createdAt <= 0) plot.createdAt = now
            // 取号冲突复核：两个未保存草稿可能在表单装载时取到同一编号，插入前重排
            if (plot.plotNo > 0 &&
                p.surveyPlots.any { it.sourcePath == plot.sourcePath && it.sourceFid == plot.sourceFid && it.plotNo == plot.plotNo }
            ) {
                plot.plotNo = nextSurveyPlotNo(plot.sourcePath, plot.sourceFid)
            }
            p.surveyPlots.add(plot)
        } else if (p.surveyPlots.none { it.id == plot.id }) {
            p.surveyPlots.add(plot)
        }
        val id = plot.id; val nm = plot.name; val pty = plot.plotType
        val attrs = plot.attributesToJson().toString(); val pts = plot.points
        enqueueDml { GeoPackageDml.upsertSurveyPlot(it, id, nm, pty, attrs, pts) }
        return plot
    }

    // ---- 工具方法 ----

    private fun rememberCurrent(openedPath: String?) {
        val p = currentProject
        val path = openedPath ?: p?.let { File(projectsDir, sanitizeFileName(it.name ?: "") + ".gpkg").absolutePath }
        if (path != null) prefs.edit().putString(KEY_LAST_PROJECT, path).apply()
    }

    private fun sanitizeFileName(name: String): String {
        // 移除非法字符
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    }

    companion object {
        private const val TAG = "ProjectManager"
        private const val PREFS_NAME = "project_manager"
        private const val KEY_LAST_PROJECT = "last_project_path"
        private const val DEFAULT_PROJECT_NAME = "默认工程"

        /** 工程记录 date 字段格式（人读友好，QGIS 中直接可读） */
        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        fun formatTime(millis: Long): String =
            if (millis > 0) TIME_FORMAT.format(Date(millis)) else ""

        fun parseTime(text: String?): Long =
            runCatching { TIME_FORMAT.parse(text ?: "")?.time ?: 0L }.getOrDefault(0L)
                .takeIf { it > 0 } ?: System.currentTimeMillis()

        /** ARGB Int → "#RRGGBB"（去 alpha，工程交换格式不含透明度） */
        fun colorToHex(color: Int): String =
            String.format("#%06X", 0xFFFFFF and color)

        /** "#RRGGBB"/"#AARRGGBB" → ARGB Int，解析失败回默认 */
        fun parseColor(hex: String?, def: Int): Int {
            if (hex.isNullOrBlank()) return def
            return runCatching {
                val c = android.graphics.Color.parseColor(hex)
                if (hex.length <= 7) c or (0xFF shl 24) else c
            }.getOrDefault(def)
        }

        /** 测量结果文本（导出附属信息，几何为准）：距离=累计长度，面积=总面积，点=坐标；[pts] 内存序 [lat,lon] */
        fun measureResultText(type: String?, pts: List<DoubleArray>): String {
            return try {
                when (type) {
                    Project.MeasureRecord.TYPE_AREA -> {
                        val ring = pts.map { doubleArrayOf(it[0], it[1]) }
                        GeoCalc.formatArea(GeoCalc.polygonAreaSquareMeters(ring))
                    }
                    Project.MeasureRecord.TYPE_POINT -> if (pts.isNotEmpty())
                        String.format(Locale.US, "%.6f, %.6f", pts[0][0], pts[0][1]) else ""
                    else -> {
                        var total = 0.0
                        for (i in 1 until pts.size) {
                            total += GeoCalc.distanceMeters(
                                pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
                        }
                        GeoCalc.formatDistance(total)
                    }
                }
            } catch (e: Exception) {
                ""
            }
        }

        @Volatile
        private var instance: ProjectManager? = null

        /** 合并照片暂存目录名（cache 下，入库即删；异常残留由 [cleanupMergeTempFiles] 清扫） */
        private const val MERGE_TMP_DIR = "merge_tmp"

        @JvmStatic
        @Synchronized
        fun get(context: Context): ProjectManager {
            return instance ?: ProjectManager(context.applicationContext).also { instance = it }
        }
    }

    // ---- 工程信息（列表展示用）----

    class ProjectInfo {
        var fileName: String? = null
        var filePath: String? = null
        var fileSize: Long = 0
        var lastModified: Long = 0
        var displaySize: String? = null
        var displayDate: String? = null
    }
}
