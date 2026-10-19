package com.zys.mobilemap.project

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
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
 * GeoPackage 增量直写层（0.5.0）
 *
 * gpkg 本体是 SQLite 文件，业务七表（tracks/measures/photos/media_groups/survey_plots/
 * project_meta/layer_refs）为自定义表。日常业务写入不再经 C++ GDAL 整文件重建（照片多时
 * 每次保存都全量重读写入所有照片 BLOB，数百 MB IO 导致停表/拍照卡顿），改由 app 端
 * android.database.sqlite 对同一文件做毫秒级增量 DML：
 *  - 几何列直接写 GPKG 二进制（GP 头 + XYZ 包络 + ISO WKB 含 Z），与 GDAL 写入等价；
 *  - gpkg_contents.last_change / bbox 与 gpkg_ogr_contents 要素计数随写随更，QGIS/ArcGIS 兼容；
 *  - GDAL 建库默认创建的 RTree 空间索引触发器引用 ST_* 扩展函数（仅 GDAL 运行时注册，
 *    纯 SQLite 客户端执行 INSERT 会报 no such function）→ 建库后一次性移除触发器与索引表
 *    （空间索引是可选优化，移除不影响读取；若后续触发整文件重建会再次出现，prepareSchema 幂等移除）；
 *  - 附加表 photo_att_data(fid, idx, data) 保存第 2..N 张附件的照片 BLOB（表不注册进
 *    gpkg_contents，GDAL/QGIS 不可见）；主照片仍在 photos.photo_data。
 *
 * 整文件重建（JNI writeEncoded）仅保留给：新建工程文件、老库 schema 升级；导出/复制改用文件复制。
 * 每次操作独立 open/beginTransaction/close，不持长连接，避免与重建 tmp+rename 换文件产生句柄竞态。
 */
internal object GeoPackageDml {

    private const val TAG = "GeoPackageDml"

    /** 附件 BLOB 附加表（非主照片，第 2..N 张） */
    private const val ATT_TABLE = "photo_att_data"

    /** photos 表增量写所要求的完整列（缺任一 → 走整文件重建升级） */
    private val PHOTOS_COLUMNS =
        listOf("name", "photo_path", "photo_data", "timestamp", "attachments", "visible", "group_id")

    /** 增量写所要求的全部业务表 */
    private val REQUIRED_TABLES =
        listOf("tracks", "measures", "photos", "media_groups", "survey_plots", "project_meta", "layer_refs")

    // ---- 几何编码 ----

    /**
     * GPKG 二进制几何编码器：GP 头（LE、SRS=4326、XYZ 包络）+ ISO WKB（含 Z）。
     * 与 GDAL/OGR 写入格式等价，OGR/QGIS 可直接读取；点列内存序 [lat,lon,alt]。
     */
    internal object GpkgGeom {

        private const val WKB_POINT_Z = 0x80000001L
        private const val WKB_LINESTRING_Z = 0x80000002L
        private const val WKB_POLYGON_Z = 0x80000003L
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

        /** GP 头（flags 0x05 = LE 头 + XYZ 包络）+ 包络 + WKB 体 */
        private fun pack(body: ByteBuffer, env: DoubleArray): ByteArray {
            val buf = ByteBuffer.allocate(8 + 4 + env.size * 8 + body.capacity())
                .order(ByteOrder.LITTLE_ENDIAN)
            buf.put('G'.code.toByte())
            buf.put('P'.code.toByte())
            buf.put(0)       // version
            buf.put(0x05)    // flags: LE 头 + 包络指示 2 (xyz)
            buf.putInt(SRS_ID)
            for (d in env) buf.putDouble(d)
            buf.put(body.array())
            return buf.array()
        }
    }

    // ---- 通用骨架 ----

    /** 独立连接 + 事务包裹；异常吞掉记日志返回 null（增量写失败不致命，下次整文件重建可兜底） */
    private inline fun <T> withDb(path: String, block: (SQLiteDatabase) -> T): T? {
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

    // ---- schema 检查 / 准备 ----

    /** 业务表与列是否齐备（齐备即可增量写；文件不存在返回 true，由新建流程负责建库） */
    fun schemaCurrent(path: String): Boolean {
        if (!File(path).exists()) return true
        return try {
            val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                for (t in REQUIRED_TABLES) {
                    if (!tableExists(db, t)) return false
                }
                columnsOf(db, "photos").containsAll(PHOTOS_COLUMNS) &&
                        columnsOf(db, "tracks").containsAll(listOf("name", "date", "color", "hidden", "line_width")) &&
                        columnsOf(db, "measures")
                            .containsAll(listOf("name", "date", "type", "result", "color", "hidden", "closed", "line_width"))
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "schemaCurrent 检查失败: $path", e)
            false
        }
    }

