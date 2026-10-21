package com.zys.mobilemap.project

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GeoPackage 增量直写层（0.4.0.4）
 *
 * gpkg 本体是 SQLite 文件，业务七表（tracks/measures/photos/media_groups/survey_plots/
 * project_meta/layer_refs）为自定义表。日常业务写入不再经 C++ GDAL 整文件重建（照片多时
 * 每次保存都全量重读写入所有照片 BLOB，数百 MB IO 导致停表/拍照卡顿），改由 app 端
 * android.database.sqlite 对同一文件做毫秒级增量 DML：
 *  - 几何列直接写 GPKG 二进制（GP 头 + XYZ 包络 + ISO WKB 含 Z），与 GDAL 写入等价；
 *  - gpkg_contents.last_change / bbox 与 gpkg_ogr_contents 要素计数随写随更，QGIS/ArcGIS 兼容；
 *  - GDAL 建库默认创建的 RTree 空间索引触发器引用 ST_* 扩展函数（仅 GDAL 运行时注册，
 *    纯 SQLite 客户端执行 INSERT 会报 no such function）→ 建库后由 C++ 侧移除触发器与索引表
 *    （空间索引是可选优化，移除不影响读取）；
 *  - 附加表 photo_att_data(fid, idx, data) 保存第 2..N 张附件的照片 BLOB（表不注册进
 *    gpkg_contents，GDAL/QGIS 不可见）；主照片仍在 photos.photo_data。
 *
 * 整文件重建（JNI writeEncoded）仅保留给：新建工程文件；导出/复制改用文件复制。
 * 每次操作独立 open/beginTransaction/close，不持长连接，避免与重建 tmp+rename 换文件产生句柄竞态。
 */
internal object GeoPackageDml {

    private const val TAG = "GeoPackageDml"

    /** 附件 BLOB 附加表（非主照片，第 2..N 张） */
    private const val ATT_TABLE = "photo_att_data"

    // ---- 几何编码 ----

    /**
     * GPKG 二进制几何编码器：GP 头（LE、SRS=4326、XYZ 包络）+ ISO WKB（含 Z）。
     * 与 GDAL/OGR 写入格式等价，OGR/QGIS 可直接读取；点列内存序 [lat,lon,alt]。
     */
    internal object GpkgGeom {

        // ISO WKB 类型码（类型 + 1000）。不能用 OGC 99-402 的 0x8000000N 2.5D 标志位：
        // GDAL 整文件重建写的是 ISO 族，同表混存两族时 ArcMap 10.8 对 OGC 族记录静默跳过（不报错，表现为少图斑）。
        private const val WKB_POINT_Z = 1001L
        private const val WKB_LINESTRING_Z = 1002L
        private const val WKB_POLYGON_Z = 1003L
        private const val SRS_ID = 4326

        fun point(lat: Double, lon: Double, alt: Double): ByteArray {
            val body = ByteBuffer.allocate(5 + 24).order(ByteOrder.LITTLE_ENDIAN)
            body.put(1)
            body.putInt(WKB_POINT_Z.toInt())
            body.putDouble(lon); body.putDouble(lat); body.putDouble(alt)
            return pack(body, doubleArrayOf(lon, lon, lat, lat, alt, alt))
        }

        fun line(pts: List<DoubleArray>): ByteArray? {
            if (pts.isEmpty()) return null
            val body = ByteBuffer.allocate(5 + 4 + pts.size * 24).order(ByteOrder.LITTLE_ENDIAN)
            body.put(1)
            body.putInt(WKB_LINESTRING_Z.toInt())
            body.putInt(pts.size)
            for (p in pts) body.putDouble(p[1]).putDouble(p[0]).putDouble(zOf(p))
            val env = envelope(pts)
            return pack(body, env)
        }

