package com.zys.mobilemap.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 球面几何量算工具：距离/长度/面积/周长（Haversine + 局部等角平面鞋带公式，地球半径取 6371000 米）。
 * 测量模块与要素详情（面积/长度/周长展示）共用。
 *
 * 平面类量算（面积、质心、点到线段距离）统一走 [projectX]/[projectY] 的局部等角投影：
 * 以要素自身的平均纬度为参考纬线，在该纬线附近可视为等角，小范围误差可忽略，
 * 跨度极大（如跨越数十个纬度）的要素面积会偏小——外业图斑尺度下不构成问题。
 */
object GeoCalc {

    private const val EARTH_RADIUS = 6371000.0

    /** 面积退化判定阈值（平方米²量级）：顶点近共线时鞋带公式结果趋零，需回退算术平均 */
    private const val AREA_EPSILON = 1e-9

    /** 亩换算系数：1 平方米 = 0.0015 亩 */
    private const val SQUARE_METER_TO_MU = 0.0015

    /** 两点间球面距离（Haversine），单位米。入参为十进制度 [纬度, 经度]。 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLat = p2 - p1
        val dLon = Math.toRadians(lon2 - lon1)
        val sinHalfDLat = sin(dLat / 2)
        val sinHalfDLon = sin(dLon / 2)
        val h = sinHalfDLat * sinHalfDLat + cos(p1) * cos(p2) * sinHalfDLon * sinHalfDLon
        // minOf 兜底：浮点误差可能让 h 略大于 1，asin 会返回 NaN
        return 2 * EARTH_RADIUS * asin(minOf(1.0, sqrt(h)))
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

        val cosLat0 = cos(meanLatitudeRadians(points))
        var area2 = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val ax = projectX(a[1], cosLat0)
            val ay = projectY(a[0])
            val bx = projectX(b[1], cosLat0)
            val by = projectY(b[0])
            area2 += ax * by - bx * ay
        }
        return area2 / 2.0
    }

    /** 多边形面积，单位平方米。点串为 [纬度, 经度] 序列，首尾无需闭合。 */
    fun polygonAreaSquareMeters(points: List<DoubleArray>): Double =
        abs(polygonSignedAreaSquareMeters(points))

    /**
     * 多边形质心（面积加权，局部等角平面近似），返回 [纬度, 经度] 十进制度；
     * 面积退化（近零，如顶点共线）时回退为各顶点算术平均。供面积标注落点使用。
     */
    fun polygonCentroid(points: List<DoubleArray>): DoubleArray {
        if (points.size < 3) {
            val p = points.firstOrNull() ?: return doubleArrayOf(0.0, 0.0)
            return doubleArrayOf(p[0], p[1])
        }

        val cosLat0 = cos(meanLatitudeRadians(points))
        var area2 = 0.0
        var cx = 0.0
        var cy = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val ax = projectX(a[1], cosLat0)
            val ay = projectY(a[0])
            val bx = projectX(b[1], cosLat0)
            val by = projectY(b[0])
            val cross = ax * by - bx * ay
            area2 += cross
            cx += (ax + bx) * cross
            cy += (ay + by) * cross
        }

        if (abs(area2) < AREA_EPSILON) {
            var lat = 0.0
            var lon = 0.0
            for (p in points) {
                lat += p[0]
                lon += p[1]
            }
            return doubleArrayOf(lat / points.size, lon / points.size)
        }

