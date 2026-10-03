package com.zys.mobilemap.util

import com.zys.mobilemap.doc.DocumentManager
import java.util.Locale

/**
 * 主界面坐标文本格式化：按系统配置（[com.zys.mobilemap.doc.SystemConfig.coordDisplayMeter]）
 * 在“十进制度”与“米（CGCS2000 3 度带高斯-克吕格投影坐标系）”之间切换，
 * 供中心坐标与定位坐标显示共用，保证两处格式一致。
 */
object CoordFormatter {

    /** 当前配置是否以米（2000 投影坐标系）显示坐标 */
    fun isMeterDisplay(): Boolean =
        DocumentManager.getInstance()
            .getDocument()?.systemConfig?.coordDisplayMeter == true

    /**
     * 按当前配置格式化坐标文本：“前缀: 值1 值2”。
     * 十进制度为 经度 纬度（4 位小数）；米为 CGCS2000 3 度带 东坐标 北坐标（3 位小数，
     * 带号已含在东坐标前缀中，如 38 带东坐标约 38500000）。
     * 米制转换不可用（native/proj.db 异常）时自动回退十进制度，保证坐标始终可读。
     */
    fun format(prefix: String, lon: Double, lat: Double): String {
        if (isMeterDisplay()) {
            val meters = CoordTransform.toCgcs2000Meters(lon, lat)
            if (meters != null) {
                return String.format(Locale.getDefault(),
                    "%s: %.3f %.3f", prefix, meters[0], meters[1])
            }
        }
        return String.format(Locale.getDefault(),
            "%s: %.4f %.4f", prefix, lon, lat)
    }

    /**
     * 强制十进制度格式化（无视「坐标以米显示」配置）：主界面底部中心坐标恒用十进制度，
     * 与定位文本（随配置可切米制）区分，避免米制长坐标挤压定位行显示。
     */
    fun formatDecimalDegrees(prefix: String, lon: Double, lat: Double): String =
        String.format(Locale.getDefault(), "%s: %.4f %.4f", prefix, lon, lat)

    /**
     * 带海拔的定位坐标文本：在 [format] 结果后追加“ hN米”（米制/十进制度均适用，h = height 海拔）。
     * alt 为 WGS84 椭球高（米），传 [Double.NaN] 表示定位无海拔信息，此时不追加、退化为 [format]。
     */
    fun formatWithAlt(prefix: String, lon: Double, lat: Double, alt: Double): String {
        val base = format(prefix, lon, lat)
        if (alt.isNaN()) return base
        return String.format(Locale.getDefault(), "%s h%.0f米", base, alt)
    }
}
