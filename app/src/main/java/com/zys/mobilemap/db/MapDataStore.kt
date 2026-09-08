package com.zys.mobilemap.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.zys.mobilemap.util.AppDirectories
import org.json.JSONArray
import java.io.File

/**
 * 轨迹记录：基本信息 + 坐标点序列（lat/lon/alt）。
 */
class TrackRecord(
    val id: Long,
    val name: String,
    val createdTime: Long,
    val points: MutableList<DoubleArray>,
    /** 逐条轨迹可见性：隐藏后地图不渲染（管理界面开关） */
    var visible: Boolean = true,
    /** 轨迹线颜色（ARGB Int，与 track 表 color 列一致） */
    var color: Int = DEFAULT_COLOR,
    /** 轨迹线宽（像素，与 track 表 line_width 列一致） */
    var lineWidth: Int = DEFAULT_LINE_WIDTH
) {
    companion object {
        /** 新建轨迹默认颜色（历史轨迹蓝 0xFF33CCFF） */
        const val DEFAULT_COLOR = 0xFF33CCFF.toInt()

        /** 新建轨迹默认线宽 */
        const val DEFAULT_LINE_WIDTH = 3
    }
}

/**
 * 测量记录：点（位置）/距离/面积，点串以 JSON 存储。
 * [lineWidth] 线要素渲染线宽（点测量不使用）。
 */
class MeasurementRecord(
    val id: Long,
    val name: String,
    val type: String,
    val color: Int,
    val createdTime: Long,
    val points: List<DoubleArray>,
    val lineWidth: Int = DEFAULT_LINE_WIDTH
) {
    companion object {
        const val TYPE_POINT = "point"
        const val TYPE_DISTANCE = "distance"
        const val TYPE_AREA = "area"

        /** 新建测量的默认线宽 */
        const val DEFAULT_LINE_WIDTH = 3
    }
}

/**
 * 地图数据库仓库：轨迹与测量数据保存在 /调查宝/map/map.db。
 * 轨迹：track + track_point 两表；测量：measurement 表（点串 JSON）。
 */
class MapDataStore private constructor(context: Context) {

    private val db: SQLiteDatabase

