package com.zys.mobilemap.ui.map

import android.app.Activity
import android.widget.Toast
import com.zys.mobilemap.R
import com.zys.mobilemap.vector.GdalVectorReader
import com.zys.mobilemap.vector.VectorFeature
import com.zys.globecore.NativeMapView
import com.zys.globecore.VectorStyle as NativeVectorStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SQL 查询高亮（自 MainActivity 拆出）：图层管理 shp/gpkg 查询键经 [GdalVectorReader.queryFeatures] 取命中要素
 * （已重投影 WGS84），清除旧高亮后按几何类型叠加洋红高亮 overlay 层并缩放到结果四至。
 *
 * 高亮为临时叠加、不写文档，故缓存命中要素于 [BusinessOverlayManager.rebuild] 后重建保留：
 *  - [onOverlayLayersCleared]：clearOverlayLayers 后仅失效索引（保留命中缓存），由 rebuild 经回调调用；
 *  - [restoreAfterRebuild]：重建末尾按缓存要素重画高亮层，由 rebuild 经回调调用；
 *  - [sourcePathIfHit]：查询高亮层命中时回源文件路径，供 [FeaturePickController] 穿透弹源要素详情
 *    （高亮层已置 noPick 不参与 native 拾取，本方法仅作防护兜底）。
 * 语句执行在注入的 [scope]（宿主 lifecycleScope）后台线程进行；缩放复用 [CameraNavigator.zoomToExtent]。
 */
