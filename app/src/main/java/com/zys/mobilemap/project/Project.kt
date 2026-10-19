package com.zys.mobilemap.project

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工程数据模型（0.4.0.0 单库版）
 *
 * 工程 = 采集数据唯一本体（轨迹、测量、拍照、样地调查），存储为 GeoPackage (.gpkg)。
 * 与文档（Document）分离：文档管配置/图层，工程管采集数据。
 *
 * 坐标序约定：内存与 UI 层统一 [lat, lon, alt]（与旧工作库一致）；
 * 仅 [toJson]/[fromJson] 在与 native gpkg 读写边界换算为 [lon, lat, alt]（GeoPackage 轴序）。
 *
 * 序列化 JSON 键名与 native geopackage_io.cpp 严格对齐，改动须两侧同步。
 */
class Project {

    // ---- 元数据 ----
    var name: String? = null                             // 工程名
    var description: String? = null                      // 描述
    var createTime: Long = System.currentTimeMillis()    // 创建时间戳
    var updateTime: Long = createTime                    // 最后更新时间戳
    var srs: String = "EPSG:4326"                        // 空间参考（默认 EPSG:4326）

    // ---- 空间数据集合 ----
    val tracks = mutableListOf<TrackRecord>()            // 轨迹
    val measures = mutableListOf<MeasureRecord>()        // 测量
    val photos = mutableListOf<PhotoRecord>()            // 拍照标识
    val mediaGroups = mutableListOf<MediaGroupRecord>()  // 拍照分组
    val surveyPlots = mutableListOf<SurveyPlot>()        // 样地调查

    // ---- 图层引用（与 Document 关联）----
    val layerRefs = mutableListOf<String>()              // 引用的图层绝对路径

    constructor()

    constructor(name: String?, description: String?) {
        this.name = name
        this.description = description
    }

    // ---- 序列化（JSON 轴序为 [lon,lat,alt]，供 native 写 gpkg）----

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

        val groupsArr = JSONArray()
        for (g in mediaGroups) groupsArr.put(g.toJson())
        o.put("mediaGroups", groupsArr)

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

            o.optJSONArray("mediaGroups")?.let { arr ->
                for (i in 0 until arr.length()) {
                    p.mediaGroups.add(MediaGroupRecord.fromJson(arr.getJSONObject(i)))
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

        /** 内存点串 [lat,lon,alt] → JSON 数组 [[lon,lat,alt],...]（native 轴序） */
        internal fun pointsToJson(points: List<DoubleArray>): JSONArray {
            val pts = JSONArray()
            for (p in points) {
                val pt = JSONArray()
                pt.put(p[1])  // lon
                pt.put(p[0])  // lat
                pt.put(if (p.size > 2) p[2] else 0.0)
                pts.put(pt)
            }
            return pts
        }

        /** JSON 数组 [[lon,lat,alt],...]（native 轴序）→ 内存点串 [lat,lon,alt] */
        internal fun pointsFromJson(arr: JSONArray?): MutableList<DoubleArray> {
            val points = mutableListOf<DoubleArray>()
            if (arr == null) return points
            for (i in 0 until arr.length()) {
                val pt = arr.optJSONArray(i) ?: continue
                if (pt.length() < 2) continue
                val lon = pt.getDouble(0)
                val lat = pt.getDouble(1)
                val alt = if (pt.length() > 2) pt.getDouble(2) else 0.0
                points.add(doubleArrayOf(lat, lon, alt))
            }
            return points
        }
    }

    // ---- 嵌套数据模型 ----

    /** 轨迹记录（线几何 + 属性）；[points] 内存序 [lat,lon,alt] */
    class TrackRecord {
        var id: Long = 0
        var name: String? = null
        var date: String? = null                     // "yyyy-MM-dd HH:mm:ss"
        val points = mutableListOf<DoubleArray>()
        var color: String? = null                    // "#RRGGBB" 或 null
        var hidden: Boolean = false                  // 隐藏后地图不渲染
        var lineWidth: Int = DEFAULT_LINE_WIDTH

        /** 逐条可见性（与旧工作库 visible 语义对齐，hidden 取反） */
        var visible: Boolean
            get() = !hidden
            set(value) { hidden = !value }

        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("id", id)
            o.put("name", name)
            o.put("date", date)
            o.put("color", color ?: JSONObject.NULL)
            o.put("hidden", hidden)
            o.put("lineWidth", lineWidth)
            o.put("points", pointsToJson(points))
            return o
        }

        companion object {
            const val DEFAULT_COLOR_HEX = "#33CCFF"
            const val DEFAULT_LINE_WIDTH = 3

            @JvmStatic
            fun fromJson(o: JSONObject): TrackRecord {
                val t = TrackRecord()
                t.id = o.optLong("id")
                t.name = o.optString("name")
                t.date = o.optString("date")
                t.color = o.optString("color", null)
                t.hidden = o.optBoolean("hidden", false)
                t.lineWidth = o.optInt("lineWidth", DEFAULT_LINE_WIDTH)
                t.points.addAll(pointsFromJson(o.optJSONArray("points")))
                return t
            }
        }
    }

