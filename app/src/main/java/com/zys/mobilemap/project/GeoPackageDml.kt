package com.zys.mobilemap.project

import android.content.ContentValues
import android.database.Cursor
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
 * gpkg 本体是 SQLite 文件，业务表（tracks/measures_point/measures_line/measures_polygon/
 * photos/media_groups/survey_point/survey_polygon/project_meta/layer_refs）为自定义表。
 * 日常业务写入不再经 C++ GDAL 整文件重建（照片多时
 * 每次保存都全量重读写入所有照片 BLOB，数百 MB IO 导致停表/拍照卡顿），改由 app 端
 * android.database.sqlite 对同一文件做毫秒级增量 DML：
 *  - 几何列直接写 GPKG 二进制（GP 头 + XYZ 包络 + ISO WKB 含 Z），与 GDAL 写入等价；
 *  - gpkg_contents.last_change / bbox 与 gpkg_ogr_contents 要素计数随写随更，QGIS/ArcGIS 兼容；
 *  - GDAL 建库默认创建的 RTree 空间索引触发器引用 ST_* 扩展函数（仅 GDAL 运行时注册，
 *    纯 SQLite 客户端执行 INSERT 会报 no such function）。新库由 C++ 侧建层时传
 *    SPATIAL_INDEX=NO 不再产生触发器；老库（0.4.0.5~0.4.1.1 建的）残留的触发器/虚拟表由
 *    [prepareSchema] 在打开工程与整文件重建后原地清除。**但空间索引不能省**：ArcMap 10.8 GUI
 *    属性表首屏按 rtree_% 枚举 OID，库里没有空间索引会直接弹「无法确定 OID」（arcpy/GDAL 读
 *    同一张表却全绿，故 provider 侧检查不能作为它的反证）。而 Android 框架 SQLite **没编 rtree
 *    模块**（CREATE VIRTUAL TABLE ... USING rtree 报 no such module: rtree），Kotlin 侧既建不了
 *    也删不了 rtree 虚拟表——旧包清老库索引时影子表（普通表）删得掉、虚拟表删不掉，留下的半残
 *    目录项正是该报错的根因。故索引改由 C++ 侧 [GeoPackageWriter.rebuildSpatialIndex] 在 GDAL
 *    自带 SQLite 里建**无触发器**版本（逐要素灌 OGR 包络 + 登记 gpkg_extensions + 点层四至外扩），
 *    每笔要素增量写提交后随 [withDb] 尾注重建一次——app 写库仍不碰 ST_*；
 *  - 附加表 photo_att_data(fid, idx, data) 保存第 2..N 张附件的照片 BLOB（表不注册进
 *    gpkg_contents，GDAL/QGIS 不可见）；主照片仍在 photos.photo_data。
 *
 * 整文件重建（JNI writeEncoded）仅保留给：新建工程文件；导出/复制改用文件复制。
 * 每次操作独立 open/beginTransaction/close，不持长连接，避免与重建 tmp+rename 换文件产生句柄竞态。
 */
internal object GeoPackageDml {

    private const val TAG = "GeoPackageDml"

    /** 本线程这轮 [withDb] 是否动过要素表：提交后由 [withDb] 触发一次 native 空间索引重建。
     *  增量写走框架 SQLiteDatabase（无 rtree 模块），索引只能在事务外由 GDAL 整体重建。 */
    private val reindexPending = ThreadLocal.withInitial { false }

    /** 附件 BLOB 附加表（非主照片，第 2..N 张） */
    private const val ATT_TABLE = "photo_att_data"

    /**
     * 量测/样地按几何类型分表（拆表，与 C++ geopackage_io.cpp 建库口径逐字一致）。
     *
     * 老结构一张 measures 同时存点/线/面，gpkg_geometry_columns 只能声明 GEOMETRY，
     * ArcMap 10.8 的 shapeType 按首个要素推断，其余类型要素被吞（实测 3 条量测只出 1 条），
     * 空表更判成 Any 直接不可用。分表后每层写实类型，ArcMap/QGIS 逐层可靠识别。
     * 老库在 [prepareSchema] 里原地迁移（按 blob 实际类型搬迁，搬不干净就不删老表）。
     */
    private val MEASURE_TABLES = listOf("measures_point", "measures_line", "measures_polygon")
    private val SURVEY_TABLES = listOf("survey_point", "survey_polygon")
    private const val LEGACY_MEASURE_TABLE = "measures"
    private const val LEGACY_SURVEY_TABLE = "survey_plots"

