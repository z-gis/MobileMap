package com.zys.mobilemap.survey

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.AppLog
import java.io.File

/**
 * 样地调查数据仓库（SQLite）。
 * 数据库文件：/调查宝/sample/sample_survey.db
 * 表：survey_plots / survey_rows / survey_plot_photos
 */
object SampleSurveyStore {

    private const val TAG = "SampleSurveyStore"
    private const val DB_VERSION = 5
    private const val DB_NAME = "sample_survey.db"

    private const val TABLE_PLOTS = "survey_plots"
    private const val TABLE_ROWS = "survey_rows"
    private const val TABLE_PHOTOS = "survey_plot_photos"

    // ─── 数据库打开 ───────────────────────────────────────────────

    private fun getDbFile(context: Context): File =
        File(AppDirectories.getSampleDir(context), DB_NAME)

    private fun openDb(context: Context, writable: Boolean): SQLiteDatabase? {
        val file = getDbFile(context)
        return try {
            file.parentFile?.mkdirs()
            if (!writable && !file.exists()) return null
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            db.rawQuery("PRAGMA busy_timeout=3000", null).use { it.moveToFirst() }
            if (writable) ensureSchema(db)
            db
        } catch (e: Exception) {
            AppLog.e(TAG, "打开数据库失败", e)
            null
        }
    }

    private fun ensureSchema(db: SQLiteDatabase) {
        val version = db.version
        if (version == 0) {
            onCreate(db)
            db.version = DB_VERSION
            return
        }
        if (version < DB_VERSION) {
            onUpgrade(db, version)
            db.version = DB_VERSION
        }
    }

    private fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_PLOTS (
            _id INTEGER PRIMARY KEY AUTOINCREMENT,
            source_path TEXT NOT NULL,
            source_fid INTEGER NOT NULL,
            compartment_no TEXT,
            plot_no INTEGER NOT NULL,
            average_tree_height TEXT,
            canopy_density REAL,
            corner_coordinates TEXT,
            center_lat REAL,
            center_lon REAL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_survey_plots_source_plot ON $TABLE_PLOTS(source_path, source_fid, plot_no)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_ROWS (
            _id INTEGER PRIMARY KEY AUTOINCREMENT,
            plot_id INTEGER NOT NULL,
            sort_no INTEGER NOT NULL,
            tree_species TEXT,
            dbh TEXT,
            category TEXT,
            thinning_method TEXT,
            remark TEXT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_survey_rows_plot_id ON $TABLE_ROWS(plot_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_PHOTOS (
            _id INTEGER PRIMARY KEY AUTOINCREMENT,
            plot_id INTEGER NOT NULL,
            file_path TEXT NOT NULL,
            captured_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_survey_photos_plot_id ON $TABLE_PHOTOS(plot_id)")
    }

    private fun onUpgrade(db: SQLiteDatabase, oldVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_PLOTS ADD COLUMN center_lat REAL")
            db.execSQL("ALTER TABLE $TABLE_PLOTS ADD COLUMN center_lon REAL")
        }
        if (oldVersion < 3) db.execSQL("ALTER TABLE $TABLE_PLOTS ADD COLUMN average_tree_height TEXT")
        if (oldVersion < 4) db.execSQL("ALTER TABLE $TABLE_PLOTS ADD COLUMN canopy_density REAL")
        if (oldVersion < 5) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_PHOTOS (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                plot_id INTEGER NOT NULL,
                file_path TEXT NOT NULL,
                captured_at INTEGER NOT NULL)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_survey_photos_plot_id ON $TABLE_PHOTOS(plot_id)")
        }
    }

    // ─── 查询 ────────────────────────────────────────────────────

    /** 按源路径+FID 加载样地列表（含明细行） */
    fun loadPlots(context: Context, sourcePath: String, sourceFid: Long): List<SurveyPlot> {
        val db = openDb(context, false) ?: return emptyList()
        return try {
            val plots = mutableListOf<SurveyPlot>()
            db.query(TABLE_PLOTS, null,
                "source_path=? AND source_fid=?",
                arrayOf(sourcePath.trim(), sourceFid.toString()),
                null, null, "plot_no ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    val plot = readPlot(cursor)
                    plot.rows.addAll(loadRows(db, plot.id))
                    plots.add(plot)
                }
            }
            plots
        } catch (e: Exception) {
            AppLog.e(TAG, "loadPlots 失败", e)
            emptyList()
        } finally {
            db.close()
        }
    }

