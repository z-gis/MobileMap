package com.zys.mobilemap.project

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zys.mobilemap.db.MapDataStore
import com.zys.mobilemap.media.MediaStore
import com.zys.mobilemap.survey.SampleSurveyStore
import com.zys.mobilemap.util.GeoCalc
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工程管理器（0.4.0.0）
 *
 * 负责工程的 CRUD、打开/保存/导出、与 Document 的关联。
 * 工程文件存储在 <项目根>/projects/<工程名>.gpkg（GeoPackage 格式）。
 *
 * 数据流（镜像同步架构）：
 *  - 采集/管理功能仍写既有工作库（map.db / media.db / sample_survey.db），叠加层显示链路不变；
 *  - 工作库数据变化后由 BusinessOverlayManager 触发 [requestAutosave]，防抖后 [syncFromStores]
 *    把工作库全量镜像进当前工程并写 gpkg —— 工程文件始终是工作库的完整快照；
 *  - 打开工程 = [importToStores] 清库导入 gpkg（工作库切换为工程内容），随后叠加层按常规重建；
 *  - 分享 = [exportProject] 先同步再导出到公共目录，gpkg 单文件可直接分享，QGIS/ArcGIS 可读。
 *
 * 注意：工作库是全局唯一的，打开工程会替换其内容，故任一时刻工作库 == 当前工程；
 * 样地调查（SampleSurveyStore）仅单向导出到工程（attributes 携带关键字段），不回导（其表单结构比工程模型丰富）。
 */
class ProjectManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val gpkgWriter = GeoPackageWriter(appContext)
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    var currentProject: Project? = null
        private set

    val projectsDir: File = File(appContext.getExternalFilesDir(null), "projects")

    init {
        if (!projectsDir.exists()) projectsDir.mkdirs()
    }

    // ---- 启动装载 ----

    /**
     * 确保已装载工程（App 启动时调用一次，幂等）：
     * 上次打开的工程文件仍在 → 读入并导入工作库；
     * 否则创建「默认工程」并立即同步（既有工作库数据自动吸入，兼容 0.3.x 老数据，免单独迁移工具）。
     */
    fun ensureLoaded() {
        if (currentProject != null) return
        val last = prefs.getString(KEY_LAST_PROJECT, null)
        if (last != null && File(last).exists()) {
            val p = gpkgWriter.read(last)
            if (p != null) {
                currentProject = p
                importToStores(p)
                Log.i(TAG, "ensureLoaded: 恢复上次工程 ${p.name}")
                return
            }
            Log.w(TAG, "ensureLoaded: 上次工程读取失败，重建默认工程 $last")
        }
        val p = createProject(DEFAULT_PROJECT_NAME, "自动创建，收纳现有采集数据")
        syncFromStores()
        saveProject(p)
        rememberCurrent(last)
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

    /** 新建空工程并落盘、导入（清空工作库），返回成功与否。工程管理对话框「新建」入口。 */
    fun createEmptyProject(name: String, description: String): Boolean {
        // 先把当前工程最新数据落盘，再切换
        currentProject?.let { syncFromStores(); saveProject(it) }
        val p = createProject(name, description)
        val ok = saveProject(p)
        if (ok) {
            importToStores(p) // 空工程 → 清空工作库
            rememberCurrent(null)
        }
        return ok
    }

    /** 检查是否有打开的工程 */
    fun hasProject(): Boolean = currentProject != null

    /** 关闭当前工程（自动保存） */
    fun closeProject() {
        currentProject?.let { syncFromStores(); saveProject(it) }
        currentProject = null
        prefs.edit().remove(KEY_LAST_PROJECT).apply()
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

    /** 只读读取工程（不切换当前工程、不导入工作库，供导出/复制非当前工程用） */
    fun readProject(filePath: String): Project? = gpkgWriter.read(filePath)

    /** 打开工程：当前工程先同步落盘 → 读 gpkg → 导入工作库 → 记为最近工程 */
    fun openProject(filePath: String): Project? {
        currentProject?.let { syncFromStores(); saveProject(it) }
        val p = gpkgWriter.read(filePath)
        if (p != null) {
            currentProject = p
            importToStores(p)
            rememberCurrent(filePath)
            Log.i(TAG, "openProject: $filePath -> ${p.name}")
        } else {
            Log.e(TAG, "openProject: 读取失败 $filePath")
        }
        return p
    }

    /** 保存工程（先同步工作库，再写入 GeoPackage） */
    fun saveProject(project: Project?): Boolean {
        if (project == null) return false
        if (project === currentProject) syncFromStores()
        project.updateTime = System.currentTimeMillis()
        val fileName = sanitizeFileName(project.name ?: "未命名工程") + ".gpkg"
        val filePath = File(projectsDir, fileName).absolutePath
        val ok = gpkgWriter.write(project, filePath)
        Log.i(TAG, "saveProject: ${project.name} -> $ok")
        return ok
    }

    /** 另存为 */
    fun saveProjectAs(project: Project?, newName: String): Boolean {
        if (project == null) return false
        project.name = newName
        project.updateTime = System.currentTimeMillis()
        return saveProject(project)
    }

    /** 导出工程为 GeoPackage（先同步，再写到 Download/调查宝/ 供分享） */
    fun exportProject(project: Project?): File? {
        if (project == null) return null
        if (project === currentProject) syncFromStores()
        project.updateTime = System.currentTimeMillis()
        val exportDir = File("/sdcard/Download/调查宝")
        if (!exportDir.exists()) exportDir.mkdirs()
        val fileName = "${sanitizeFileName(project.name ?: "未命名工程")}_${System.currentTimeMillis()}.gpkg"
        val filePath = File(exportDir, fileName).absolutePath
        val ok = gpkgWriter.write(project, filePath)
        if (ok) {
            Log.i(TAG, "exportProject: ${project.name} -> $filePath")
            return File(filePath)
        }
        Log.e(TAG, "exportProject: 导出失败 ${project.name}")
        return null
    }

    /** 删除工程 */
    fun deleteProject(fileName: String): Boolean {
        val f = File(projectsDir, fileName)
        val ok = f.delete()
        val cur = currentProject
        if (ok && cur != null &&
                sanitizeFileName(cur.name ?: "") == fileName.removeSuffix(".gpkg")) {
            currentProject = null
            prefs.edit().remove(KEY_LAST_PROJECT).apply()
        }
        Log.i(TAG, "deleteProject: $fileName -> $ok")
        return ok
    }

    /** 重命名工程文件（若重命名的是当前工程，同步改工程名并落盘） */
    fun renameProject(oldFileName: String, newName: String): Boolean {
        val oldFile = File(projectsDir, oldFileName)
        val newFile = File(projectsDir, sanitizeFileName(newName) + ".gpkg")
        val ok = oldFile.renameTo(newFile)
        val cur = currentProject
        if (ok && cur != null &&
                sanitizeFileName(cur.name ?: "") == oldFileName.removeSuffix(".gpkg")) {
            cur.name = newName
            saveProject(cur)
            rememberCurrent(newFile.absolutePath)
        }
        Log.i(TAG, "renameProject: $oldFileName -> $newName $ok")
        return ok
    }

    /** 复制工程（直读文件，不切换当前工程） */
    fun duplicateProject(fileName: String, newName: String): Boolean {
        val p = gpkgWriter.read(File(projectsDir, fileName).absolutePath) ?: return false
        p.name = newName
        p.createTime = System.currentTimeMillis()
        p.updateTime = System.currentTimeMillis()
        val path = File(projectsDir, sanitizeFileName(newName) + ".gpkg").absolutePath
        return gpkgWriter.write(p, path)
    }

    // ---- 工作库 → 工程镜像同步 ----

    private val autosaveRunnable = Runnable {
        val p = currentProject ?: return@Runnable
        syncFromStores()
        saveProject(p)
    }

    /**
     * 请求自动保存（防抖 1.5s）：工作库数据变化后由叠加层重建链路触发。
     * 合并窗口内的连续变化（轨迹逐点、连拍），避免频繁整文件重写。
     */
    fun requestAutosave() {
        if (currentProject == null) return
        mainHandler.removeCallbacks(autosaveRunnable)
        mainHandler.postDelayed(autosaveRunnable, AUTOSAVE_DELAY_MS)
    }

    /**
     * 工作库全量镜像进当前工程（tracks/measures/photos/surveyPlots 重建，元数据保留）。
     * 坐标序转换：工作库点串为 [lat,lon,alt]，工程/gpkg 为 [lon,lat,alt]。
     * 线宽属显示样式不入工程（gpkg 表结构不含），工程记录 id 取工作库主键（仅本次镜像内稳定）。
     */
    fun syncFromStores() {
        val p = currentProject ?: return

        p.tracks.clear()
        runCatching {
            for (t in MapDataStore.getInstance(appContext).loadTracks()) {
                val r = Project.TrackRecord()
                r.id = t.id
                r.name = t.name
                r.date = formatTime(t.createdTime)
                r.color = colorToHex(t.color)
                r.hidden = !t.visible
                for (pt in t.points) {
                    r.points.add(doubleArrayOf(pt[1], pt[0], pt.getOrElse(2) { 0.0 }))
                }
                p.tracks.add(r)
            }
        }.onFailure { Log.e(TAG, "同步轨迹失败", it) }

        p.measures.clear()
        runCatching {
            for (m in MapDataStore.getInstance(appContext).loadMeasurements()) {
                val r = Project.MeasureRecord()
                r.id = m.id
                r.name = m.name
                r.date = formatTime(m.createdTime)
                r.type = m.type
                r.result = measureResultText(m.type, m.points)
                r.color = colorToHex(m.color)
                r.hidden = false
                r.closed = m.type == "area"
                for (pt in m.points) {
                    r.points.add(doubleArrayOf(pt[1], pt[0], pt.getOrElse(2) { 0.0 }))
                }
                p.measures.add(r)
            }
        }.onFailure { Log.e(TAG, "同步测量失败", it) }

        p.photos.clear()
        runCatching {
            for (pm in MediaStore.getInstance(appContext).loadPlacemarks()) {
                val r = Project.PhotoRecord()
                r.id = pm.id
                r.name = pm.name
                r.lat = pm.latitude
                r.lon = pm.longitude
                r.alt = 0.0
                r.photoPath = pm.attachments.firstOrNull()?.path ?: ""
                r.timestamp = pm.createdTime
                p.photos.add(r)
            }
        }.onFailure { Log.e(TAG, "同步拍照失败", it) }

        p.surveyPlots.clear()
        runCatching {
            for (s in SampleSurveyStore.loadAllPlots(appContext)) {
                if (s.centerLatitude.isNaN() || s.centerLongitude.isNaN()) continue
                val r = Project.SurveyPlot()
                r.id = s.id
                r.name = "样地#${s.plotNo}"
                r.plotType = "point"
                r.points.add(doubleArrayOf(s.centerLongitude, s.centerLatitude, 0.0))
                r.attributes = JSONObject().apply {
                    put("plotNo", s.plotNo)
                    put("compartmentNo", s.compartmentNo)
                    put("sourcePath", s.sourcePath)
                    put("sourceFid", s.sourceFid)
                    put("updatedAt", s.updatedAt)
                }
                p.surveyPlots.add(r)
            }
        }.onFailure { Log.e(TAG, "同步样地失败", it) }

        p.updateTime = System.currentTimeMillis()
    }

    // ---- 工程 → 工作库导入（打开工程）----

    /**
     * 工程数据导入工作库（替换式）：清空后按工程内容重建。
     * 轨迹草稿（recording=1）保留，不打断进行中的记录；照片文件不删（附件按路径存在性回补）；
     * 样地调查不回导（工程仅持有点位与摘要属性）。
     */
    private fun importToStores(p: Project) {
        val mapStore = MapDataStore.getInstance(appContext)
        val mediaStore = MediaStore.getInstance(appContext)

        runCatching { mapStore.clearFinishedData() }.onFailure { Log.e(TAG, "清空轨迹/测量失败", it) }
        runCatching { mediaStore.deleteAllPlacemarks() }.onFailure { Log.e(TAG, "清空拍照点位失败", it) }

        for (t in p.tracks) {
            runCatching {
                val pts = t.points.map { doubleArrayOf(it[1], it[0], it.getOrElse(2) { 0.0 }) }
                val id = mapStore.addTrack(
                    t.name ?: "轨迹", parseTime(t.date), pts,
                    color = parseColor(t.color, com.zys.mobilemap.db.TrackRecord.DEFAULT_COLOR)
                )
                if (id >= 0 && t.hidden) mapStore.updateTrackVisible(id, false)
            }.onFailure { Log.e(TAG, "导入轨迹失败: ${t.name}", it) }
        }

        for (m in p.measures) {
            runCatching {
                val dbType = when (m.type) {
                    "point" -> "point"
                    "area", "polygon" -> "area"
                    else -> "distance" // distance/angle/line 统一按线显示
                }
                val pts = m.points.map { doubleArrayOf(it[1], it[0], it.getOrElse(2) { 0.0 }) }
                mapStore.addMeasurement(
                    m.name ?: "测量", dbType,
                    parseColor(m.color, 0xFFE91E63.toInt()),
                    parseTime(m.date), pts
                )
            }.onFailure { Log.e(TAG, "导入测量失败: ${m.name}", it) }
        }

        for (ph in p.photos) {
            runCatching {
                val id = mediaStore.insertPlacemark(
                    ph.name ?: "拍照", ph.lat, ph.lon,
                    if (ph.timestamp > 0) ph.timestamp else System.currentTimeMillis()
                )
                // 照片文件本机仍在（本机工程往返）则回补附件记录；分享来的工程照片路径不存在则仅留点位
                val path = ph.photoPath
                if (id >= 0 && !path.isNullOrEmpty() && File(path).exists()) {
                    mediaStore.insertAttachment(
                        id, com.zys.mobilemap.media.MediaAttachment.TYPE_PHOTO,
                        File(path).name, path, ph.timestamp
                    )
                }
            }.onFailure { Log.e(TAG, "导入拍照失败: ${ph.name}", it) }
        }
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

    // ---- 数据操作（添加到当前工程，一般由镜像同步代劳；保留给直接追加场景）----

    fun addTrack(track: Project.TrackRecord) {
        currentProject?.let {
            it.tracks.add(track)
            it.updateTime = System.currentTimeMillis()
        }
    }

    fun addMeasure(measure: Project.MeasureRecord) {
        currentProject?.let {
            it.measures.add(measure)
            it.updateTime = System.currentTimeMillis()
        }
    }

    fun addPhoto(photo: Project.PhotoRecord) {
        currentProject?.let {
            it.photos.add(photo)
            it.updateTime = System.currentTimeMillis()
        }
    }

    fun addSurveyPlot(plot: Project.SurveyPlot) {
        currentProject?.let {
            it.surveyPlots.add(plot)
            it.updateTime = System.currentTimeMillis()
        }
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

        private fun formatTime(millis: Long): String =
            if (millis > 0) TIME_FORMAT.format(Date(millis)) else ""

        private fun parseTime(text: String?): Long =
            runCatching { TIME_FORMAT.parse(text ?: "")?.time ?: 0L }.getOrDefault(0L)
                .takeIf { it > 0 } ?: System.currentTimeMillis()

        /** ARGB Int → "#RRGGBB"（去 alpha，工程交换格式不含透明度） */
        private fun colorToHex(color: Int): String =
            String.format("#%06X", 0xFFFFFF and color)

        /** "#RRGGBB"/"#AARRGGBB" → ARGB Int，解析失败回默认 */
        private fun parseColor(hex: String?, def: Int): Int {
            if (hex.isNullOrBlank()) return def
            return runCatching {
                val c = android.graphics.Color.parseColor(hex)
                if (hex.length <= 7) c or (0xFF shl 24) else c
            }.getOrDefault(def)
        }

        /** 测量结果文本（导出附属信息，几何为准）：距离=累计长度，面积=总面积，点=坐标 */
        private fun measureResultText(type: String?, pts: List<DoubleArray>): String {
            return try {
                when (type) {
                    "area", "polygon" -> {
                        val ring = pts.map { doubleArrayOf(it[0], it[1]) }
                        GeoCalc.formatArea(GeoCalc.polygonAreaSquareMeters(ring))
                    }
                    "point" -> if (pts.isNotEmpty())
                        String.format(Locale.US, "%.6f, %.6f", pts[0][1], pts[0][0]) else ""
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
