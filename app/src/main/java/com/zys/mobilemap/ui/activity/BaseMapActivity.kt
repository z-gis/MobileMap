package com.zys.mobilemap.ui.activity

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zys.mobilemap.cache.TileCacheManager
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.doc.LayerInfo
import com.zys.mobilemap.doc.MapDocument
import com.zys.mobilemap.doc.MapSource
import com.zys.mobilemap.raster.GdalRasterLayer
import com.zys.mobilemap.util.util
import com.zys.mobilemap.vector.LocalVectorLoader
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.Layer
import kotlinx.coroutines.launch
import java.lang.Double
import kotlin.math.cos

open class BaseMapActivity : AppCompatActivity(), DocumentManager.DocumentChangeListener {


    private lateinit var wwd: WorldWindow

    /**
     * 底图图层：key 为地图源标识（[sourceKey]），全部地图源常驻，
     * 可见性由文档 [MapSource.visible] 驱动（[Layer.isEnabled]），切换底图仅翻转可见性。
     */
    private val baseMapLayers = mutableMapOf<String, Layer>()

    /** 注记图层：默认加载，渲染位于地图源最后 */
    private var annotationLayer: Layer? = null

    /** 注记图层当前地图源标识，用于检测地图源或 Token 是否变化 */
    private var annotationSourceKey: String? = null

    /** 已加载的栅格图层：key 为栅格文件路径，渲染位于底图之上、矢量之下 */
    private val rasterLayerMap = mutableMapOf<String, Layer>()

    /** 已加载的矢量图层：key 为图层缓存键（路径|样式版本），一个矢量文件可能产出多个图层（如 KML 文件夹） */
    private val vectorLayerMap = mutableMapOf<String, MutableList<Layer>>()

    /** 正在异步加载中的矢量图层路径，防重复加载 */
    private val vectorLoadingKeys = mutableSetOf<String>()

    fun getWorldWindow() = wwd

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全屏显示，沉浸式体验
        util.enableFullscreen(this)