    /** 测量记录（点/距离/面积 + 属性）；[points] 内存序 [lat,lon,alt] */
    class MeasureRecord {
        var id: Long = 0
        var name: String? = null
        var date: String? = null
        var type: String? = null      // TYPE_POINT / TYPE_DISTANCE / TYPE_AREA
        var result: String? = null    // 测量结果文本（导出附属信息，几何为准）
        val points = mutableListOf<DoubleArray>()
        var color: String? = null
        var hidden: Boolean = false
        var closed: Boolean = false   // 面是否闭合
        var lineWidth: Int = DEFAULT_LINE_WIDTH

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
            o.put("lineWidth", lineWidth)
            o.put("points", pointsToJson(points))
            return o
        }

        companion object {
            const val TYPE_POINT = "point"
            const val TYPE_DISTANCE = "distance"
            const val TYPE_AREA = "area"
            const val DEFAULT_LINE_WIDTH = 3

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
                m.lineWidth = o.optInt("lineWidth", DEFAULT_LINE_WIDTH)
                m.points.addAll(pointsFromJson(o.optJSONArray("points")))
                return m
            }
        }
    }

    /** 拍照附件（照片文件） */
    class PhotoAttachment {
        var name: String = ""
        var path: String = ""
        var time: Long = 0

        constructor()

        constructor(name: String, path: String, time: Long) {
            this.name = name
            this.path = path
            this.time = time
        }

        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("name", name)
            o.put("path", path)
            o.put("time", time)
            return o
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): PhotoAttachment {
                val a = PhotoAttachment()
                a.name = o.optString("name", "")
                a.path = o.optString("path", "")
                a.time = o.optLong("time", 0)
                return a
            }
        }
    }

    /** 拍照标识（点 + 多照片附件，对齐旧 media.db placemark+attachment 结构） */
    class PhotoRecord {
        var id: Long = 0
        var name: String? = null
        var lon: Double = 0.0
        var lat: Double = 0.0
        var alt: Double = 0.0
        var timestamp: Long = 0
        var visible: Boolean = true                   // 隐藏后地图不渲染
        var groupId: Long = UNGROUPED_ID              // 所属分组，[UNGROUPED_ID] 未分组
        val attachments = mutableListOf<PhotoAttachment>()

        /** 主照片路径（首个附件），无附件返回 null */
        val photoPath: String?
            get() = attachments.firstOrNull()?.path

        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("id", id)
            o.put("name", name)
            o.put("lon", lon)
            o.put("lat", lat)
            o.put("alt", alt)
            o.put("timestamp", timestamp)
            o.put("visible", visible)
            o.put("groupId", groupId)
            val arr = JSONArray()
            for (a in attachments) arr.put(a.toJson())
            o.put("attachments", arr)
            return o
        }

        companion object {
            const val UNGROUPED_ID = -1L

            @JvmStatic
            fun fromJson(o: JSONObject): PhotoRecord {
                val p = PhotoRecord()
                p.id = o.optLong("id")
                p.name = o.optString("name")
                p.lon = o.optDouble("lon")
                p.lat = o.optDouble("lat")
                p.alt = o.optDouble("alt", 0.0)
                p.timestamp = o.optLong("timestamp")
                p.visible = o.optBoolean("visible", true)
                p.groupId = o.optLong("groupId", UNGROUPED_ID)
                o.optJSONArray("attachments")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        p.attachments.add(PhotoAttachment.fromJson(arr.getJSONObject(i)))
                    }
                }
                // 旧版 gpkg 兼容：无 attachments 但有 photoPath 时回退单附件
                if (p.attachments.isEmpty()) {
                    val legacy = o.optString("photoPath", "")
                    if (legacy.isNotEmpty()) {
                        p.attachments.add(PhotoAttachment(java.io.File(legacy).name, legacy, p.timestamp))
                    }
                }
                return p
            }
        }
    }

    /** 拍照分组（对齐旧 media_group 表）；组可见性通过批量写点位 visible 生效 */
    class MediaGroupRecord {
        var id: Long = 0
        var name: String? = null
        var createdTime: Long = 0
        var visible: Boolean = true

        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("id", id)
            o.put("name", name)
            o.put("createdTime", createdTime)
            o.put("visible", visible)
            return o
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): MediaGroupRecord {
                val g = MediaGroupRecord()
                g.id = o.optLong("id")
                g.name = o.optString("name")
                g.createdTime = o.optLong("createdTime")
                g.visible = o.optBoolean("visible", true)
                return g
            }
        }
    }

    /** 样地调查明细行（序列化进 [SurveyPlot] attributes JSON） */
    class SurveyRow {
        var id: Long = 0
        var sortNo: Int = 0
        var treeSpecies: String? = null
        var dbh: String? = null
        var category: String? = null
        var thinningMethod: String? = null
        var remark: String? = null

        fun toJson(): JSONObject {
            val o = JSONObject()
            if (id > 0) o.put("id", id)  // 行 id 落盘保留（仅有效 id 写入，避免噪声）
            o.put("sortNo", sortNo)
            o.put("treeSpecies", treeSpecies ?: JSONObject.NULL)
            o.put("dbh", dbh ?: JSONObject.NULL)
            o.put("category", category ?: JSONObject.NULL)
            o.put("thinningMethod", thinningMethod ?: JSONObject.NULL)
            o.put("remark", remark ?: JSONObject.NULL)
            return o
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): SurveyRow {
                val r = SurveyRow()
                r.id = o.optLong("id", 0L)
                r.sortNo = o.optInt("sortNo")
                r.treeSpecies = o.optString("treeSpecies", null)
                r.dbh = o.optString("dbh", null)
                r.category = o.optString("category", null)
                r.thinningMethod = o.optString("thinningMethod", null)
                r.remark = o.optString("remark", null)
                return r
            }
        }
    }

    /**
     * 样地调查（中心点 + 富表单）：表单字段与明细行/照片在内存为强类型字段，
     * 序列化时整体打包进 attributes JSON 列（gpkg schema 不变）。
     * [points] 内存序 [lat,lon,alt]，首点即样地中心。
     */
    class SurveyPlot {
        var id: Long = 0
        var name: String? = null
        var plotType: String? = "point"
        val points = mutableListOf<DoubleArray>()

        // ---- 表单字段（对齐旧 sample_survey.db survey_plots 表）----
        var sourcePath: String = ""
        var sourceFid: Long = 0L
        var compartmentNo: String? = null
        var plotNo: Int = 1
        var averageTreeHeight: String? = null
        var canopyDensity: Double = Double.NaN
        var cornerCoordinates: String? = null
        var createdAt: Long = 0L
        var updatedAt: Long = 0L
        val rows = mutableListOf<SurveyRow>()
        val photoPaths = mutableListOf<String>()

        val centerLatitude: Double
            get() = points.firstOrNull()?.get(0) ?: Double.NaN
        val centerLongitude: Double
            get() = points.firstOrNull()?.get(1) ?: Double.NaN

        fun setCenter(lat: Double, lon: Double) {
            if (points.isEmpty()) points.add(doubleArrayOf(lat, lon, 0.0))
            else points[0] = doubleArrayOf(lat, lon, 0.0)
        }

        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("id", id)
            o.put("name", name)
            o.put("plotType", plotType)
            o.put("points", pointsToJson(points))
            o.put("attributes", attributesToJson())
            return o
        }

        internal fun attributesToJson(): JSONObject {
            val a = JSONObject()
            a.put("sourcePath", sourcePath)
            a.put("sourceFid", sourceFid)
            a.put("compartmentNo", compartmentNo ?: JSONObject.NULL)
            a.put("plotNo", plotNo)
            a.put("averageTreeHeight", averageTreeHeight ?: JSONObject.NULL)
            if (canopyDensity.isNaN()) a.put("canopyDensity", JSONObject.NULL)
            else a.put("canopyDensity", canopyDensity)
            a.put("cornerCoordinates", cornerCoordinates ?: JSONObject.NULL)
            a.put("createdAt", createdAt)
            a.put("updatedAt", updatedAt)
            val rowsArr = JSONArray()
            for (r in rows) rowsArr.put(r.toJson())
            a.put("rows", rowsArr)
            val photosArr = JSONArray()
            for (p in photoPaths) photosArr.put(p)
            a.put("photos", photosArr)
            return a
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): SurveyPlot {
                val s = SurveyPlot()
                s.id = o.optLong("id")
                s.name = o.optString("name")
                s.plotType = o.optString("plotType", "point")
                s.points.addAll(pointsFromJson(o.optJSONArray("points")))
                // attributes 兼容两种形态：JSON 对象（native 解析后）或 JSON 字符串（列原文）
                val attr = o.optJSONObject("attributes")
                    ?: o.optString("attributes", "").takeIf { it.startsWith("{") }
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (attr != null) {
                    s.sourcePath = attr.optString("sourcePath", "")
                    s.sourceFid = attr.optLong("sourceFid", 0L)
                    s.compartmentNo = attr.optString("compartmentNo", null)
                    s.plotNo = attr.optInt("plotNo", 1)
                    s.averageTreeHeight = attr.optString("averageTreeHeight", null)
                    s.canopyDensity =
                        if (attr.isNull("canopyDensity")) Double.NaN else attr.optDouble("canopyDensity", Double.NaN)
                    s.cornerCoordinates = attr.optString("cornerCoordinates", null)
                    s.createdAt = attr.optLong("createdAt", 0L)
                    s.updatedAt = attr.optLong("updatedAt", 0L)
                    attr.optJSONArray("rows")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            s.rows.add(SurveyRow.fromJson(arr.getJSONObject(i)))
                        }
                    }
                    attr.optJSONArray("photos")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optString(i).takeIf { p -> p.isNotEmpty() }?.let { p -> s.photoPaths.add(p) }
                        }
                    }
                }
                return s
            }
        }
    }
}
