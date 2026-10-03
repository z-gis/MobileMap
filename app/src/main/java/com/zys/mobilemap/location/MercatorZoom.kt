package com.zys.mobilemap.location

import kotlin.math.PI
import kotlin.math.ln

/**
 * Web Mercator 层级/相机高度换算（纯数学，不依赖渲染栈）。
 *
 * 自原 wwd 主界面 NavigatorModel 的 companion 抽出（NavigatorModel 已随主界面移除）：
 * 级别可见性判定与层级显示文本同源，MainActivity 缩放限幅与级别文本均以此为唯一口径。
 */
object MercatorZoom {

    /**
     * WGS84 赤道半径（米）。层级换算属 Web Mercator 金字塔口径，用赤道半径；
     * 与 [com.zys.mobilemap.util.GeoCalc] 量算用的平均半径 6371000 米不是同一个值，勿混用。
     */
    private const val WGS84_EQUATORIAL_RADIUS = 6378137.0

    /** 层级显示上限（超出后底图走祖先瓦片拉伸，层级号不再增长） */
    const val MAX_LEVEL = 20

    /**
     * 相机高度下限（米）：对应 [MAX_LEVEL] 级的地面高度，手势/按键放大到此高度后不再拉近。
     * 防止相机高度趋近 0 时底图末级瓦片过度拉伸（撕裂）乃至无瓦片可绘（黑屏），
     * 并避免从黑屏状态缩小恢复时相机中心漂移。
     * 计算：赤道周长 / 2^MAX_LEVEL = 2π×6378137 / 2^20 ≈ 38.2m。
     */
    val MIN_CAMERA_ALTITUDE: Double =
        2.0 * PI * WGS84_EQUATORIAL_RADIUS / Math.pow(2.0, MAX_LEVEL.toDouble())

    /**
     * 相机高度 -> Web Mercator 瓦片层级：层级每加 1 地面分辨率减半，
     * 故 level = log2(赤道周长 / 高度)，并限幅到 0..[MAX_LEVEL]。
     */
    fun altitudeToLevel(altitude: Double): Int {
        if (altitude <= 0) return 0
        val circumference = 2.0 * PI * WGS84_EQUATORIAL_RADIUS
        val level = (ln(circumference / altitude) / ln(2.0)).toInt()
        return level.coerceIn(0, MAX_LEVEL)
    }
}