    /** 分表名 → 该层声明的几何类型（gpkg_geometry_columns.geometry_type_name） */
    private val TYPED_TABLES: List<Pair<String, String>> = listOf(
        "measures_point" to "POINT",
        "measures_line" to "LINESTRING",
        "measures_polygon" to "POLYGON",
        "survey_point" to "POINT",
        "survey_polygon" to "POLYGON",
    )

    /** 量测 type（内存模型）→ 分表；distance/line/angle 与未知一律按线，对齐 C++ measureTableOf */
    private fun measureTableOf(type: String?): String = when (type) {
        "point" -> "measures_point"
        "area", "polygon" -> "measures_polygon"
        else -> "measures_line"
    }

    private fun surveyTableOf(plotType: String?): String =
        if (plotType == "point") "survey_point" else "survey_polygon"

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

    /**
     * 库结构自愈（打开工程、以及整文件重建之后各调用一次；幂等，毫秒级）。分两步、各自一个事务：
     *
     * 1) [healRtreeAndAttach] 老库（0.4.0.5~0.4.1.1 由 GDAL 建库）里每张要素表带一组 rtree 触发器，
     *    触发器体调 ST_IsEmpty/ST_MinX 等 GDAL 专有函数；app 侧用框架 SQLiteDatabase 增量写时这些函数
     *    不存在，**每条 INSERT/UPDATE 在编译触发器阶段就失败**，而 [withDb] 吞异常只记日志，
     *    表现为「界面提示保存成功、库里 0 行」。此处删掉 rtree_% 触发器与同名虚拟表：
     *    DROP TABLE rtree_% 在部分 SQLite 上不被虚拟表接受，退回 writable_schema 直删
     *    sqlite_master 目录项（虚拟表本体随之失效，不影响 gpkg 读取）。同时把 z 归一为 1、
     *    补齐附加表 photo_att_data。
     * 2) [migrateToTypedTables] 拆表迁移：补建 measures_point/line/polygon 与 survey_point/polygon，
     *    把老混装表 measures/survey_plots 的要素按 **blob 实际几何类型** 搬进对应分表，全部搬成功
     *    才删老表并注销其 gpkg_contents/gpkg_geometry_columns/gpkg_ogr_contents 登记项；
     *    任一要素判不出类型即抛异常整笔回滚，老表原样保留（宁可 GIS 端仍旧少数，不可丢数据）。
     *    先做 1) 再做 2)：老表的 rtree 触发器不清掉，2) 的 DELETE/DROP 与分表 INSERT 都会编译失败。
     */
    fun prepareSchema(path: String): Boolean {
        purgeOrphanRtree(path)
        val healed = withDb(path, "prepareSchema") { db -> healRtreeAndAttach(db, path) } != null
        val migrated = withDb(path, "migrateToTypedTables") { db -> migrateToTypedTables(db, path) } != null
        // 存量库没有 rtree_%＝ArcMap 打开属性表直接报「无法确定 OID」：打开工程即按当前数据
        // 把每层空间索引与点层四至一起自愈（native GDAL 建，框架 SQLite 建不了）。
        GeoPackageWriter.rebuildSpatialIndex(path)
        return healed && migrated
    }

