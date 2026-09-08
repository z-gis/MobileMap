package com.zys.mobilemap.media

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.zys.mobilemap.db.SqliteSchema
import com.zys.mobilemap.util.AppDirectories
import java.io.File

/**
 * 媒体 Placemark 记录：一个拍照点位对应一行，可挂多个附件（照片/视频/音频）。
 * [groupId] 为所属分组主键，[MediaStore.UNGROUPED_ID] 表示未分组。
 */
class MediaPlacemark(
    val id: Long,
    var name: String,
    val latitude: Double,
    val longitude: Double,
    val createdTime: Long,
    var visible: Boolean = true,
    var groupId: Long = MediaStore.UNGROUPED_ID,
    val attachments: MutableList<MediaAttachment> = mutableListOf()
)

/**
 * 媒体分组记录：一组拍照点位（如某次外业/某地块），对应 media_group 表一行。
 * [visible] 为分组整体可见性，隐藏后组内全部点位在地图不渲染。
 */
class MediaGroup(
    val id: Long,
    var name: String,
    val createdTime: Long,
    var visible: Boolean = true
)

/**
 * 媒体附件记录，对应 attachment 表一行。
 */
class MediaAttachment(
    val id: Long,
    val placemarkId: Long,
    val type: String,
    val name: String,
    val path: String,
    val time: Long
) {
    companion object {
        const val TYPE_PHOTO = "photo"
        const val TYPE_VIDEO = "video"
        const val TYPE_AUDIO = "audio"
    }
}

/**
 * 媒体数据库仓库：数据保存在 /调查宝/Media/media.db。
 * placemark 表一个拍照点位一行，attachment 表分表保存附件信息，media_group 表保存分组。
 */
class MediaStore private constructor(context: Context) {

    private val db: SQLiteDatabase

