package com.zys.mobilemap.media

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.zys.mobilemap.util.AppDirectories
import java.io.File

/**
 * 媒体 Placemark 记录：一个拍照点位对应一行，可挂多个附件（照片/视频/音频）。
 */
class MediaPlacemark(
    val id: Long,
    var name: String,
    val latitude: Double,
    val longitude: Double,
    val createdTime: Long,
    var visible: Boolean = true,
    val attachments: MutableList<MediaAttachment> = mutableListOf()
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
 * placemark 表一个拍照点位一行，attachment 表分表保存附件信息。
 */
class MediaStore private constructor(context: Context) {

    private val db: SQLiteDatabase

    init {
        val dir = AppDirectories.getMediaDir(context) ?: context.filesDir
        db = SQLiteDatabase.openOrCreateDatabase(File(dir, DB_NAME), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS placemark (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT, " +
                    "latitude REAL, " +
                    "longitude REAL, " +
                    "created_time INTEGER, " +
                    "visible INTEGER DEFAULT 1)"
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
    }

    /**
     * 新建一个媒体 Placemark，返回自增主键。
     */
    fun insertPlacemark(name: String, latitude: Double, longitude: Double, createdTime: Long): Long {
        val values = ContentValues()
        values.put("name", name)
        values.put("latitude", latitude)
        values.put("longitude", longitude)
        values.put("created_time", createdTime)
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
            "SELECT id, name, latitude, longitude, created_time, visible " +
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
                    c.getInt(5) != 0
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
     * 删除 Placemark 及其附件记录（附件文件由调用方删除）。
     */
    fun deletePlacemark(placemarkId: Long) {
        db.delete("attachment", "placemark_id = ?", arrayOf(placemarkId.toString()))
        db.delete("placemark", "id = ?", arrayOf(placemarkId.toString()))
    }

    companion object {
        private const val DB_NAME = "media.db"

        @Volatile
        private var instance: MediaStore? = null

        fun getInstance(context: Context): MediaStore =
            instance ?: synchronized(this) {
                instance ?: MediaStore(context.applicationContext).also { instance = it }
            }
    }
}
