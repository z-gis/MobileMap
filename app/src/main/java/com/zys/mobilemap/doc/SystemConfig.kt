package com.zys.mobilemap.doc

import org.json.JSONObject

class SystemConfig {
    var showCenterCross: Boolean = true
    var photoAddWatermark: Boolean = true
    var backgroundLocation: Boolean = false
    var showMediaLayer: Boolean = true
    var showPhotoName: Boolean = false
    var showTrackLayer: Boolean = true
    var showMeasureLayer: Boolean = true

    /**
     * 定位/中心坐标显示格式：false = 十进制度（经纬度），
     * true = 米（CGCS2000 3 度带高斯-克吕格投影坐标系，带号按经度自动选取）。
     */
    var coordDisplayMeter: Boolean = false

    /**
     * shp 读取引擎：true = GDAL/OGR（默认，任意坐标系经重投影，与 dwg/dxf 同一条路径），
     * false = WorldWind 原生字节入口（地理坐标/UTM/无 prj；其余投影仍自动回退 GDAL）。
     * 两条路径并存不删除，切换后 shp 图层按新引擎重载。
     */
    var shpUseGdal: Boolean = true

    /**
     * KML/KMZ 标注显示：true = 显示名称标注（点 Placemark 名称 + 面/线名称），
     * false = 隐藏标注（默认，地图更清爽）。同时作用于原生解析与 GDAL 回退两条路径；
     * 切换后经文档变更通知重载 KML/KMZ 图层（开关已纳入图层缓存键）。
     */
    var showKmlLabel: Boolean = false

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("showCenterCross", showCenterCross)
        o.put("photoAddWatermark", photoAddWatermark)
        o.put("backgroundLocation", backgroundLocation)
        o.put("showMediaLayer", showMediaLayer)
        o.put("showPhotoName", showPhotoName)
        o.put("showTrackLayer", showTrackLayer)
        o.put("showMeasureLayer", showMeasureLayer)
        o.put("coordDisplayMeter", coordDisplayMeter)
        o.put("shpUseGdal", shpUseGdal)
        o.put("showKmlLabel", showKmlLabel)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SystemConfig {
            val c = SystemConfig()
            if (o.has("showCenterCross")) c.showCenterCross = o.getBoolean("showCenterCross")
            if (o.has("photoAddWatermark")) c.photoAddWatermark = o.getBoolean("photoAddWatermark")
            if (o.has("backgroundLocation")) c.backgroundLocation = o.getBoolean("backgroundLocation")
            if (o.has("showMediaLayer")) c.showMediaLayer = o.getBoolean("showMediaLayer")
            if (o.has("showPhotoName")) c.showPhotoName = o.getBoolean("showPhotoName")
            if (o.has("showTrackLayer")) c.showTrackLayer = o.getBoolean("showTrackLayer")
            if (o.has("showMeasureLayer")) c.showMeasureLayer = o.getBoolean("showMeasureLayer")
            if (o.has("coordDisplayMeter")) c.coordDisplayMeter = o.getBoolean("coordDisplayMeter")
            if (o.has("shpUseGdal")) c.shpUseGdal = o.getBoolean("shpUseGdal")
            if (o.has("showKmlLabel")) c.showKmlLabel = o.getBoolean("showKmlLabel")
            return c
        }
    }
}
