package com.zys.mobilemap.doc

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

    /** 级别可见性生效值：用户指定（≥0）优先，否则用自动计算值；两者皆 <0 表示不限制 */
    val effectiveMinDisplayLevel: Int
        get() = if (userMinDisplayLevel >= 0) userMinDisplayLevel else autoMinDisplayLevel

    constructor()

    constructor(name: String?, path: String?, type: String?, visible: Boolean) {
        this.name = name
        this.path = path
        this.type = type
        this.visible = visible
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name ?: JSONObject.NULL)
        o.put("path", path ?: JSONObject.NULL)
        o.put("visible", visible)
        o.put("type", type ?: JSONObject.NULL)
        if (styleVersion != 0) o.put("styleVersion", styleVersion)
        if (autoMinDisplayLevel != -1) o.put("autoMinDisplayLevel", autoMinDisplayLevel)
        if (userMinDisplayLevel != -1) o.put("userMinDisplayLevel", userMinDisplayLevel)
        val style = vectorStyle
        if (style != null && !style.isEmpty()) o.put("vectorStyle", style.toJson())
        return o
    }

    companion object {
        /** 图层类型取值（document.json 的 type 字段）：矢量图层 */
        const val TYPE_VECTOR = "vector"

        /** 图层类型取值（document.json 的 type 字段）：栅格图层 */
        const val TYPE_RASTER = "raster"

        fun fromJson(o: JSONObject): LayerInfo {
            val l = LayerInfo()
            if (o.has("name") && !o.isNull("name")) l.name = o.getString("name")
            if (o.has("path") && !o.isNull("path")) l.path = o.getString("path")
            if (o.has("visible")) l.visible = o.getBoolean("visible")
            if (o.has("type") && !o.isNull("type")) l.type = o.getString("type")
            if (o.has("styleVersion")) l.styleVersion = o.optInt("styleVersion")
            if (o.has("autoMinDisplayLevel")) l.autoMinDisplayLevel = o.optInt("autoMinDisplayLevel", -1)
            if (o.has("userMinDisplayLevel")) l.userMinDisplayLevel = o.optInt("userMinDisplayLevel", -1)
            val style = o.optJSONObject("vectorStyle")
            if (style != null) l.vectorStyle = VectorStyle.fromJson(style)
            return l
        }
    }
}
