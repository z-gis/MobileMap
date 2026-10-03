package com.zys.mobilemap.ui.map

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.doc.LayerInfo
import com.zys.mobilemap.doc.MapDocument
import com.zys.mobilemap.doc.MapSource
import com.zys.mobilemap.util.AppDirectories
import com.zys.mobilemap.util.AppLog
import com.zys.mobilemap.vector.FeatureIcons
import com.zys.mobilemap.vector.GdalVectorReader
import com.zys.globecore.NativeLayerInfo
import com.zys.globecore.NativeMapView
import com.zys.globecore.VectorExtent
import com.zys.globecore.VectorStyle as NativeVectorStyle
import java.io.File
import kotlin.math.abs

/**
 * 图层栈管理（自 MainActivity 拆出）：瓦片底图/注记 + 矢量 + 栅格三类图层的常驻装配、可见性驱动、
 * 懒加入同步与结构签名判定（对齐原主界面 BaseMapActivity 的文档驱动）。
 *
 * 依赖单向：构造注入 [mapView]、注记开关按钮（图标刷新）、大数据矢量加载提示胶囊（可空，见 [notifyVectorLoadStarted]）
 * 与宿主 [Activity]（Toast/缓存目录）。
 * [onDocumentChanged] 只做「是否需重建」判定与非结构变化时的就地同步，真正的 recreate/pending/dismiss
 * 编排留在宿主（涉及 FragmentManager/对话框）。矢量层索引经 [layerInfoByIndex]/[nativeIndexOf] 供拾取回查。
 * 颜色/图标解析统一委托 [MapStyleColors]。
 */