    /** 加载全部样地（地图覆盖用，不含明细行） */
    fun loadAllPlots(context: Context): List<SurveyPlot> {
        val db = openDb(context, false) ?: return emptyList()
        return try {
            val plots = mutableListOf<SurveyPlot>()
            db.query(TABLE_PLOTS, null, null, null, null, null, "updated_at DESC, plot_no ASC")
                .use { cursor ->
                    while (cursor.moveToNext()) plots.add(readPlot(cursor))
                }
            plots
        } catch (e: Exception) {
            AppLog.e(TAG, "loadAllPlots 失败", e)
            emptyList()
        } finally {
            db.close()
        }
    }

    /** 按 ID 加载单个样地（含明细行） */
    fun loadPlot(context: Context, plotId: Long): SurveyPlot? {
        if (plotId <= 0L) return null
        val db = openDb(context, false) ?: return null
        return try {
            db.query(TABLE_PLOTS, null, "_id=?", arrayOf(plotId.toString()),
                null, null, null, "1").use { cursor ->
                if (!cursor.moveToFirst()) return null
                val plot = readPlot(cursor)
                plot.rows.addAll(loadRows(db, plot.id))
                plot
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "loadPlot 失败", e)
            null
        } finally {
            db.close()
        }
    }

    /** 获取下一个样地编号 */
    fun nextPlotNo(context: Context, sourcePath: String, sourceFid: Long): Int {
        val db = openDb(context, true) ?: return 1
        return try {
            db.rawQuery(
                "SELECT COALESCE(MAX(plot_no), 0) FROM $TABLE_PLOTS WHERE source_path=? AND source_fid=?",
                arrayOf(sourcePath.trim(), sourceFid.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) 1 else cursor.getInt(0) + 1
            }
        } catch (e: Exception) {
            1
        } finally {
            db.close()
        }
    }

    // ─── 写入 ────────────────────────────────────────────────────

    /** 创建草稿样地（未持久化，id=0） */
    fun createDraftPlot(
        context: Context,
        sourcePath: String,
        sourceFid: Long,
        compartmentNo: String?,
        cornerCoordinates: String?,
        centerLat: Double = Double.NaN,
        centerLon: Double = Double.NaN
    ): SurveyPlot {
        val now = System.currentTimeMillis()
        return SurveyPlot(
            id = 0L,
            sourcePath = sourcePath.trim(),
            sourceFid = sourceFid,
            compartmentNo = compartmentNo?.trim(),
            plotNo = nextPlotNo(context, sourcePath, sourceFid),
            cornerCoordinates = cornerCoordinates?.trim(),
            centerLatitude = centerLat,
            centerLongitude = centerLon,
            createdAt = now,
            updatedAt = now
        )
    }

