package com.zys.mobilemap.project

import android.content.Context
import android.util.Log
import com.zys.mobilemap.db.MapDataStore
import com.zys.mobilemap.media.MediaStore
import com.zys.mobilemap.survey.SampleSurveyStore
import com.zys.mobilemap.util.AppDirectories
import java.io.File

/**
 * 旧工作库一次性迁移（0.4.0.0 单库版）。
 *
 * 旧版本采集数据存于三个 SQLite 工作库：/调查宝/map/map.db（轨迹/测量）、
 * /调查宝/Media/media.db（拍照点位/附件/分组）、/调查宝/sample/sample_survey.db（样地调查）。
 * 单库切换后它们废弃：首启创建「默认工程」时由本类把旧库数据一次性读入工程（旧文件保留不删），
 * 此后所有读写只走工程 gpkg，旧库不再触达。
 *
 * 三个旧 store 类（[MapDataStore]/[MediaStore]/[SampleSurveyStore]）保留仅作本迁移的读取器，
 * 不再被其它代码使用。迁移前先判库文件存在性（store 构造会 openOrCreate 建空库，须避免误建）。
 */
object LegacyMigration {

    private const val TAG = "LegacyMigration"

    /**
     * 把存在的旧库数据迁入工程 [p]（幂等性差、仅限首启默认工程创建时调用一次）。
     * @return 是否迁入了任何数据
     */
    fun migrateInto(context: Context, p: Project): Boolean {
        var migrated = false
        runCatching { migrated = migrateMapStore(context, p) || migrated }
            .onFailure { Log.e(TAG, "迁移 map.db 失败", it) }
        runCatching { migrated = migrateMediaStore(context, p) || migrated }
            .onFailure { Log.e(TAG, "迁移 media.db 失败", it) }
        runCatching { migrated = migrateSurveyStore(context, p) || migrated }
            .onFailure { Log.e(TAG, "迁移 sample_survey.db 失败", it) }
        if (migrated) {
            Log.i(TAG, "旧库迁移完成: tracks=${p.tracks.size} measures=${p.measures.size} " +
                    "photos=${p.photos.size} groups=${p.mediaGroups.size} plots=${p.surveyPlots.size}")
        }
        return migrated
    }

    /** 旧三库（map.db / media.db / sample_survey.db）任一仍存在：启动期判定是否走迁移文案的依据 */
    fun hasLegacyStores(context: Context): Boolean {
        val root = AppDirectories.getRootDir(context)
        return File(File(root, AppDirectories.MAP_DIR_NAME), "map.db").exists() ||
                File(File(root, AppDirectories.MEDIA_DIR_NAME), "media.db").exists() ||
                File(File(root, AppDirectories.SAMPLE_DIR_NAME), "sample_survey.db").exists()
    }

    // ---- map.db（轨迹 + 测量）----

    private fun migrateMapStore(context: Context, p: Project): Boolean {
        val dbFile = File(File(AppDirectories.getRootDir(context), AppDirectories.MAP_DIR_NAME), "map.db")
        if (!dbFile.exists()) return false
        val store = MapDataStore.getInstance(context)
        var migrated = false

        for (t in store.loadTracks()) {
            val r = Project.TrackRecord()
            r.id = t.id
            r.name = t.name
            r.date = ProjectManager.formatTime(t.createdTime)
            r.color = ProjectManager.colorToHex(t.color)
            r.hidden = !t.visible
            r.lineWidth = t.lineWidth
            r.points.addAll(t.points)  // 旧库点串同为 [lat,lon,alt] 内存序，直接搬
            p.tracks.add(r)
            migrated = true
        }

        for (m in store.loadMeasurements()) {
            val r = Project.MeasureRecord()
            r.id = m.id
            r.name = m.name
            r.date = ProjectManager.formatTime(m.createdTime)
            r.type = m.type
            r.result = ProjectManager.measureResultText(m.type, m.points)
            r.color = ProjectManager.colorToHex(m.color)
            r.closed = m.type == Project.MeasureRecord.TYPE_AREA
            r.lineWidth = m.lineWidth
            r.points.addAll(m.points)
            p.measures.add(r)
            migrated = true
        }

        // 「记录中」草稿轨迹：loadDraftTrack 不受 recording=0 过滤，写入新方案的草稿文件，
        // 升级后恢复记录会话（restoreDraftTrack 读回），避免升级瞬间进行中的轨迹滞留旧库丢失
        val draft = store.loadDraftTrack()
        if (draft != null && draft.points.isNotEmpty()) {
            val draftFile = File(AppDirectories.getProjectDir(context), ".track_draft.json")
            if (!draftFile.exists()) {
                val pts = org.json.JSONArray()
                for (pt in draft.points) {
                    pts.put(org.json.JSONArray().apply {
                        put(pt[0]); put(pt[1]); put(if (pt.size > 2) pt[2] else 0.0)
                    })
                }
                draftFile.writeText(
                    org.json.JSONObject()
                        .put("name", draft.name)
                        .put("createdTime", draft.createdTime)
                        .put("points", pts)
                        .toString()
                )
                migrated = true
                Log.i(TAG, "迁移「记录中」草稿轨迹: ${draft.points.size} 点")
            }
        }
        return migrated
    }

