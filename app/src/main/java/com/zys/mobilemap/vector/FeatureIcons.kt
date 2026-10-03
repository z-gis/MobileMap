package com.zys.mobilemap.vector

import com.zys.mobilemap.R

/**
 * 点要素内置标识图标集。
 *
 * 图标 key 持久化于 [com.zys.mobilemap.doc.VectorStyle.iconKey]（写入 document.json），
 * 加载时经 `ImageSource.fromResource` 应用到点 Placemark。原底层库支持矢量 drawable：
 * 其 ImageDecoder.decodeResource 先用 BitmapFactory 解码（矢量 XML 返回 null），
 * 再回退 AppCompatResources.getDrawable + Canvas 渲染为位图，故矢量图标可正常显示。
 */
object FeatureIcons {

    /** 缺省图标 key：点要素未指定样式时使用，改善原 imageSource=null 仅画 1px 白方块（不可见/难点） */
    const val DEFAULT_KEY = "pin"

    /** 可选图标：key -> 矢量 drawable 资源；列表顺序即样式页图标网格展示顺序 */
    val ICONS: List<Pair<String, Int>> = listOf(
        "pin" to R.drawable.ic_marker_pin,
        "circle" to R.drawable.ic_marker_circle,
        "triangle" to R.drawable.ic_marker_triangle,
        "square" to R.drawable.ic_marker_square,
        "star" to R.drawable.ic_marker_star,
        "flag" to R.drawable.ic_marker_flag
    )

    /** 按 key 取图标资源；未匹配（含 null）回退默认针形图标 */
    fun resOf(key: String?): Int =
        ICONS.firstOrNull { it.first == key }?.second ?: R.drawable.ic_marker_pin
}
