package com.zys.mobilemap.util

/**
 * 球面几何量算工具：距离/长度/面积/周长（Haversine + 局部等角平面鞋带公式，地球半径取 6371000 米）。
 * 测量模块与要素详情（面积/长度/周长展示）共用。
 */
object GeoCalc {

    private const val EARTH_RADIUS = 6371000.0

    /** 两点间球面距离（Haversine），单位米。入参为十进制度 [纬度, 经度]。 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLat = p2 - p1
        val dLon = Math.toRadians(lon2 - lon1)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(p1) * kotlin.math.cos(p2) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * EARTH_RADIUS * kotlin.math.asin(minOf(1.0, kotlin.math.sqrt(h)))
    }

    /** 折线长度，单位米。点串为 [纬度, 经度] 序列。 */
    fun polylineLengthMeters(points: List<DoubleArray>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += distanceMeters(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1])
        }
        return sum
    }

    /** 闭合环周长，单位米（首尾自动闭合）。 */
    fun ringPerimeterMeters(ring: List<DoubleArray>): Double {
        if (ring.size < 2) return 0.0
        var sum = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += distanceMeters(a[0], a[1], b[0], b[1])
        }
        return sum
    }

    /** 多边形带符号面积（局部等角平面鞋带公式），单位平方米；逆时针为正。
     *  带洞多边形可对各环求代数和（洞环绕向相反）后再取绝对值。 */
    fun polygonSignedAreaSquareMeters(points: List<DoubleArray>): Double {
        if (points.size < 3) return 0.0

        var lat0 = 0.0
        for (p in points) lat0 += Math.toRadians(p[0])
        lat0 /= points.size

        var area2 = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val ax = EARTH_RADIUS * Math.toRadians(a[1]) * kotlin.math.cos(lat0)
            val ay = EARTH_RADIUS * Math.toRadians(a[0])
            val bx = EARTH_RADIUS * Math.toRadians(b[1]) * kotlin.math.cos(lat0)
            val by = EARTH_RADIUS * Math.toRadians(b[0])
            area2 += ax * by - bx * ay
        }
        return area2 / 2.0
    }

    /** 多边形面积，单位平方米。点串为 [纬度, 经度] 序列，首尾无需闭合。 */
    fun polygonAreaSquareMeters(points: List<DoubleArray>): Double =
        kotlin.math.abs(polygonSignedAreaSquareMeters(points))

    /** 距离格式化：<1km 显示米，否则显示千米。 */
    fun formatDistance(meters: Double): String =
        if (meters < 1000.0) String.format(java.util.Locale.getDefault(), "%.1f m", meters)
        else String.format(java.util.Locale.getDefault(), "%.3f km", meters / 1000.0)

    /** 面积格式化：<1km² 显示平方米，否则显示平方千米。 */
    fun formatArea(squareMeters: Double): String =
        if (squareMeters < 1_000_000.0) String.format(java.util.Locale.getDefault(), "%.1f m²", squareMeters)
        else String.format(java.util.Locale.getDefault(), "%.3f km²", squareMeters / 1_000_000.0)
}
