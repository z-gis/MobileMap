package com.zys.mobilemap.doc

import org.json.JSONObject

class MapSource {
    var name: String? = null
    var visible: Boolean = true
    var url: String? = null

    var token1: String? = null
    var token2: String? = null

    var imageFormat: String? = null

    /** 图源数据最大级别：超出后末级瓦片纹理拉伸（无限放大）；默认 18，可在 document.json 按源调整 */
    var maxLevel: Int = DEFAULT_MAX_LEVEL

    /** 系统自带地图源（名称不可修改） */
    var isSystem: Boolean = false

    /** 注记图层：不在地图源列表中显示，由主界面按钮控制可见性，渲染位于地图源最后 */
    var isAnnotation: Boolean = false

    /** 天地图系列地图源：所有图层共用 Token，任一处修改同步到全部天地图图层（含注记层） */
    val isTiandituSource: Boolean
        get() = name?.startsWith(TIANDITU_NAME_PREFIX) == true

    constructor()

    constructor(name: String?, url: String?, visible: Boolean) {
        this.name = name
        this.url = url
        this.visible = visible
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name ?: JSONObject.NULL)
        o.put("url", url ?: JSONObject.NULL)
        o.put("visible", visible)
        o.put("token1", token1 ?: JSONObject.NULL)
        o.put("token2", token2 ?: JSONObject.NULL)
        o.put("imageFormat", imageFormat ?: JSONObject.NULL)
        o.put("maxLevel", maxLevel)
        o.put("system", isSystem)
        o.put("annotation", isAnnotation)
        return o
    }

    companion object {
        /** 图源默认最大级别（多数在线底图源为 18 级） */
        const val DEFAULT_MAX_LEVEL = 18

        /** URL 中的 Token 占位符，加载地图时替换为 [token1] */
        const val TOKEN1_PLACEHOLDER = "\$tiandituToken"

        /** URL 中的第二个 Token 占位符，加载地图时替换为 [token2] */
        const val TOKEN2_PLACEHOLDER = "\$tiandituToken2"

        /** 天地图系统地图源名称前缀 */
        const val TIANDITU_NAME_PREFIX = "天地图-"

        fun fromJson(o: JSONObject): MapSource {
            val m = MapSource()
            if (o.has("name") && !o.isNull("name")) m.name = o.getString("name")
            if (o.has("url") && !o.isNull("url")) m.url = o.getString("url")
            if (o.has("visible")) m.visible = o.getBoolean("visible")
            if (o.has("token1") && !o.isNull("token1")) m.token1 = o.getString("token1")
            if (o.has("token2") && !o.isNull("token2")) m.token2 = o.getString("token2")
            if (o.has("imageFormat") && !o.isNull("imageFormat")) m.imageFormat = o.getString("imageFormat")
            if (o.has("maxLevel")) m.maxLevel = o.optInt("maxLevel", DEFAULT_MAX_LEVEL)
            if (o.has("system")) m.isSystem = o.getBoolean("system")
            if (o.has("annotation")) m.isAnnotation = o.getBoolean("annotation")
            return m
        }
    }
}
