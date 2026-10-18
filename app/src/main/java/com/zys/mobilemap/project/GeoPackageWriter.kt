package com.zys.mobilemap.project

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * GeoPackage 读写器（0.4.0.0）
 *
 * 将 Project 数据模型与 GeoPackage (.gpkg) 文件互转。
 * GeoPackage 是 OGC 标准，基于 SQLite，ArcGIS/QGIS 直接支持。
 *
 * 表结构：
 *   - tracks:      id, name, date, color, hidden, geom (LineString)
 *   - measures:    id, name, date, type, result, color, hidden, closed, geom (Point/LineString/Polygon)
 *   - photos:      id, name, lon, lat, alt, photo_path, timestamp, geom (Point)
 *   - survey_plots: id, name, plot_type, geom (Point/Polygon), attributes (JSON)
 *   - project_meta: key, value（工程元数据）
 *   - layer_refs:  layer_path（引用图层清单）
 */
class GeoPackageWriter(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * 将 Project 写入 GeoPackage 文件。
     *
     * 实现方式：通过原生 GDAL/OGR 驱动（globecore 已内嵌）。
     * 调用 JNI 方法：nativeWriteGeoPackage(filePath, projectJson)
     *
     * @param project 工程数据
     * @param filePath 输出文件路径（.gpkg）
     * @return 成功返回 true
     */
    fun write(project: Project, filePath: String): Boolean {
        return try {
            val json = project.toJson().toString()
            val ok = nativeWriteGeoPackage(filePath, json)
            Log.i(TAG, "write: $filePath -> $ok")
            ok
        } catch (e: JSONException) {
            Log.e(TAG, "write: JSON 序列化失败", e)
            false
        }
    }

    /**
     * 从 GeoPackage 文件读取 Project。
     *
     * @param filePath 输入文件路径（.gpkg）
     * @return 成功返回 Project，失败返回 null
     */
    fun read(filePath: String): Project? {
        val json = nativeReadGeoPackage(filePath)
        if (json.isNullOrEmpty()) {
            Log.e(TAG, "read: $filePath 读取失败或为空")
            return null
        }
        return try {
            Project.fromJson(JSONObject(json))
        } catch (e: JSONException) {
            Log.e(TAG, "read: JSON 解析失败", e)
            null
        }
    }

    // ---- JNI 接口 ----

    /**
     * 原生写入 GeoPackage。
     * 由 globecore C++ 层实现，使用 OGR 驱动创建 SQLite/GeoPackage。
     */
    private external fun nativeWriteGeoPackage(filePath: String, projectJson: String): Boolean

    /**
     * 原生读取 GeoPackage。
     * 由 globecore C++ 层实现，使用 OGR 驱动解析 SQLite/GeoPackage。
     */
    private external fun nativeReadGeoPackage(filePath: String): String?

    companion object {
        private const val TAG = "GeoPackageWriter"

        init {
            System.loadLibrary("globecore")
        }
    }
}
