package com.zys.mobilemap.doc

import org.json.JSONException
import org.json.JSONObject

class SystemConfig {
    var showCenterCross: Boolean = true
    var photoAddWatermark: Boolean = true
    var backgroundLocation: Boolean = false
    var showMediaLayer: Boolean = true
    var showTrackLayer: Boolean = true
    var showMeasureLayer: Boolean = true

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("showCenterCross", showCenterCross)
        o.put("photoAddWatermark", photoAddWatermark)
        o.put("backgroundLocation", backgroundLocation)
        o.put("showMediaLayer", showMediaLayer)
        o.put("showTrackLayer", showTrackLayer)
        o.put("showMeasureLayer", showMeasureLayer)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SystemConfig {
            val c = SystemConfig()
            if (o.has("showCenterCross")) c.showCenterCross = o.getBoolean("showCenterCross")
            if (o.has("photoAddWatermark")) c.photoAddWatermark = o.getBoolean("photoAddWatermark")
            if (o.has("backgroundLocation")) c.backgroundLocation = o.getBoolean("backgroundLocation")
            if (o.has("showMediaLayer")) c.showMediaLayer = o.getBoolean("showMediaLayer")
            if (o.has("showTrackLayer")) c.showTrackLayer = o.getBoolean("showTrackLayer")
            if (o.has("showMeasureLayer")) c.showMeasureLayer = o.getBoolean("showMeasureLayer")
            return c
        }
    }
}
