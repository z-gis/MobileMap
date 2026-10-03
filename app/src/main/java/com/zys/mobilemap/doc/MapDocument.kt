package com.zys.mobilemap.doc

import org.json.JSONArray
import org.json.JSONObject

/**
 * 地图文档模型：`document.json` 的完整内存表示。
 *
 * 含图层清单（矢量/栅格）、底图数据源、系统配置，以及上次退出时的相机姿态。
 * 反序列化为严格模式——结构损坏时直接抛出异常，由 DocumentManager 决定重建默认文档，
 * 宁可整体重建也不静默丢弃单个图层。
 */
class MapDocument {

    var version: Int = CURRENT_VERSION
    var vectorLayers: MutableList<LayerInfo> = mutableListOf()
    var rasterLayers: MutableList<LayerInfo> = mutableListOf()
    var mapSources: MutableList<MapSource> = mutableListOf()
    var systemConfig: SystemConfig = SystemConfig()

    // 相机完整姿态；NaN 表示“未记录”，序列化时会被跳过
    var camLatitude: Double = Double.NaN
    var camLongitude: Double = Double.NaN
    var camAltitude: Double = Double.NaN
    var camHeading: Double = Double.NaN
    var camTilt: Double = Double.NaN
    var camRoll: Double = Double.NaN
    var camAltitudeMode: Int = -1

    fun toJson(): JSONObject = JSONObject().also { root ->
        root.put("version", version)
        root.put("systemConfig", systemConfig.toJson())

        // 姿态字段仅在已记录时写入，避免 document.json 里堆积无意义的 NaN
        root.putIfRecorded("camLatitude", camLatitude)
        root.putIfRecorded("camLongitude", camLongitude)
        root.putIfRecorded("camAltitude", camAltitude)
        root.putIfRecorded("camHeading", camHeading)
        root.putIfRecorded("camTilt", camTilt)
        root.putIfRecorded("camRoll", camRoll)
        if (camAltitudeMode >= 0) root.put("camAltitudeMode", camAltitudeMode)

        root.put("mapSources", JSONArray(mapSources.map { it.toJson() }))
        root.put("rasterLayers", JSONArray(rasterLayers.map { it.toJson() }))
        root.put("vectorLayers", JSONArray(vectorLayers.map { it.toJson() }))
    }

    /** 姿态字段仅在非 NaN（即确实记录过）时写入 */
    private fun JSONObject.putIfRecorded(key: String, value: Double) {
        if (!value.isNaN()) put(key, value)
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun fromJson(json: JSONObject): MapDocument = MapDocument().also { doc ->
            doc.version = json.optInt("version", CURRENT_VERSION)

            if (json.has("systemConfig")) {
                doc.systemConfig = SystemConfig.fromJson(json.getJSONObject("systemConfig"))
            }

            doc.mapSources.addAll(parseArray(json, "mapSources") { MapSource.fromJson(it) })
            doc.rasterLayers.addAll(parseArray(json, "rasterLayers") { LayerInfo.fromJson(it) })
            doc.vectorLayers.addAll(parseArray(json, "vectorLayers") { LayerInfo.fromJson(it) })

            // 姿态字段缺失或非数值时，optXxx 返回默认值（即当前值），效果与 has 守卫一致
            doc.camLatitude = json.optDouble("camLatitude", doc.camLatitude)
            doc.camLongitude = json.optDouble("camLongitude", doc.camLongitude)
            doc.camAltitude = json.optDouble("camAltitude", doc.camAltitude)
            doc.camHeading = json.optDouble("camHeading", doc.camHeading)
            doc.camTilt = json.optDouble("camTilt", doc.camTilt)
            doc.camRoll = json.optDouble("camRoll", doc.camRoll)
            doc.camAltitudeMode = json.optInt("camAltitudeMode", doc.camAltitudeMode)
        }

        /**
         * 读取对象数组字段并逐项解析。
         *
         * 刻意保持严格语义：字段存在但结构损坏时直接抛异常，交由 DocumentManager 整体重建。
         * 若改用 optJSONArray / optJSONObject 宽松跳过，坏掉的图层会被静默丢弃，
         * 用户看到的将是“图层莫名少了一个”，比整体重建更难排查。
         */
        private fun <T> parseArray(json: JSONObject, key: String, parse: (JSONObject) -> T): List<T> {
            if (!json.has(key)) return emptyList()
            val arr = json.getJSONArray(key)
            return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
        }
    }
}