        super.onCreate(savedInstanceState)
    }

    override fun onDocumentChanged(document: MapDocument) {
        syncMapLayers(document)
    }

    /**
     * 按文档同步地图：底图地图源切换、矢量/栅格图层增删与可见性。
     */
    protected fun syncMapLayers(doc: MapDocument) {
        syncBaseMap(doc)
        syncRasterLayers(doc)
        syncUserLayers(doc)
        // 注记图层始终最后同步，保证渲染位于所有图层最上层（置顶）
        syncAnnotationLayer(doc)
        wwd.requestRedraw()
    }

    /**
     * 底图地图源同步：文档中每个非注记地图源对应一个常驻图层（惰性创建），
     * 可见性由 [MapSource.visible] 驱动；切换底图仅翻转 [Layer.isEnabled]，不重建。
     * 地图源删除或标识（网址/类型/Token）变化时，移除旧图层并释放其网络资源。
     */
    private fun syncBaseMap(doc: MapDocument) {
        val targetKeys = mutableSetOf<String>()
        for (src in doc.mapSources) {
            if (src.isAnnotation) continue
            val key = sourceKey(src)
            targetKeys.add(key)

            var layer = baseMapLayers[key]
            if (layer == null) {
                val url = resolveUrl(src)
                if (url.isNullOrEmpty()) {
                    Log.w(TAG, "地图源[${src.name}]网址为空，无法加载底图")
                    continue
                }
                try {
                    layer = TileCacheManager.createCachedLayer(
                        context = this,
                        urlTemplate = url,
                        name = src.name ?: "",
                        imageFormat = if (src.imageFormat.isNullOrEmpty()) "image/png" else src.imageFormat!!,
                        sourceMaxLevel = src.maxLevel
                    )
                    baseMapLayers[key] = layer
                    addBaseMapLayer(layer)
                } catch (e: Exception) {
                    Log.e(TAG, "创建底图图层失败: ${src.name}", e)
                    continue
                }
            }
            layer.isEnabled = src.visible
        }

        // 移除文档中已不存在（被删除或标识变化）的底图图层，释放网络资源
        val iterator = baseMapLayers.iterator()
        while (iterator.hasNext()) {
            val (key, layer) = iterator.next()
            if (key !in targetKeys) {
                wwd.engine.layers.removeLayer(layer)
                TileCacheManager.closeLayerSource(layer)
                iterator.remove()
            }
        }
    }

    /**
     * 底图图层集中插入到背景层之后（已有底图图层之后），
     * 保证渲染顺序：底图 -> 栅格 -> 矢量 -> 注记，不遮挡已加载的用户图层。
     */
    private fun addBaseMapLayer(layer: Layer) {
        val all = wwd.engine.layers.toList()
        var index = 0
        while (index < all.size && all[index] is BackgroundLayer) index++
        while (index < all.size && baseMapLayers.values.contains(all[index])) index++
        if (index >= all.size) wwd.engine.layers.addLayer(layer) else wwd.engine.layers.addLayer(index, layer)
    }

    /**
     * 注记图层同步：默认加载到 WorldWindow，可见性由主界面开关（[MapSource.visible]）控制，
     * 并始终移动到图层列表末尾，保证渲染位于地图源最后。
     */
    private fun syncAnnotationLayer(doc: MapDocument) {
        val src = doc.mapSources.firstOrNull { it.isAnnotation }
        if (src == null) {
            removeAnnotationLayer()
            return
        }

        // Token 共用：Token 变化时重建注记图层（含主界面之外的修改入口）
        val key = sourceKey(src)
        if (annotationLayer != null && key != annotationSourceKey) {
            removeAnnotationLayer()
        }

        if (annotationLayer == null) {
            val url = resolveUrl(src)
            if (url.isNullOrEmpty()) {
                Log.w(TAG, "地图源[${src.name}]网址为空，无法加载注记图层")
                return
            }
            try {
                annotationLayer = TileCacheManager.createCachedLayer(
                    context = this,
                    urlTemplate = url,
                    name = src.name ?: "",
                    imageFormat = if (src.imageFormat.isNullOrEmpty()) "image/png" else src.imageFormat!!,
                    sourceMaxLevel = src.maxLevel
                )
                annotationSourceKey = key
            } catch (e: Exception) {
                Log.e(TAG, "创建注记图层失败: ${src.name}", e)
                return
            }
        }

        annotationLayer?.let {
            it.isEnabled = src.visible
            // 移动到图层列表末尾，渲染位于地图源最后（置顶）
            moveAnnotationLayerToTop()
        }
    }

    /**
     * 将注记图层移动到图层列表末尾，保证渲染位于所有图层最上层。
     */
    private fun moveAnnotationLayerToTop() {
        annotationLayer?.let {
            wwd.engine.layers.removeLayer(it)
            wwd.engine.layers.addLayer(it)
        }
    }

    private fun removeAnnotationLayer() {
        annotationLayer?.let {
            wwd.engine.layers.removeLayer(it)
            TileCacheManager.closeLayerSource(it)
        }
        annotationLayer = null
        annotationSourceKey = null
    }

    /**
     * 地图源标识：名称|网址|类型|Token|最大级别，任一变化时触发图层重建。
     */
    private fun sourceKey(src: MapSource): String =
        (src.name ?: "") + "|" + (src.url ?: "") + "|" + (src.imageFormat ?: "") +
                "|" + (src.token1 ?: "") + "|" + (src.token2 ?: "") + "|" + src.maxLevel

    /**
     * 将 URL 中的 Token 占位符替换为实际 Token。
     */
    private fun resolveUrl(src: MapSource): String? {
        val url = src.url
        if (url.isNullOrEmpty()) return null
        var result = url
        val token2 = src.token2
        if (!token2.isNullOrEmpty()) {
            result = result.replace(MapSource.TOKEN2_PLACEHOLDER, token2)
        }
        val token1 = src.token1
        if (!token1.isNullOrEmpty()) {
            result = result.replace(MapSource.TOKEN1_PLACEHOLDER, token1)
        }
        return result
    }

    /**
     * 矢量图层缓存键：路径|样式版本。样式修改递增版本号后旧键失效，
     * 已加载图层被移除并按新样式重载（与可见性增删同构，无需另建通知）。
     */
    private fun layerKey(info: LayerInfo): String = (info.path ?: "") + "|" + info.styleVersion

    /**
     * 栅格图层同步：按文档中可见的栅格图层（TIF）增删。
     * 图层构造时经 JNI 解析栅格四至并建立瓦片金字塔，解析失败仅记录日志跳过。
     */
    private fun syncRasterLayers(doc: MapDocument) {
        val targetKeys = doc.rasterLayers
            .filter { it.visible && !it.path.isNullOrEmpty() }
            .map { it.path!! }
            .toSet()

        // 移除不再是目标的栅格图层（删除或置隐）
        val iterator = rasterLayerMap.iterator()
        while (iterator.hasNext()) {
            val (key, layer) = iterator.next()
            if (key !in targetKeys) {
                wwd.engine.layers.removeLayer(layer)
                iterator.remove()
            }
        }

        // 新增目标栅格图层（已加载的跳过）
        for (info in doc.rasterLayers) {
            val path = info.path
            if (!info.visible || path.isNullOrEmpty()) continue
            if (rasterLayerMap.containsKey(path)) continue
            try {
                val layer = GdalRasterLayer(this, path, wwd)
                layer.isEnabled = true
                insertRasterLayer(layer)
                rasterLayerMap[path] = layer
            } catch (e: Exception) {
                Log.e(TAG, "栅格图层加载失败[${info.name}]: $path", e)
                Toast.makeText(this, "栅格图层加载失败: ${info.name}", Toast.LENGTH_SHORT).show()
                // 加载失败：从文档移除该栅格图层并保存，避免图层列表残留无法显示的条目
                if (doc.rasterLayers.removeAll { it.path == path }) {
                    DocumentManager.getInstance().save(this)
                }
            }
        }
    }

    /**
     * 栅格图层插入位置：背景层与底图图层之后、已有栅格图层之后（矢量之下），
     * 保证渲染顺序：底图 -> 栅格 -> 矢量 -> 注记。
     */
    private fun insertRasterLayer(layer: Layer) {
        val all = wwd.engine.layers.toList()
        var index = 0
        while (index < all.size
            && (all[index] is BackgroundLayer
                    || all[index] in baseMapLayers.values
                    || all[index] in rasterLayerMap.values)
        ) index++
        if (index >= all.size) wwd.engine.layers.addLayer(layer) else wwd.engine.layers.addLayer(index, layer)
    }

    /**
     * 矢量图层同步：按文档中可见的矢量图层增删。
     * 加载为异步（大文件解析耗时），完成后校验图层仍为目标且可见才加入地图；
     * 加入后重新移动注记图层到末尾，保证注记渲染置顶。
     */
    private fun syncUserLayers(doc: MapDocument) {
        val targetKeys = doc.vectorLayers
            .filter { it.visible && !it.path.isNullOrEmpty() }
            .map { layerKey(it) }
            .toSet()

        // 移除不再是目标的已加载图层
        val iterator = vectorLayerMap.iterator()
        while (iterator.hasNext()) {
            val (key, layers) = iterator.next()
            if (key !in targetKeys) {
                layers.forEach { wwd.engine.layers.removeLayer(it) }
                iterator.remove()
                vectorLoadingKeys.remove(key)
            }
        }

        // 新增目标异步加载（已加载或加载中的跳过）；
        // GDAL 路径按当前屏幕中心点四至空间过滤，仅读取屏幕中心要素及相邻要素，防大文件 OOM
        val extent = computeVisibleExtent()
        for (info in doc.vectorLayers) {
            val key = layerKey(info)
            if (!info.visible || info.path.isNullOrEmpty()) continue
            if (vectorLayerMap.containsKey(key) || key in vectorLoadingKeys) continue
            vectorLoadingKeys.add(key)
            lifecycleScope.launch {
                try {
                    val layers = LocalVectorLoader.loadLayers(this@BaseMapActivity, info, extent)
                    // 加载完成后校验图层仍在文档中且可见（异步期间可能已被移除/置隐/样式版本变化）
                    val stillTarget = DocumentManager.getInstance().getDocument()
                        ?.vectorLayers?.any { layerKey(it) == key && it.visible } == true
                    if (stillTarget) {
                        insertVectorLayers(key, layers)
                        moveAnnotationLayerToTop()
                        wwd.requestRedraw()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "矢量图层加载失败[${info.name}]: $key", e)
                    runOnUiThread {
                        Toast.makeText(this@BaseMapActivity, "矢量图层加载失败: ${info.name}", Toast.LENGTH_SHORT).show()
                    }
                    // 加载失败：从文档移除该图层并保存，避免图层列表残留无法显示的条目
                    // （导入时先加入文档再异步加载，失败即回退，对齐“打开失败即从列表删除”）
                    val doc = DocumentManager.getInstance().getDocument()
                    if (doc != null && doc.vectorLayers.removeAll { layerKey(it) == key }) {
                        DocumentManager.getInstance().save(this@BaseMapActivity)
                    }
                } finally {
                    vectorLoadingKeys.remove(key)
                }
            }
        }
    }

    /**
     * 按渲染顺序插入矢量图层：位于底图（及后续栅格图层）之上，
     * 矢量之间按文档加载顺序排列（异步加载完成顺序不定，需按文档顺序定位）。
     */
    private fun insertVectorLayers(key: String, newLayers: List<Layer>) {
        val doc = DocumentManager.getInstance().getDocument()
        val layerList = wwd.engine.layers
        val all = layerList.toList()

        // 起始位置：跳过背景层、全部底图图层与栅格图层（栅格位于矢量之下）
        var index = 0
        while (index < all.size
            && (all[index] is BackgroundLayer
                    || all[index] in baseMapLayers.values
                    || all[index] in rasterLayerMap.values)
        ) index++

        // 按文档顺序排到已加载的前序矢量图层之后，保证矢量按加载顺序渲染
        // （缓存键含样式版本，同一路径可能有多个版本键，均计入排序）
        if (doc != null) {
            for (info in doc.vectorLayers) {
                val path = info.path ?: continue
                if (key.startsWith("$path|")) break
                val loaded = vectorLayerMap.entries
                    .filter { it.key.startsWith("$path|") }
                    .flatMap { it.value }
                for (layer in loaded) {
                    val i = all.indexOf(layer)
                    if (i >= index) index = i + 1
                }
            }
        }

        val startSize = all.size
        newLayers.forEachIndexed { i, layer ->
            val pos = index + i
            if (pos >= startSize + i) layerList.addLayer(layer) else layerList.addLayer(pos, layer)
        }
        vectorLayerMap[key] = newLayers.toMutableList()
    }

    /**
     * 计算当前屏幕中心点及周边缓冲的 WGS84 四至 [minLon, minLat, maxLon, maxLat]，
     * 供 GDAL 矢量读取做空间过滤。缓冲取相机高度的 3 倍换算成经纬度跨度，
     * 覆盖当前视野及相邻区域要素。
     */
    private fun computeVisibleExtent(): DoubleArray {
        val pos = wwd.engine.camera.position
        val lat = pos.latitude.inDegrees
        val lon = pos.longitude.inDegrees
        val dLat = (pos.altitude * 3.0 / 111_320.0).coerceAtLeast(0.01)
        val dLon = dLat / cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        return doubleArrayOf(
            (lon - dLon).coerceAtLeast(-180.0),
            (lat - dLat).coerceAtLeast(-90.0),
            (lon + dLon).coerceAtMost(180.0),
            (lat + dLat).coerceAtMost(90.0)
        )
    }

    /**
     * Create a WorldWindow and add the background layer.
     */
    protected fun createWorldWindow(): WorldWindow {
        wwd = WorldWindow(this)

        wwd.engine.layers.apply {
            addLayer(BackgroundLayer())
        }

        // 恢复文档中保存的地图位置（文档在 Splash 阶段已加载）
        restoreCameraState()

        return wwd
    }

    /**
     * Resumes the rendering thread.
     */
    override fun onResume() {
        super.onResume()
        wwd.onResume()
    }

    /**
     * Restores the camera state from the document.
     */
    private fun restoreCameraState() {
        val mapDoc = DocumentManager.getInstance().getDocument()
        if (mapDoc == null) return
        if (Double.isNaN(mapDoc.camLatitude) ||
            Double.isNaN(mapDoc.camLongitude) ||
            Double.isNaN(mapDoc.camAltitude)
        )
            return

        val altMode = if (mapDoc.camAltitudeMode >= 0) AltitudeMode.entries[mapDoc.camAltitudeMode] else AltitudeMode.ABSOLUTE

        val camera = wwd.engine.camera
        camera.set(
            Angle.fromDegrees(mapDoc.camLatitude),
            Angle.fromDegrees(mapDoc.camLongitude),
            mapDoc.camAltitude,
            altMode,
            Angle.fromDegrees(mapDoc.camHeading),
            Angle.fromDegrees(mapDoc.camTilt), Angle.fromDegrees(mapDoc.camRoll)
        )
        wwd.requestRedraw()
    }

    /**
     * Pauses the rendering thread and saves the camera state.
     */
    override fun onPause() {
        super.onPause()
        saveCameraState()
        // pauses the rendering thread
        this.wwd.onPause()
    }

    /**
     * Saves the camera state to the document.
     */
    private fun saveCameraState() {
        val camera = wwd.engine.camera
        val doc = DocumentManager.getInstance().getDocument()
        if (doc != null) {
            doc.camLatitude = camera.position.latitude.inDegrees
            doc.camLongitude = camera.position.longitude.inDegrees
            doc.camAltitude = camera.position.altitude
            doc.camHeading = camera.heading.inDegrees
            doc.camTilt = camera.tilt.inDegrees
            doc.camRoll = camera.roll.inDegrees
            doc.camAltitudeMode = camera.altitudeMode.ordinal

            DocumentManager.getInstance().save(applicationContext)
        }
    }

    /**
     * onDestroy
     */
    override fun onDestroy() {
        super.onDestroy()
        DocumentManager.getInstance().unregisterDocumentChangeListener(this)
    }



    companion object {
        private const val TAG = "BaseMapActivity"
    }
}
