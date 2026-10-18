package com.zys.mobilemap.project

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工程数据模型（0.4.0.0）
 *
 * 工程 = 空间数据容器（轨迹、测量、拍照、样地调查）
 * 与文档（Document）分离：文档管配置/图层，工程管采集数据。
 *
 * 存储格式：GeoPackage (.gpkg) —— OGC 标准，ArcGIS/QGIS 直接读取。
 * 单文件，无需服务器，支持矢量表 + 属性表 + 样式。
 */
class Project {

    // ---- 元数据 ----
    var name: String? = null                             // 工程名
    var description: String? = null                      // 描述
    var createTime: Long = System.currentTimeMillis()    // 创建时间戳
    var updateTime: Long = createTime                    // 最后更新时间戳
    var srs: String = "EPSG:4326"                        // 空间参考（默认 EPSG:4326）

    // ---- 空间数据集合 ----
    val tracks = mutableListOf<TrackRecord>()        // 轨迹
    val measures = mutableListOf<MeasureRecord>()    // 测量
    val photos = mutableListOf<PhotoRecord>()        // 拍照标识
    val surveyPlots = mutableListOf<SurveyPlot>()    // 样地调查

    // ---- 图层引用（与 Document 关联）----
    val layerRefs = mutableListOf<String>()          // 引用的图层绝对路径

    constructor()

    constructor(name: String?, description: String?) {
        this.name = name
        this.description = description
    }

    // ---- 序列化 ----

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("description", description)
        o.put("createTime", createTime)
        o.put("updateTime", updateTime)
        o.put("srs", srs)

        val tracksArr = JSONArray()
        for (t in tracks) tracksArr.put(t.toJson())
        o.put("tracks", tracksArr)

        val measuresArr = JSONArray()
        for (m in measures) measuresArr.put(m.toJson())
        o.put("measures", measuresArr)

        val photosArr = JSONArray()
        for (p in photos) photosArr.put(p.toJson())
        o.put("photos", photosArr)

        val plotsArr = JSONArray()
        for (s in surveyPlots) plotsArr.put(s.toJson())
        o.put("surveyPlots", plotsArr)

        val layersArr = JSONArray()
        for (l in layerRefs) layersArr.put(l)
        o.put("layerRefs", layersArr)

