package com.zys.mobilemap.vector

import android.util.Log
import earth.worldwind.WorldWindow
import earth.worldwind.layer.RenderableLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 矢量 SQL 查询模型：对 shp 等 GDAL 可读矢量执行属性查询，
 * 命中要素以高亮图层叠加在源图层之上（见 [LocalVectorLoader.buildQueryResultLayer]），
 * 并缩放到结果四至；支持随时清除。
 *
 * 查询在后台线程执行（ExecuteSQL 需扫描属性表，大文件耗时），完成后回主线程更新图层。
 * 结果为临时叠加层，不写入文档，应用重启后不保留。
 *
 * @param onZoomToExtent 结果四至回调（WGS84 minLon/minLat/maxLon/maxLat），由宿主完成相机缩放
 */
class VectorQueryModel(
    private val worldWindow: WorldWindow,
    private val scope: CoroutineScope,
    private val onZoomToExtent: (minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) -> Unit
) {

    /** 当前查询结果图层（null 表示无结果叠加） */
    private var resultLayer: RenderableLayer? = null

    /**
     * 执行查询：成功则替换已有结果图层并缩放到命中范围。
     * [onResult] 回传（是否成功, 命中要素数, 失败原因），已在主线程，可直接 Toast。
     */
    fun query(path: String, sql: String, onResult: (success: Boolean, count: Int, error: String?) -> Unit) {
        Log.i(TAG, "执行 SQL 查询: $sql")
        scope.launch {
            val result = withContext(Dispatchers.Default) { GdalVectorReader.queryFeatures(path, sql) }
            if (!result.isSuccess) {
                onResult(false, 0, result.error)
                return@launch
            }
            val features = result.features ?: emptyList()
            // 无论命中与否都先清除旧结果，避免多次查询叠加
            clear()
            if (features.isEmpty()) {
                onResult(true, 0, null)
                return@launch
            }
            val layer = LocalVectorLoader.buildQueryResultLayer(QUERY_LAYER_NAME, features)
            insertAboveSourceLayer(layer, path)
            resultLayer = layer
            worldWindow.requestRedraw()
            extentOf(features)?.let { onZoomToExtent(it[0], it[1], it[2], it[3]) }
            onResult(true, features.size, null)
        }
    }

    /** 清除查询结果图层（幂等） */
    fun clear() {
        val layer = resultLayer ?: return
        worldWindow.engine.layers.removeLayer(layer)
        resultLayer = null
        worldWindow.requestRedraw()
    }

    /**
     * 结果图层插入到源图层之后：渲染紧贴其上方且不打乱“底图 -> 栅格 -> 矢量 -> 注记”的顺序
     * （注记图层恒在列表末尾，插入位置必然在其之前）。源图层未加载时退化为追加到末尾。
     */
    private fun insertAboveSourceLayer(layer: RenderableLayer, sourcePath: String) {
        // 同一路径理论上只加载一份，防御性取最后一个匹配项
        val index = worldWindow.engine.layers.toList().indexOfLast {
            it.getUserProperty(LocalVectorLoader.LAYER_PATH_KEY) == sourcePath
        }
        if (index >= 0) {
            worldWindow.engine.layers.addLayer(index + 1, layer)
        } else {
            worldWindow.engine.layers.addLayer(layer)
        }
    }

    /** 命中要素的 WGS84 四至，无有效坐标返回 null */
    private fun extentOf(features: List<VectorFeature>): DoubleArray? {
        var minLon = Double.MAX_VALUE
        var minLat = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE

        fun accumulate(lon: Double, lat: Double) {
            if (lon.isNaN() || lat.isNaN()) return
            if (lon < minLon) minLon = lon
            if (lat < minLat) minLat = lat
            if (lon > maxLon) maxLon = lon
            if (lat > maxLat) maxLat = lat
        }

        fun accumulateFlat(flat: DoubleArray?) {
            if (flat == null) return
            var i = 0
            while (i + 1 < flat.size) {
                accumulate(flat[i], flat[i + 1])
                i += 2
            }
        }

        for (f in features) {
            when (f.geom) {
                "POINT" -> accumulate(f.x, f.y)
                "LINE" -> f.parts?.forEach { accumulateFlat(it) }
                "POLYGON" -> {
                    accumulateFlat(f.outer)
                    f.holes?.forEach { accumulateFlat(it) }
                }
            }
        }
        if (minLon > maxLon || minLat > maxLat) return null
        return doubleArrayOf(minLon, minLat, maxLon, maxLat)
    }

    companion object {
        private const val TAG = "VectorQueryModel"

        /** 结果图层名称（图层列表不展示，仅用于调试与日志辨识） */
        private const val QUERY_LAYER_NAME = "SQL 查询结果"
    }
}