    /**
     * 写前准备（幂等）：移除 RTree 触发器与索引表（触发器引用 GDAL 运行时注册的 ST_* 函数，
     * 纯 SQLite 执行 INSERT 会失败）、清理 gpkg_extensions 登记项、补建附件 BLOB 附加表。
     */
    fun prepareSchema(path: String): Boolean =
        withDb(path) { db ->
            val names = ArrayList<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'rtree_%'", null).use { c ->
                while (c.moveToNext()) names.add(c.getString(0))
            }
            for (t in names) runCatching { db.execSQL("DROP TRIGGER IF EXISTS \"$t\"") }
            names.clear()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'rtree_%'", null).use { c ->
                while (c.moveToNext()) names.add(c.getString(0))
            }
            // 影子表（_node/_rowid/_parent）为普通表可直接 DROP；虚拟表本体 DROP 需 rtree 模块
            // 参与析构，无该模块的环境抛 no such module → 降级 writable_schema 删 sqlite_master 条目
            val dropFailed = ArrayList<String>()
            for (t in names) {
                try {
                    db.execSQL("DROP TABLE IF EXISTS \"$t\"")
                } catch (e: Exception) {
                    dropFailed.add(t)
                }
            }
            if (dropFailed.isNotEmpty()) {
                runCatching {
                    db.rawQuery("PRAGMA writable_schema=ON", null).use { }
                    for (t in dropFailed) {
                        db.execSQL("DELETE FROM sqlite_master WHERE type='table' AND name=?", arrayOf<Any>(t))
                    }
                    db.rawQuery("PRAGMA writable_schema=OFF", null).use { }
                }.onFailure { Log.e(TAG, "writable_schema 清理 rtree 虚拟表失败: $dropFailed", it) }
            }
            // GDAL 登记名为 gpkg_rtree_index（非 gpkg_rtree），LIKE 前缀匹配确保清干净
            runCatching { db.execSQL("DELETE FROM gpkg_extensions WHERE extension_name LIKE 'gpkg_rtree%'") }
            db.execSQL("CREATE TABLE IF NOT EXISTS $ATT_TABLE (fid INTEGER NOT NULL, idx INTEGER NOT NULL, " +
                    "data BLOB NOT NULL, PRIMARY KEY (fid, idx))")
            true
        } != null

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { c ->
            return c.moveToFirst()
        }
    }

    private fun columnsOf(db: SQLiteDatabase, table: String): List<String> {
        val cols = ArrayList<String>()
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
            while (c.moveToNext()) cols.add(c.getString(1))
        }
        return cols
    }

    // ---- 轨迹 ----

    fun insertTrack(
        path: String, id: Long, name: String?, date: String?, color: String?,
        hidden: Boolean, lineWidth: Int, pts: List<DoubleArray>
    ): Boolean =
        withDb(path) { db ->
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
        withDb(path) { db ->
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
        val ok = withDb(path) { db ->
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

    /** 读照片 BLOB：idx=0 主照片（photos.photo_data），idx≥1 附件（photo_att_data） */
    fun readPhotoBlob(path: String?, fid: Long, idx: Long): ByteArray? {
        if (path.isNullOrEmpty() || fid <= 0) return null
        return try {
            val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                if (idx <= 0L) {
                    db.rawQuery("SELECT photo_data FROM photos WHERE fid=?", arrayOf(fid.toString())).use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null
                    }
                } else {
                    db.rawQuery("SELECT data FROM $ATT_TABLE WHERE fid=? AND idx=?",
                        arrayOf(fid.toString(), idx.toString())).use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null
                    }
                }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "readPhotoBlob 失败 fid=$fid idx=$idx", e)
            null
        }
    }

    /**
     * 照片文件清扫（幂等，打开工程时后台执行）：
     *  - BLOB 已在库 → 删除磁盘原件（历史版本的照片文件退役）；
     *  - BLOB 缺失且磁盘原件存在 → 读入库（主照片 photo_data / 附件附加表）后删除文件。
     * 仅处理 [mediaRoot] 目录下的文件（/调查宝/Media），其它路径的引用文件不动。
     * @return 是否发生任何清扫
     */
    fun sweepPhotoFiles(path: String, mediaRoot: String): Boolean {
        if (!schemaCurrent(path)) return false  // 列不齐时先整文件重建，photo_data 列可能不存在
        val root = runCatching { File(mediaRoot).canonicalPath }.getOrNull() ?: return false
        return withDb(path) { db ->
            data class Row(val fid: Long, val photoPath: String?, val attachments: String?, val hasMain: Boolean)
            val rows = ArrayList<Row>()
            db.rawQuery("SELECT fid, photo_path, attachments, photo_data IS NOT NULL FROM photos", null).use { c ->
                while (c.moveToNext()) rows.add(Row(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) != 0))
            }
            var swept = false
            for (row in rows) {
                for ((idx, p) in attachmentPathsOf(row.photoPath, row.attachments)) {
                    val f = File(p)
                    if (p.isEmpty() || !f.exists()) continue
                    val managed = runCatching { f.canonicalPath.startsWith(root) }.getOrDefault(false)
                    if (!managed) continue
                    val has = if (idx == 0L) row.hasMain else auxHas(db, row.fid, idx)
                    if (has) {
                        f.delete(); swept = true
                        continue
                    }
                    val bytes = f.takeIf { it.length() > 0 }?.readBytes() ?: continue
                    if (idx == 0L) {
                        val cv = ContentValues()
                        cv.put("photo_data", bytes)
                        db.update("photos", cv, "fid=?", arrayOf(row.fid.toString()))
                    } else {
                        val acv = ContentValues()
                        acv.put("fid", row.fid); acv.put("idx", idx); acv.put("data", bytes)
                        db.insertWithOnConflict(ATT_TABLE, null, acv, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    f.delete()
                    swept = true
                }
            }
            swept
        } != null
    }

    /** 解析某照片全部附件 (idx, path)：attachments JSON 优先，回退 photo_path 单附件 */
    private fun attachmentPathsOf(photoPath: String?, attachments: String?): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        if (!attachments.isNullOrEmpty()) {
            runCatching {
                val arr = JSONArray(attachments)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = o.optString("path", "")
                    if (p.isNotEmpty()) out.add(i.toLong() to p)
                }
            }
        }
        if (out.isEmpty() && !photoPath.isNullOrEmpty()) out.add(0L to photoPath)
        return out
    }

    private fun auxHas(db: SQLiteDatabase, fid: Long, idx: Long): Boolean {
        db.rawQuery("SELECT 1 FROM $ATT_TABLE WHERE fid=? AND idx=?",
            arrayOf(fid.toString(), idx.toString())).use { c -> return c.moveToFirst() }
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
        withDb(path) { db ->
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