        return o
    }

    companion object {
        @JvmStatic
        fun fromJson(o: JSONObject): Project {
            val p = Project()
            p.name = o.optString("name", "未命名工程")
            p.description = o.optString("description", "")
            p.createTime = o.optLong("createTime", System.currentTimeMillis())
            p.updateTime = o.optLong("updateTime", System.currentTimeMillis())
            p.srs = o.optString("srs", "EPSG:4326")

            o.optJSONArray("tracks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    p.tracks.add(TrackRecord.fromJson(arr.getJSONObject(i)))
                }
            }

            o.optJSONArray("measures")?.let { arr ->
                for (i in 0 until arr.length()) {
                    p.measures.add(MeasureRecord.fromJson(arr.getJSONObject(i)))
                }
            }

            o.optJSONArray("photos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    p.photos.add(PhotoRecord.fromJson(arr.getJSONObject(i)))
                }
            }

            o.optJSONArray("surveyPlots")?.let { arr ->
                for (i in 0 until arr.length()) {
                    p.surveyPlots.add(SurveyPlot.fromJson(arr.getJSONObject(i)))
                }
            }

            o.optJSONArray("layerRefs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    p.layerRefs.add(arr.optString(i))
                }
            }

            return p
        }
    }

    // ---- 嵌套数据模型 ----

    /** 轨迹记录（线几何 + 属性） */
    class TrackRecord {
        var id: Long = 0
        var name: String? = null
        var date: String? = null
        val points = mutableListOf<DoubleArray>()    // [lon, lat, alt]
        var color: String? = null                    // "#RRGGBB" 或 null
        var hidden: Boolean = false

        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("id", id)
            o.put("name", name)
            o.put("date", date)
            o.put("color", color ?: JSONObject.NULL)
            o.put("hidden", hidden)
            val pts = JSONArray()
            for (p in points) {
                val pt = JSONArray()
                pt.put(p[0])
                pt.put(p[1])
                if (p.size > 2) pt.put(p[2])
                pts.put(pt)
            }
            o.put("points", pts)
            return o
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): TrackRecord {
                val t = TrackRecord()
                t.id = o.optLong("id")
                t.name = o.optString("name")
                t.date = o.optString("date")
                t.color = o.optString("color", null)
                t.hidden = o.optBoolean("hidden", false)
                o.optJSONArray("points")?.let { pts ->
                    for (i in 0 until pts.length()) {
                        val pt = pts.getJSONArray(i)
                        val lon = pt.getDouble(0)
                        val lat = pt.getDouble(1)
                        val alt = if (pt.length() > 2) pt.getDouble(2) else 0.0
                        t.points.add(doubleArrayOf(lon, lat, alt))
                    }
                }
                return t
            }
        }
    }

    /** 测量记录（点/线/面 + 属性） */
    class MeasureRecord {
        var id: Long = 0
        var name: String? = null
        var date: String? = null
        var type: String? = null      // "distance" | "area" | "angle" | "point" | "line" | "polygon"
        var result: String? = null    // 测量结果文本
        val points = mutableListOf<DoubleArray>()
        var color: String? = null
        var hidden: Boolean = false
        var closed: Boolean = false   // 面是否闭合

        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("id", id)
            o.put("name", name)
            o.put("date", date)
            o.put("type", type)
            o.put("result", result)
            o.put("color", color ?: JSONObject.NULL)
            o.put("hidden", hidden)
            o.put("closed", closed)
            val pts = JSONArray()
            for (p in points) {
                val pt = JSONArray()
                pt.put(p[0])
                pt.put(p[1])
                if (p.size > 2) pt.put(p[2])
                pts.put(pt)
            }
            o.put("points", pts)
            return o
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): MeasureRecord {
                val m = MeasureRecord()
                m.id = o.optLong("id")
                m.name = o.optString("name")
                m.date = o.optString("date")
                m.type = o.optString("type")
                m.result = o.optString("result")
                m.color = o.optString("color", null)
                m.hidden = o.optBoolean("hidden", false)
                m.closed = o.optBoolean("closed", false)
                o.optJSONArray("points")?.let { pts ->
                    for (i in 0 until pts.length()) {
                        val pt = pts.getJSONArray(i)
                        val lon = pt.getDouble(0)
                        val lat = pt.getDouble(1)
                        val alt = if (pt.length() > 2) pt.getDouble(2) else 0.0
                        m.points.add(doubleArrayOf(lon, lat, alt))
                    }
                }
                return m
            }
        }
    }

    /** 拍照标识（点 + 照片路径） */
    class PhotoRecord {
        var id: Long = 0
        var name: String? = null
        var lon: Double = 0.0
        var lat: Double = 0.0
        var alt: Double = 0.0
        var photoPath: String? = null   // 照片文件绝对路径
        var timestamp: Long = 0

        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("id", id)
            o.put("name", name)
            o.put("lon", lon)
            o.put("lat", lat)
            o.put("alt", alt)
            o.put("photoPath", photoPath)
            o.put("timestamp", timestamp)
            return o
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): PhotoRecord {
                val p = PhotoRecord()
                p.id = o.optLong("id")
                p.name = o.optString("name")
                p.lon = o.optDouble("lon")
                p.lat = o.optDouble("lat")
                p.alt = o.optDouble("alt", 0.0)
                p.photoPath = o.optString("photoPath", "")
                p.timestamp = o.optLong("timestamp")
                return p
            }
        }
    }

    /** 样地调查（多边形/点 + 调查表数据） */
    class SurveyPlot {
        var id: Long = 0
        var name: String? = null
        var plotType: String? = null    // "polygon" | "point"
        val points = mutableListOf<DoubleArray>()   // 样地边界或中心点
        var attributes: JSONObject? = null          // 调查表 JSON（树种、胸径、树高等）

        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("id", id)
            o.put("name", name)
            o.put("plotType", plotType)
            attributes?.let { o.put("attributes", it) }
            val pts = JSONArray()
            for (p in points) {
                val pt = JSONArray()
                pt.put(p[0])
                pt.put(p[1])
                if (p.size > 2) pt.put(p[2])
                pts.put(pt)
            }
            o.put("points", pts)
            return o
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): SurveyPlot {
                val s = SurveyPlot()
                s.id = o.optLong("id")
                s.name = o.optString("name")
                s.plotType = o.optString("plotType")
                s.attributes = o.optJSONObject("attributes")
                o.optJSONArray("points")?.let { pts ->
                    for (i in 0 until pts.length()) {
                        val pt = pts.getJSONArray(i)
                        val lon = pt.getDouble(0)
                        val lat = pt.getDouble(1)
                        val alt = if (pt.length() > 2) pt.getDouble(2) else 0.0
                        s.points.add(doubleArrayOf(lon, lat, alt))
                    }
                }
                return s
            }
        }
    }
}
