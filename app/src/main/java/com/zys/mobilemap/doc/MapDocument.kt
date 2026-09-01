package com.zys.mobilemap.doc

import org.json.JSONArray
import org.json.JSONObject

class MapDocument {

    var version: Int = CURRENT_VERSION
    var vectorLayers: MutableList<LayerInfo> = mutableListOf()
    var rasterLayers: MutableList<LayerInfo> = mutableListOf()
    var mapSources: MutableList<MapSource> = mutableListOf()
    var systemConfig: SystemConfig = SystemConfig()

    // camera full state
    var camLatitude: Double = Double.NaN
    var camLongitude: Double = Double.NaN
    var camAltitude: Double = Double.NaN
    var camHeading: Double = Double.NaN
    var camTilt: Double = Double.NaN
    var camRoll: Double = Double.NaN
    var camAltitudeMode: Int = -1

    fun toJson(): JSONObject {
        val root = JSONObject()

        root.put("version", version)
        root.put("systemConfig", systemConfig.toJson())
        if (!camLatitude.isNaN()) root.put("camLatitude", camLatitude)
        if (!camLongitude.isNaN()) root.put("camLongitude", camLongitude)
        if (!camAltitude.isNaN()) root.put("camAltitude", camAltitude)
        if (!camHeading.isNaN()) root.put("camHeading", camHeading)
        if (!camTilt.isNaN()) root.put("camTilt", camTilt)
        if (!camRoll.isNaN()) root.put("camRoll", camRoll)
        if (camAltitudeMode >= 0) root.put("camAltitudeMode", camAltitudeMode)

        val ms = JSONArray()
        for (m in mapSources) {
            ms.put(m.toJson())
        }
        root.put("mapSources", ms)

        val rs = JSONArray()
        for (r in rasterLayers) {
            rs.put(r.toJson())
        }
        root.put("rasterLayers", rs)

        val vs = JSONArray()
        for (v in vectorLayers) {
            vs.put(v.toJson())
        }
        root.put("vectorLayers", vs)

        return root
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun fromJson(json: JSONObject): MapDocument {
            val doc = MapDocument()
            doc.version = json.optInt("version", 1)

            if (json.has("systemConfig")) {
                doc.systemConfig = SystemConfig.fromJson(json.getJSONObject("systemConfig"))
            }

            if (json.has("mapSources")) {
                val ms = json.getJSONArray("mapSources")
                for (i in 0 until ms.length()) {
                    doc.mapSources.add(MapSource.fromJson(ms.getJSONObject(i)))
                }
            }

            if (json.has("rasterLayers")) {
                val rs = json.getJSONArray("rasterLayers")
                for (i in 0 until rs.length()) {
                    doc.rasterLayers.add(LayerInfo.fromJson(rs.getJSONObject(i)))
                }
            }

            if (json.has("vectorLayers")) {
                val vs = json.getJSONArray("vectorLayers")
                for (i in 0 until vs.length()) {
                    doc.vectorLayers.add(LayerInfo.fromJson(vs.getJSONObject(i)))
                }
            }

            if (json.has("camLatitude")) doc.camLatitude = json.optDouble("camLatitude", doc.camLatitude)

            if (json.has("camLongitude")) doc.camLongitude = json.optDouble("camLongitude", doc.camLongitude)
            if (json.has("camAltitude")) doc.camAltitude = json.optDouble("camAltitude", doc.camAltitude)
            if (json.has("camHeading")) doc.camHeading = json.optDouble("camHeading", doc.camHeading)
            if (json.has("camTilt")) doc.camTilt = json.optDouble("camTilt", doc.camTilt)
            if (json.has("camRoll")) doc.camRoll = json.optDouble("camRoll", doc.camRoll)
            if (json.has("camAltitudeMode")) doc.camAltitudeMode = json.optInt("camAltitudeMode", doc.camAltitudeMode)

            return doc
        }
    }
}