        /** 多边形（自动闭合首环，与 C++ pointsToPolygon 语义一致）；空点列返回 null */
        fun polygon(pts: List<DoubleArray>): ByteArray? {
            if (pts.isEmpty()) return null
            val ring = ArrayList<DoubleArray>(pts.size + 1)
            ring.addAll(pts)
            val f = pts[0]; val l = pts[pts.size - 1]
            if (f[0] != l[0] || f[1] != l[1]) ring.add(doubleArrayOf(f[0], f[1], zOf(f)))
            val body = ByteBuffer.allocate(5 + 8 + ring.size * 24).order(ByteOrder.LITTLE_ENDIAN)
            body.put(1)
            body.putInt(WKB_POLYGON_Z.toInt())
            body.putInt(1)  // 环数
            body.putInt(ring.size)
            for (p in ring) body.putDouble(p[1]).putDouble(p[0]).putDouble(zOf(p))
            return pack(body, envelope(pts))
        }

        private fun zOf(p: DoubleArray): Double = if (p.size > 2) p[2] else 0.0

        /** [minx,maxx,miny,maxy,minz,maxz]（内存点列 [lat,lon,alt] → lon/lat） */
        private fun envelope(pts: List<DoubleArray>): DoubleArray {
            var minx = Double.MAX_VALUE; var maxx = -Double.MAX_VALUE
            var miny = Double.MAX_VALUE; var maxy = -Double.MAX_VALUE
            var minz = Double.MAX_VALUE; var maxz = -Double.MAX_VALUE
            for (p in pts) {
                val x = p[1]; val y = p[0]; val z = zOf(p)
                if (x < minx) minx = x; if (x > maxx) maxx = x
                if (y < miny) miny = y; if (y > maxy) maxy = y
                if (z < minz) minz = z; if (z > maxz) maxz = z
            }
            return doubleArrayOf(minx, maxx, miny, maxy, minz, maxz)
        }

        /** GP 头（flags 0x05 = LE 头 + XYZ 包络）+ 包络 + WKB 体（头块恰 8 字节，尾部不得有填充） */
        private fun pack(body: ByteBuffer, env: DoubleArray): ByteArray {
            val buf = ByteBuffer.allocate(8 + env.size * 8 + body.capacity())
                .order(ByteOrder.LITTLE_ENDIAN)
            buf.put('G'.code.toByte())
            buf.put('P'.code.toByte())
            buf.put(0)       // version
            buf.put(0x05)    // flags: LE 头 + 包络指示 2 (xyz)
            buf.putInt(SRS_ID)
            for (d in env) buf.putDouble(d)
            // 只拷已写入的 WKB 体（body.array() 含容量尾零），末条同样只取已写部分
            buf.put(body.array(), 0, body.position())
            return buf.array().copyOf(buf.position())
        }
    }

    // ---- 通用骨架 ----