        cx /= 3.0 * area2
        cy /= 3.0 * area2
        return doubleArrayOf(
            Math.toDegrees(cy / EARTH_RADIUS),
            Math.toDegrees(cx / (EARTH_RADIUS * cosLat0))
        )
    }

    /**
     * 点到线段的最短距离（局部等角平面近似），单位米。
     * 点为十进制度 [lat, lon]，线段两端为 [纬度, 经度] 数组；
     * 测量几何与轨迹的点击命中判定共用（贴地细线难以 pick 命中，改走地理坐标判定）。
     */
    fun distanceToSegmentMeters(lat: Double, lon: Double, a: DoubleArray, b: DoubleArray): Double {
        // 参考纬线取点与线段两端的平均纬度，使投影在三者之间都近似等角
        val cosLat0 = cos(Math.toRadians((a[0] + b[0] + lat) / 3.0))
        val px = projectX(lon, cosLat0)
        val py = projectY(lat)
        val ax = projectX(a[1], cosLat0)
        val ay = projectY(a[0])
        val bx = projectX(b[1], cosLat0)
        val by = projectY(b[0])
        val dx = bx - ax
        val dy = by - ay
        // 退化线段（两端重合）：直接取点到端点距离
        if (dx == 0.0 && dy == 0.0) {
            val sx = px - ax
            val sy = py - ay
            return sqrt(sx * sx + sy * sy)
        }
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val ex = px - (ax + t * dx)
        val ey = py - (ay + t * dy)
        return sqrt(ex * ex + ey * ey)
    }

    /**
     * 点到折线各段的最小距离，单位米；[closed] 为真时额外计入末点到首点的闭合边（面要素）。
     * 点串为 [纬度, 经度] 序列，少于两点时返回 [Double.MAX_VALUE]（视为不命中）。
     */
    fun minEdgeDistanceMeters(
        points: List<DoubleArray>, lat: Double, lon: Double, closed: Boolean = false
    ): Double {
        if (points.size < 2) return Double.MAX_VALUE
        var min = Double.MAX_VALUE
        for (i in 1 until points.size) {
            min = minOf(min, distanceToSegmentMeters(lat, lon, points[i - 1], points[i]))
        }
        if (closed && points.size >= 3) {
            min = minOf(min, distanceToSegmentMeters(lat, lon, points.last(), points.first()))
        }
        return min
    }

    /**
     * 射线法判定点是否在多边形环内（经纬度平面近似）。
     * [ring] 为 [lat, lon] 序列（至少 3 点，无需首尾闭合）；从点 (lat, lon) 向 +lon 方向发水平射线，
     * 统计与环边的交点数，奇数则在内部。供样地调查判定 GPS 是否在小班范围内。
     */
    fun containsPoint(ring: List<DoubleArray>, lat: Double, lon: Double): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val yi = ring[i][0]; val xi = ring[i][1]
            val yj = ring[j][0]; val xj = ring[j][1]
            if ((yi > lat) != (yj > lat)) {
                val intersectLon = (xj - xi) * (lat - yi) / (yj - yi) + xi
                if (lon < intersectLon) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** 距离格式化：<1km 显示米，否则显示千米。 */
    fun formatDistance(meters: Double): String =
        if (meters < 1000.0) "%.1f m".format(Locale.getDefault(), meters)
        else "%.3f km".format(Locale.getDefault(), meters / 1000.0)

    /** 面积格式化：平方米 +（亩），亩 = 平方米 × 0.0015，均保留 1 位小数。 */
    fun formatArea(squareMeters: Double): String =
        "%.1f m²（%.1f亩）".format(Locale.getDefault(), squareMeters, squareMeters * SQUARE_METER_TO_MU)

    /** 各顶点纬度的算术平均（弧度），作为局部等角平面投影的参考纬线 */
    private fun meanLatitudeRadians(points: List<DoubleArray>): Double {
        var sum = 0.0
        for (p in points) sum += Math.toRadians(p[0])
        return sum / points.size
    }

    /** 局部等角平面投影 x（米）：经度弧长按参考纬线余弦 [cosLat0] 缩放 */
    private fun projectX(lonDegrees: Double, cosLat0: Double): Double =
        EARTH_RADIUS * Math.toRadians(lonDegrees) * cosLat0

    /** 局部等角平面投影 y（米）：纬度弧长，与经度无关 */
    private fun projectY(latDegrees: Double): Double = EARTH_RADIUS * Math.toRadians(latDegrees)
}
