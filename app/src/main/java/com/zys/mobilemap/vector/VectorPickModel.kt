package com.zys.mobilemap.vector

import android.content.Context
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import earth.worldwind.PickedObject
import earth.worldwind.PickedObjectList
import earth.worldwind.WorldWindow
import earth.worldwind.render.Renderable
import earth.worldwind.shape.AbstractShape
import earth.worldwind.shape.Placemark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拾取到的矢量要素信息：图层名/图层路径（定位文档 LayerInfo）、
 * 源数据要素标识（shp 记录号 / GDAL FID）、展示名称、属性表与对应渲染对象，
 * 供要素详情（样式编辑/属性显示）使用。
 */
class PickedFeatureInfo(
    val layerName: String?,
    val layerPath: String?,
    val featureId: Long,
    val name: String?,
    val renderable: Renderable,
    val attributes: Map<String, String>?,
    /** KML/KMZ 要素 <description> HTML（非空时属性页以 WebView 渲染，对齐 2.5.3） */
    val description: String? = null
)

/**
 * 矢量要素点击拾取：单击地图选中矢量要素并高亮（isHighlighted），
 * 提取要素标识经回调反馈（当前为日志+提示，等待属性表显示阶段接入）。
 * 仅拾取带 [LocalVectorLoader.VECTOR_LAYER_KEY] 标记的矢量图层，
 * 底图/注记/定位图层不参与点选。
 */
class VectorPickModel(
    private val scope: CoroutineScope,
    private val worldWindow: WorldWindow,
    private val onFeaturePicked: (PickedFeatureInfo?) -> Unit,
    /** 拾取前拦截（如测量模式加点）：返回 true 时跳过矢量拾取 */
    private val onBeforePick: ((x: Float, y: Float) -> Boolean)? = null,
    /** 拾取结果前置处理（如测量几何/媒体标注优先于矢量要素）：返回 true 时跳过矢量拾取 */
    private val onPicked: ((x: Float, y: Float, picked: PickedObjectList) -> Boolean)? = null
) {

    /** 当前选中的渲染对象（用于切换选中时取消上一个高亮） */
    private var selected: Renderable? = null

    /** 延迟属性补拉竞态防护：快速连点两个要素时，仅回调最后一次的属性结果 */
    private var pickToken = 0L

    fun setup(context: Context) {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return false
            }
        })
        // 不消费事件，WorldWind 的导航手势（平移/缩放/旋转）继续生效
        worldWindow.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
    }

    private fun handleTap(x: Float, y: Float) {
        // 前置拦截：测量模式下由测量模块消费点击，不进行矢量拾取
        if (onBeforePick?.invoke(x, y) == true) return
        scope.launch {
            val picked = try {
                worldWindow.pickAsync(x, y).await()
            } catch (e: Exception) {
                Log.e(TAG, "拾取矢量要素失败", e)
                return@launch
            }
            // 前置处理：测量几何/媒体标注等优先于矢量要素，被消费则跳过矢量拾取
            if (onPicked?.invoke(x, y, picked) == true) return@launch
            // 取拾取结果中第一个矢量图层要素（topPickedObject 可能是地形）；
            // 几何要素优先于 Label/贴图等附属对象（KML 开启标注后同位置会有 Label 参与拾取）
            val candidates = picked.objects.filter {
                it.userObject is Renderable &&
                        it.layer?.getUserProperty(LocalVectorLoader.VECTOR_LAYER_KEY) as? Boolean == true
            }
            val po = candidates.firstOrNull { it.userObject is AbstractShape || it.userObject is Placemark }
                ?: candidates.firstOrNull()
            select(po)
        }
    }

    /**
     * 切换选中：旧要素取消高亮，新要素高亮并回调反馈；点击空白处取消选中。
     *
     * 延迟属性读取（shp/gpkg 启用，见 [LocalVectorLoader.loadWithGdal]）：加载阶段仅传 labelField
     * 白名单字段，FEATURE_ATTRS_KEY 缓存为空。首次点击时按 FID 异步回取全字段（IO
     * 线程，~10-30ms），写回缓存后回调详情对话框；二次点击命中缓存直接回调。
     * 竞态防护：[pickToken] 序号保证连点时只回调最后一次；选中态二次校验避免图层重载后弹旧要素。
     */
    private fun select(po: PickedObject?) {
        val renderable = po?.userObject as? Renderable
        setHighlighted(selected, false)
        selected = renderable
        setHighlighted(renderable, true)
        worldWindow.requestRedraw()

        // 点击空白：取消选中，直接回调 null（renderable 由 po 安全转换而来，非空即蕴含 po 非空）
        if (renderable == null) {
            onFeaturePicked(null)
            return
        }

        val cachedRaw = renderable.getUserProperty(LocalVectorLoader.FEATURE_ATTRS_KEY) as? Map<*, *>
        val cached = cachedRaw?.mapValues { it.value?.toString() ?: "" }?.mapKeys { it.key.toString() }
        val fid = renderable.getUserProperty(LocalVectorLoader.FEATURE_ID_KEY) as? Long ?: -1L
        val path = po.layer?.getUserProperty(LocalVectorLoader.LAYER_PATH_KEY) as? String

        // 已有属性缓存 / 无 fid / 无图层路径：直接回调（延迟模式未启用或不可补拉）
        if (cached != null || fid < 0 || path == null) {
            onFeaturePicked(buildInfo(po, renderable, cached))
            return
        }

        // 延迟属性模式：IO 线程按 FID 单 seek 回取全字段，写缓存后回调
        val token = ++pickToken
        scope.launch(Dispatchers.IO) {
            val attrs = GdalVectorReader.getFeatureAttributes(path, fid)
            if (attrs != null) {
                renderable.putUserProperty(LocalVectorLoader.FEATURE_ATTRS_KEY, attrs)
            }
            withContext(Dispatchers.Main) {
                // 竞态：连点只回调最后一次；图层重载/取消选中时跳过回调避免弹错要素
                if (token == pickToken && selected === renderable) {
                    onFeaturePicked(buildInfo(po, renderable, attrs))
                }
            }
        }
    }

    /** 构造拾取要素信息：[attrs] 为延迟模式下二次回取的全字段或已有缓存 */
    private fun buildInfo(po: PickedObject, renderable: Renderable,
                          attrs: Map<String, String>?): PickedFeatureInfo {
        return PickedFeatureInfo(
            layerName = po.layer?.displayName,
            layerPath = po.layer?.getUserProperty(LocalVectorLoader.LAYER_PATH_KEY) as? String,
            featureId = renderable.getUserProperty(LocalVectorLoader.FEATURE_ID_KEY) as? Long ?: -1L,
            name = renderableName(renderable),
            renderable = renderable,
            attributes = attrs,
            description = renderable.getUserProperty(LocalVectorLoader.FEATURE_DESC_KEY) as? String
        )
    }

    private fun setHighlighted(renderable: Renderable?, on: Boolean) {
        when (renderable) {
            is AbstractShape -> renderable.isHighlighted = on
            is Placemark -> renderable.isHighlighted = on
        }
    }

    /** 取消当前选中（样式保存后图层将重载，旧渲染对象失效） */
    fun clearSelection() {
        setHighlighted(selected, false)
        selected = null
        worldWindow.requestRedraw()
    }

    private fun renderableName(renderable: Renderable): String? = when (renderable) {
        is AbstractShape -> renderable.displayName
        is Placemark -> renderable.displayName
        else -> null
    }

    companion object {
        private const val TAG = "VectorPickModel"
    }
}
