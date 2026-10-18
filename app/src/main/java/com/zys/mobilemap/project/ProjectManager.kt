package com.zys.mobilemap.project

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.GeoCalc
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工程管理器（0.4.0.0 单库版）
 *
 * 工程 gpkg 是采集数据（轨迹/测量/拍照/样地）的唯一数据本体，旧工作库
 * （map.db / media.db / sample_survey.db）已废弃，仅在首启由 [LegacyMigration]
 * 一次性迁入「默认工程」（旧文件保留不删）。
 *
 * 数据流：
 *  - 采集/管理功能直接读写 [currentProject] 内存模型，变更经 [requestSave] 防抖落盘 gpkg；
 *  - 打开工程 = 读 gpkg 替换 [currentProject]，叠加层随后按签名重建；
 *  - 分享 = 先落盘再直接分享 gpkg 文件本体（QGIS/ArcGIS 可读）。
 *
 * 工程文件存储在 /调查宝/projects/<工程名>.gpkg（公共目录，卸载不清除、文件管理器可直接取走）。
 * 内存模型坐标序为 [lat,lon,alt]（与采集/叠加层一致），gpkg 轴序转换在 [Project.toJson] 边界完成。
 */
class ProjectManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val gpkgWriter = GeoPackageWriter(appContext)
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    var currentProject: Project? = null
        private set

    val projectsDir: File = AppDirectories.getProjectDir(appContext)

    init {
        migrateLegacyProjectsDir()
    }

    /**
     * 一次性迁移：0.4.0.0 早期构建把工程放在应用私有目录（Android/data/<pkg>/files/projects），
     * 现已统一到 /调查宝/projects/（卸载不清除、文件管理器可见）。迁移同挂载点 rename 秒级完成；
     * 最近工程路径同步重映射，迁移后旧目录文件清空则删除空目录。
     */
    private fun migrateLegacyProjectsDir() {
        val legacy = File(appContext.getExternalFilesDir(null) ?: return, "projects")
        if (legacy.absolutePath == projectsDir.absolutePath || !legacy.isDirectory) return
        val moved = legacy.listFiles { _, n -> n.endsWith(".gpkg") } ?: return
        for (f in moved) {
            val target = File(projectsDir, f.name)
            // 同名冲突（上次迁移已搬过）：改带序号的名字保留，绝不删除既有工程文件
            val ok: Boolean
            if (!target.exists()) {
                ok = f.renameTo(target)
            } else {
                var i = 1
                var alt = File(projectsDir, "${f.nameWithoutExtension}_$i.gpkg")
                while (alt.exists()) {
                    i++
                    alt = File(projectsDir, "${f.nameWithoutExtension}_$i.gpkg")
                }
                ok = f.renameTo(alt)
            }
            Log.i(TAG, "迁移旧工程文件 ${f.name} -> $ok")
        }
        val last = prefs.getString(KEY_LAST_PROJECT, null)
        if (last != null && last.startsWith(legacy.absolutePath)) {
            prefs.edit()
                .putString(KEY_LAST_PROJECT, File(projectsDir, File(last).name).absolutePath)
                .apply()
        }
        runCatching { legacy.delete() } // 空目录才删得掉
    }

    // ---- 启动装载 ----

    /**
     * 确保已装载工程（App 启动时调用一次，幂等）：
     * 上次打开的工程文件仍在 → 读入；否则创建「默认工程」，并由 [LegacyMigration]
     * 把旧工作库（map.db / media.db / sample_survey.db，若存在）一次性迁入后落盘。
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
        // 重装/清数据后最近工程 pref 丢失，但默认工程文件仍在：直接打开，避免重建触发旧库重迁并覆盖既有数据
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
        val migrated = LegacyMigration.migrateInto(appContext, p)
        saveProject(p)
        rememberCurrent(null)
        val migText = if (migrated) "有" else "无"
        Log.i(TAG, "ensureLoaded: 已创建默认工程（旧库迁移 ${migText}数据）")
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
        val ok = saveNowProject(p)
        if (ok) rememberCurrent(null)
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
     *  同步落盘防竞态：异步写时「切换到当前工程自身」（或 1.5s 防抖窗口内切换）会读到
     *  GDAL 整文件重建中的半成品，导致切换失败。
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

    /** 保存工程（主线程快照 + 后台线程落盘：GDAL 整文件重写不再阻塞主线程，删除/记录轨迹时平移地图不卡顿）
     *  返回 true 表示已提交落盘任务（写入结果异步，失败仅记日志；需确定性落盘用 [saveNow]） */
    fun saveProject(project: Project?): Boolean {
        if (project == null) return false
        mainHandler.removeCallbacks(autosaveRunnable)
        project.updateTime = System.currentTimeMillis()
        val fileName = sanitizeFileName(project.name ?: "未命名工程") + ".gpkg"
        val filePath = File(projectsDir, fileName).absolutePath
        val json = gpkgWriter.encode(project) // 主线程快照，此后内存工程可继续改
        saveExecutor.execute { gpkgWriter.writeEncoded(json, filePath) }
        Log.i(TAG, "saveProject(异步): ${project.name} 已提交")
        return true
    }

    /** 当前工程 gpkg 文件绝对路径（由 name 推导，与 [saveNowProject] 同规则）；无当前工程返回 null */
    val currentProjectPath: String?
        get() = currentProject?.let {
            File(projectsDir, sanitizeFileName(it.name ?: "未命名工程") + ".gpkg").absolutePath
        }

    /** 保存任务封装：快照 + 目标路径（同步等待类 API 共用） */
    private fun prepareWrite(project: Project, filePath: String): Pair<String, String> =
        Pair(gpkgWriter.encode(project), filePath)

    /**
     * 同步落盘指定工程（返回真实写入结果）。适用于「写后立即读文件/枚举列表/分享/删除/重命名」的场景——
     * 异步 [saveProject] 的「调用返回≠已落盘」在这些流程会产生竞态：读到整文件重建中的半成品、
     * 列表枚举不到新文件、删除后又被队列尾部的写任务复活。低频文件管理操作可承受同步等待。
     */
    fun saveNowProject(project: Project?): Boolean {
        if (project == null) return false
        project.updateTime = System.currentTimeMillis()
        val fileName = sanitizeFileName(project.name ?: "未命名工程") + ".gpkg"
        val filePath = File(projectsDir, fileName).absolutePath
        val (json, _) = prepareWrite(project, filePath)
        return runCatching {
            saveExecutor.submit(java.util.concurrent.Callable { gpkgWriter.writeEncoded(json, filePath) }).get()
        }.getOrElse { false }
    }

    /**
     * 等待后台保存队列排空（栅栏）：串行池提交空任务并阻塞至其执行，此前所有写任务必已完成。
     * 供删除/重命名工程文件前调用，防止队列尾部同路径写任务让已删文件复活。
     */
    private fun awaitSaveQueue() {
        runCatching { saveExecutor.submit({}).get() }
    }

    /**
     * 退出保存：主线程同步快照 + 立即提交后台队列，**不等待落盘**直接返回。
     * 退出即刻完成（用户决策：不因保存等待而卡退出）；风险兜底是工程管理的「备份」按钮——
     * 极端情况（进程瞬间被杀、后台写未及完成）仅丢最后一次保存窗口。
     */
    fun saveOnExit() {
        val p = currentProject ?: return
        p.updateTime = System.currentTimeMillis()
        val filePath = File(projectsDir, sanitizeFileName(p.name ?: "未命名工程") + ".gpkg").absolutePath
        val json = gpkgWriter.encode(p)
        saveExecutor.submit { gpkgWriter.writeEncoded(json, filePath) }
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

    /** 导出工程为 GeoPackage（先落盘当前工程，再写到 Download/调查宝/ 供分享）。
     *  导出文件由 [ok] 路径直接交给系统分享，调用返回时文件必须已在磁盘——同步等待写完。 */
    fun exportProject(project: Project?): File? {
        if (project == null) return null
        project.updateTime = System.currentTimeMillis()
        val exportDir = File("/sdcard/Download/调查宝")
        if (!exportDir.exists()) exportDir.mkdirs()
        val fileName = "${sanitizeFileName(project.name ?: "未命名工程")}_${System.currentTimeMillis()}.gpkg"
        val filePath = File(exportDir, fileName).absolutePath
        val (json, _) = prepareWrite(project, filePath)
        val ok = runCatching {
            saveExecutor.submit(java.util.concurrent.Callable { gpkgWriter.writeEncoded(json, filePath) }).get()
        }.getOrElse { false }
        if (ok) {
            Log.i(TAG, "exportProject: $filePath")
            return File(filePath)
        }
        Log.e(TAG, "exportProject: 导出失败 ${project.name}")
        return null
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

    /** 重命名工程文件（若重命名的是当前工程，同步改工程名并落盘）。
     *  先栅栏排空队列再 renameTo：否则队列尾部旧路径写任务会把旧名文件复活成「副本」。 */
    fun renameProject(oldFileName: String, newName: String): Boolean {
        awaitSaveQueue()
        val oldFile = File(projectsDir, oldFileName)
        val newFile = File(projectsDir, sanitizeFileName(newName) + ".gpkg")
        val ok = oldFile.renameTo(newFile)
        val cur = currentProject
        if (ok && cur != null &&
                sanitizeFileName(cur.name ?: "") == oldFileName.removeSuffix(".gpkg")) {
            cur.name = newName
            saveNowProject(cur)
            rememberCurrent(newFile.absolutePath)
        }
        Log.i(TAG, "renameProject: $oldFileName -> $newName $ok")
        return ok
    }

    /** 复制工程（直读文件，不切换当前工程；写盘同步等待——复制完成的提示须以真实落盘为准） */
    fun duplicateProject(fileName: String, newName: String): Boolean {
        val p = gpkgWriter.read(File(projectsDir, fileName).absolutePath) ?: return false
        p.name = newName
        p.createTime = System.currentTimeMillis()
        p.updateTime = System.currentTimeMillis()
        val path = File(projectsDir, sanitizeFileName(newName) + ".gpkg").absolutePath
        val (json, _) = prepareWrite(p, path)
        return runCatching {
            saveExecutor.submit(java.util.concurrent.Callable { gpkgWriter.writeEncoded(json, path) }).get()
        }.getOrElse { false }
    }

    // ---- 防抖自动保存 ----

    /** GeoPackage 写盘线程池（单线程串行）：GDAL 整文件重写是重 IO，全部离开主线程；
     *  串行保证写写不并发，队列按序写最终以最新快照收尾（防抖已合并连续变更，队列深度 1~2）。 */
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "gpkg-save").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val autosaveRunnable = Runnable {
        val p = currentProject ?: return@Runnable
        saveProject(p)
    }

    /**
     * 请求自动保存（防抖 1.5s）：工程数据变更后由各 mutation API 触发。
     * 合并窗口内的连续变化（轨迹逐点、连拍），避免频繁整文件重写。
     */
    fun requestSave() {
        if (currentProject == null) return
        mainHandler.removeCallbacks(autosaveRunnable)
        mainHandler.postDelayed(autosaveRunnable, AUTOSAVE_DELAY_MS)
    }

    /**
     * 立即同步落盘当前工程（跳过防抖）：定型类关键写入（轨迹停表入库、拍照入库、退出前兜底），
     * 调用返回时数据已在磁盘，防抖窗口内进程被杀不丢失。
     */
    fun saveNow() {
        mainHandler.removeCallbacks(autosaveRunnable)
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
        requestSave()
        return r.id
    }

    fun removeTrack(id: Long) {
        val p = currentProject ?: return
        if (p.tracks.removeAll { it.id == id }) requestSave()
    }

    fun updateTrackName(id: Long, name: String) {
        findTrack(id)?.let { it.name = name; requestSave() }
    }

    fun updateTrackVisible(id: Long, visible: Boolean) {
        findTrack(id)?.let { it.hidden = !visible; requestSave() }
    }

    fun updateTrackStyle(id: Long, colorInt: Int, lineWidth: Int) {
        findTrack(id)?.let {
            it.color = colorToHex(colorInt)
            it.lineWidth = lineWidth
            requestSave()
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
        requestSave()
        return r.id
    }

    fun removeMeasure(id: Long) {
        val p = currentProject ?: return
        if (p.measures.removeAll { it.id == id }) requestSave()
    }

    fun updateMeasureStyle(id: Long, colorInt: Int, lineWidth: Int) {
        findMeasure(id)?.let {
            it.color = colorToHex(colorInt)
            it.lineWidth = lineWidth
            requestSave()
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
        requestSave()
        return r.id
    }

    /** 为拍照点位追加一个照片附件 */
    fun addPhotoAttachment(photoId: Long, name: String, path: String, time: Long) {
        findPhoto(photoId)?.let {
            it.attachments.add(Project.PhotoAttachment(name, path, time))
            requestSave()
        }
    }

    fun removePhoto(id: Long) {
        val p = currentProject ?: return
        if (p.photos.removeAll { it.id == id }) requestSave()
    }

    fun updatePhotoName(id: Long, name: String) {
        findPhoto(id)?.let { it.name = name; requestSave() }
    }

    fun updatePhotoGroup(id: Long, groupId: Long) {
        findPhoto(id)?.let { it.groupId = groupId; requestSave() }
    }

    fun updatePhotoVisible(id: Long, visible: Boolean) {
        findPhoto(id)?.let { it.visible = visible; requestSave() }
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
        requestSave()
        return g.id
    }

    fun renameGroup(id: Long, name: String) {
        currentProject?.mediaGroups?.firstOrNull { it.id == id }
            ?.let { it.name = name; requestSave() }
    }

    /** 删除分组：组内点位不删除，移到未分组 */
    fun removeGroup(id: Long) {
        val p = currentProject ?: return
        if (p.mediaGroups.removeAll { it.id == id }) {
            for (photo in p.photos) {
                if (photo.groupId == id) photo.groupId = Project.PhotoRecord.UNGROUPED_ID
            }
            requestSave()
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
        if (changed) requestSave()
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
     * 保存样地（新增或更新）：id<=0 分配新 id 插入，否则按 id 原位更新。
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
        requestSave()
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

        /** 自动保存防抖窗口（毫秒） */
        private const val AUTOSAVE_DELAY_MS = 1500L

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
