package com.zys.mobilemap.util

import android.util.Log
import com.zys.globecore.NativeSrs
import kotlin.math.roundToInt

/**
 * 坐标转换（PROJ）：WGS84 经纬度与 CGCS2000 3 度带高斯-克吕格平面坐标（米）互转。
 *
 * C++ 侧按 "源坐标系|目标坐标系" 缓存 PJ 转换管道（经 globecore [NativeSrs.convert]），
 * 因此可承受主界面坐标文本随导航事件逐帧刷新的高频调用。
 * 转换不可用（native 库未加载 / proj.db 未就绪 / 超出带号范围）时返回 null，
 * 调用方需回退到十进制度显示，不得抛异常中断渲染。
 */
object CoordTransform {

    private const val TAG = "CoordTransform"

    /** CGCS2000 3 度带（带号前缀假东偏移）EPSG 编码起点：25 带 -> EPSG:4534 */
    private const val ZONE_MIN_EPSG = 4534

    /** 3 度带带号范围（中央经线 75°E ~ 135°E，覆盖全国） */
    private const val ZONE_MIN = 25
    private const val ZONE_MAX = 45

    /** WGS84 地理坐标系（经纬度） */
    const val CRS_WGS84 = "EPSG:4326"

    /**
     * CGCS2000 地理坐标系（经纬度）。
     *
     * 与 3 度带投影编码（[epsgOfZone]）并列，作为 [convert] 的源/目标编码入参；
     * 当前无调用方，保留是因为它是本模块对外转换契约的一部分。
     */
    const val CRS_CGCS2000 = "EPSG:4490"

    /** native 转换是否可用：首次调用失败后置假，不再重复尝试（避免逐帧刷无效调用） */
    @Volatile
    private var unavailable = false

    /** 按经度取 3 度带带号（中央经线 = 带号 × 3） */
    private fun zoneOf(lon: Double): Int = (lon / 3.0).roundToInt().coerceIn(ZONE_MIN, ZONE_MAX)

    /** 带号对应的 EPSG 编码（如 38 带 -> EPSG:4547，中央经线 114°E）；
     *  公开作为带号 -> 编码映射的唯一来源（“移动到”对话框坐标系下拉也由此推导）。 */
    fun epsgOfZone(zone: Int): String = "EPSG:${ZONE_MIN_EPSG + zone - ZONE_MIN}"

    /**
     * WGS84 经纬度 -> CGCS2000 3 度带平面坐标（米，横坐标含带号前缀）。
     * 带号按经度自动选取，返回 [东坐标, 北坐标]；不可用返回 null。
     */
    fun toCgcs2000Meters(lon: Double, lat: Double): DoubleArray? {
        if (unavailable || lon.isNaN() || lat.isNaN()) return null
        return convert(lon, lat, CRS_WGS84, epsgOfZone(zoneOf(lon)))
    }

    /** 任意坐标系互转（与"移动到"对话框的坐标系下拉共用），失败返回 null */
    fun convert(x: Double, y: Double, srcCrs: String, tgtCrs: String): DoubleArray? {
        if (srcCrs == tgtCrs) return doubleArrayOf(x, y)
        return try {
            NativeSrs.convert(x, y, srcCrs, tgtCrs)
        } catch (e: Throwable) {
            // UnsatisfiedLinkError（native 库未加载）等：标记不可用，后续直接回退
            unavailable = true
            Log.w(TAG, "坐标转换不可用: $srcCrs -> $tgtCrs", e)
            null
        }
    }
}
