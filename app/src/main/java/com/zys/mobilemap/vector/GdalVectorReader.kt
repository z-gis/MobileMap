package com.zys.mobilemap.vector

import android.util.Log
import org.json.JSONObject

/**
 * 单个矢量要素（坐标已统一为 WGS84 经纬度）：
 *  - POINT：x/y 为经度/纬度；
 *  - LINE：parts 为各线段，每条线段为摊平的 [lon0,lat0,lon1,lat1,…]；
 *  - POLYGON：outer 为外环，holes 为内环，均为摊平数组。
 * fid 为源数据要素标识（供点击拾取后读取属性/回写定位），layerName 为所属 GDAL 图层名。
 */
class VectorFeature(
    val geom: String,
    val x: Double = Double.NaN,
    val y: Double = Double.NaN,
    val parts: List<DoubleArray>? = null,
    val outer: DoubleArray? = null,
    val holes: List<DoubleArray>? = null,
    val fid: Long = -1,
    val layerName: String? = null,
    val fields: Map<String, String> = emptyMap()
)

/**
 * SQL 查询结果：成功时 [features] 为命中要素（可能为空列表）；
 * 失败时 [error] 为 GDAL/OGR 上报的语句错误文本（语法错/字段不存在等），供界面提示。
 */
class VectorQueryResult(val features: List<VectorFeature>?, val error: String?) {
    val isSuccess: Boolean get() = features != null
}

/**
 * GDAL/OGR 矢量读取：矢量数据的统一读取入口（shp/dwg/dxf/kml/kmz 均经此重投影到 WGS84），
 * 并提供 SQL 查询（[queryFeatures]）供属性筛选高亮。
 *
 * JNI 返回 JSON：{"features":[{"geom":"POINT|LINE|POLYGON",…,"fields":{"k":"v"}}]}，
 * 查询失败额外返回 {"error":"…"}；要素数上限在 C++ 层控制，防大文件 OOM。
 */
object GdalVectorReader {

    private const val TAG = "GdalVectorReader"

    // JNI 符号为 Java_com_zys_mobilemap_vector_GdalVectorReader_nativeReadVectorFeatures
    private external fun nativeReadVectorFeatures(
        path: String, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double
    ): String?

    // JNI 符号为 Java_com_zys_mobilemap_vector_GdalVectorReader_nativeQueryVectorFeatures
    private external fun nativeQueryVectorFeatures(path: String, sql: String): String?

    /**
     * 读取矢量要素列表，打开失败（如不支持的格式）返回 null。
     * extent 为 WGS84 四至 [minLon, minLat, maxLon, maxLat]：非空时仅读取范围内要素（空间过滤）；
     * null 时读取全部。
     */
    fun readFeatures(path: String, extent: DoubleArray? = null): List<VectorFeature>? {
        val json = try {
            if (extent != null) {
                nativeReadVectorFeatures(path, extent[0], extent[1], extent[2], extent[3])
            } else {
                nativeReadVectorFeatures(path, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "nativeReadVectorFeatures 未加载", e)
            return null
        }
        if (json == null) return null
        return parseFeatures(json, path)
    }

    /**
     * 对矢量数据执行 SQL 查询，返回命中要素（坐标已重投影到 WGS84）。
     * 语句错误/文件无法打开时通过 [VectorQueryResult.error] 回传提示文本，不抛异常。
     */
    fun queryFeatures(path: String, sql: String): VectorQueryResult {
        val json = try {
            nativeQueryVectorFeatures(path, sql)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "nativeQueryVectorFeatures 未加载", e)
            return VectorQueryResult(null, "原生库未加载，无法执行查询")
        }
        if (json == null) return VectorQueryResult(null, "无法打开数据文件")
        return try {
            val obj = JSONObject(json)
            val error = obj.optString("error", "")
            if (error.isNotBlank()) return VectorQueryResult(null, error)
            VectorQueryResult(parseFeatures(json, path) ?: emptyList(), null)
        } catch (e: Exception) {
            Log.e(TAG, "解析查询结果失败: $path", e)
            VectorQueryResult(null, "查询结果解析失败")
        }
    }

    /** 解析要素 JSON（读取与查询共用），格式异常返回 null */
    private fun parseFeatures(json: String, path: String): List<VectorFeature>? {
        return try {
            val arr = JSONObject(json).getJSONArray("features")
            val list = ArrayList<VectorFeature>(arr.length())
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val fields = mutableMapOf<String, String>()
                val fo = f.optJSONObject("fields")
                if (fo != null) {
                    val keys = fo.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        fields[k] = fo.optString(k)
                    }
                }
                val fid = f.optLong("fid", -1)
                val layerName = if (f.has("layer") && !f.isNull("layer")) f.getString("layer") else null
                when (f.getString("geom")) {
                    "POINT" -> list.add(
                        VectorFeature("POINT", x = f.getDouble("x"), y = f.getDouble("y"),
                            fid = fid, layerName = layerName, fields = fields)
                    )
                    "LINE" -> list.add(
                        VectorFeature("LINE", parts = parseParts(f.getJSONArray("parts")),
                            fid = fid, layerName = layerName, fields = fields)
                    )
                    "POLYGON" -> {
                        val holes = ArrayList<DoubleArray>()
                        val ha = f.optJSONArray("holes")
                        if (ha != null) {
                            for (h in 0 until ha.length()) {
                                holes.add(parseRing(ha.getJSONArray(h)))
                            }
                        }
                        list.add(
                            VectorFeature(
                                "POLYGON",
                                outer = parseRing(f.getJSONArray("outer")),
                                holes = holes,
                                fid = fid,
                                layerName = layerName,
                                fields = fields
                            )
                        )
                    }
                }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "解析矢量要素 JSON 失败: $path", e)
            null
        }
    }

    /** 解析线段数组：[[lon,lat],…] -> [lon,lat,lon,lat,…] */
    private fun parseParts(arr: org.json.JSONArray): List<DoubleArray> {
        val parts = ArrayList<DoubleArray>(arr.length())
        for (i in 0 until arr.length()) {
            parts.add(parseRing(arr.getJSONArray(i)))
        }
        return parts
    }

    private fun parseRing(arr: org.json.JSONArray): DoubleArray {
        val flat = DoubleArray(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val pt = arr.getJSONArray(i)
            flat[i * 2] = pt.getDouble(0)
            flat[i * 2 + 1] = pt.getDouble(1)
        }
        return flat
    }
}
