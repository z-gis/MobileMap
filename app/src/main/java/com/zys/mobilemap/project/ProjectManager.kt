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
 * 工程管理器（0.5.0 增量落盘版）
 *
 * 工程 gpkg 是采集数据（轨迹/测量/拍照/样地）的唯一数据本体，旧工作库
 * （map.db / media.db / sample_survey.db）已废弃，仅在首启由 [LegacyMigration]
 * 一次性迁入「默认工程」（旧文件保留不删）。
 *
 * 数据流：
 *  - 采集/管理功能改 [currentProject] 内存模型，每次 mutation 同步提交毫秒级增量 DML
 *    （app 端 SQLite 直写 gpkg 业务表，见 [GeoPackageDml]）到后台串行队列；
 *  - 定型写入（停表/拍照/退出）用 [saveNow] 做队列栅栏——只等排队的增量任务，毫秒级返回；
 *  - 整文件重建（JNI GDAL）仅用于：新建工程文件、老库 schema 升级（[ensureProjectReady] 内触发）；
 *  - 分享/导出/复制 = 队列栅栏后直接复制 gpkg 文件本体（单文件自包含，QGIS/ArcGIS 可读）；
 *  - 打开工程 = 读 gpkg 替换 [currentProject]，随后 [ensureProjectReady] 后台做 schema 检查、
 *    RTree 触发器清理与照片文件清扫（BLOB 已入库的磁盘原件退役）。
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
                ensureProjectReady(p, last)
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
                    ensureProjectReady(p, defaultFile.absolutePath)
                    Log.i(TAG, "ensureLoaded: 默认工程文件已存在，直接打开")
                    return
                }
            }
        }
        val p = createProject(DEFAULT_PROJECT_NAME, "自动创建，收纳现有采集数据")
        val migrated = LegacyMigration.migrateInto(appContext, p)
        val filePath = File(projectsDir, "$DEFAULT_PROJECT_NAME.gpkg").absolutePath
        writeProjectFileSync(p, filePath)
        ensureProjectReady(p, filePath)
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
        val filePath = File(projectsDir, sanitizeFileName(name) + ".gpkg").absolutePath
        val ok = writeProjectFileSync(p, filePath)
        if (ok) {
            rememberCurrent(null)
            ensureProjectReady(p, filePath)
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

    /** 打开工程：当前工程先同步落盘 → 读 gpkg 切换 [currentProject] → 记为最近工程 → 后台就绪检查。
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
            ensureProjectReady(p, filePath)
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

    /** 主线程快照 + 后台整文件重建（JNI GDAL）：仅用于新建工程文件；老库升级见 [ensureProjectReady] */
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
     * 工程打开/新建后的后台就绪流程（幂等）：
     *  1. schema 检查——老库缺列时整文件重建升级（快照在此前的主线程 encode 完成）；
     *  2. 移除 RTree 触发器/索引表（GDAL 专属 ST_* 函数纯 SQLite 不可执行）并补建附件 BLOB 附加表；
     *  3. 照片文件清扫——BLOB 已入库的磁盘原件退役，BLOB 缺失的从文件补收后删原件。
     */
    private fun ensureProjectReady(project: Project, filePath: String) {
        val json = if (GeoPackageDml.schemaCurrent(filePath)) null else gpkgWriter.encode(project)
        saveExecutor.execute {
            if (json != null) {
                gpkgWriter.writeEncoded(json, filePath)
                Log.i(TAG, "ensureProjectReady: 老库 schema 升级重建 $filePath")
            }
            runCatching { GeoPackageDml.prepareSchema(filePath) }
                .onFailure { Log.e(TAG, "ensureProjectReady: prepareSchema 失败 $filePath", it) }
            runCatching {
                GeoPackageDml.sweepPhotoFiles(filePath, AppDirectories.getMediaDir(appContext).absolutePath)
            }.onFailure { Log.e(TAG, "ensureProjectReady: 照片文件清扫失败 $filePath", it) }
        }
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
