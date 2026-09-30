package com.zys.mobilemap.vector

import android.util.JsonReader
import android.util.Log
import com.zys.globecore.NativeVector
import org.json.JSONObject
import java.io.StringReader

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
 * native 调用经 globecore [NativeVector] 门面（符号实现见其 cpp/bridge/vector_io.cpp），本类只做 JSON 解析与业务包装。
 * JNI 返回 JSON：{"features":[{"geom":"POINT|LINE|POLYGON",…,"fields":{"k":"v"}}]}，
 * 查询失败额外返回 {"error":"…"}；要素数上限在 C++ 层控制，防大文件 OOM。
 */
/** CAD 数据无坐标系（无同名 .prj 且坐标不含投影带号）：坐标无法定位，
 * 加载/查询应终止并提示用户，不得按 WGS84 原样错位显示。 */
class NoCoordinateSystemException(message: String) : IllegalStateException(message)

object GdalVectorReader {

    private const val TAG = "GdalVectorReader"

    /**
     * 快速统计矢量数据要素总数（遍历全部子图层求和），供上层判定小数据集是否直接全量渲染。
     * 打开失败或原生库未加载返回 -1。
     */
    fun countFeatures(path: String): Int {
        return try {
            NativeVector.countVectorFeatures(path)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "nativeCountVectorFeatures 未加载", e)
            -1
        } catch (e: Exception) {
            Log.e(TAG, "统计要素数失败: $path", e)
            -1
        }
    }

    /**
     * 按 FID 就地写回属性字段到源文件（GDAL_OF_UPDATE + SetField + SetFeature）。
     * 仅修改现有字段值；dwg 等只读驱动、无写权限、字段名不存在时返回 false。
     * attrs 为空直接返回 true（无需写入）。
     */
    fun updateAttributes(path: String, featureId: Long, attrs: Map<String, String>): Boolean {
        if (attrs.isEmpty()) return true
        if (featureId < 0) return false
        return try {
            val keys = attrs.keys.toTypedArray()
            val values = attrs.values.toTypedArray()
            NativeVector.updateFeatureAttributes(path, featureId, keys, values)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "nativeUpdateFeatureAttributes 未加载", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "属性写回失败: $path fid=$featureId", e)
            false
        }
    }

    /**
     * 读取矢量要素列表，打开失败（如不支持的格式）返回 null。
     * extent 为 WGS84 四至 [minLon, minLat, maxLon, maxLat]：非空时仅读取范围内要素（空间过滤）；
     * null 时读取全部。
     *
     * [includeAllFields]=false 启用延迟属性读取：C++ 仅保留 [labelField] 匹配的字段（供标注渲染），
     * 其余字段留到点击时经 [getFeatureAttributes] 按 FID 单独回取。仅对 shp/gpkg 等索引格式启用，
     * kml/kmz/dwg/dxf 保持全字段（要素数少 / description 依赖 / CAD 属性表窄，改造收益低）。
     */
    fun readFeatures(path: String, extent: DoubleArray? = null,
                     includeAllFields: Boolean = true,
                     labelField: String? = null,
                     simplifyGeometry: Boolean = false,
                     simplifyTolerance: Double = 0.0): List<VectorFeature>? {
        val json = try {
            if (extent != null) {
                NativeVector.readVectorFeatures(path, extent[0], extent[1], extent[2], extent[3],
                    includeAllFields, labelField, simplifyGeometry, simplifyTolerance)
            } else {
                NativeVector.readVectorFeatures(path, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    includeAllFields, labelField, simplifyGeometry, simplifyTolerance)
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "nativeReadVectorFeatures 未加载", e)
            return null
        }
        if (json == null) return null
        // CAD 无坐标系时 C++ 回传 {"error":"…"}：抛异常终止加载，由界面提示原因，不按 WGS84 错位显示
        val err = runCatching { JSONObject(json).optString("error", "") }.getOrDefault("")
        if (err.isNotBlank()) throw NoCoordinateSystemException(err)
        return parseFeatures(json, path)
    }

    /**
     * 按 FID 单要素属性回取（延迟属性读取模式配套）：加载阶段仅传 labelField 白名单，
     * 点击时经此二次拉取全字段。shp/gpkg 索引 seek ~10-30ms。
     * 打开失败 / fid 未命中 / 原生库未加载均返回 null（由调用方回退到已有缓存）。
     */
    fun getFeatureAttributes(path: String, featureId: Long): Map<String, String>? {
        if (featureId < 0) return null
        val json = try {
            NativeVector.getFeatureAttributes(path, featureId)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "nativeGetFeatureAttributes 未加载", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "属性回取失败: $path fid=$featureId", e)
            return null
        }
        if (json == null) return null
        return try {
            val fo = JSONObject(json).optJSONObject("fields") ?: return emptyMap()
            val map = LinkedHashMap<String, String>()
            val keys = fo.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = fo.optString(k)
            }
            map
        } catch (e: Exception) {
            Log.e(TAG, "解析属性 JSON 失败: $path fid=$featureId", e)
            null
        }
    }

    /**
     * 对矢量数据执行 SQL 查询，返回命中要素（坐标已重投影到 WGS84）。
     * 语句错误/文件无法打开时通过 [VectorQueryResult.error] 回传提示文本，不抛异常。
     */
    fun queryFeatures(path: String, sql: String): VectorQueryResult {
        val json = try {
            NativeVector.queryVectorFeatures(path, sql)
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
        // Phase 4：流式解析开关（默认 false = 沿用 JSONObject 树形解析）
        if (VectorReloadConfig.USE_STREAMING_JSON) {
            val streamed = parseFeaturesStreaming(json, path)
            if (streamed != null) return streamed
            Log.w(TAG, "流式解析失败回退树形解析: $path")
        }
        return parseFeaturesTree(json, path)
    }

    /** 树形解析（org.json.JSONObject）：当前行为，大 JSON 下 GC 压力大 */
    private fun parseFeaturesTree(json: String, path: String): List<VectorFeature>? {
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

    // ═════════════════ Phase 4：流式解析（android.util.JsonReader）═════════════════

    /**
     * 流式解析：逐 token 读取，避免构造完整 JSONObject/JSONArray 树，大幅降低 GC 压力。
     * 仅处理已知结构 {"features":[{...}]}，遇未知字段 skipValue（容忍 C++ 侧新增字段）。
     * 解析异常返回 null，由调用方回退到树形解析。
     */
    private fun parseFeaturesStreaming(json: String, path: String): List<VectorFeature>? {
        return try {
            val list = ArrayList<VectorFeature>()
            JsonReader(StringReader(json)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "features" -> {
                            r.beginArray()
                            while (r.hasNext()) list.add(readFeatureStreaming(r))
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "流式解析矢量要素 JSON 失败: $path", e)
            null
        }
    }

    /** 读取单个要素对象，支持 POINT / LINE / POLYGON；未知几何类型抛异常触发回退 */
    private fun readFeatureStreaming(r: JsonReader): VectorFeature {
        var geom: String? = null
        var x = Double.NaN
        var y = Double.NaN
        var fid = -1L
        var layerName: String? = null
        var parts: List<DoubleArray>? = null
        var outer: DoubleArray? = null
        var holes: List<DoubleArray>? = null
        val fields = LinkedHashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "geom" -> geom = r.nextString()
                "x" -> x = r.nextDouble()
                "y" -> y = r.nextDouble()
                "fid" -> fid = r.nextLong()
                "layer" -> layerName = if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); null } else r.nextString()
                "fields" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        val k = r.nextName()
                        val v = if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); "" } else r.nextString()
                        fields[k] = v
                    }
                    r.endObject()
                }
                "parts" -> parts = readRingListStreaming(r)
                "outer" -> outer = readRingStreaming(r)
                "holes" -> holes = readRingListStreaming(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        return when (geom) {
            "POINT" -> VectorFeature("POINT", x = x, y = y, fid = fid, layerName = layerName, fields = fields)
            "LINE" -> VectorFeature("LINE", parts = parts, fid = fid, layerName = layerName, fields = fields)
            "POLYGON" -> VectorFeature("POLYGON", outer = outer, holes = holes, fid = fid, layerName = layerName, fields = fields)
            else -> throw IllegalStateException("未知几何类型: $geom")
        }
    }

    /** 读取环列表：[[[lon,lat],...], ...] -> List<DoubleArray>（摊平） */
    private fun readRingListStreaming(r: JsonReader): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        r.beginArray()
        while (r.hasNext()) out.add(readRingStreaming(r))
        r.endArray()
        return out
    }

    /** 读取单环：[[lon,lat],...] -> DoubleArray(lon,lat,lon,lat,...) */
    private fun readRingStreaming(r: JsonReader): DoubleArray {
        val buf = ArrayList<Double>()
        r.beginArray()
        while (r.hasNext()) {
            r.beginArray()
            // 每点固定 2 个坐标（lon, lat）
            buf.add(r.nextDouble())
            buf.add(r.nextDouble())
            r.endArray()
        }
        r.endArray()
        val arr = DoubleArray(buf.size)
        for (i in buf.indices) arr[i] = buf[i]
        return arr
    }
}