internal class SqlQueryController(
    private val activity: Activity,
    private val mapView: NativeMapView,
    private val navigator: CameraNavigator,
    private val scope: CoroutineScope
) {

    /** SQL 查询高亮：命中要素缓存（临时叠加、不写文档，[BusinessOverlayManager.rebuild] 重建后据此恢复） */
    private var queryHighlight: List<VectorFeature>? = null

    /** 查询高亮占用的 overlay 层索引（供清除与重建后恢复） */
    private val queryOverlayIndices = mutableListOf<Int>()

    /** 当前查询的源文件路径：高亮层命中时点击穿透回源要素、按 FID 回取属性用 */
    private var queryPath: String? = null

    /**
     * 执行 shp/gpkg 图层 SQL 查询：后台线程经 [GdalVectorReader.queryFeatures] 取命中要素（已重投影 WGS84），
     * 清除旧高亮后按几何类型叠加洋红高亮层并缩放到结果四至；语句错误按 GDAL/OGR 原因长时提示。
     */
    fun runSqlQuery(path: String, sql: String) {
        Toast.makeText(activity, R.string.sql_query_running, Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.Default) { GdalVectorReader.queryFeatures(path, sql) }
            if (!result.isSuccess) {
                Toast.makeText(
                    activity, activity.getString(R.string.sql_query_failed, result.error ?: ""), Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val features = result.features ?: emptyList()
            // 无论命中与否先清旧高亮，避免多次查询叠加
            clearSqlQueryHighlight()
            if (features.isEmpty()) {
                Toast.makeText(activity, R.string.sql_query_result_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            queryHighlight = features
            queryPath = path
            addQueryHighlightOverlay(features)
            queryExtentOf(features)?.let { navigator.zoomToExtent(it[0], it[1], it[2], it[3]) }
            Toast.makeText(
                activity, activity.getString(R.string.sql_query_result_count, features.size), Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 清除地图上的 SQL 查询高亮结果（图层管理「清除」键入口） */
    fun clearSqlQuery() {
        clearSqlQueryHighlight()
        Toast.makeText(activity, R.string.sql_query_cleared, Toast.LENGTH_SHORT).show()
    }

    /** 拆除查询高亮层并清缓存（幂等）：墓碑各高亮 overlay 层，清空索引与命中要素缓存 */
    private fun clearSqlQueryHighlight() {
        for (idx in queryOverlayIndices) mapView.removeOverlayLayer(idx)
        queryOverlayIndices.clear()
        queryHighlight = null
        queryPath = null
    }

    /**
     * 命中要素 → 洋红高亮叠加层（overlay 一层一种几何类型，故按 POINT/LINE/POLYGON 分建至多三层）。
     * [VectorFeature] 的摊平坐标已是 [lon,lat,…] 顺序，与 updateOverlayLines/Polygons 入参同构，无需换序。
     * 高亮层置 noPick 不参与拾取（对齐原主界面 buildQueryResultLayer 关闭拾取的穿透语义）：其大面积几何
     * 会遮蔽测量/拍照等业务叠加与源要素的点击，命中直接落到下方源矢量层。
     * 各高亮层索引记入 [queryOverlayIndices]，供清除与重建后恢复。
     */
    private fun addQueryHighlightOverlay(features: List<VectorFeature>) {
        val points = features.filter { it.geom == "POINT" }
        val lines = features.filter { it.geom == "LINE" }
        val polys = features.filter { it.geom == "POLYGON" }

        if (points.isNotEmpty()) {
            val idx = mapView.addOverlayLayer(
                NativeVectorStyle(
                    fillColor = MapStyleColors.QUERY_OUTLINE_COLOR, outlineColor = MapStyleColors.QUERY_OUTLINE_COLOR, outlineWidth = MapStyleColors.QUERY_LINE_WIDTH,
                    lineColor = MapStyleColors.QUERY_OUTLINE_COLOR, lineWidth = MapStyleColors.QUERY_LINE_WIDTH, pointColor = MapStyleColors.QUERY_OUTLINE_COLOR,
                    pointRadiusDp = MapStyleColors.QUERY_POINT_RADIUS_DP
                )
            )
            if (idx >= 0) {
                mapView.setOverlayNoPick(idx, true) // 瞬态高亮层退出拾取竞争，命中直接落源层
                queryOverlayIndices.add(idx)
                val lonlat = DoubleArray(points.size * 2)
                val fids = LongArray(points.size)
                for (i in points.indices) {
                    lonlat[i * 2] = points[i].x
                    lonlat[i * 2 + 1] = points[i].y
                    fids[i] = points[i].fid
                }
                mapView.updateOverlayPoints(idx, lonlat, fids)
            }
        }

        if (lines.isNotEmpty()) {
            val idx = mapView.addOverlayLayer(
                NativeVectorStyle(
                    fillColor = MapStyleColors.QUERY_OUTLINE_COLOR, outlineColor = MapStyleColors.QUERY_OUTLINE_COLOR, outlineWidth = MapStyleColors.QUERY_LINE_WIDTH,
                    lineColor = MapStyleColors.QUERY_OUTLINE_COLOR, lineWidth = MapStyleColors.QUERY_LINE_WIDTH, pointColor = MapStyleColors.QUERY_OUTLINE_COLOR,
                    pointRadiusDp = MapStyleColors.QUERY_POINT_RADIUS_DP
                )
            )
            if (idx >= 0) {
                mapView.setOverlayNoPick(idx, true) // 瞬态高亮层退出拾取竞争，命中直接落源层
                queryOverlayIndices.add(idx)
                // 多段线：每 part 作为一条折线（fids 重复填所属要素 fid）
                val flat = ArrayList<Double>()
                val counts = ArrayList<Int>()
                val fids = ArrayList<Long>()
                for (f in lines) {
                    f.parts?.forEach { part ->
                        val n = part.size / 2
                        if (n < 2) return@forEach
                        for (v in part) flat.add(v)
                        counts.add(n)
                        fids.add(f.fid)
                    }
                }
                mapView.updateOverlayLines(idx, flat.toDoubleArray(), counts.toIntArray(), fids.toLongArray())
            }
        }

        if (polys.isNotEmpty()) {
            val idx = mapView.addOverlayLayer(
                NativeVectorStyle(
                    fillColor = MapStyleColors.QUERY_FILL_COLOR, outlineColor = MapStyleColors.QUERY_OUTLINE_COLOR, outlineWidth = MapStyleColors.QUERY_LINE_WIDTH,
                    lineColor = MapStyleColors.QUERY_OUTLINE_COLOR, lineWidth = MapStyleColors.QUERY_LINE_WIDTH, pointColor = MapStyleColors.QUERY_OUTLINE_COLOR,
                    pointRadiusDp = MapStyleColors.QUERY_POINT_RADIUS_DP
                )
            )
            if (idx >= 0) {
                mapView.setOverlayNoPick(idx, true) // 瞬态高亮层退出拾取竞争，命中直接落源层
                queryOverlayIndices.add(idx)
                // 面：外环 + 内环（洞）摊平；ringVertexCounts 每环顶点数、ringsPerFeature 每面环数（首环外环）
                val flat = ArrayList<Double>()
                val ringCounts = ArrayList<Int>()
                val ringsPer = ArrayList<Int>()
                val fids = ArrayList<Long>()
                for (f in polys) {
                    val outer = f.outer ?: continue
                    if (outer.size < 6) continue  // 不足 3 顶点跳过
                    var rings = 0
                    for (v in outer) flat.add(v)
                    ringCounts.add(outer.size / 2)
                    rings++
                    f.holes?.forEach { hole ->
                        if (hole.size < 6) return@forEach
                        for (v in hole) flat.add(v)
                        ringCounts.add(hole.size / 2)
                        rings++
                    }
                    ringsPer.add(rings)
                    fids.add(f.fid)
                }
                if (ringsPer.isNotEmpty()) {
                    mapView.updateOverlayPolygons(
                        idx, flat.toDoubleArray(), ringCounts.toIntArray(), ringsPer.toIntArray(), fids.toLongArray()
                    )
                }
            }
        }
    }

    /** 命中要素的 WGS84 四至 [minLon,minLat,maxLon,maxLat]（复刻原主界面 VectorQueryModel.extentOf），无有效坐标返回 null */
    private fun queryExtentOf(features: List<VectorFeature>): DoubleArray? {
        var minLon = Double.MAX_VALUE
        var minLat = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        fun acc(lon: Double, lat: Double) {
            if (lon.isNaN() || lat.isNaN()) return
            if (lon < minLon) minLon = lon
            if (lat < minLat) minLat = lat
            if (lon > maxLon) maxLon = lon
            if (lat > maxLat) maxLat = lat
        }
        fun accFlat(flat: DoubleArray?) {
            if (flat == null) return
            var i = 0
            while (i + 1 < flat.size) { acc(flat[i], flat[i + 1]); i += 2 }
        }
        for (f in features) when (f.geom) {
            "POINT" -> acc(f.x, f.y)
            "LINE" -> f.parts?.forEach { accFlat(it) }
            "POLYGON" -> { accFlat(f.outer); f.holes?.forEach { accFlat(it) } }
        }
        if (minLon > maxLon || minLat > maxLat) return null
        return doubleArrayOf(minLon, minLat, maxLon, maxLat)
    }

    /** clearOverlayLayers 后调用：仅失效高亮层索引（命中缓存保留待重建），由 [BusinessOverlayManager.rebuild] 经回调触发 */
    fun onOverlayLayersCleared() {
        queryOverlayIndices.clear()
    }

    /** 叠加层重建末尾调用：按缓存命中要素重新渲染高亮层（若有），由 [BusinessOverlayManager.rebuild] 经回调触发 */
    fun restoreAfterRebuild() {
        queryHighlight?.let { addQueryHighlightOverlay(it) }
    }

    /** 查询高亮层命中时回源文件路径（供拾取穿透弹源要素只读详情）；非查询高亮层命中返回 null。
     *  高亮层已置 noPick，native 拾取正常不会命中于此，本方法仅作 [FeaturePickController] 防护兑底。 */
    fun sourcePathIfHit(layerIndex: Int): String? =
        if (layerIndex in queryOverlayIndices) queryPath else null
}