    init {
        val dir = AppDirectories.getMediaDir(context)
        db = SQLiteDatabase.openOrCreateDatabase(File(dir, DB_NAME), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS placemark (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT, " +
                    "latitude REAL, " +
                    "longitude REAL, " +
                    "created_time INTEGER, " +
                    "visible INTEGER DEFAULT 1, " +
                    "group_id INTEGER DEFAULT " + UNGROUPED_ID + ")"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS attachment (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "placemark_id INTEGER, " +
                    "attachment_type TEXT, " +
                    "attachment_name TEXT, " +
                    "attachment_path TEXT, " +
                    "attachment_time DATETIME)"
        )
        // group 为 SQL 关键字，表名用 media_group
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS media_group (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT, " +
                    "created_time INTEGER, " +
                    "visible INTEGER DEFAULT 1)"
        )
        // 旧库平滑迁移：placemark 缺 group_id 列时补列（默认未分组），不丢弃既有数据（见 [SqliteSchema]）
        SqliteSchema.ensureColumn(
            db, "placemark", "group_id",
            "ALTER TABLE placemark ADD COLUMN group_id INTEGER DEFAULT " + UNGROUPED_ID
        )
    }

    /**
     * 新建一个媒体 Placemark，返回自增主键。
     */
    fun insertPlacemark(
        name: String,
        latitude: Double,
        longitude: Double,
        createdTime: Long,
        groupId: Long = UNGROUPED_ID
    ): Long {
        val values = ContentValues()
        values.put("name", name)
        values.put("latitude", latitude)
        values.put("longitude", longitude)
        values.put("created_time", createdTime)
        values.put("group_id", groupId)
        return db.insert("placemark", null, values)
    }

    /**
     * 为 Placemark 追加一条附件记录，返回附件主键。
     */
    fun insertAttachment(
        placemarkId: Long,
        type: String,
        name: String,
        path: String,
        time: Long
    ): Long {
        val values = ContentValues()
        values.put("placemark_id", placemarkId)
        values.put("attachment_type", type)
        values.put("attachment_name", name)
        values.put("attachment_path", path)
        values.put("attachment_time", time)
        return db.insert("attachment", null, values)
    }

    /**
     * 加载全部 Placemark（含各自附件），按创建时间降序。
     */
    fun loadPlacemarks(): List<MediaPlacemark> {
        val placemarks = LinkedHashMap<Long, MediaPlacemark>()
        db.rawQuery(
            "SELECT id, name, latitude, longitude, created_time, visible, group_id " +
                    "FROM placemark ORDER BY created_time DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val placemark = MediaPlacemark(
                    c.getLong(0),
                    c.getString(1) ?: "",
                    c.getDouble(2),
                    c.getDouble(3),
                    c.getLong(4),
                    c.getInt(5) != 0,
                    c.getLong(6)
                )
                placemarks[placemark.id] = placemark
            }
        }
        if (placemarks.isEmpty()) return emptyList()

        db.rawQuery(
            "SELECT id, placemark_id, attachment_type, attachment_name, attachment_path, attachment_time " +
                    "FROM attachment ORDER BY attachment_time ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val attachment = MediaAttachment(
                    c.getLong(0), c.getLong(1), c.getString(2) ?: "",
                    c.getString(3) ?: "", c.getString(4) ?: "", c.getLong(5)
                )
                placemarks[attachment.placemarkId]?.attachments?.add(attachment)
            }
        }
        return placemarks.values.toList()
    }

    /**
     * 按主键加载单个 Placemark（含其附件），不存在时返回 null。
     *
     * 拍照流程与点位详情只需一条记录，用 WHERE id=? 精确查询，
     * 避开先走 [loadPlacemarks] 把全部点位与附件读进内存、再 firstOrNull 过滤的无谓开销。
     */
    fun findPlacemark(placemarkId: Long): MediaPlacemark? {
        val placemark = db.rawQuery(
            "SELECT id, name, latitude, longitude, created_time, visible, group_id " +
                    "FROM placemark WHERE id = ?",
            arrayOf(placemarkId.toString())
        ).use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                MediaPlacemark(
                    c.getLong(0),
                    c.getString(1) ?: "",
                    c.getDouble(2),
                    c.getDouble(3),
                    c.getLong(4),
                    c.getInt(5) != 0,
                    c.getLong(6)
                )
            }
        } ?: return null

        db.rawQuery(
            "SELECT id, placemark_id, attachment_type, attachment_name, attachment_path, attachment_time " +
                    "FROM attachment WHERE placemark_id = ? ORDER BY attachment_time ASC",
            arrayOf(placemarkId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                placemark.attachments.add(
                    MediaAttachment(
                        c.getLong(0), c.getLong(1), c.getString(2) ?: "",
                        c.getString(3) ?: "", c.getString(4) ?: "", c.getLong(5)
                    )
                )
            }
        }
        return placemark
    }

    /**
     * 修改 Placemark 名称（拍照标识管理）。
     */
    fun updatePlacemarkName(placemarkId: Long, name: String) {
        val values = ContentValues()
        values.put("name", name)
        db.update("placemark", values, "id = ?", arrayOf(placemarkId.toString()))
    }

    /**
     * 修改 Placemark 可见性（隐藏后地图不渲染该点位）。
     */
    fun updatePlacemarkVisible(placemarkId: Long, visible: Boolean) {
        val values = ContentValues()
        values.put("visible", if (visible) 1 else 0)
        db.update("placemark", values, "id = ?", arrayOf(placemarkId.toString()))
    }

    /**
     * 修改 Placemark 所属分组（[UNGROUPED_ID] 表示移出到未分组）。
     */
    fun updatePlacemarkGroup(placemarkId: Long, groupId: Long) {
        val values = ContentValues()
        values.put("group_id", groupId)
        db.update("placemark", values, "id = ?", arrayOf(placemarkId.toString()))
    }

    /**
     * 删除 Placemark 及其附件记录（附件文件由调用方删除）。
     */
    fun deletePlacemark(placemarkId: Long) {
        db.delete("attachment", "placemark_id = ?", arrayOf(placemarkId.toString()))
        db.delete("placemark", "id = ?", arrayOf(placemarkId.toString()))
    }

    /**
     * 新建分组，返回自增主键。
     */
    fun insertGroup(name: String, createdTime: Long): Long {
        val values = ContentValues()
        values.put("name", name)
        values.put("created_time", createdTime)
        return db.insert("media_group", null, values)
    }

    /**
     * 加载全部分组，按创建时间升序。
     */
    fun loadGroups(): List<MediaGroup> {
        val groups = mutableListOf<MediaGroup>()
        db.rawQuery(
            "SELECT id, name, created_time, visible FROM media_group ORDER BY created_time ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                groups.add(
                    MediaGroup(
                        c.getLong(0),
                        c.getString(1) ?: "",
                        c.getLong(2),
                        c.getInt(3) != 0
                    )
                )
            }
        }
        return groups
    }

    /**
     * 修改分组名称。
     */
    fun updateGroupName(groupId: Long, name: String) {
        val values = ContentValues()
        values.put("name", name)
        db.update("media_group", values, "id = ?", arrayOf(groupId.toString()))
    }

    /**
     * 批量设置分组内全部点位的可见性（分组可见性开关直接作用于组内各点位）。
     * 渲染层只需判断点位自身 visible，无需再耦合分组标志。
     */
    fun setGroupPlacemarksVisible(groupId: Long, visible: Boolean) {
        val values = ContentValues()
        values.put("visible", if (visible) 1 else 0)
        db.update("placemark", values, "group_id = ?", arrayOf(groupId.toString()))
    }

    /**
     * 删除分组：仅删分组本身，组内点位不删除，改为移出到未分组。
     */
    fun deleteGroup(groupId: Long) {
        val values = ContentValues()
        values.put("group_id", UNGROUPED_ID)
        db.update("placemark", values, "group_id = ?", arrayOf(groupId.toString()))
        db.delete("media_group", "id = ?", arrayOf(groupId.toString()))
    }

    companion object {
        private const val DB_NAME = "media.db"

        /** 未分组标识（placemark.group_id 的默认值） */
        const val UNGROUPED_ID = -1L

        @Volatile
        private var instance: MediaStore? = null

        fun getInstance(context: Context): MediaStore =
            instance ?: synchronized(this) {
                instance ?: MediaStore(context.applicationContext).also { instance = it }
            }
    }
}