    /** 半残 rtree（sqlite_master 里名字还在、影子表实体已丢）在事务里 DROP 会报
     *  `no such table: rtree_x_geom_node`，而 writable_schema 直删目录项后必须重开连接 SQLite 才重读
     *  schema——所以单独走一趟：独立连接、不进事务、删完即关。实测这种半残态（虚拟表在、查不动、
     *  又没登记 gpkg_extensions）正是 ArcMap 打开属性表报「无法确定 OID」的直接原因，
     *  [prepareSchema] 尾步会经 native GDAL 把完整索引重建出来。 */
    private fun purgeOrphanRtree(path: String) {
        runCatching {
            val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                val names = ArrayList<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE 'rtree_%'", null).use { c ->
                    while (c.moveToNext()) names.add(c.getString(0))
                }
                if (names.isEmpty()) return@runCatching
                val failed = ArrayList<String>()
                for (n in names.sorted())            // 虚拟表名最短排最前，DROP 它会连带影子表
                    runCatching { db.execSQL("DROP TABLE IF EXISTS \"$n\"") }.onFailure { failed.add(n) }
                val left = ArrayList<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE 'rtree_%'", null).use { c ->
                    while (c.moveToNext()) left.add(c.getString(0))
                }
                if (left.isNotEmpty()) {
                    db.rawQuery("PRAGMA writable_schema=ON", null).use { }
                    for (n in left)
                        db.execSQL(
                            "DELETE FROM sqlite_master WHERE type IN ('table','trigger') AND name=?",
                            arrayOf<Any>(n)
                        )
                    db.rawQuery("PRAGMA writable_schema=OFF", null).use { }
                }
                if (failed.isNotEmpty() || left.isNotEmpty())
                    Log.w(TAG, "prepareSchema: ${File(path).name} 清半残 rtree DROP失败=$failed 直删=$left")
            } finally {
                db.close()
            }
        }.onFailure { Log.e(TAG, "purgeOrphanRtree 失败: $path", it) }
    }

    /** 步骤 1：清 rtree 触发器残留、z 归一、补附件表。返回 true 便于 [withDb] 判空。
     *  rtree 虚拟表/影子表本体由 [purgeOrphanRtree] 在事务外清、索引由 native 重建，这里只处理
     *  触发器：触发器体调 ST_IsEmpty/ST_MinX 等 GDAL 专有函数，框架 SQLite 增量写会在编译触发器
     *  阶段失败并静默丢数据（0.4.1.x 事故）。 */
    private fun healRtreeAndAttach(db: SQLiteDatabase, path: String): Boolean {
        val names = ArrayList<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'rtree_%'", null).use { c ->
            while (c.moveToNext()) names.add(c.getString(0))
        }
        val triggerCount = names.size
        for (t in names) runCatching { db.execSQL("DROP TRIGGER IF EXISTS \"$t\"") }
        if (triggerCount > 0) {
            runCatching {
                db.execSQL("DELETE FROM gpkg_extensions WHERE extension_name LIKE 'gpkg_rtree%'")
            }.onFailure { Log.d(TAG, "prepareSchema: 无 gpkg_extensions 表，跳过扩展注销", it) }
            Log.w(TAG, "prepareSchema: ${File(path).name} 清除 rtree 触发器残留 $triggerCount 个")
        }
        // 分表名一并纳入：老库本轮才补建的分表也要保证 z=1
        runCatching {
            db.execSQL(
                "UPDATE gpkg_geometry_columns SET z=1 WHERE (z IS NULL OR z<>1) " +
                        "AND table_name IN ('tracks','photos','measures','survey_plots'," +
                        "'measures_point','measures_line','measures_polygon','survey_point','survey_polygon')"
            )
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $ATT_TABLE (fid INTEGER NOT NULL, idx INTEGER NOT NULL, " +
                    "data BLOB NOT NULL, PRIMARY KEY (fid, idx))"
        )
        // 照片/测量补 remark 列（幂等）；老 measures 单表也补，供随后拆表迁移 SELECT 携带。
        // remark 不进任何重建门禁，避免老库判缺列触发整文件重建丢照片 BLOB。
        for (t in listOf("photos", "measures_point", "measures_line", "measures_polygon", LEGACY_MEASURE_TABLE))
            addColumnIfMissing(db, t, "remark", "TEXT(255)")
        return true
    }

    /** 步骤 2：补建分表 + 搬迁老混装表。任一要素无法判型即抛，整笔事务回滚，老表保持原样。 */
    private fun migrateToTypedTables(db: SQLiteDatabase, path: String): Boolean {
        for ((table, gtype) in TYPED_TABLES)
            if (!tableExists(db, table)) createTypedTable(db, table, gtype)
        val m = migrateLegacyTable(
            db, LEGACY_MEASURE_TABLE,
            listOf("fid", "geom", "name", "date", "type", "result", "remark", "color", "hidden", "closed", "line_width"),
            "type"
        ) { blobBase, typeValue ->
            // blob 类型优先（分表声明的就是几何类型）；blob 为空/不可辨时退回 type 列
            when (blobBase ?: when (typeValue) {
                "point" -> 1
                "area", "polygon" -> 3
                else -> 2
            }) {
                1 -> "measures_point"
                2 -> "measures_line"
                3 -> "measures_polygon"
                else -> null
            }
        }
        val s = migrateLegacyTable(
            db, LEGACY_SURVEY_TABLE,
            listOf("fid", "geom", "name", "plot_type", "attributes"),
            "plot_type"
        ) { blobBase, plotType ->
            when (blobBase ?: if (plotType == "point") 1 else 3) {
                1 -> "survey_point"
                3 -> "survey_polygon"
                else -> null
            }
        }
        if (m + s > 0) Log.w(TAG, "拆表迁移完成：${File(path).name} 量测 $m 条、样地 $s 条已入分表")
        return true
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)
        ).use { it.moveToFirst() }

    /** 幂等补列：表存在且缺列时 ALTER ADD COLUMN（PRAGMA table_info 第 1 列为列名） */
    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, decl: String) {
        if (!tableExists(db, table)) return
        val has = db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == column) found = true
            found
        }
        if (!has) db.execSQL("ALTER TABLE \"$table\" ADD COLUMN \"$column\" $decl")
    }

    /** 建一张写实类型的分表，DDL/注册项/要素计数触发器与 GDAL GPKG 驱动建层产物逐字对齐 */
    private fun createTypedTable(db: SQLiteDatabase, table: String, gtype: String) {
        val cols = if (table.startsWith("measures"))
            "\"name\" TEXT(255), \"date\" TEXT(255), \"type\" TEXT(255), \"result\" TEXT(255), " +
                    "\"remark\" TEXT(255), \"color\" TEXT(255), \"hidden\" MEDIUMINT, \"closed\" MEDIUMINT, \"line_width\" MEDIUMINT"
        else
            "\"name\" TEXT(255), \"plot_type\" TEXT(255), \"attributes\" TEXT(255)"
        db.execSQL(
            "CREATE TABLE \"$table\" ( \"fid\" INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "\"geom\" $gtype, $cols)"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO gpkg_contents (table_name, data_type, identifier, description, last_change, " +
                    "min_x, min_y, max_x, max_y, srs_id) VALUES (?, 'features', ?, '', ?, NULL, NULL, NULL, NULL, 4326)",
            arrayOf<Any>(table, table, lastChange())
        )
        db.execSQL(
            "INSERT OR IGNORE INTO gpkg_geometry_columns (table_name, column_name, geometry_type_name, srs_id, z, m) " +
                    "VALUES (?, 'geom', ?, 4326, 1, 0)",
            arrayOf<Any>(table, gtype)
        )
        db.execSQL(
            "INSERT OR IGNORE INTO gpkg_ogr_contents (table_name, feature_count) VALUES (?, 0)", arrayOf<Any>(table)
        )
        db.execSQL(
            "CREATE TRIGGER \"trigger_insert_feature_count_$table\" AFTER INSERT ON \"$table\" BEGIN " +
                    "UPDATE gpkg_ogr_contents SET feature_count = feature_count + 1 " +
                    "WHERE lower(table_name) = lower('$table'); END"
        )
        db.execSQL(
            "CREATE TRIGGER \"trigger_delete_feature_count_$table\" AFTER DELETE ON \"$table\" BEGIN " +
                    "UPDATE gpkg_ogr_contents SET feature_count = feature_count - 1 " +
                    "WHERE lower(table_name) = lower('$table'); END"
        )
        Log.i(TAG, "拆表：补建分表 $table（$gtype）")
    }

    /**
     * 把老混装表的每一行搬进分表；[route] 返回 null 视为判型失败即抛（回滚）。
     * 全部成功后删老表并注销三处登记项。返回搬迁行数（老表不存在返回 0）。
     * 搬迁顺带按 blob 头里的 XY 包络刷新各分表在 gpkg_contents 的四至——ArcMap 的「缩放到图层」
     * 与图层范围全靠它，留 NULL 会让新补建的分表在地图上找不到位置。
     */
    private fun migrateLegacyTable(
        db: SQLiteDatabase,
        legacy: String,
        columns: List<String>,
        typeColumn: String,
        route: (blobBase: Int?, typeValue: String?) -> String?
    ): Int {
        if (!tableExists(db, legacy)) return 0
        val colSql = columns.joinToString(", ")
        val marks = columns.joinToString(", ") { "?" }
        val geomIx = columns.indexOf("geom")
        val typeIx = columns.indexOf(typeColumn)
        val perTable = LinkedHashMap<String, Int>()
        val bbox = LinkedHashMap<String, DoubleArray>()
        db.rawQuery("SELECT $colSql FROM \"$legacy\"", null).use { c ->
            while (c.moveToNext()) {
                val vals = arrayOfNulls<Any?>(columns.size)
                for (i in columns.indices) {
                    vals[i] = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_NULL -> null
                        else -> c.getString(i)
                    }
                }
                val blob = vals[geomIx] as ByteArray?
                val head = gpHead(blob)
                val target = route(head?.first, vals[typeIx] as String?)
                    ?: throw IllegalStateException("$legacy 存在无法判定几何类型的要素，放弃拆表迁移")
                db.execSQL("INSERT OR REPLACE INTO \"$target\" ($colSql) VALUES ($marks)", vals)
                perTable[target] = (perTable[target] ?: 0) + 1
                head?.second?.let { box -> bbox[target] = mergeBbox(bbox[target], box) }
            }
        }
        db.execSQL("DROP TABLE \"$legacy\"")
        db.execSQL("DELETE FROM gpkg_contents WHERE table_name=?", arrayOf<Any>(legacy))
        db.execSQL("DELETE FROM gpkg_geometry_columns WHERE table_name=?", arrayOf<Any>(legacy))
        db.execSQL("DELETE FROM gpkg_ogr_contents WHERE table_name=?", arrayOf<Any>(legacy))
        runCatching { db.execSQL("DELETE FROM sqlite_sequence WHERE name=?", arrayOf<Any>(legacy)) }
        for ((t, box) in bbox) finish(db, t, box)
        Log.i(TAG, "拆表：$legacy ${perTable.values.sum()} 条 → $perTable，老表已删除")
        return perTable.values.sum()
    }

    private fun mergeBbox(cur: DoubleArray?, box: DoubleArray): DoubleArray =
        if (cur == null) box.clone()
        else doubleArrayOf(
            minOf(cur[0], box[0]), maxOf(cur[1], box[1]),
            minOf(cur[2], box[2]), maxOf(cur[3], box[3])
        )

    /**
     * GPKG 二进制几何头 → （基础类型码 1 点 / 2 线 / 3 面，XY 包络 [minx,maxx,miny,maxy]）；
     * 类型不可辨返回 null（包络缺失只让第二项为 null，不影响判型）。
     *
     * 头 8B：magic 'GP' + version + flags + srs_id；flags bit0＝小端，bit1..3＝包络指示
     * （0 无 / 1 XY=32B / 2 XYZ=48B / 3 XYM=48B / 4 XYZM=64B，前四个 double 恒为
     * minx,maxx,miny,maxy），bit4＝empty。其后 WKB：1B 字节序 + 4B 类型码——ISO 族
     * 1001/2002/3003 取 %1000，OGC 99-402 的 2.5D 族（bit31 置位）取低 8 位。
     */
    private fun gpHead(blob: ByteArray?): Pair<Int, DoubleArray?>? {
        if (blob == null || blob.size < 13) return null
        if (blob[0] != 'G'.code.toByte() || blob[1] != 'P'.code.toByte()) return null
        val flags = blob[3].toInt() and 0xFF
        if ((flags and 0x10) != 0) return null                      // empty geometry
        val le = (flags and 0x01) != 0
        val env = when ((flags shr 1) and 0x07) {
            0 -> 0
            1 -> 32
            2, 3 -> 48
            4 -> 64
            else -> return null
        }
        val p = 8 + env
        if (blob.size < p + 5) return null
        if ((blob[p].toInt() and 0xFF) != (if (le) 1 else 0)) return null   // WKB 字节序与头不一致
        val b1 = blob[p + 1].toInt() and 0xFF
        val b2 = blob[p + 2].toInt() and 0xFF
        val b3 = blob[p + 3].toInt() and 0xFF
        val b4 = blob[p + 4].toInt() and 0xFF
        val type = if (le) b1 or (b2 shl 8) or (b3 shl 16) or (b4 shl 24)
        else (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
        val base = when {
            type < 0 -> type and 0xFF                               // bit31 置位＝OGC 2.5D/3DM 标志族（Int 负值）
            type >= 1000 -> type % 1000                             // ISO WKB 1001/2002/3003 族
            else -> type
        }
        if (base !in 1..3) return null
        val box = if (env >= 32) {
            val bb = ByteBuffer.wrap(blob).order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            doubleArrayOf(bb.getDouble(8), bb.getDouble(16), bb.getDouble(24), bb.getDouble(32))
        } else null
        return base to box
    }

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
            // 增量写动了要素表：事务已提交、连接已关，此时 GDAL 的写连接才拿得到锁。
            // 索引必须在 native 建（框架 SQLite 无 rtree 模块），且每笔写后整体重建以跟上增量。
            if (reindexPending.get()) {
                reindexPending.set(false)
                GeoPackageWriter.rebuildSpatialIndex(path)
            }
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
        // 单个点要素的并框是零面积的（min_x≈max_x 且 min_y≈max_y），ArcMap 10.8 GUI 首屏按四至做
        // 空间枚举读 OID 时查不到行。判退化用「两维跨度都 <1e-9 度」而非严格相等：整文件重建走
        // GDAL，它在写要素阶段已先落一份四至，单点经 float 往返后 min_x 与 max_x 会差 ~1e-14。
        // 非点层的退化框退回 NULL；空间索引与点层四至在事务提交后由 native 重建（见 [withDb] 尾注）
        val degenerate = bbox != null && bbox.size == 4 &&
            kotlin.math.abs(bbox[1] - bbox[0]) < 1e-9 && kotlin.math.abs(bbox[3] - bbox[2]) < 1e-9
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
            if (degenerate) {
                db.execSQL(
                    "UPDATE gpkg_contents SET min_x=NULL, max_x=NULL, min_y=NULL, max_y=NULL " +
                            "WHERE table_name=? AND abs(max_x - min_x) < 1e-9 AND abs(max_y - min_y) < 1e-9",
                    arrayOf(table)
                )
            }
            reindexPending.set(true)
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

    // ---- 测量（按几何类型入 measures_point / measures_line / measures_polygon）----

    fun insertMeasure(
        path: String, id: Long, name: String?, date: String?, type: String?, result: String?,
        remark: String?, color: String?, hidden: Boolean, closed: Boolean, lineWidth: Int, pts: List<DoubleArray>
    ): Boolean =
        withDb(path, "insertMeasure") { db ->
            val table = measureTableOf(type)
            val cv = ContentValues()
            cv.put("fid", id)
            putStr(cv, "name", name); putStr(cv, "date", date); putStr(cv, "type", type)
            putStr(cv, "result", result); putStr(cv, "remark", remark); putStr(cv, "color", color)
            cv.put("hidden", if (hidden) 1 else 0)
            cv.put("closed", if (closed) 1 else 0)
            cv.put("line_width", lineWidth)
            // 几何与分表声明的类型必须一致：类型不符一律留空几何，绝不写错型（错型即 ArcMap 吞要素之因）
            val geom = when (table) {
                "measures_point" -> pts.firstOrNull()?.let { GpkgGeom.point(it[0], it[1], zOfLocal(it)) }
                "measures_polygon" -> if (pts.size >= 3) GpkgGeom.polygon(pts) else null
                else -> if (pts.size >= 2) GpkgGeom.line(pts) else null
            }
            if (geom != null) cv.put("geom", geom)
            db.insertOrThrow(table, null, cv)
            finish(db, table, bboxOf(pts))
            true
        } != null

    private fun zOfLocal(p: DoubleArray): Double = if (p.size > 2) p[2] else 0.0

    fun deleteMeasure(path: String, id: Long, type: String? = null): Boolean =
        withDb(path, "deleteMeasure") { db ->
            var n = 0
            for (t in measureTableOrder(db, type)) {
                val hit = runCatching { db.delete(t, "fid=?", arrayOf(id.toString())) }.getOrDefault(0)
                if (hit > 0) {
                    n += hit
                    finish(db, t)
                }
            }
            if (n == 0) Log.w(TAG, "deleteMeasure: fid=$id 在各量测表均未命中")
            true
        } != null

    fun updateMeasureStyle(path: String, id: Long, color: String?, lineWidth: Int, type: String? = null): Boolean =
        withDb(path, "updateMeasureStyle") { db ->
            val cv = ContentValues()
            putStr(cv, "color", color)
            cv.put("line_width", lineWidth)
            applyMeasureUpdate(db, cv, id, type)
            true
        } != null

    fun updateMeasureName(path: String, id: Long, name: String?, type: String? = null): Boolean =
        withDb(path, "updateMeasureName") { db ->
            val cv = ContentValues()
            putStr(cv, "name", name)
            applyMeasureUpdate(db, cv, id, type)
            true
        } != null

    fun updateMeasureRemark(path: String, id: Long, remark: String?, type: String? = null): Boolean =
        withDb(path, "updateMeasureRemark") { db ->
            val cv = ContentValues()
            putStr(cv, "remark", remark)
            applyMeasureUpdate(db, cv, id, type)
            true
        } != null

    /**
     * 分表定位顺序：类型路由表 → 其余分表 → 老混装表（迁移未完成时仍在）。
     * 命中即刷该表的 last_change/要素计数；未命中记日志（老库遗留或重复删除均可能）。
     */
    private fun measureTableOrder(db: SQLiteDatabase, type: String?): List<String> {
        val first = measureTableOf(type)
        val order = ArrayList<String>()
        order.add(first)
        order.addAll(MEASURE_TABLES.filter { it != first })
        if (tableExists(db, LEGACY_MEASURE_TABLE)) order.add(LEGACY_MEASURE_TABLE)
        return order.distinct().filter { tableExists(db, it) }
    }

    private fun applyMeasureUpdate(db: SQLiteDatabase, cv: ContentValues, id: Long, type: String?): Int {
        for (t in measureTableOrder(db, type)) {
            val n = runCatching { db.update(t, cv, "fid=?", arrayOf(id.toString())) }.getOrDefault(0)
            if (n > 0) {
                finish(db, t)
                return n
            }
        }
        Log.w(TAG, "量测更新未命中 fid=$id（分表与老表均无此行）")
        return 0
    }

    // ---- 拍照 ----

    fun insertPhoto(
        path: String, id: Long, name: String?, remark: String?, lat: Double, lon: Double, alt: Double,
        timestamp: Long, visible: Boolean, groupId: Long
    ): Boolean =
        withDb(path, "insertPhoto") { db ->
            val cv = ContentValues()
            cv.put("fid", id)
            putStr(cv, "name", name)
            putStr(cv, "remark", remark)
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
        path: String, id: Long, name: String?, groupId: Long?, visible: Boolean?, remark: String? = null
    ): Boolean =
        withDb(path) { db ->
            val cv = ContentValues()
            if (name != null) putStr(cv, "name", name)
            if (groupId != null) cv.put("group_id", groupId)
            if (visible != null) cv.put("visible", if (visible) 1 else 0)
            if (remark != null) putStr(cv, "remark", remark)
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

    // ---- 样地（按几何类型入 survey_point / survey_polygon）----

    fun upsertSurveyPlot(
        path: String, id: Long, name: String?, plotType: String?, attributesJson: String?,
        pts: List<DoubleArray>
    ): Boolean =
        withDb(path, "upsertSurveyPlot") { db ->
            val table = surveyTableOf(plotType)
            if (!tableExists(db, table)) {
                Log.e(TAG, "upsertSurveyPlot: 分表 $table 不存在，放弃写入 fid=$id")
                return@withDb false
            }
            val cv = ContentValues()
            cv.put("fid", id)
            putStr(cv, "name", name); putStr(cv, "plot_type", plotType)
            putStr(cv, "attributes", attributesJson)
            // 点样地只写点几何、面样地只写面几何；点数不足以构成面时留空几何，不写错型
            val geom = if (table == "survey_point") {
                pts.firstOrNull()?.let { GpkgGeom.point(it[0], it[1], zOfLocal(it)) }
            } else {
                if (pts.size >= 3) GpkgGeom.polygon(pts) else null
            }
            if (geom != null) cv.put("geom", geom)
            db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            finish(db, table, bboxOf(pts))
            // 老混装表若仍在（迁移未成功），同 fid 一并删除，避免 GIS 端同一份数据出现两次
            if (tableExists(db, LEGACY_SURVEY_TABLE))
                runCatching { db.delete(LEGACY_SURVEY_TABLE, "fid=?", arrayOf(id.toString())) }
            true
        } == true

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