    /** 保存样地（新增或更新），含明细行全量替换。返回保存后的样地（含 id），失败返回 null */
    fun savePlot(context: Context, plot: SurveyPlot, rows: List<SurveyRow>): SurveyPlot? {
        val db = openDb(context, true) ?: return null
        val now = System.currentTimeMillis()
        var plotId = plot.id
        val isInsert = plotId <= 0L
        return try {
            db.beginTransaction()
            val values = ContentValues().apply {
                put("source_path", plot.sourcePath.trim())
                put("source_fid", plot.sourceFid)
                put("compartment_no", plot.compartmentNo?.trim())
                put("plot_no", plot.plotNo)
                put("average_tree_height", plot.averageTreeHeight?.trim())
                if (plot.canopyDensity.isNaN()) putNull("canopy_density")
                else put("canopy_density", plot.canopyDensity)
                put("corner_coordinates", plot.cornerCoordinates?.trim())
                put("center_lat", if (plot.centerLatitude.isNaN()) null else plot.centerLatitude)
                put("center_lon", if (plot.centerLongitude.isNaN()) null else plot.centerLongitude)
                put("updated_at", now)
            }
            if (isInsert) {
                values.put("created_at", if (plot.createdAt > 0L) plot.createdAt else now)
                plotId = db.insertOrThrow(TABLE_PLOTS, null, values)
            } else {
                db.update(TABLE_PLOTS, values, "_id=?", arrayOf(plotId.toString()))
            }
            // 明细行全量替换
            db.delete(TABLE_ROWS, "plot_id=?", arrayOf(plotId.toString()))
            for (row in rows) {
                val rv = ContentValues().apply {
                    put("plot_id", plotId)
                    put("sort_no", row.sortNo)
                    put("tree_species", row.treeSpecies?.trim())
                    put("dbh", row.dbh?.trim())
                    put("category", row.category?.trim())
                    put("thinning_method", row.thinningMethod?.trim())
                    put("remark", row.remark?.trim())
                }
                row.id = db.insertOrThrow(TABLE_ROWS, null, rv)
            }
            db.setTransactionSuccessful()
            plot.copy(id = plotId, updatedAt = now).apply {
                this.rows.clear()
                this.rows.addAll(rows)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "savePlot 失败", e)
            null
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    /** 删除样地（含明细行与照片记录） */
    fun deletePlot(context: Context, plotId: Long): Boolean {
        if (plotId <= 0L) return false
        val db = openDb(context, true) ?: return false
        return try {
            db.beginTransaction()
            db.delete(TABLE_ROWS, "plot_id=?", arrayOf(plotId.toString()))
            db.delete(TABLE_PHOTOS, "plot_id=?", arrayOf(plotId.toString()))
            db.delete(TABLE_PLOTS, "_id=?", arrayOf(plotId.toString()))
            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "deletePlot 失败", e)
            false
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    /** 添加样地照片记录 */
    fun addPhoto(context: Context, plotId: Long, filePath: String, capturedAt: Long = System.currentTimeMillis()): SurveyPhoto? {
        if (plotId <= 0L || filePath.isBlank()) return null
        val db = openDb(context, true) ?: return null
        return try {
            val values = ContentValues().apply {
                put("plot_id", plotId)
                put("file_path", filePath.trim())
                put("captured_at", capturedAt)
            }
            val id = db.insertOrThrow(TABLE_PHOTOS, null, values)
            SurveyPhoto(id, plotId, filePath.trim(), capturedAt)
        } catch (e: Exception) {
            AppLog.e(TAG, "addPhoto 失败", e)
            null
        } finally {
            db.close()
        }
    }

    /** 加载样地照片列表 */
    fun loadPhotos(context: Context, plotId: Long): List<SurveyPhoto> {
        if (plotId <= 0L) return emptyList()
        val db = openDb(context, false) ?: return emptyList()
        return try {
            val photos = mutableListOf<SurveyPhoto>()
            db.query(TABLE_PHOTOS, arrayOf("_id", "plot_id", "file_path", "captured_at"),
                "plot_id=?", arrayOf(plotId.toString()),
                null, null, "captured_at DESC, _id DESC").use { cursor ->
                while (cursor.moveToNext()) {
                    photos.add(SurveyPhoto(cursor.getLong(0), cursor.getLong(1),
                        cursor.getString(2), cursor.getLong(3)))
                }
            }
            photos
        } catch (e: Exception) {
            emptyList()
        } finally {
            db.close()
        }
    }

    // ─── 内部辅助 ─────────────────────────────────────────────────

    private fun readPlot(cursor: Cursor): SurveyPlot {
        val athIdx = cursor.getColumnIndex("average_tree_height")
        val cdIdx = cursor.getColumnIndex("canopy_density")
        val ccIdx = cursor.getColumnIndex("corner_coordinates")
        val latIdx = cursor.getColumnIndex("center_lat")
        val lonIdx = cursor.getColumnIndex("center_lon")
        return SurveyPlot(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
            sourcePath = cursor.getString(cursor.getColumnIndexOrThrow("source_path")) ?: "",
            sourceFid = cursor.getLong(cursor.getColumnIndexOrThrow("source_fid")),
            compartmentNo = cursor.getString(cursor.getColumnIndexOrThrow("compartment_no")),
            plotNo = cursor.getInt(cursor.getColumnIndexOrThrow("plot_no")),
            averageTreeHeight = if (athIdx >= 0) cursor.getString(athIdx) else null,
            canopyDensity = if (cdIdx >= 0 && !cursor.isNull(cdIdx)) cursor.getDouble(cdIdx) else Double.NaN,
            cornerCoordinates = if (ccIdx >= 0) cursor.getString(ccIdx) else null,
            centerLatitude = if (latIdx >= 0 && !cursor.isNull(latIdx)) cursor.getDouble(latIdx) else Double.NaN,
            centerLongitude = if (lonIdx >= 0 && !cursor.isNull(lonIdx)) cursor.getDouble(lonIdx) else Double.NaN,
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        )
    }

    private fun loadRows(db: SQLiteDatabase, plotId: Long): List<SurveyRow> {
        val rows = mutableListOf<SurveyRow>()
        db.query(TABLE_ROWS,
            arrayOf("_id", "sort_no", "tree_species", "dbh", "category", "thinning_method", "remark"),
            "plot_id=?", arrayOf(plotId.toString()),
            null, null, "sort_no ASC, _id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(SurveyRow(
                    id = cursor.getLong(0),
                    sortNo = cursor.getInt(1),
                    treeSpecies = cursor.getString(2),
                    dbh = cursor.getString(3),
                    category = cursor.getString(4),
                    thinningMethod = cursor.getString(5),
                    remark = cursor.getString(6)
                ))
            }
        }
        return rows
    }
}
