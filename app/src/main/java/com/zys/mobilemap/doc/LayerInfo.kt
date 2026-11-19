package com.zys.mobilemap.doc

import org.json.JSONArray
import org.json.JSONObject

class LayerInfo {
    var name: String? = null
    var path: String? = null
    var visible: Boolean = true
    /** 图层类型：[TYPE_VECTOR] / [TYPE_RASTER] / 其他 */
    var type: String? = null

    /** 矢量样式覆盖（仅矢量图层使用，原数据不变，加载时渲染层应用） */
    var vectorStyle: VectorStyle? = null

    /** 样式版本号：样式修改时递增，驱动已加载图层重载生效（图层同步以此为缓存键之一） */
    var styleVersion: Int = 0

    /** 数据集内的 OGR 图层名（GDB 要素类等）；空串保持旧版默认图层行为。 */
    var layerName: String = ""

    /**
     * 栅格加载模式（仅 [TYPE_RASTER] 图层使用）：true=全幅加载（整幅直读为一张贴图一次性呈现），
     * false（默认）=分瓦加载（后台转瓦片、逐级呈现的老路）。切换即图层结构变化，经 recreate 重建生效
     * （见 MapLayerManager.rasterKey）。宽松读取：文档缺字段时按默认 false，零迁移。
     */
    var rasterFullExtent: Boolean = false

    /**
     * 级别可见性——自动值：首次加载写入的默认最小显示级别（固定为 5）。
     * 当前级别 < 生效级别时隐藏图层（整层入屏、要素密集成片无辨识意义）。
     * `-1` 表示尚未初始化。生效规则见 [userMinDisplayLevel]。
     */
    var autoMinDisplayLevel: Int = -1

    /**
     * 级别可见性——用户值：用户在图层管理手动指定的最小显示级别。
     * `-1`（默认）表示未指定，此时生效 [autoMinDisplayLevel]；
     * `≥0` 时优先生效本值。用户值生效导致过滤要素过多（>5000）时会被复位为 -1 回退自动值。
     */
    var userMinDisplayLevel: Int = -1

    /** 所属分组 id；[UNGROUPED_ID] = 未分组。组纯组织管理，不影响压盖顺序 */
    var groupId: Int = UNGROUPED_ID

    // ── 元数据缓存（P1，全部可选；旧文档缺字段行为与现在完全一致）──
    // 由 native 读取链顺手产出的全量值写回（见 vector/VectorMetaCache），以 metaMtime 指纹判新鲜度：
    // 冷启动/图层管理逐行回显不再为元数据另开 GDAL；源文件被外部修改（指纹变）自动失效回落直读。

    /** 几何类型（app 口径：0 点 / 1 线 / 2 面 / -1 未知或混合）；KML/KMZ 不缓存（走 geomMaskOf 采样） */
    var geomType: Int = -1

    /** 数据全量要素总数；-1 = 未知（不以屏幕过滤子集冒充） */
    var featureCount: Long = -1L

    /** 数据全量 WGS84 四至 [minLon, minLat, maxLon, maxLat]；null = 未知 */
    var extent: DoubleArray? = null

    /** 元数据指纹：写入缓存时源文件修改时间（gdb 扫内层取最大、shp 取附属族最大）；0 = 无指纹 */
    var metaMtime: Long = 0L

    /** 级别可见性生效值：用户指定（≥0）优先，否则用自动计算值；两者皆 <0 表示不限制 */
    val effectiveMinDisplayLevel: Int
        get() = if (userMinDisplayLevel >= 0) userMinDisplayLevel else autoMinDisplayLevel

    constructor()

    constructor(
        name: String?, path: String?, type: String?, visible: Boolean,
        layerName: String = ""
    ) {
        this.name = name
        this.path = path
        this.type = type
        this.visible = visible
        this.layerName = layerName
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name ?: JSONObject.NULL)
        o.put("path", path ?: JSONObject.NULL)
        o.put("visible", visible)
        o.put("type", type ?: JSONObject.NULL)
        if (layerName.isNotEmpty()) o.put("layerName", layerName)
        if (styleVersion != 0) o.put("styleVersion", styleVersion)
        if (rasterFullExtent) o.put("rasterFullExtent", true)
        if (autoMinDisplayLevel != -1) o.put("autoMinDisplayLevel", autoMinDisplayLevel)
        if (userMinDisplayLevel != -1) o.put("userMinDisplayLevel", userMinDisplayLevel)
        if (groupId != UNGROUPED_ID) o.put("groupId", groupId)
        if (geomType >= 0) o.put("geomType", geomType)
        if (featureCount >= 0L) o.put("featureCount", featureCount)
        val ext = extent
        if (ext != null && ext.size == 4) {
            val a = JSONArray()
            for (v in ext) a.put(v)
            o.put("extent", a)
        }
        if (metaMtime != 0L) o.put("metaMtime", metaMtime)
        val style = vectorStyle
        if (style != null && !style.isEmpty()) o.put("vectorStyle", style.toJson())
        return o
    }

    companion object {
        /** 图层类型取值（document.json 的 type 字段）：矢量图层 */
        const val TYPE_VECTOR = "vector"

        /** 图层类型取值（document.json 的 type 字段）：栅格图层 */
        const val TYPE_RASTER = "raster"

        /** 未分组哨兵值（与拍照 UNGROUPED_ID 同口径） */
        const val UNGROUPED_ID = -1

        fun fromJson(o: JSONObject): LayerInfo {
            val l = LayerInfo()
            if (o.has("name") && !o.isNull("name")) l.name = o.getString("name")
            if (o.has("path") && !o.isNull("path")) l.path = o.getString("path")
            if (o.has("visible")) l.visible = o.getBoolean("visible")
            if (o.has("type") && !o.isNull("type")) l.type = o.getString("type")
            l.layerName = o.optString("layerName", "")
            if (o.has("styleVersion")) l.styleVersion = o.optInt("styleVersion")
            l.rasterFullExtent = o.optBoolean("rasterFullExtent", false)
            if (o.has("autoMinDisplayLevel")) l.autoMinDisplayLevel = o.optInt("autoMinDisplayLevel", -1)
            if (o.has("userMinDisplayLevel")) l.userMinDisplayLevel = o.optInt("userMinDisplayLevel", -1)
            l.groupId = o.optInt("groupId", UNGROUPED_ID)
            // 元数据缓存：宽松读取，旧文档无这些字段时保持「未知」默认（行为与现在完全一致）
            l.geomType = o.optInt("geomType", -1)
            l.featureCount = o.optLong("featureCount", -1L)
            val ea = o.optJSONArray("extent")
            if (ea != null && ea.length() == 4) {
                val arr = DoubleArray(4) { ea.optDouble(it, Double.NaN) }
                if (arr.all { it.isFinite() }) l.extent = arr
            }
            l.metaMtime = o.optLong("metaMtime", 0L)
            val style = o.optJSONObject("vectorStyle")
            if (style != null) l.vectorStyle = VectorStyle.fromJson(style)
            return l
        }
    }
}
