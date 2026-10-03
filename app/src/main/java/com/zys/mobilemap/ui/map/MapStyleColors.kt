package com.zys.mobilemap.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.appcompat.content.res.AppCompatResources
import com.zys.mobilemap.doc.VectorStyle
import com.zys.mobilemap.util.AppLog
import java.util.Locale

/**
 * 地图样式公共解析器（自 MainActivity 拆出）：#AARRGGBB 颜色解析/回退、面填充透明度合成、
 * ARGB → 十六进制串，以及矢量层默认样式常量（对齐原主界面 LocalVectorLoader 口径）。
 * 供图层管理、业务叠加、测量采集等多域共用。
 */
internal object MapStyleColors {

    // 矢量默认样式（对齐原主界面 LocalVectorLoader：面填充半透明蓝 + 白描边、线橙红；颜色 #AARRGGBB）
    val DEFAULT_FILL_COLOR = 0x664A8FE3.toInt()            // Color(0.29,0.56,0.89,0.4)
    val DEFAULT_POLYGON_OUTLINE_COLOR = 0xFFFFFFFF.toInt() // Color(1,1,1,1) 白描边
    val DEFAULT_LINE_COLOR = 0xFFE66B33.toInt()           // Color(0.9,0.42,0.2,1)
    const val DEFAULT_OUTLINE_WIDTH = 1.0f
    const val DEFAULT_LINE_WIDTH = 2.0f
    const val DEFAULT_POINT_RADIUS_DP = 5.0f

    // 矢量标注默认样式（对齐原底层库主界面 Label：白字、无轮廓、基准字号）
    val DEFAULT_LABEL_COLOR = 0xFFFFFFFF.toInt()
    val DEFAULT_LABEL_OUTLINE_COLOR = 0xFF000000.toInt()
    const val DEFAULT_LABEL_SIZE = 1.0f

    // 业务叠加层（测量/轨迹/拍照/样地）显示样式：测量点半径略大于矢量默认保证可见；面积填充透明度对齐原主界面 0.22
    const val MEASURE_POINT_RADIUS_DP = 6.0f
    const val MEASURE_AREA_FILL_ALPHA = 0.22f
    const val PHOTO_POINT_RADIUS_DP = 6.0f
    const val SURVEY_POINT_RADIUS_DP = 6.0f

    // 采集交互样式（对齐原主界面 MeasureModel/TrackModel 默认色）：测量各模式默认色 + 轨迹活动线红色加粗
    val MEASURE_COLOR_DISTANCE = 0xFF57ACFD.toInt()  // 距离测量=蓝
    val MEASURE_COLOR_AREA = 0xFF2ECC71.toInt()      // 面积测量=绿
    val MEASURE_COLOR_POINT = 0xFFF1C40F.toInt()     // 点位测量=黄
    const val MEASURE_LIVE_WIDTH = 3.0f              // 测量采集过程线宽（对齐原主界面 LIVE_LINE_WIDTH）
    val TRACK_ACTIVE_COLOR = 0xFFFF5933.toInt()      // 活动轨迹=红（Color(1,0.35,0.2,1)）
    const val TRACK_ACTIVE_WIDTH = 4.0f
    // 轨迹点最小间距（米）：与上一点距离小于此值视为 GPS 抖动/步走摆动，不记录（对齐 TrackModel）
    const val MIN_TRACK_POINT_DISTANCE_METERS = 5.0

    // SQL 查询高亮样式（对齐原主界面 LocalVectorLoader.QUERY_FILL/OUTLINE_COLOR）：洋红半透明填充 + 洋红粗描边
    const val QUERY_FILL_COLOR = 0x59FF00FF
    val QUERY_OUTLINE_COLOR = 0xFFFF00FF.toInt()
    const val QUERY_LINE_WIDTH = 3.0f
    const val QUERY_POINT_RADIUS_DP = 7.0f

    // 点击选中高亮样式（对齐原主界面 LocalVectorLoader.highlight*Attributes 黄色选中态）：黄色半透明填充 + 黄色描边
    const val SELECT_FILL_COLOR = 0x73FFFF00
    val SELECT_OUTLINE_COLOR = 0xFFFFFF00.toInt()
    const val SELECT_LINE_WIDTH = 3.0f
    const val SELECT_POINT_RADIUS_DP = 7.0f

    /** 解析 #AARRGGBB 十六进制为 ARGB Int；空或非法时回退 [defaultArgb]（对齐原主界面 LocalVectorLoader.parseColor） */
    fun resolveArgb(hex: String?, defaultArgb: Int): Int =
        if (hex.isNullOrEmpty()) defaultArgb
        else runCatching { Color.parseColor(hex) }.getOrElse { defaultArgb }

    /** 面填充色：取 fillColor（回退默认半透明蓝），再按 fillOpacity 覆盖 alpha 通道（对齐原主界面 applyShapeStyle） */
    fun resolveFillColor(s: VectorStyle?): Int {
        val base = resolveArgb(s?.fillColor, DEFAULT_FILL_COLOR)
        val opacity = s?.fillOpacity ?: return base
        val alpha = (opacity.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
        return (base and 0x00FFFFFF) or (alpha shl 24)
    }

    /** ARGB Int → #AARRGGBB 十六进制串（对齐原主界面 colorToHex 格式，供样式对话框 Color.parseColor 解析；自实现不依赖原底层库 ColorHex） */
    fun argbToHex(argb: Int): String = String.format(Locale.US, "#%08X", argb)
}

/**
 * 解码点要素图标矢量 drawable 为 ARGB 像素（供 native 上传纹理作 billboard；自 MainActivity 拆出，
 * 图层/叠加/定位多域共用）。
 *
 * native C++ 无法解码 Android 矢量 drawable，故沿用原底层库 ImageDecoder 的回退口径：用
 * [AppCompatResources.getDrawable] 取 drawable（支持矢量 XML），按其固有尺寸（intrinsicWidth/Height，
 * 已含 density 缩放）渲染到 [Bitmap.Config.ARGB_8888] 位图，再 [Bitmap.getPixels] 取出 ARGB IntArray。
 * 返回 Triple(像素, 宽, 高)；解码失败（drawable 为空 / 尺寸非法 / 异常）返回 null → 点要素回退画屏幕固定圆。
 */
internal fun decodeIconArgb(context: Context, resId: Int): Triple<IntArray, Int, Int>? {
    return try {
        val d = AppCompatResources.getDrawable(context, resId)
        val w = d?.intrinsicWidth ?: 0
        val h = d?.intrinsicHeight ?: 0
        if (d == null || w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        Triple(px, w, h)
    } catch (e: Exception) {
        AppLog.w("MapStyleColors", "点图标解码失败 resId=$resId", e)
        null
    }
}