internal class MapLayerManager(
    private val activity: Activity,
    private val mapView: NativeMapView,
    private val annotationButton: ImageView,
    private val vectorLoadingPill: View? = null,
    private val onRebuildOverlays: () -> Unit
) {

    /**
     * 常驻底图源（按文档顺序的非注记图源）：索引与 native 图层栈 0..N-1 对齐。
     * 切换底图仅翻 [NativeMapView.setLayerVisible]（对齐原主界面「可见性驱动」），无需重建 native 图层。
     */
    private val baseLayers = mutableListOf<MapSource>()

    /** 注记图层在 native 渲染栈中的索引（= 底图源数量）；-1 表示无注记源 */
    private var annotationLayerIndex = -1

    /** 注记图层当前可见性（与文档 [MapSource.visible] 同步） */
    private var annotationVisible = false

    /** 构建图层栈时的图源结构签名（各图源 [sourceKey] 有序拼接）；文档结构变化（增/删/改图源）时据此判定需重建 */
    private var builtSignature = ""

    /**
     * 已加入 native 的矢量图层：[vectorKey] → native 矢量层索引（add-only，与瓦片层同口径——隐层不删除只翻可见性）。
     * 采用「懒加入」：仅可见层加入 native（不可见层不触发 GDAL 读取），首次转可见时再加入并记录索引。
     */
    private val vectorLayerIndices = mutableMapOf<String, Int>()

    /** 构建矢量层时的结构签名（各矢量层 path#styleVersion 有序拼接）；结构或样式版本变化则重建界面（native 无删除/重设样式能力） */
    private var builtVectorSignature = ""

    /**
     * 提供当前相机可见的 WGS84 屏幕范围（宿主接线到 [CameraNavigator.computeVisibleExtent]）：
     * 加入大数据矢量层时取初始 extent，使首屏即按视口空间相交只加载屏内要素。返回 null 时退化为整文件全量。
     */
    var extentProvider: (() -> VectorExtent?)? = null

    /** 小数据全量层键集合（要素总数 < [DIRECT_RENDER_LIMIT]）：整文件一次渲染，不参与相机静止重载（避免平移时要素数跳变闪烁）。 */
    private val directVectorKeys = mutableSetOf<String>()

    /** 各重载层上次成功应用的屏幕范围（相机静止时 extent 无实质变化则去重，不重复触发 native 重载）。 */
    private val lastExtentByKey = mutableMapOf<String, VectorExtent>()

    /**
     * 屏外或级别不足而挂起的大数据矢量层键集合：可见但图层四至与屏幕范围不相交、或相机显示级别低于
     * 该层 effectiveMinDisplayLevel 的层暂不加入 native（免无谓的 GDAL 读取与加载胶囊误亮，
     * 整层跳过判定 native 虽廉价但开文件仍有成本），相机静止后若范围相交且级别达标再补加入（见 [onCameraSettled]）。
     */
    private val pendingViewportKeys = mutableSetOf<String>()

    /** 各矢量层数据四至缓存（WGS84）：加入/挂起判定时读取一次避免重复开文件；null=读取失败（不缓存，下次重试），四至未知时不挂起。 */
    private val dataExtentByKey = mutableMapOf<String, VectorExtent?>()

    // ── 矢量加载提示胶囊（大图首载/慢重载体感优化）：延迟显示 + 轮询隐藏，均经 View.postDelayed 挂在主线程 ──

    /** 轮询隐藏：胶囊显示后每 [VECTOR_LOADING_POLL_MS] 查询 native 加载/上传状态，完成即收起（覆盖同帧秒级 GL 上传窗口）。 */
    private val vectorLoadingPollRunnable = object : Runnable {
        override fun run() {
            val pill = vectorLoadingPill ?: return
            if (mapView.hasVectorLoading()) {
                pill.postDelayed(this, VECTOR_LOADING_POLL_MS)
            } else {
                pill.visibility = View.GONE
            }
        }
    }

    /** 延迟显示：加载启动 [VECTOR_LOADING_SHOW_DELAY_MS] 后仍 loading 才显示（快速加载全程 <500ms 永不闪现）。 */
    private val vectorLoadingShowRunnable = Runnable {
        val pill = vectorLoadingPill ?: return@Runnable
        if (mapView.hasVectorLoading()) {
            pill.visibility = View.VISIBLE
            pill.postDelayed(vectorLoadingPollRunnable, VECTOR_LOADING_POLL_MS)
        }
    }

    /**
     * 已加入 native 的栅格图层：[rasterKey] → native 图层索引（layers_ 空间，与瓦片底图/注记共享；add-only，隐层只翻可见性）。
     * 懒加入：仅可见层加入 native（不可见层不触发 GDAL 打开 + 重投影 VRT 构建），首次转可见时再加入并记录索引。
     */
    private val rasterLayerIndices = mutableMapOf<String, Int>()

    /** 构建栅格层时的结构签名（各栅格层 path 有序拼接）；结构变化（增/删/改栅格层）则重建界面（native 无删除图层能力） */
    private var builtRasterSignature = ""

    /**
     * 装配瓦片图层栈（对齐原主界面「可见性驱动」）：文档中全部非注记底图源常驻加入（索引 0..N-1），
     * 可见性按 [MapSource.visible] 逐个翻转——切换底图仅翻可见性、无需重建 native 图层；
     * 注记源作为 overlay 置顶加入（索引 N）。记录 [builtSignature] 供文档变更时判定结构是否变化。
     */
    fun buildLayers(doc: MapDocument?) {
        var index = 0
        val bases = doc?.mapSources?.filter { !it.isAnnotation } ?: emptyList()
        for (src in bases) {
            addLayer(src, overlay = false)
            mapView.setLayerVisible(index, src.visible)
            baseLayers.add(src)
            index++
        }
        // 注记图层（isAnnotation）：作为 overlay 置顶加入，可见性交由开关控制
        val annot = doc?.mapSources?.firstOrNull { it.isAnnotation }
        if (annot != null) {
            annotationLayerIndex = index
            addLayer(annot, overlay = true)
            mapView.setLayerVisible(index, annot.visible)
            annotationVisible = annot.visible
            index++
        }
        if (bases.none { it.visible }) {
            Toast.makeText(activity, R.string.jni_map_no_source, Toast.LENGTH_SHORT).show()
            AppLog.w(TAG, "未找到可见底图图源，地图仅显示占位网格")
        }
        builtSignature = sourceSignature(doc)
        updateAnnotationIcon()
    }

    /**
     * 文档变更响应（对齐原主界面 BaseMapActivity 的文档驱动，回调在主线程）：
     * - 图源/栅格结构变化（增/删/改图源或栅格层）：返回 true 交宿主执行 recreate（native 暂无删除图层能力）；
     * - 否则就地同步可见性/矢量层/栅格层并刷新叠加层，返回 false（无需重建）。
     * 矢量层结构/样式变化经 [syncVectorLayers] 就地换层，不触发重建。
     */
    fun onDocumentChanged(document: MapDocument): Boolean {
        if (sourceSignature(document) != builtSignature || rasterSignature(document) != builtRasterSignature) {
            return true
        }
        applyVisibilities(document)
        // 仅可见性变化：新增转可见的栅格层 / 翻转已加入栅格层显隐（对齐瓦片层可见性驱动）
        syncRasterLayers(document)
        // 仅可见性变化：新增转可见的矢量层 / 翻转已加入矢量层显隐（对齐瓦片层可见性驱动）
        syncVectorLayers(document)
        // 测量/轨迹/拍照可见性开关（showMeasureLayer 等）随设置落盘经此到达：按数据签名刷新叠加层
        onRebuildOverlays()
        return false
    }

    /** 按文档可见性翻转常驻底图层（索引 0..N-1）与注记层，并同步步记开关图标 */
    private fun applyVisibilities(doc: MapDocument) {
        val bases = doc.mapSources.filter { !it.isAnnotation }
        bases.forEachIndexed { i, src ->
            if (i < baseLayers.size) mapView.setLayerVisible(i, src.visible)
        }
        val annot = doc.mapSources.firstOrNull { it.isAnnotation }
        if (annot != null && annotationLayerIndex >= 0) {
            annotationVisible = annot.visible
            mapView.setLayerVisible(annotationLayerIndex, annotationVisible)
        }
        updateAnnotationIcon()
    }

    /**
     * 注记显隐开关：翻转可见性 → 经 native [NativeMapView.setLayerVisible] 即时生效 → 同步图标，
     * 并写回文档注记源的 [MapSource.visible] 落盘（与原主界面 main_iv_isCIA 口径一致）。
     * 落盘后经文档变更监听再次应用（幂等），此处先即时翻转保证响应跟手。
     */
    fun toggleAnnotation() {
        if (annotationLayerIndex < 0) {
            AppLog.w(TAG, "无注记图源，注记开关不可用")
            return
        }
        annotationVisible = !annotationVisible
        mapView.setLayerVisible(annotationLayerIndex, annotationVisible)
        updateAnnotationIcon()
        val a = DocumentManager.getInstance().getDocument()?.mapSources?.firstOrNull { it.isAnnotation }
        if (a != null) {
            a.visible = annotationVisible
            DocumentManager.getInstance().save(activity)
        }
    }

    /** 按注记可见性切换开关图标（对齐原主界面 nav_ic_cia_open/close） */
    private fun updateAnnotationIcon() {
        annotationButton.setImageResource(
            if (annotationVisible) R.drawable.nav_ic_cia_open else R.drawable.nav_ic_cia_close
        )
    }

    /**
     * 同步文档矢量图层到 native（懒加入 + 可见性驱动）：
     * - 新增：可见、有路径、级别达标且尚未加入的矢量层，按文档顺序经 [NativeMapView.addVectorLayer] 加入并记录索引
     *   （不可见/级别不足层不加入，避免无谓的 GDAL 读取与加载胶囊误亮；首次转可见且级别达标时再懒加入）；
     * - 显隐：已加入的层按 [LayerInfo.visible] 翻转 [NativeMapView.setVectorLayerVisible]（隐层不删除，与瓦片层同口径）。
     * 样式版本/增删变化：经旧键失效检测就地换层（removeVectorLayer 墓碑 + 新键重新懒加入），无需重建界面；
     * 仅图源/栅格结构变化才由 [onDocumentChanged] 触发重建（native 无删除瓦片/栅格图层能力）。
     */
    fun syncVectorLayers(doc: MapDocument?) {
        val infos = doc?.vectorLayers ?: return
        // 样式版本变化 / 图层被移除：将已不在当前文档 key 集合中的旧映射对应的 native 层墓碑（removeVectorLayer），
        // 使新样式就地生效，无需 recreate 整界面造成地图闪动（native 以 dead+visible=false 墓碑，
        // 其它层 index 保持稳定，Renderer 下一帧回收其 GL 资源）。
        val currentKeys = HashSet<String>(infos.size)
        for (info in infos) {
            val path = info.path
            if (path.isNullOrEmpty()) continue
            currentKeys.add(vectorKey(info))
        }
        val staleIter = vectorLayerIndices.entries.iterator()
        while (staleIter.hasNext()) {
            val entry = staleIter.next()
            if (entry.key in currentKeys) continue
            AppLog.i(TAG, "矢量层旧键失效，就地换层: ${entry.key} -> removeVectorLayer(${entry.value})")
            mapView.removeVectorLayer(entry.value)
            directVectorKeys.remove(entry.key)
            lastExtentByKey.remove(entry.key)
            pendingViewportKeys.remove(entry.key)
            dataExtentByKey.remove(entry.key)
            staleIter.remove()
        }
        // 新增：可见、级别达标且尚未加入的矢量层（懒加入，隐层不触发 GDAL 读取；
        // 级别不足层同口径挂起——整层隐藏中照样开文件读取+建几何就是无谓开销，还误亮加载胶囊）
        val camLevel = mapView.cameraZoomLevel()
        for (info in infos) {
            val path = info.path
            if (path.isNullOrEmpty() || !info.visible) continue
            val key = vectorKey(info)
            if (vectorLayerIndices.containsKey(key)) continue
            if (key in pendingViewportKeys) {
                // 已挂起（屏外或级别不足）：级别仍不达标则继续等相机静止补加入（onCameraSettled）；
                // 级别已达标则解除挂起照常加入（屏外层由 addVectorLayerNative 内四至预判自动再挂起）
                if (info.effectiveMinDisplayLevel > camLevel) continue
                pendingViewportKeys.remove(key)
            }
            if (!File(path).exists()) {
                AppLog.w(TAG, "矢量文件不存在，跳过[${info.name}]: $path")
                continue
            }
            if (info.effectiveMinDisplayLevel > camLevel) {
                // 级别不足：不加入，挂起待相机静止级别达标时补加入（与屏外挂起同一队列同一补加入通路）
                pendingViewportKeys.add(key)
                continue
            }
            val idx = addVectorLayerNative(info, path)
            if (idx >= 0) vectorLayerIndices[key] = idx
        }
        // 显隐：已加入的层按文档可见性翻转；级别可见性下限（用户指定或自动，未设为 -1→不限）一并同步，
        // 图层管理对话框改级别后即时生效（native 门控绘制/上传/拾取，与 visible 同口径）
        for (info in infos) {
            val path = info.path
            if (path.isNullOrEmpty()) continue
            val idx = vectorLayerIndices[vectorKey(info)] ?: continue
            mapView.setVectorLayerVisible(idx, info.visible)
            mapView.setVectorMinLevel(idx, info.effectiveMinDisplayLevel.coerceAtLeast(0))
        }
        builtVectorSignature = vectorSignature(doc)
    }

    /**
     * 同步文档栅格图层到 native（懒加入 + 可见性驱动，与矢量层同口径）：
     * - 新增：可见、有路径、文件存在且尚未加入的栅格层，经 [NativeMapView.addRasterLayer] 加入并记录索引
     *   （不可见层不加入，避免无谓的 GDAL 打开 + 重投影 VRT 构建；首次转可见时再懒加入）；
     * - 显隐：已加入的层按 [LayerInfo.visible] 翻转 [NativeMapView.setLayerVisible]（隐层不删除，与瓦片层同口径）。
     * 瓦片缓存目录用 `raster-jni/<路径哈希>`（区别于原底层库主界面的 `raster/<哈希>`——两者瓦片命名格式不同，不共享）。
     * 结构变化（增/删/改栅格层）由 [onDocumentChanged] 判定并重建界面（native 无删除图层能力）。
     */
    fun syncRasterLayers(doc: MapDocument?) {
        val infos = doc?.rasterLayers ?: return
        // 新增：可见且尚未加入的栅格层（懒加入，隐层不触发 GDAL 打开）
        for (info in infos) {
            val path = info.path
            if (path.isNullOrEmpty() || !info.visible) continue
            val key = rasterKey(info)
            if (rasterLayerIndices.containsKey(key)) continue
            if (!File(path).exists()) {
                AppLog.w(TAG, "栅格文件不存在，跳过[${info.name}]: $path")
                continue
            }
            val cacheDir = File(
                AppDirectories.getTileCacheDir(activity),
                "raster-jni/${Integer.toHexString(path.hashCode())}"
            )
            val idx = mapView.addRasterLayer(cacheDir.absolutePath, path)
            if (idx >= 0) rasterLayerIndices[key] = idx
        }
        // 显隐：已加入的层按文档可见性翻转
        for (info in infos) {
            val path = info.path
            if (path.isNullOrEmpty()) continue
            val idx = rasterLayerIndices[rasterKey(info)] ?: continue
            mapView.setLayerVisible(idx, info.visible)
        }
        builtRasterSignature = rasterSignature(doc)
    }

    /**
     * 加入一个矢量图层到 native：把文档 [com.zys.mobilemap.doc.VectorStyle] 覆盖映射为 native 的 #AARRGGBB 颜色 / 线宽参数，
     * 未设置的字段回退原主界面默认样式（LocalVectorLoader 的面填充/白描边/线色）。返回 native 图层索引（<0 失败）。
     * doc 样式未区分「面描边」与「线色」，故 outlineColor 覆盖同时作用于面描边与线要素（与原主界面 applyShapeStyle 一致）。
     */
    private fun addVectorLayerNative(info: LayerInfo, path: String): Int {
        val s = info.vectorStyle?.takeIf { !it.isEmpty() }
        // 填充开关关闭（fillEnabled=false）：填充色 alpha 置 0 → 面仅描边不填充（native 描边与填充独立成组，互不影响）
        val fillColor = if (s?.fillEnabled == false) 0 else MapStyleColors.resolveFillColor(s)
        val outlineColor = MapStyleColors.resolveArgb(s?.outlineColor, MapStyleColors.DEFAULT_POLYGON_OUTLINE_COLOR)
        val outlineWidth = s?.outlineWidth ?: MapStyleColors.DEFAULT_OUTLINE_WIDTH
        val lineColor = MapStyleColors.resolveArgb(s?.outlineColor, MapStyleColors.DEFAULT_LINE_COLOR)
        val lineWidth = s?.outlineWidth ?: MapStyleColors.DEFAULT_LINE_WIDTH
        // 逐要素源文件配色门控（按通道覆盖）：用户在图层管理显式设了该通道（doc 字段非空）才覆盖 KML 原色；
        // 未设的通道保留源文件逐要素原色（fillEnabled=false 视为显式关闭填充，亦覆盖为仅描边）。
        val fillExplicit = s?.fillColor != null || s?.fillOpacity != null || s?.fillEnabled != null
        val lineExplicit = s?.outlineColor != null
        // 标注：对齐原主界面——门控于 VectorStyle.labelField 非空；KML/KMZ 额外受 showKmlLabel 开关控制
        // （复刻原主界面 LocalVectorLoader 的 suppressLabels 口径）。样式颜色/字号/轮廓未设时回退原主界面默认。
        val ext = path.substringAfterLast('.', "").lowercase()
        val suppressLabel = (ext == "kml" || ext == "kmz") &&
                !(DocumentManager.getInstance().getDocument()?.systemConfig?.showKmlLabel ?: false)
        val labelField = if (suppressLabel) "" else (s?.labelField?.trim() ?: "")
        val labelColor = MapStyleColors.resolveArgb(s?.labelColor, MapStyleColors.DEFAULT_LABEL_COLOR)
        val labelSize = s?.labelSize ?: MapStyleColors.DEFAULT_LABEL_SIZE
        val labelOutline = s?.labelOutline ?: false
        val labelOutlineColor = MapStyleColors.resolveArgb(s?.labelOutlineColor, MapStyleColors.DEFAULT_LABEL_OUTLINE_COLOR)
        // 点要素图标：对齐原主界面——按 VectorStyle.iconKey 取内置矢量图标（FeatureIcons.resOf，默认 pin），
        // 在宿主侧解码为 ARGB 像素传入 native 作 billboard（native C++ 无法解码 Android 矢量 drawable）。
        val icon = decodeIconArgb(activity, FeatureIcons.resOf(s?.iconKey))
        // 单要素样式覆盖串：把文档 featureStyles 逐 fid 解析为填充/描边 ARGB（仅取渲染相关子集），下传 native 逐要素命中。
        val featureOverrideStr = buildFeatureStyleOverride(info.vectorStyle)
        // 大小数据分流：快速统计要素总数，< DIRECT_RENDER_LIMIT 走整文件全量（direct，不参与相机重载）；
        // 否则按当前屏幕范围空间相交加载（首屏即只取屏内要素）。统计失败(<0)保守按全量（等同旧行为）。
        val key = vectorKey(info)
        val count = GdalVectorReader.countFeatures(path)
        val direct = count < 0 || count < DIRECT_RENDER_LIMIT
        val extent = if (direct) null else extentProvider?.invoke()
        // 屏外预判：大数据层若四至与屏幕范围确定不相交 → 挂起不加入（免无谓 GDAL 读取与加载胶囊误亮）；
        // 四至/屏幕范围任一未知则照常加入，交 native 防线1 整层跳过兜底（行为与旧版一致）。
        if (!direct && extent != null) {
            val bbox = dataExtentByKey.getOrPut(key) { vectorDataBBox(path) }
            if (bbox != null && !extentIntersects(bbox, extent)) {
                pendingViewportKeys.add(key)
                AppLog.i(TAG, "矢量层[${info.name}]四至与屏幕范围不相交，挂起待进入视口再加入")
                return -1
            }
        }
        val idx = mapView.addVectorLayer(
            path,
            NativeVectorStyle(
                fillColor = fillColor, outlineColor = outlineColor, outlineWidth = outlineWidth,
                lineColor = lineColor, lineWidth = lineWidth, pointColor = lineColor,
                pointRadiusDp = MapStyleColors.DEFAULT_POINT_RADIUS_DP,
                fillExplicit = fillExplicit, lineExplicit = lineExplicit,
                labelField = labelField, labelColor = labelColor, labelSize = labelSize,
                labelOutline = labelOutline, labelOutlineColor = labelOutlineColor,
                featureStyleOverride = featureOverrideStr
            ),
            icon?.first, icon?.second ?: 0, icon?.third ?: 0,
            extent, MAX_FEATURES_BUDGET
        )
        if (idx >= 0) {
            // 级别可见性下限（文档 effectiveMinDisplayLevel，未设为 -1→0 不限）：小级别整层隐藏，
            // 免无谓的读取/上传/零散显示（与显隐同步循环同源，加入即带上下限避免首帧闪现后隐藏）
            mapView.setVectorMinLevel(idx, info.effectiveMinDisplayLevel.coerceAtLeast(0))
            if (direct) {
                directVectorKeys.add(key)
                lastExtentByKey.remove(key)
            } else {
                directVectorKeys.remove(key)
                extent?.let { lastExtentByKey[key] = it }
            }
            notifyVectorLoadStarted()
        }
        AppLog.i(TAG, "JNI 矢量层[${info.name}] index=$idx direct=$direct count=$count path=$path")
        return idx
    }

    /**
     * 相机静止回调（宿主接线 [CameraNavigator.onCameraSettled]）：按新屏幕范围 [extent] 重载可见的大数据矢量层。
     * 仅对非 direct（大数据）且已加入 native 的可见层生效；extent 相对上次无实质变化（<[EXTENT_CHANGE_RATIO]）
     * 则跳过，避免小幅平移抖动重复重载。级别未达该层 [LayerInfo.effectiveMinDisplayLevel] 下限的层整层隐藏
     * 中，不发重载（免无谓读取；lastExtent 不更新，回到曾发出的范围时 native 现有数据即该范围，无缝恢复显示）。
     * native 内 Swap-on-ready 保证停下即有数据、重载期间无空窗。
     */
    fun onCameraSettled(extent: VectorExtent) {
        val doc = DocumentManager.getInstance().getDocument() ?: return
        AppLog.i(TAG, "[VecReload] onCameraSettled: extent=[%.6f,%.6f,%.6f,%.6f]".format(
            extent.minLon, extent.minLat, extent.maxLon, extent.maxLat))
        val camLevel = mapView.cameraZoomLevel()
        // 补加入：先前因屏外挂起的大数据层，现屏幕范围与四至相交（或四至未知）则正常懒加入；
        // 刚加入者 lastExtent 已被本 extent 命中 → 下方重载判定 extentChanged=false 不重复加载。
        if (pendingViewportKeys.isNotEmpty()) {
            for (key in pendingViewportKeys.toList()) {
                val info = doc.vectorLayers.firstOrNull { vectorKey(it) == key } ?: run {
                    pendingViewportKeys.remove(key) // 已从文档移除
                    continue
                }
                val path = info.path
                if (path.isNullOrEmpty() || !info.visible || !File(path).exists() ||
                    info.effectiveMinDisplayLevel > camLevel
                ) continue // 保持挂起：转可见/级别达标后再试
                val bbox = dataExtentByKey.getOrPut(key) { vectorDataBBox(path) }
                if (bbox != null && !extentIntersects(bbox, extent)) continue // 仍屏外，继续挂起
                pendingViewportKeys.remove(key)
                val idx = addVectorLayerNative(info, path)
                if (idx >= 0) vectorLayerIndices[key] = idx
                AppLog.i(TAG, "[VecReload] 屏外挂起层补加入[${info.name}] idx=$idx")
            }
        }
        for (info in doc.vectorLayers) {
            val path = info.path
            if (path.isNullOrEmpty() || !info.visible) continue
            if (info.effectiveMinDisplayLevel > camLevel) continue // 级别不足隐藏中，不发无谓重载
            val key = vectorKey(info)
            if (key in directVectorKeys) continue // 小数据全量层不重载
            val idx = vectorLayerIndices[key] ?: continue
            val changed = extentChanged(lastExtentByKey[key], extent)
            AppLog.i(TAG, "[VecReload] 重载判定 layer[${info.name}] idx=$idx changed=$changed last=${lastExtentByKey[key]}")
            if (!changed) continue
            mapView.updateVectorExtent(idx, extent, MAX_FEATURES_BUDGET)
            lastExtentByKey[key] = extent
            notifyVectorLoadStarted()
        }
    }

    /**
     * 矢量加载启动入口（懒加入新层 / 静止重载发出 updateVectorExtent 后调用）：
     * 挂起 [VECTOR_LOADING_SHOW_DELAY_MS] 后延迟检查，native 仍在加载/上传才显示提示胶囊。
     * 幂等可重复调用——多图层连续重载合并为一次等待，胶囊显示后由轮询统一收起。
     */
    fun notifyVectorLoadStarted() {
        val pill = vectorLoadingPill ?: return
        pill.removeCallbacks(vectorLoadingShowRunnable)
        pill.removeCallbacks(vectorLoadingPollRunnable)
        pill.postDelayed(vectorLoadingShowRunnable, VECTOR_LOADING_SHOW_DELAY_MS)
    }

    /** 宿主 onDestroy 注销：取消一切挂起任务并收起胶囊，避免界面销毁后回调触碰 View。 */
    fun cancelVectorLoadingPill() {
        val pill = vectorLoadingPill ?: return
        pill.removeCallbacks(vectorLoadingShowRunnable)
        pill.removeCallbacks(vectorLoadingPollRunnable)
        pill.visibility = View.GONE
    }

    /** 读矢量数据四至（WGS84，kmz 走 /vsizip/ 虚拟文件）；失败返回 null（四至未知时不挂起，交 native 兜底）。 */
    private fun vectorDataBBox(path: String): VectorExtent? {
        val openPath = if (path.lowercase().endsWith(".kmz")) "/vsizip/$path" else path
        val e = NativeLayerInfo.getLayerExtent(openPath) ?: return null
        if (e.size < 4) return null
        return VectorExtent(e[0], e[1], e[2], e[3])
    }

    /** 两 WGS84 矩形是否相交（边界接触算相交，与 native 防线1「整层跳过」判定同口径）。 */
    private fun extentIntersects(a: VectorExtent, b: VectorExtent): Boolean {
        return !(a.maxLon < b.minLon || a.minLon > b.maxLon || a.maxLat < b.minLat || a.minLat > b.maxLat)
    }

    /** 屏幕范围是否相对上次有实质变化：任一边界位移超过跨度 [EXTENT_CHANGE_RATIO] 视为变化（需重载）。上次为 null 视为变化。 */
    private fun extentChanged(last: VectorExtent?, next: VectorExtent): Boolean {
        last ?: return true
        val lonTol = maxOf(abs(last.maxLon - last.minLon), abs(next.maxLon - next.minLon)) * EXTENT_CHANGE_RATIO
        val latTol = maxOf(abs(last.maxLat - last.minLat), abs(next.maxLat - next.minLat)) * EXTENT_CHANGE_RATIO
        return abs(last.minLon - next.minLon) > lonTol || abs(last.maxLon - next.maxLon) > lonTol ||
                abs(last.minLat - next.minLat) > latTol || abs(last.maxLat - next.maxLat) > latTol
    }

    /** 装配一个瓦片图层：定位其磁盘缓存目录 + 解析联网 URL 模板，按 [overlay] 标志加入 native 渲染栈。 */
    private fun addLayer(src: MapSource, overlay: Boolean) {
        val cacheDir = File(AppDirectories.getTileCacheDir(activity), sanitizeDirName(src.name ?: ""))
        val urlTemplate = resolveUrl(src) ?: ""
        mapView.addTileLayer(cacheDir.absolutePath, urlTemplate, src.maxLevel, overlay)
        AppLog.i(TAG, "图层[${src.name}] overlay=$overlay 缓存目录=${cacheDir.absolutePath} 联网=${urlTemplate.isNotEmpty()} maxLevel=${src.maxLevel} url=$urlTemplate")
    }

    /**
     * 将 URL 中的 Token 占位符替换为实际 Token（复刻原主界面 BaseMapActivity.resolveUrl 逻辑）。
     * 含 token 的模板直接下发给 native，用于联网拉取瓦片。
     */
    private fun resolveUrl(src: MapSource): String? {
        val url = src.url
        if (url.isNullOrEmpty()) return null
        var result = url
        val token2 = src.token2
        if (!token2.isNullOrEmpty()) result = result.replace(MapSource.TOKEN2_PLACEHOLDER, token2)
        val token1 = src.token1
        if (!token1.isNullOrEmpty()) result = result.replace(MapSource.TOKEN1_PLACEHOLDER, token1)
        return result
    }

    /** 缓存子目录名净化（复刻原主界面 TileCacheManager 的 sanitizeDirName，保证目录一致） */
    private fun sanitizeDirName(name: String): String {
        val clean = name.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
        return clean.ifEmpty { "unknown" }
    }

    /** 图源结构签名：各图源 [sourceKey] 有序拼接，任一图源增删改（名称/网址/类型/Token/级别变化）即变 */
    private fun sourceSignature(doc: MapDocument?): String =
        doc?.mapSources?.joinToString(";") { sourceKey(it) } ?: ""

    /** 图源标识（复刻原主界面 BaseMapActivity.sourceKey）：名称|网址|类型|Token1|Token2|最大级别 */
    private fun sourceKey(src: MapSource): String =
        (src.name ?: "") + "|" + (src.url ?: "") + "|" + (src.imageFormat ?: "") +
                "|" + (src.token1 ?: "") + "|" + (src.token2 ?: "") + "|" + src.maxLevel

    /** 矢量层缓存键：路径 + 样式版本（样式改动递增 styleVersion，结构签名变化触发重建后按新样式重载） */
    private fun vectorKey(info: LayerInfo): String = (info.path ?: "") + "|" + info.styleVersion

    /**
     * 把文档层 [com.zys.mobilemap.doc.VectorStyle.featureStyles]（fid → 单要素覆盖）拼为 native 下传串：
     * 条目以 ';' 分隔，每条目 8 字段逗号分隔：
     *   `fid,fillArgb,lineArgb,labelFieldName,labelArgb,labelSize,labelOutline,labelOutlineArgb`
     * - 各 ARGB 为 #AARRGGBB 无符号十进制；'-' = 该通道未显式设过（继承整层）；
     * - labelFieldName = 该要素要标注的属性字段名（DBF/GDAL 字段名，约定不含 ','/';'，含则退回 '-' 不污染分隔）；
     * - labelSize 为浮点串；labelOutline 为 1/0。
     * 与整层 [addVectorLayerNative] 同口径解析：填充关闭（fillEnabled=false）→ alpha 置 0。
     * 仅当 doc 对应字段非空才视作显式设过。无任何覆盖通道时整条跳过；空表返回空串（零回归）。
     */
    private fun buildFeatureStyleOverride(vs: com.zys.mobilemap.doc.VectorStyle?): String {
        val fs = vs?.featureStyles
        if (fs.isNullOrEmpty()) return ""
        val sb = StringBuilder()
        for ((fid, s) in fs) {
            val hasFill = s.fillColor != null || s.fillOpacity != null || s.fillEnabled != null
            val hasLine = s.outlineColor != null
            // 标注通道：字段名非空且合法（不含分隔符）才算显式设过；样式子通道按各自非空判定
            val labelName = s.labelField?.trim()
            val hasLabel = !labelName.isNullOrEmpty() && !labelName.contains(',') && !labelName.contains(';')
            val hasLabelColor = s.labelColor != null
            val hasLabelSize = s.labelSize != null
            val hasLabelOutline = s.labelOutline != null
            val hasLabelOutlineColor = s.labelOutlineColor != null
            if (!hasFill && !hasLine && !hasLabel && !hasLabelColor &&
                !hasLabelSize && !hasLabelOutline && !hasLabelOutlineColor
            ) continue
            val fillArgb = if (!hasFill) null else
                if (s.fillEnabled == false) 0 else MapStyleColors.resolveFillColor(s)
            val lineArgb = if (hasLine)
                MapStyleColors.resolveArgb(s.outlineColor, MapStyleColors.DEFAULT_POLYGON_OUTLINE_COLOR)
            else null
            val labelArgb = if (hasLabelColor)
                MapStyleColors.resolveArgb(s.labelColor, MapStyleColors.DEFAULT_LABEL_COLOR)
            else null
            val labelOutlineArgb = if (hasLabelOutlineColor)
                MapStyleColors.resolveArgb(s.labelOutlineColor, MapStyleColors.DEFAULT_LABEL_OUTLINE_COLOR)
            else null
            // 无符号十进制串（把 Int 按无符号处理，避免负数颜色值符号问题）
            fun u(value: Int?): String = value?.let { (it.toLong() and 0xFFFFFFFFL).toString() } ?: "-"
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(fid)
                .append(',').append(u(fillArgb))
                .append(',').append(u(lineArgb))
                .append(',').append(if (hasLabel) labelName!! else "-")
                .append(',').append(u(labelArgb))
                .append(',').append(if (hasLabelSize) s.labelSize.toString() else "-")
                .append(',').append(if (hasLabelOutline) (if (s.labelOutline == true) "1" else "0") else "-")
                .append(',').append(u(labelOutlineArgb))
        }
        return sb.toString()
    }

    /** 矢量层结构签名：各矢量层 path#styleVersion 有序拼接，任一矢量层增删改（路径/样式版本变化）即变 */
    private fun vectorSignature(doc: MapDocument?): String =
        doc?.vectorLayers?.joinToString(";") { (it.path ?: "") + "#" + it.styleVersion } ?: ""

    /** 栅格层缓存键：路径（栅格无样式版本，路径唯一标识一个图层） */
    private fun rasterKey(info: LayerInfo): String = info.path ?: ""

    /** 栅格层结构签名：各栅格层 path 有序拼接，任一栅格层增删改（路径变化）即变 */
    private fun rasterSignature(doc: MapDocument?): String =
        doc?.rasterLayers?.joinToString(";") { it.path ?: "" } ?: ""

    /** 由 native 矢量层索引反查文档 [LayerInfo]（索引与 [vectorLayerIndices] 的值对应）；未找到返回 null */
    fun layerInfoByIndex(index: Int): LayerInfo? {
        val doc = DocumentManager.getInstance().getDocument() ?: return null
        return doc.vectorLayers.firstOrNull { vectorLayerIndices[vectorKey(it)] == index }
    }

    /** 文档矢量层对应的 native 层索引（供拾取/详情按 FID 回取几何与属性）；未加入返回 null */
    fun nativeIndexOf(info: LayerInfo): Int? = vectorLayerIndices[vectorKey(info)]

    /**
     * 矢量图层当前实际渲染色（#AARRGGBB 填充/描边）：复用 [addVectorLayerNative] 同款解析
     * （[MapStyleColors.resolveFillColor] / [MapStyleColors.resolveArgb] + 文档 [com.zys.mobilemap.doc.VectorStyle]），即 native 实际使用的整层颜色，
     * 供图层管理样式对话框与预览图标所见即所得回显（对齐原主界面 layerRenderColorsOf）。
     * 区别于原底层库主界面从 renderable 采样：本界面矢量层完全文档驱动（无 shp 内嵌/单要素等额外色来源），
     * 故实际渲染色即“文档样式 ?: 默认色”，与 addVectorLayerNative 传入 native 的值一致。图层不存在返回 null。
     */
    fun layerRenderColorsOf(path: String): Pair<String?, String?> {
        val info = DocumentManager.getInstance().getDocument()
            ?.vectorLayers?.firstOrNull { it.path == path } ?: return Pair(null, null)
        val s = info.vectorStyle?.takeIf { !it.isEmpty() }
        val fill = MapStyleColors.resolveFillColor(s)
        val outline = MapStyleColors.resolveArgb(s?.outlineColor, MapStyleColors.DEFAULT_POLYGON_OUTLINE_COLOR)
        return Pair(MapStyleColors.argbToHex(fill), MapStyleColors.argbToHex(outline))
    }

    companion object {
        // 保持与原 MainActivity 相同日志 tag，行为零变化
        private const val TAG = "MainActivity"

        // 小数据全量渲染阈值：要素总数 < 此值一次性全量渲染、不参与相机静止重载（迁移前已验证参数 5000）
        private const val DIRECT_RENDER_LIMIT = 5000

        // 单次加载要素预算：0 = 取 native 硬上限（10000 兜底防 OOM）；屏幕过滤通常命中几百~千级
        private const val MAX_FEATURES_BUDGET = 0

        // 相机静止重载的范围变化阈值：边界位移超过屏幕跨度的此比例才触发重载（去抖小幅平移）
        private const val EXTENT_CHANGE_RATIO = 0.1

        // 矢量加载提示胶囊：加载启动后延迟显示阈值（快速加载不超过此值则永不闪现）
        private const val VECTOR_LOADING_SHOW_DELAY_MS = 500L

        // 胶囊显示后的状态轮询间隔（250ms 粒度足以平滑收尾，轮询 JNI 为纯查询不触发重绘）
        private const val VECTOR_LOADING_POLL_MS = 250L
    }
}
