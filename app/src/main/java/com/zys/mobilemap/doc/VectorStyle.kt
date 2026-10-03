package com.zys.mobilemap.doc

import org.json.JSONObject

/**
 * 矢量图层样式覆盖（文档层覆盖方案：写入 document.json，矢量源数据不变化）。
 * 全部字段可空，空 = 保持源文件/默认样式。
 * 优先级：单要素样式（[featureStyles]）> 整层样式 > 源文件自带样式。
 *
 * 颜色统一 #AARRGGBB 十六进制字符串；加载时由渲染层覆盖应用（MainActivity/native 矢量装载链路）。
 */
class VectorStyle {

    /** 填充色（面） */
    var fillColor: String? = null

    /** 填充透明度 0..1（面） */
    var fillOpacity: Float? = null

    /** 是否填充（面）：null/true = 填充，false = 不填充（面仅描边不填充） */
    var fillEnabled: Boolean? = null

    /** 外框/线条颜色（面、线） */
    var outlineColor: String? = null

    /** 外框/线条宽度（像素） */
    var outlineWidth: Float? = null

    /** 标注字段名（shp 为 DBF 字段名，GDAL 路径为属性字段名） */
    var labelField: String? = null

    /** 标注文字颜色 */
    var labelColor: String? = null

    /** 标注缩放（1.0 = 默认字号） */
    var labelSize: Float? = null

    /** 标注轮廓开关 */
    var labelOutline: Boolean? = null

    /** 标注轮廓颜色 */
    var labelOutlineColor: String? = null

    /** 点要素标识图标 key（见 [com.zys.mobilemap.vector.FeatureIcons]），缺省 null = 默认图标 */
    var iconKey: String? = null

    /** 单要素样式覆盖：键 = 要素标识（shp 记录号 / GDAL FID），缺省字段继承整层样式 */
    val featureStyles = mutableMapOf<Long, VectorStyle>()

    /** 无任何样式设置 */
    fun isEmpty(): Boolean = fillColor == null && fillOpacity == null && fillEnabled == null &&
            outlineColor == null && outlineWidth == null &&
            labelField == null && labelColor == null && labelSize == null &&
            labelOutline == null && labelOutlineColor == null &&
            iconKey == null &&
            featureStyles.isEmpty()

    fun toJson(): JSONObject {
        val o = JSONObject()
        fillColor?.let { o.put("fillColor", it) }
        fillOpacity?.let { o.put("fillOpacity", it.toDouble()) }
        fillEnabled?.let { o.put("fillEnabled", it) }
        outlineColor?.let { o.put("outlineColor", it) }
        outlineWidth?.let { o.put("outlineWidth", it.toDouble()) }
        labelField?.let { o.put("labelField", it) }
        labelColor?.let { o.put("labelColor", it) }
        labelSize?.let { o.put("labelSize", it.toDouble()) }
        labelOutline?.let { o.put("labelOutline", it) }
        labelOutlineColor?.let { o.put("labelOutlineColor", it) }
        iconKey?.let { o.put("iconKey", it) }
        if (featureStyles.isNotEmpty()) {
            val fs = JSONObject()
            for ((id, s) in featureStyles) {
                val so = s.toJson()
                if (so.length() > 0) fs.put(id.toString(), so)
            }
            if (fs.length() > 0) o.put("featureStyles", fs)
        }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): VectorStyle {
            val s = VectorStyle()
            if (o.has("fillColor") && !o.isNull("fillColor")) s.fillColor = o.getString("fillColor")
            if (o.has("fillOpacity")) s.fillOpacity = o.optDouble("fillOpacity").toFloat()
            if (o.has("fillEnabled")) s.fillEnabled = o.getBoolean("fillEnabled")
            if (o.has("outlineColor") && !o.isNull("outlineColor")) s.outlineColor = o.getString("outlineColor")
            if (o.has("outlineWidth")) s.outlineWidth = o.optDouble("outlineWidth").toFloat()
            if (o.has("labelField") && !o.isNull("labelField")) s.labelField = o.getString("labelField")
            if (o.has("labelColor") && !o.isNull("labelColor")) s.labelColor = o.getString("labelColor")
            if (o.has("labelSize")) s.labelSize = o.optDouble("labelSize").toFloat()
            if (o.has("labelOutline")) s.labelOutline = o.getBoolean("labelOutline")
            if (o.has("labelOutlineColor") && !o.isNull("labelOutlineColor")) s.labelOutlineColor = o.getString("labelOutlineColor")
            if (o.has("iconKey") && !o.isNull("iconKey")) s.iconKey = o.getString("iconKey")
            val fs = o.optJSONObject("featureStyles")
            if (fs != null) {
                val keys = fs.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    s.featureStyles[k.toLongOrNull() ?: continue] = fromJson(fs.getJSONObject(k))
                }
            }
            return s
        }
    }
}