    /** 独立连接 + 事务包裹；异常吞掉记日志返回 null（增量写失败不致命，下次整文件重建可兜底）。
     *  每次操作计时打点（open+事务+close 总耗时），[op] 为操作名便于 logcat 定位。 */
    private inline fun <T> withDb(path: String, op: String = "", block: (SQLiteDatabase) -> T): T? {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                db.beginTransaction()
                val r = block(db)
                db.setTransactionSuccessful()
                r
            } finally {
                db.endTransaction()
                db.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "gpkg 增量写失败: $path", e)
            null
        } finally {
            Log.d(TAG, "DML $op ${File(path).name} cost=${SystemClock.elapsedRealtime() - t0}ms")
        }
    }

    private fun lastChange(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun putStr(cv: ContentValues, key: String, value: String?) {
        cv.put(key, value?.takeIf { it.isNotEmpty() })  // 空串视为 NULL，对齐 C++ setFieldStr
    }

    /** gpkg_contents.last_change/bbox 与 gpkg_ogr_contents 要素计数随写更新（表不存在时静默跳过） */
    private fun finish(db: SQLiteDatabase, table: String, bbox: DoubleArray? = null) {
        runCatching {
            if (bbox != null && bbox.size == 4) {
                db.execSQL(
                    "UPDATE gpkg_contents SET last_change=?, " +
                            "min_x=min(COALESCE(min_x,?),?), max_x=max(COALESCE(max_x,?),?), " +
                            "min_y=min(COALESCE(min_y,?),?), max_y=max(COALESCE(max_y,?),?) WHERE table_name=?",
                    arrayOf<Any>(lastChange(), bbox[0], bbox[0], bbox[1], bbox[1], bbox[2], bbox[2], bbox[3], bbox[3], table)
                )
            } else {
                db.execSQL("UPDATE gpkg_contents SET last_change=? WHERE table_name=?", arrayOf(lastChange(), table))
            }
        }
        runCatching {
            db.execSQL(
                "UPDATE gpkg_ogr_contents SET feature_count=(SELECT COUNT(*) FROM \"$table\") WHERE table_name=?",
                arrayOf(table)
            )
        }
    }

    /** [minx,maxx,miny,maxy]（内存点列 [lat,lon,alt] → lon/lat） */
    private fun bboxOf(pts: List<DoubleArray>): DoubleArray? {
        if (pts.isEmpty()) return null
        var minx = Double.MAX_VALUE; var maxx = -Double.MAX_VALUE
        var miny = Double.MAX_VALUE; var maxy = -Double.MAX_VALUE
        for (p in pts) {
            val x = p[1]; val y = p[0]
            if (x < minx) minx = x; if (x > maxx) maxx = x
            if (y < miny) miny = y; if (y > maxy) maxy = y
        }
        return doubleArrayOf(minx, maxx, miny, maxy)
    }

    // ---- 轨迹 ----

    fun insertTrack(
        path: String, id: Long, name: String?, date: String?, color: String?,
        hidden: Boolean, lineWidth: Int, pts: List<DoubleArray>
    ): Boolean =
        withDb(path, "insertTrack") { db ->
            val cv = ContentValues()
            cv.put("fid", id)
            putStr(cv, "name", name); putStr(cv, "date", date); putStr(cv, "color", color)
            cv.put("hidden", if (hidden) 1 else 0)
            cv.put("line_width", lineWidth)
            GpkgGeom.line(pts)?.let { cv.put("geom", it) }
            db.insertOrThrow("tracks", null, cv)
            finish(db, "tracks", bboxOf(pts))
            true
        } != null

    fun deleteTrack(path: String, id: Long): Boolean =
        withDb(path) { db ->
            db.execSQL("DELETE FROM tracks WHERE fid=?", arrayOf(id))
            finish(db, "tracks")
            true
        } != null

    fun updateTrack(path: String, id: Long, name: String?, color: String?, hidden: Boolean, lineWidth: Int): Boolean =
        withDb(path) { db ->
            val cv = ContentValues()
            putStr(cv, "name", name); putStr(cv, "color", color)
            cv.put("hidden", if (hidden) 1 else 0)
            cv.put("line_width", lineWidth)
            db.update("tracks", cv, "fid=?", arrayOf(id.toString()))
            finish(db, "tracks")
            true
        } != null

    // ---- 测量 ----

    fun insertMeasure(
        path: String, id: Long, name: String?, date: String?, type: String?, result: String?,
        color: String?, hidden: Boolean, closed: Boolean, lineWidth: Int, pts: List<DoubleArray>
    ): Boolean =
        withDb(path) { db ->
            val cv = ContentValues()
            cv.put("fid", id)
            putStr(cv, "name", name); putStr(cv, "date", date); putStr(cv, "type", type)
            putStr(cv, "result", result); putStr(cv, "color", color)
            cv.put("hidden", if (hidden) 1 else 0)
            cv.put("closed", if (closed) 1 else 0)
            cv.put("line_width", lineWidth)
            val geom = when (type) {
                "point" -> pts.firstOrNull()?.let { GpkgGeom.point(it[0], it[1], zOfLocal(it)) }
                "area", "polygon" -> GpkgGeom.polygon(pts)
                else -> GpkgGeom.line(pts)
            }
            if (geom != null) cv.put("geom", geom)
            db.insertOrThrow("measures", null, cv)
            finish(db, "measures", bboxOf(pts))
            true
        } != null

    private fun zOfLocal(p: DoubleArray): Double = if (p.size > 2) p[2] else 0.0

    fun deleteMeasure(path: String, id: Long): Boolean =
        withDb(path) { db ->
            db.execSQL("DELETE FROM measures WHERE fid=?", arrayOf(id))
            finish(db, "measures")
            true
        } != null

    fun updateMeasureStyle(path: String, id: Long, color: String?, lineWidth: Int): Boolean =
        withDb(path) { db ->
            val cv = ContentValues()
            putStr(cv, "color", color)
            cv.put("line_width", lineWidth)
            db.update("measures", cv, "fid=?", arrayOf(id.toString()))
            finish(db, "measures")
            true
        } != null

    // ---- 拍照 ----

    fun insertPhoto(
        path: String, id: Long, name: String?, lat: Double, lon: Double, alt: Double,
        timestamp: Long, visible: Boolean, groupId: Long
    ): Boolean =
        withDb(path, "insertPhoto") { db ->
            val cv = ContentValues()
            cv.put("fid", id)
            putStr(cv, "name", name)
            cv.put("timestamp", timestamp)
            cv.put("visible", if (visible) 1 else 0)
            cv.put("group_id", groupId)
            cv.put("geom", GpkgGeom.point(lat, lon, alt))
            db.insertOrThrow("photos", null, cv)
            finish(db, "photos", doubleArrayOf(lon, lon, lat, lat))
            true
        } != null

    /**
     * 追加照片附件：整行重写 attachments JSON + photo_path；首附件 BLOB 写 photo_data，
     * 非首附件写 [ATT_TABLE]。[srcFile] 读入成功并入库后删除（照片本体只留库内一份）。
     * @return 是否成功入库（文件缺失/读失败返回 true 但未删文件，下次打开清扫重试）
     */
    fun addPhotoAttachment(
        path: String, fid: Long, idx: Long, attachmentsJson: String?, mainPath: String?, srcFile: String
    ): Boolean {
        val bytes = File(srcFile).takeIf { it.exists() && it.length() > 0 }?.readBytes()
        val ok = withDb(path, "addPhotoAttachment") { db ->
            val cv = ContentValues()
            putStr(cv, "attachments", attachmentsJson)
            putStr(cv, "photo_path", mainPath)
            if (bytes != null && idx == 0L) cv.put("photo_data", bytes)
            db.update("photos", cv, "fid=?", arrayOf(fid.toString()))
            if (bytes != null && idx != 0L) {
                val acv = ContentValues()
                acv.put("fid", fid); acv.put("idx", idx); acv.put("data", bytes)
                db.insertWithOnConflict(ATT_TABLE, null, acv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            finish(db, "photos")
            true
        } == true
        // 库提交成功且拿到照片本体 → 删源文件（读失败保留文件，启动清扫幂等重试）
        if (ok && bytes != null) {
            runCatching { File(srcFile).delete() }
                .onFailure { Log.w(TAG, "删除照片源文件失败: $srcFile", it) }
        }
        return ok
    }

    fun deletePhoto(path: String, id: Long): Boolean =
        withDb(path) { db ->
            db.execSQL("DELETE FROM photos WHERE fid=?", arrayOf(id))
            db.execSQL("DELETE FROM $ATT_TABLE WHERE fid=?", arrayOf(id))
            finish(db, "photos")
            true
        } != null

    fun updatePhotoColumns(
        path: String, id: Long, name: String?, groupId: Long?, visible: Boolean?
    ): Boolean =
        withDb(path) { db ->
            val cv = ContentValues()
            if (name != null) putStr(cv, "name", name)
            if (groupId != null) cv.put("group_id", groupId)
            if (visible != null) cv.put("visible", if (visible) 1 else 0)
            if (cv.size() > 0) db.update("photos", cv, "fid=?", arrayOf(id.toString()))
            finish(db, "photos")
            true
        } != null

    /**
     * 读照片 BLOB：idx=0 主照片（photos.photo_data），idx≥1 附件（photo_att_data）。
     * 走 simpleQueryForBlobFileDescriptor 流式读取——rawQuery+getBlob 受 CursorWindow 单行
     * 2MB 上限，真机照片（加水印后常 2~8MB）必抛 "Failed to read row"，表现为对话框无图
     * （入库后源文件已删，无文件可回退）。
     */
    fun readPhotoBlob(path: String?, fid: Long, idx: Long): ByteArray? {
        if (path.isNullOrEmpty() || fid <= 0) return null
        return try {
            val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val sql = if (idx <= 0L) {
                    "SELECT photo_data FROM photos WHERE fid=?"
                } else {
                    "SELECT data FROM $ATT_TABLE WHERE fid=? AND idx=?"
                }
                db.compileStatement(sql).use { stmt ->
                    stmt.bindLong(1, fid)
                    if (idx > 0L) stmt.bindLong(2, idx)
                    runCatching {
                        stmt.simpleQueryForBlobFileDescriptor()?.let { pfd ->
                            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
                        }
                    }.onFailure { Log.e(TAG, "readPhotoBlob 读取失败 fid=$fid idx=$idx", it) }
                        .getOrNull()
                }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "readPhotoBlob 失败 fid=$fid idx=$idx", e)
            null
        }
    }

    // ---- 分组 ----

    fun insertGroup(path: String, id: Long, name: String?, createdTime: Long, visible: Boolean): Boolean =
        withDb(path) { db ->
            val cv = ContentValues()
            cv.put("fid", id)
            putStr(cv, "name", name)
            cv.put("created_time", createdTime)
            cv.put("visible", if (visible) 1 else 0)
            db.insertOrThrow("media_groups", null, cv)
            finish(db, "media_groups")
            true
        } != null

    fun updateGroup(path: String, id: Long, name: String?): Boolean =
        withDb(path) { db ->
            val cv = ContentValues()
            putStr(cv, "name", name)
            db.update("media_groups", cv, "fid=?", arrayOf(id.toString()))
            finish(db, "media_groups")
            true
        } != null

    /** 删除分组：组内点位移到未分组（-1），与内存语义一致 */
    fun deleteGroup(path: String, id: Long): Boolean =
        withDb(path) { db ->
            db.execSQL("DELETE FROM media_groups WHERE fid=?", arrayOf(id))
            db.execSQL("UPDATE photos SET group_id=-1 WHERE group_id=?", arrayOf(id))
            finish(db, "media_groups")
            finish(db, "photos")
            true
        } != null

    fun setGroupPhotosVisible(path: String, groupId: Long, visible: Boolean): Boolean =
        withDb(path) { db ->
            db.execSQL("UPDATE photos SET visible=? WHERE group_id=?",
                arrayOf(if (visible) 1 else 0, groupId))
            finish(db, "photos")
            true
        } != null

    // ---- 样地 ----

    fun upsertSurveyPlot(
        path: String, id: Long, name: String?, plotType: String?, attributesJson: String?,
        pts: List<DoubleArray>
    ): Boolean =
        withDb(path) { db ->
            val cv = ContentValues()
            cv.put("fid", id)
            putStr(cv, "name", name); putStr(cv, "plot_type", plotType)
            putStr(cv, "attributes", attributesJson)
            val geom = if (plotType == "point") {
                pts.firstOrNull()?.let { GpkgGeom.point(it[0], it[1], zOfLocal(it)) }
            } else {
                GpkgGeom.polygon(pts)
            }
            if (geom != null) cv.put("geom", geom)
            db.insertWithOnConflict("survey_plots", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            finish(db, "survey_plots", bboxOf(pts))
            true
        } != null

    // ---- 工程元数据 ----

    /** 同步五个元数据键（DELETE+INSERT，project_meta.key 无唯一索引）；空值删行对齐 C++ 写侧 */
    fun syncMeta(
        path: String, name: String?, description: String?, srs: String?,
        createTime: Long, updateTime: Long
    ): Boolean =
        withDb(path, "syncMeta") { db ->
            upsertMeta(db, "name", name)
            upsertMeta(db, "description", description)
            upsertMeta(db, "srs", srs)
            upsertMeta(db, "createTime", createTime.toString())
            upsertMeta(db, "updateTime", updateTime.toString())
            finish(db, "project_meta")
            true
        } != null

    private fun upsertMeta(db: SQLiteDatabase, key: String, value: String?) {
        db.execSQL("DELETE FROM project_meta WHERE key=?", arrayOf(key))
        if (value.isNullOrEmpty()) return
        db.execSQL("INSERT INTO project_meta (key, value) VALUES (?, ?)", arrayOf(key, value))
    }
}