    init {
        val dir = AppDirectories.getMapDir(context)
        db = SQLiteDatabase.openOrCreateDatabase(File(dir, DB_NAME), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS track (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT, " +
                    "created_time INTEGER, " +
                    "visible INTEGER DEFAULT 1, " +
                    "color INTEGER DEFAULT -13382401, " +
                    "line_width INTEGER DEFAULT 3, " +
                    "recording INTEGER DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS track_point (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "track_id INTEGER, " +
                    "seq INTEGER, " +
                    "latitude REAL, " +
                    "longitude REAL, " +
                    "altitude REAL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS measurement (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT, " +
                    "type TEXT, " +
                    "color INTEGER, " +
                    "created_time INTEGER, " +
                    "points TEXT)"
        )
        // 旧库迁移：PRAGMA 检测缺列后 ALTER TABLE 补列，不重建表不丢数据（见 [SqliteSchema]）
        // measurement 线宽列：点击测量详情 BottomSheet 编辑线宽
        val measurementColumns = SqliteSchema.tableColumns(db, "measurement")
        if ("line_width" !in measurementColumns) {
            db.execSQL("ALTER TABLE measurement ADD COLUMN line_width INTEGER DEFAULT 3")
        }
        // track 逐条可见性（管理界面显示/隐藏）、样式列（详情 BottomSheet 编辑颜色/线宽）、
        // “记录中”标记列（采集过程实时落库，进程被回收后可恢复未保存轨迹）
        val trackColumns = SqliteSchema.tableColumns(db, "track")
        if ("visible" !in trackColumns) {
            db.execSQL("ALTER TABLE track ADD COLUMN visible INTEGER DEFAULT 1")
        }
        if ("color" !in trackColumns) {
            db.execSQL("ALTER TABLE track ADD COLUMN color INTEGER DEFAULT -13382401")
        }
        if ("line_width" !in trackColumns) {
            db.execSQL("ALTER TABLE track ADD COLUMN line_width INTEGER DEFAULT 3")
        }
        if ("recording" !in trackColumns) {
            db.execSQL("ALTER TABLE track ADD COLUMN recording INTEGER DEFAULT 0")
        }
    }

    /**
     * 保存一条轨迹及其坐标点，返回轨迹主键。
     */
    fun addTrack(
        name: String,
        createdTime: Long,
        points: List<DoubleArray>,
        color: Int = TrackRecord.DEFAULT_COLOR,
        lineWidth: Int = TrackRecord.DEFAULT_LINE_WIDTH
    ): Long {
        val values = ContentValues()
        values.put("name", name)
        values.put("created_time", createdTime)
        values.put("visible", 1)
        values.put("color", color)
        values.put("line_width", lineWidth)
        val trackId = db.insert("track", null, values)
        if (trackId < 0) return -1

        db.beginTransaction()
        try {
            points.forEachIndexed { index, point ->
                val pointValues = ContentValues()
                pointValues.put("track_id", trackId)
                pointValues.put("seq", index)
                pointValues.put("latitude", point[0])
                pointValues.put("longitude", point[1])
                pointValues.put("altitude", point.getOrElse(2) { 0.0 })
                db.insert("track_point", null, pointValues)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return trackId
    }

    /**
     * 加载全部已结束的历史轨迹（含坐标点），按创建时间降序。
     * 记录中的草稿轨迹（recording=1）不作为历史轨迹返回，避免出现在管理列表与历史渲染中。
     */
    fun loadTracks(): List<TrackRecord> {
        val tracks = LinkedHashMap<Long, TrackRecord>()
        db.rawQuery(
            "SELECT id, name, created_time, visible, color, line_width FROM track " +
                    "WHERE recording = 0 ORDER BY created_time DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                val record = TrackRecord(c.getLong(0), c.getString(1) ?: "", c.getLong(2), mutableListOf())
                record.visible = c.getInt(3) != 0
                val colorValue = c.getInt(4)
                record.color = if (colorValue == 0) TrackRecord.DEFAULT_COLOR else colorValue
                record.lineWidth = c.getInt(5).takeIf { it > 0 } ?: TrackRecord.DEFAULT_LINE_WIDTH
                tracks[record.id] = record
            }
        }
        if (tracks.isEmpty()) return emptyList()

        db.rawQuery(
            "SELECT track_id, latitude, longitude, altitude FROM track_point ORDER BY seq ASC", null
        ).use { c ->
            while (c.moveToNext()) {
                val record = tracks[c.getLong(0)] ?: continue
                record.points.add(doubleArrayOf(c.getDouble(1), c.getDouble(2), c.getDouble(3)))
            }
        }
        return tracks.values.filter { it.points.size >= 2 }
    }

    /**
     * 新建一条“记录中”的草稿轨迹，返回主键（失败返回 -1）。
     * 采集开始后即落库，轨迹点随采集实时追加，进程被系统回收/异常退出后据此恢复。
     */
    fun beginDraftTrack(name: String, createdTime: Long): Long {
        val values = ContentValues()
        values.put("name", name)
        values.put("created_time", createdTime)
        values.put("visible", 1)
        values.put("color", TrackRecord.DEFAULT_COLOR)
        values.put("line_width", TrackRecord.DEFAULT_LINE_WIDTH)
        values.put("recording", 1)
        return db.insert("track", null, values)
    }

    /**
     * 追加一个轨迹坐标点（采集过程中实时落库）。
     */
    fun appendTrackPoint(trackId: Long, seq: Int, lat: Double, lon: Double, alt: Double) {
        val values = ContentValues()
        values.put("track_id", trackId)
        values.put("seq", seq)
        values.put("latitude", lat)
        values.put("longitude", lon)
        values.put("altitude", alt)
        db.insert("track_point", null, values)
    }

    /**
     * 结束草稿轨迹：写入最终名称并清除“记录中”标记，此后作为历史轨迹显示。
     */
    fun finishDraftTrack(trackId: Long, name: String) {
        val values = ContentValues()
        values.put("name", name)
        values.put("recording", 0)
        db.update("track", values, "id = ?", arrayOf(trackId.toString()))
    }

    /**
     * 读取最近一条未结束的草稿轨迹（含已采集坐标点），无则返回 null。
     */
    fun loadDraftTrack(): TrackRecord? {
        val record = db.rawQuery(
            "SELECT id, name, created_time, visible, color, line_width FROM track " +
                    "WHERE recording = 1 ORDER BY created_time DESC LIMIT 1", null
        ).use { c ->
            if (!c.moveToNext()) return@use null
            val r = TrackRecord(c.getLong(0), c.getString(1) ?: "", c.getLong(2), mutableListOf())
            r.visible = c.getInt(3) != 0
            val colorValue = c.getInt(4)
            r.color = if (colorValue == 0) TrackRecord.DEFAULT_COLOR else colorValue
            r.lineWidth = c.getInt(5).takeIf { it > 0 } ?: TrackRecord.DEFAULT_LINE_WIDTH
            r
        } ?: return null

        db.rawQuery(
            "SELECT latitude, longitude, altitude FROM track_point " +
                    "WHERE track_id = ? ORDER BY seq ASC", arrayOf(record.id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                record.points.add(doubleArrayOf(c.getDouble(0), c.getDouble(1), c.getDouble(2)))
            }
        }
        return record
    }

    /**
     * 删除轨迹及其坐标点。
     */
    fun deleteTrack(trackId: Long) {
        db.delete("track_point", "track_id = ?", arrayOf(trackId.toString()))
        db.delete("track", "id = ?", arrayOf(trackId.toString()))
    }

    /**
     * 重命名轨迹。
     */
    fun updateTrackName(trackId: Long, name: String) {
        val values = ContentValues()
        values.put("name", name)
        db.update("track", values, "id = ?", arrayOf(trackId.toString()))
    }

    /**
     * 更新轨迹逐条可见性（隐藏后地图不渲染）。
     */
    fun updateTrackVisible(trackId: Long, visible: Boolean) {
        val values = ContentValues()
        values.put("visible", if (visible) 1 else 0)
        db.update("track", values, "id = ?", arrayOf(trackId.toString()))
    }

    /**
     * 更新轨迹样式（颜色 + 线宽），轨迹详情 BottomSheet"保存"调用。
     */
    fun updateTrackStyle(trackId: Long, color: Int, lineWidth: Int) {
        val values = ContentValues()
        values.put("color", color)
        values.put("line_width", lineWidth)
        db.update("track", values, "id = ?", arrayOf(trackId.toString()))
    }

    /**
     * 保存一条测量记录，返回主键。
     */
    fun addMeasurement(
        name: String,
        type: String,
        color: Int,
        createdTime: Long,
        points: List<DoubleArray>,
        lineWidth: Int = MeasurementRecord.DEFAULT_LINE_WIDTH
    ): Long {
        val values = ContentValues()
        values.put("name", name)
        values.put("type", type)
        values.put("color", color)
        values.put("created_time", createdTime)
        values.put("points", pointsToJson(points))
        values.put("line_width", lineWidth)
        return db.insert("measurement", null, values)
    }

    /**
     * 加载全部测量记录，按创建时间降序。
     */
    fun loadMeasurements(): List<MeasurementRecord> {
        val records = mutableListOf<MeasurementRecord>()
        db.rawQuery(
            "SELECT id, name, type, color, created_time, points, line_width " +
                    "FROM measurement ORDER BY created_time DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val points = jsonToPoints(c.getString(5) ?: "")
                if (points.isEmpty()) continue
                records.add(
                    MeasurementRecord(
                        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "",
                        c.getInt(3), c.getLong(4), points,
                        c.getInt(6).takeIf { it > 0 } ?: MeasurementRecord.DEFAULT_LINE_WIDTH
                    )
                )
            }
        }
        return records
    }

    /**
     * 更新测量记录样式（颜色 + 线宽），测量详情 BottomSheet"保存"调用。
     */
    fun updateMeasurementStyle(measurementId: Long, color: Int, lineWidth: Int) {
        val values = ContentValues()
        values.put("color", color)
        values.put("line_width", lineWidth)
        db.update("measurement", values, "id = ?", arrayOf(measurementId.toString()))
    }

    /**
     * 删除测量记录。
     */
    fun deleteMeasurement(measurementId: Long) {
        db.delete("measurement", "id = ?", arrayOf(measurementId.toString()))
    }

    private fun pointsToJson(points: List<DoubleArray>): String {
        val array = JSONArray()
        for (point in points) {
            val item = JSONArray()
            item.put(point[0])
            item.put(point[1])
            item.put(point.getOrElse(2) { 0.0 })
            array.put(item)
        }
        return array.toString()
    }

    private fun jsonToPoints(json: String): List<DoubleArray> {
        val points = mutableListOf<DoubleArray>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val item = array.optJSONArray(i) ?: continue
                if (item.length() < 2) continue
                points.add(
                    doubleArrayOf(
                        item.optDouble(0, Double.NaN),
                        item.optDouble(1, Double.NaN),
                        item.optDouble(2, 0.0)
                    )
                )
            }
        } catch (ignored: Exception) {
            // JSON 格式错误时返回空列表
        }
        return points
    }

    companion object {
        private const val DB_NAME = "map.db"

        @Volatile
        private var instance: MapDataStore? = null

        fun getInstance(context: Context): MapDataStore =
            instance ?: synchronized(this) {
                instance ?: MapDataStore(context.applicationContext).also { instance = it }
            }
    }
}
