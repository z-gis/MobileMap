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
import com.zys.mobilemap.location.NavigatorModel
import com.zys.mobilemap.raster.GdalRasterLayer
import com.zys.mobilemap.util.UiUtil
import com.zys.mobilemap.vector.LocalVectorLoader
import com.zys.mobilemap.vector.VectorReloadConfig
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.Layer
import earth.worldwind.navigator.NavigatorEvent
import earth.worldwind.navigator.NavigatorListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
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

    /** 已加载的矢量图层：key 为图层缓存键（路径|样式版本|shp 读取引擎，见 [layerKey]），
     *  一个矢量文件可能产出多个图层（如 KML 文件夹） */
    private val vectorLayerMap = mutableMapOf<String, MutableList<Layer>>()

    /** 正在异步加载中的矢量图层路径，防重复加载 */
    private val vectorLoadingKeys = mutableSetOf<String>()

    /** 已加载 GDAL 矢量图层的上次加载屏幕四至（WGS84），
     *  相机静止后与新四至对比，变化显著则触发动态重载，保证平移/缩放后要素不缺失 */
    private val vectorLoadedExtents = mutableMapOf<String, DoubleArray>()

    /** Phase 1 Swap-on-ready：重载时旧图层不立即从 wwd 移除，暂存于此保持可见，
     *  待新图层加载完成后一帧内 add new + remove old，消除视觉空窗。
     *  key 与 [vectorLayerMap] 一致（layerKey）。仅 [VectorReloadConfig.SWAP_ON_READY] = true 时使用。 */
    private val pendingSwapOutLayers = mutableMapOf<String, List<Layer>>()

    /** 相机静止后触发矢量图层动态重载的节流任务（每次导航事件重置计时） */
    private var reloadJob: Job? = null

    /** 相机变化监听：按当前级别翻转已加载矢量图层 isEnabled，
     *  限幅相机高度下限防止超级别放大致瓦片撕裂/黑屏与中心漂移，
     *  并节流触发 GDAL 路径矢量图层的动态重载（相机静止后按新屏幕四至重新过滤要素） */
    private val levelVisibilityListener = object : NavigatorListener {
        override fun onNavigatorEvent(wwd: WorldWindow, event: NavigatorEvent) {
            clampCameraAltitude()
            applyLevelVisibility()
            scheduleVectorReload()
        }
    }

    /**
     * 限幅相机高度下限：手势/按键放大到 [NavigatorModel.MIN_CAMERA_ALTITUDE]（对应 20 级）后不再拉近。
     * 底图图源数据最大级别多为 18，超出后末级瓦片纹理拉伸，相机高度趋近 0 时拉伸过度致撕裂乃至无瓦片可绘（黑屏），
     * 且从黑屏状态缩小恢复时相机中心会漂移。高度已被限幅时不重复设置，避免导航事件递归。
     */
    private fun clampCameraAltitude() {
        val camera = wwd.engine.camera
        if (camera.position.altitude < NavigatorModel.MIN_CAMERA_ALTITUDE) {
            camera.position.altitude = NavigatorModel.MIN_CAMERA_ALTITUDE
            wwd.requestRedraw()
        }
    }

    fun getWorldWindow() = wwd

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全屏显示，沉浸式体验
        UiUtil.enableFullscreen(this)

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
        // 文档变更（含图层管理中修改级别可见性）后，按当前级别重新应用矢量图层可见性
        applyLevelVisibility()
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
                        imageFormat = imageFormatOf(src),
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
                    imageFormat = imageFormatOf(src),
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
     * 地图源瓦片图片格式：未声明或为空时回退 [DEFAULT_IMAGE_FORMAT]（底图与注记图层共用）。
     */
    private fun imageFormatOf(src: MapSource): String =
        src.imageFormat?.takeIf { it.isNotEmpty() } ?: DEFAULT_IMAGE_FORMAT

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
     * 矢量图层缓存键：路径|样式版本|shp 读取引擎|KML/KMZ 标注开关。样式修改递增版本号后旧键失效，
     * 已加载图层被移除并按新样式重载（与可见性增删同构，无需另建通知）；
     * 引擎纳入键后，设置中切换 GDAL / WorldWind 原生同样触发 shp 图层按新引擎重载；
     * KML/KMZ 标注开关纳入键后，切换标注显示同样触发 KML/KMZ 图层按新标注设置重载。
     */
    private fun layerKey(info: LayerInfo): String {
        val base = (info.path ?: "") + "|" + info.styleVersion
        val path = info.path ?: return base
        val cfg = DocumentManager.getInstance().getDocument()?.systemConfig
        var key = base
        // shp 读取引擎纳入键：切换 GDAL / WorldWind 原生触发 shp 图层按新引擎重载
        if (path.endsWith(".shp", ignoreCase = true)) {
            key += "|" + if (cfg?.shpUseGdal ?: true) "gdal" else "native"
        }
        // KML/KMZ 标注开关纳入键：切换后经文档变更通知触发 KML/KMZ 图层按新标注设置重载
        if (path.endsWith(".kml", ignoreCase = true) || path.endsWith(".kmz", ignoreCase = true)) {
            key += "|" + if (cfg?.showKmlLabel ?: false) "kmlLabel" else "noKmlLabel"
        }
        return key
    }

    /**
     * 栅格图层同步：按文档中可见的栅格图层（TIF）增删。
     * 图层构造时经 JNI 解析栅格四至并建立瓦片金字塔，解析失败仅记录日志跳过。
     */
    private fun syncRasterLayers(doc: MapDocument) {
        val targetKeys = doc.rasterLayers
            .filter { it.visible }
            .mapNotNull { it.path?.takeIf { p -> p.isNotEmpty() } }
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
                vectorLoadedExtents.remove(key)
                // Phase 1 Swap-on-ready：文档已删除该图层，暂存的旧图层也需一并移除防幽灵
                pendingSwapOutLayers.remove(key)?.forEach { wwd.engine.layers.removeLayer(it) }
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
                        // 记录本次加载使用的屏幕四至，供相机变化后判断是否需要动态重载
                        vectorLoadedExtents[key] = extent
                        // 首次加载写入默认级别可见性；用户值致要素过多则回退自动值
                        var docChanged = ensureAutoMinDisplayLevel(info)
                        if (applyFeatureCountFallback(info, layers)) docChanged = true
                        if (docChanged) DocumentManager.getInstance().save(this@BaseMapActivity)
                        applyLevelVisibility()
                        moveAnnotationLayerToTop()
                        wwd.requestRedraw()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "矢量图层加载失败[${info.name}]: $key", e)
                    // 优先展示底层具体原因（无坐标系 CAD 提示补 .prj、DWG 版本不受支持提示转换等），
                    // 无 message 时回退通用文案
                    val tip = e.message?.takeIf { it.isNotBlank() } ?: "矢量图层加载失败: ${info.name}"
                    runOnUiThread {
                        Toast.makeText(this@BaseMapActivity, tip, Toast.LENGTH_LONG).show()
                    }
                    // 加载失败：从文档移除该图层并保存，避免图层列表残留无法显示的条目
                    // （导入时先加入文档再异步加载，失败即回退，对齐“打开失败即从列表删除”）
                    val doc = DocumentManager.getInstance().getDocument()
                    if (doc != null && doc.vectorLayers.removeAll { layerKey(it) == key }) {
                        DocumentManager.getInstance().save(this@BaseMapActivity)
                    }
                    // Phase 1 Swap-on-ready：加载失败，暂存的旧图层也需移除，防止幽灵图层
                    pendingSwapOutLayers.remove(key)?.forEach { wwd.engine.layers.removeLayer(it) }
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
        // Phase 1 Swap-on-ready：新图层已入图，同帧移除暂存的旧图层，实现无缝替换
        if (VectorReloadConfig.SWAP_ON_READY) {
            pendingSwapOutLayers.remove(key)?.forEach { layerList.removeLayer(it) }
        }
    }

    /**
     * 计算当前屏幕中心点及周边缓冲的 WGS84 四至 [minLon, minLat, maxLon, maxLat]，
     * 供 GDAL 矢量读取做空间过滤。缓冲取相机高度等值换算成经纬度跨度（×1.0），
     * 配合动态重载机制无需过大缓冲，保证要素数可控且不触发截断。
     */
    private fun computeVisibleExtent(): DoubleArray {
        val pos = wwd.engine.camera.position
        val lat = pos.latitude.inDegrees
        val lon = pos.longitude.inDegrees
        // Phase 3：预加载缓冲系数（1.0=无缓冲；1.5=缓冲 50% 减少小幅平移重载频率）
        val factor = VectorReloadConfig.EXTENT_BUFFER_FACTOR
        val dLat = (pos.altitude / 111_320.0 * factor).coerceAtLeast(0.005)
        val dLon = dLat / cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        return doubleArrayOf(
            (lon - dLon).coerceAtLeast(-180.0),
            (lat - dLat).coerceAtLeast(-90.0),
            (lon + dLon).coerceAtMost(180.0),
            (lat + dLat).coerceAtMost(90.0)
        )
    }

    /**
     * 首次加载矢量图层时写入默认最小显示级别（固定 [DEFAULT_AUTO_MIN_DISPLAY_LEVEL]），
     * 不再按四至宽高自动计算——实测计算值与用户直观感受偏差较大，改为统一默认值，
     * 用户如需精细控制可在图层管理手动设置 [LayerInfo.userMinDisplayLevel]。
     * 返回是否发生变更（需落盘）。
     */
    private fun ensureAutoMinDisplayLevel(info: LayerInfo): Boolean {
        if (info.autoMinDisplayLevel == DEFAULT_AUTO_MIN_DISPLAY_LEVEL) return false
        info.autoMinDisplayLevel = DEFAULT_AUTO_MIN_DISPLAY_LEVEL
        return true
    }

    /**
     * 级别可见性用户值回退校验：生效值来自用户（[LayerInfo.userMinDisplayLevel] ≥ 0）
     * 且本次读取要素数 > [FEATURE_FALLBACK_LIMIT] 时，判定用户级别设得过低（整层入屏要素过多），
     * 复位 userMinDisplayLevel = -1 回退自动值。返回是否变更（需落盘）。
     */
    private fun applyFeatureCountFallback(info: LayerInfo, layers: List<Layer>): Boolean {
        if (info.userMinDisplayLevel < 0) return false
        val count = layers.sumOf { (it.getUserProperty(LocalVectorLoader.FEATURE_COUNT_KEY) as? Int) ?: 0 }
        if (count > FEATURE_FALLBACK_LIMIT) {
            Log.i(TAG, "级别可见性用户值致要素过多($count)，回退自动值: ${info.name}")
            info.userMinDisplayLevel = -1
            return true
        }
        return false
    }

    /**
     * 按当前相机级别更新各已加载矢量图层 [Layer.isEnabled]：
     * 当前级别 < 生效最小显示级别（[LayerInfo.effectiveMinDisplayLevel]）时隐藏，否则显示。
     * 只翻转 isEnabled，不重载、不重建渲染对象，开销极小；由相机导航事件驱动。
     */
    private fun applyLevelVisibility() {
        val doc = DocumentManager.getInstance().getDocument() ?: return
        if (vectorLayerMap.isEmpty()) return
        val level = NavigatorModel.altitudeToLevel(wwd.engine.camera.position.altitude)
        var changed = false
        for (info in doc.vectorLayers) {
            val layers = vectorLayerMap[layerKey(info)] ?: continue
            val minLevel = info.effectiveMinDisplayLevel
            val enabled = minLevel < 0 || level >= minLevel
            for (l in layers) {
                if (l.isEnabled != enabled) {
                    l.isEnabled = enabled
                    changed = true
                }
            }
        }
        if (changed) wwd.requestRedraw()
    }

    /**
     * 相机静止 [RELOAD_DEBOUNCE_MS] 后触发矢量图层动态重载检查：
     * 只对走 GDAL 且已启用空间过滤的图层（图层带 [LocalVectorLoader.FEATURE_COUNT_KEY] 标记）判断，
     * 若新屏幕四至与上次加载四至的中心位移超过新框一半或宽度变化超 2 倍，
     * 则移除旧图层并按新四至重新加载，保证平移/缩放后要素不缺失。
     * kml 小文件原生与 shp 原生路径为整文读入，无空间过滤，不参与重载；
     * 小数据（要素总数 <5000）经 GDAL 全量渲染的图层（含 kml/kmz/dwg/dxf）带 FEATURE_DIRECT_KEY 标记，同样不参与重载。
     */
    private fun scheduleVectorReload() {
        reloadJob?.cancel()
        reloadJob = lifecycleScope.launch {
            delay(RELOAD_DEBOUNCE_MS)
            checkAndReloadVectorLayers()
        }
    }

    private fun checkAndReloadVectorLayers() {
        val doc = DocumentManager.getInstance().getDocument() ?: return
        if (vectorLayerMap.isEmpty()) return
        val newExtent = computeVisibleExtent()
        val keysToReload = mutableListOf<String>()
        for ((key, layers) in vectorLayerMap) {
            // 加载中的图层不重载，避免并发交叉
            if (key in vectorLoadingKeys) continue
            // 仅 GDAL 路径参与重载（图层带 FEATURE_COUNT_KEY 标记，包含空图层）
            val isGdal = layers.any { it.getUserProperty(LocalVectorLoader.FEATURE_COUNT_KEY) != null }
            if (!isGdal) continue
            // 小数据集全量直接渲染（0.2.8，含 shp/gpkg/kml/kmz/dwg/dxf）：已全量加载，不参与相机变化后的四至重载
            val isDirect = layers.any { it.getUserProperty(LocalVectorLoader.FEATURE_DIRECT_KEY) == true }
            if (isDirect) continue
            // 当前隐藏的图层（级别不够）不重载，避免浪费 CPU；重新可见后下一次相机事件触发重载
            if (layers.none { it.isEnabled }) continue
            val lastExtent = vectorLoadedExtents[key] ?: continue
            if (extentMovedSignificantly(lastExtent, newExtent)) {
                keysToReload.add(key)
            }
        }
        if (keysToReload.isEmpty()) return
        Log.i(TAG, "相机移动显著，重载 ${keysToReload.size} 个 GDAL 矢量图层 swapOnReady=${VectorReloadConfig.SWAP_ON_READY}")
        for (key in keysToReload) {
            val oldLayers = vectorLayerMap.remove(key)
            if (VectorReloadConfig.SWAP_ON_READY) {
                // Swap-on-ready：旧图层保留在 wwd 中可见，仅移出 vectorLayerMap 让 syncUserLayers 重新加载；
                // 上一次 swap 未完成又触发新的重载时，先真正移除上次的旧图层（防堆积）
                pendingSwapOutLayers.remove(key)?.forEach { wwd.engine.layers.removeLayer(it) }
                if (oldLayers != null) pendingSwapOutLayers[key] = oldLayers
            } else {
                // 旧行为：立即移除，会有 3-5s 视觉空窗
                oldLayers?.forEach { wwd.engine.layers.removeLayer(it) }
            }
            vectorLoadedExtents.remove(key)
        }
        // 重新进入同步流程，syncUserLayers 将按新 extent 异步加载被移除的图层
        syncUserLayers(doc)
    }

    /**
     * 判断新旧屏幕四至差异是否显著（需触发重载）：
     * 中心位移超过新框一半（平移），或新框宽度是旧框的 2 倍以上/一半以下（缩放）。
     */
    private fun extentMovedSignificantly(old: DoubleArray, new: DoubleArray): Boolean {
        val oldWidth = old[2] - old[0]
        val newWidth = new[2] - new[0]
        val newHeight = new[3] - new[1]
        val movedLon = abs((new[0] + new[2]) / 2 - (old[0] + old[2]) / 2)
        val movedLat = abs((new[1] + new[3]) / 2 - (old[1] + old[3]) / 2)
        if (movedLon > newWidth * 0.5 || movedLat > newHeight * 0.5) return true
        if (oldWidth > 0 && (newWidth > oldWidth * 2.0 || newWidth < oldWidth * 0.5)) return true
        return false
    }

    // JNI 符号为 Java_com_zys_mobilemap_ui_activity_BaseMapActivity_nativeGetLayerExtent，
    // 与 MainActivity/LayerManageDialog 共用 C++ computeLayerExtent（不能放入 companion object）
    protected external fun nativeGetLayerExtent(path: String): DoubleArray?

    /**
     * 创建 WorldWindow 并加入背景层。
     */
    protected fun createWorldWindow(): WorldWindow {
        wwd = MapWorldWindow(this)
        wwd.engine.layers.addLayer(BackgroundLayer())
        // 注册相机监听：按级别翻转矢量图层可见性 + 限幅相机高度 + 节流触发 GDAL 图层动态重载
        wwd.navigatorEvents.addNavigatorListener(levelVisibilityListener)

        // 恢复文档中保存的地图位置（文档在 Splash 阶段已加载）
        restoreCameraState()

        return wwd
    }

    /**
     * 恢复渲染线程。
     */
    override fun onResume() {
        super.onResume()
        wwd.onResume()
    }

    /**
     * 从文档恢复相机状态。
     */
    private fun restoreCameraState() {
        val mapDoc = DocumentManager.getInstance().getDocument() ?: return
        // 三个位置分量任一未记录即视为无有效视角，避免把相机设到 NaN 坐标
        if (mapDoc.camLatitude.isNaN() || mapDoc.camLongitude.isNaN() || mapDoc.camAltitude.isNaN()) return

        // 用 getOrNull 兜底：document.json 可能被手改出越界序号，直接 entries[i] 会抛 IndexOutOfBounds
        val altMode = AltitudeMode.entries.getOrNull(mapDoc.camAltitudeMode) ?: AltitudeMode.ABSOLUTE

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
     * 暂停渲染线程并保存相机状态。
     */
    override fun onPause() {
        super.onPause()
        saveCameraState()
        wwd.onPause()
    }

    /**
     * 把当前相机状态写回文档（含高度模式序号）并落盘。
     */
    private fun saveCameraState() {
        val camera = wwd.engine.camera
        val doc = DocumentManager.getInstance().getDocument() ?: return
        doc.camLatitude = camera.position.latitude.inDegrees
        doc.camLongitude = camera.position.longitude.inDegrees
        doc.camAltitude = camera.position.altitude
        doc.camHeading = camera.heading.inDegrees
        doc.camTilt = camera.tilt.inDegrees
        doc.camRoll = camera.roll.inDegrees
        doc.camAltitudeMode = camera.altitudeMode.ordinal

        DocumentManager.getInstance().save(applicationContext)
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

        /** 瓦片图片格式缺省值：地图源未声明格式时按 PNG 解码 */
        private const val DEFAULT_IMAGE_FORMAT = "image/png"

        /** 矢量图层默认最小显示级别：低于此级别隐藏图层（整层入屏要素密集成片无辨识意义）。
         *  直接固定为 5，不再按四至计算——实测计算值与用户直观感受偏差较大。 */
        private const val DEFAULT_AUTO_MIN_DISPLAY_LEVEL = 5

        /** 用户指定级别生效时，过滤要素数超过此值判定不合理，回退自动值 */
        private const val FEATURE_FALLBACK_LIMIT = 5000

        /** 相机静止多久后触发矢量图层动态重载检查（毫秒），避免手势过程中频繁重载 */
        private const val RELOAD_DEBOUNCE_MS = 500L
    }
}
