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
     * KML/KMZ 标注显示：true = 显示名称标注（点 Placemark 名称 + 面/线名称），
     * false = 隐藏标注（默认，地图更清爽）。同时作用于原生解析与 GDAL 回退两条路径；
     * 切换后经文档变更通知重载 KML/KMZ 图层（开关已纳入图层缓存键）。
     */
    var showKmlLabel: Boolean = false

    /**
     * 视图模式：false = 2D 平面墨卡托（默认），true = 3D 球体。
     * 原由主界面左上按键切换、随相机独立持久化；现改由设置页控制、随系统配置持久化，
     * 主界面 onResume 读回此值即时生效。
     */
    var viewMode3d: Boolean = false

    /**
     * 本地栅格（tif/img）瓦片磁盘缓存：true = 即时生成的瓦片落盘并优先读盘（默认，行为与既往一致）；
     * false = 完全不使用磁盘缓存（既不写盘也不读盘），每次可见瓦片都由 native GDAL 即时重生成
     * ——内存纹理 LRU 仍在，故本开关用于对比「磁盘读写是否为渲染速度的瓶颈」。仅作用于栅格层，
     * 在线图源（天地图/高德）缓存不受影响。切换后经文档变更通知就地换栅格层即时生效。
     */
    var rasterTileCacheEnabled: Boolean = true

    /**
     * 在线图源仅读离线缓存：true = 底图/注记瓦片层一律禁用联网拉取，只显示已落盘的缓存瓦片
     * （native 侧 URL 模板传空即「只读缓存」）；false = 正常联网取瓦（默认）。
     * 用于无网络野外作业：避免在线请求超时/404 导致已缓存区域被占位棋盘覆盖。
     * 开关值参入图源结构签名，切换后返回主界面经 recreate 重建瓦片层即时生效。
     */
    var tileOfflineOnly: Boolean = false

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
        o.put("showKmlLabel", showKmlLabel)
        o.put("viewMode3d", viewMode3d)
        o.put("rasterTileCacheEnabled", rasterTileCacheEnabled)
        o.put("tileOfflineOnly", tileOfflineOnly)
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
            if (o.has("showKmlLabel")) c.showKmlLabel = o.getBoolean("showKmlLabel")
            if (o.has("viewMode3d")) c.viewMode3d = o.getBoolean("viewMode3d")
            if (o.has("rasterTileCacheEnabled")) c.rasterTileCacheEnabled = o.getBoolean("rasterTileCacheEnabled")
            if (o.has("tileOfflineOnly")) c.tileOfflineOnly = o.getBoolean("tileOfflineOnly")
            return c
        }
    }
}