    // ---- media.db（拍照点位 + 附件 + 分组）----

    private fun migrateMediaStore(context: Context, p: Project): Boolean {
        val dbFile = File(File(AppDirectories.getRootDir(context), AppDirectories.MEDIA_DIR_NAME), "media.db")
        if (!dbFile.exists()) return false
        val store = MediaStore.getInstance(context)
        var migrated = false

        for (g in store.loadGroups()) {
            val r = Project.MediaGroupRecord()
            r.id = g.id
            r.name = g.name
            r.createdTime = g.createdTime
            r.visible = g.visible
            p.mediaGroups.add(r)
            migrated = true
        }

        for (pm in store.loadPlacemarks()) {
            val r = Project.PhotoRecord()
            r.id = pm.id
            r.name = pm.name
            r.lat = pm.latitude
            r.lon = pm.longitude
            r.timestamp = pm.createdTime
            r.visible = pm.visible
            r.groupId = pm.groupId
            for (a in pm.attachments) {
                r.attachments.add(Project.PhotoAttachment(a.name, a.path, a.time))
            }
            p.photos.add(r)
            migrated = true
        }
        return migrated
    }

    // ---- sample_survey.db（样地调查，含明细行与照片记录）----

    private fun migrateSurveyStore(context: Context, p: Project): Boolean {
        val dbFile = File(File(AppDirectories.getRootDir(context), AppDirectories.SAMPLE_DIR_NAME), "sample_survey.db")
        if (!dbFile.exists()) return false
        var migrated = false
        for (brief in SampleSurveyStore.loadAllPlots(context)) {
            // loadAllPlots 不含明细行，按 id 重读完整样地（含 rows）
            val s = SampleSurveyStore.loadPlot(context, brief.id) ?: continue
            val r = Project.SurveyPlot()
            r.id = s.id
            r.name = "样地#${s.plotNo}"
            r.plotType = "point"
            if (!s.centerLatitude.isNaN() && !s.centerLongitude.isNaN()) {
                r.points.add(doubleArrayOf(s.centerLatitude, s.centerLongitude, 0.0))
            }
            r.sourcePath = s.sourcePath
            r.sourceFid = s.sourceFid
            r.compartmentNo = s.compartmentNo
            r.plotNo = s.plotNo
            r.averageTreeHeight = s.averageTreeHeight
            r.canopyDensity = s.canopyDensity
            r.cornerCoordinates = s.cornerCoordinates
            r.createdAt = s.createdAt
            r.updatedAt = s.updatedAt
            for (row in s.rows) {
                r.rows.add(Project.SurveyRow().also {
                    it.sortNo = row.sortNo
                    it.treeSpecies = row.treeSpecies
                    it.dbh = row.dbh
                    it.category = row.category
                    it.thinningMethod = row.thinningMethod
                    it.remark = row.remark
                })
            }
            for (photo in SampleSurveyStore.loadPhotos(context, s.id)) {
                r.photoPaths.add(photo.filePath)
            }
            p.surveyPlots.add(r)
            migrated = true
        }
        return migrated
    }
}
