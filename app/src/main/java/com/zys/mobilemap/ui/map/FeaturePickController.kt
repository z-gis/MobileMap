package com.zys.mobilemap.ui.map

import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.doc.VectorStyle
import com.zys.mobilemap.location.LocationManager
import com.zys.mobilemap.media.MediaStore
import com.zys.mobilemap.media.MediaPlacemark
import com.zys.mobilemap.survey.SampleSurveyActivity
import com.zys.mobilemap.survey.SampleSurveyStore
import com.zys.mobilemap.ui.dialog.FeatureDetailDialog
import com.zys.mobilemap.ui.dialog.PhotoSheetDialog
import com.zys.mobilemap.util.AppLog
import com.zys.mobilemap.util.GeoCalc
import com.zys.mobilemap.vector.GdalVectorReader
import com.zys.globecore.FeatureGeometry
import com.zys.globecore.NativeMapView
import com.zys.globecore.VectorStyle as NativeVectorStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * 点击业务分流路由（[FeaturePickController] → 宿主）：叠加层命中后的记录详情/编辑入口回 Activity 完成
 * （对话框/Fragment 专属逻辑留宿主，与拆分前语义一致）。
 */
internal interface PickHitRouter {
    /** 测量叠加命中（含标注层）：Activity 读记录后转 MeasureCaptureController.showMeasureDetail */
    fun onMeasureHit(recordId: Long)

    /** 轨迹叠加命中：Activity 读记录后转 TrackCaptureController.showTrackDetail */
    fun onTrackHit(recordId: Long)

    /** 拍照点位命中：Activity 弹 PhotoSheetDialog */
    fun onMediaHit(placemark: MediaPlacemark)

    /** 样地点位命中：Activity 启动 SampleSurveyActivity */
    fun onSurveyHit(plotId: Long)
}

/**
 * 矢量拾取与选中详情控制器（自 MainActivity 拆出）：单击地图命中分发（测量拦截→叠加命中→文件矢量拾取）、
 * 黄色选中高亮层管理、要素详情弹层（属性后台回取回填）、属性写回与样地调查入口。
 *
 * 协作单向：测量拦截经 [MeasureCaptureController.handleTap] 先行；叠加命中分类经 [BusinessOverlayManager.hitTest]，
 * 分流细节走 [PickHitRouter] 回宿主；查询高亮穿透经 [SqlQueryController.sourcePathIfHit]；矢量层索引回查经
 * [MapLayerManager]。选中为瞬态交互：[onOverlayLayersCleared] 供叠加层重建后重置失效索引。
 */
internal class FeaturePickController(
    private val activity: AppCompatActivity,
    private val mapView: NativeMapView,
    private val layerManager: MapLayerManager,
    private val overlayManager: BusinessOverlayManager,
    private val queryController: SqlQueryController,
    private val measureController: MeasureCaptureController,
    private val router: PickHitRouter,
    private val fm: FragmentManager,
    private val scope: CoroutineScope
) {

    // 点击选中高亮：选中要素的黄色高亮 overlay 层索引 + 源文件路径/FID
    // （高亮层置 noPick 不参与 native 拾取，命中直接落源要素层；缓存源 path/FID 仅作防护兜底：
    // 异常情形（如 native 旧版未支持 noPick）高亮层被命中时穿透回详情）
    private var selectedHighlightIndex = -1
    private var selectedPath: String? = null
    private var selectedFid: Long = -1

    /**
     * 单击地图拾取矢量要素：native 命中检测（屏幕点 → 图层索引/FID），命中则按 FID 回取属性并弹出只读详情层。
     * 拾取为纯 CPU（主线程执行）；属性回读直接复用原主界面既有接口 [GdalVectorReader.getFeatureAttributes]
     * （含 GDAL 磁盘 IO，切 IO 线程后回主线程弹层，对齐原主界面 VectorPickModel）。未命中任何矢量要素时不弹窗
     * （底图/注记不参与点选）；样式编辑暂不开放（styleEditable=false，仅属性页）。
     */
    fun onMapTap(x: Float, y: Float) {
        // 测量采集模式：拦截点击用于加点（优先于拾取，对齐原主界面 VectorPickModel.onBeforePick 的测量拦截）
        if (measureController.handleTap(x, y)) return
        val hit = mapView.pickVector(x, y)
        if (hit == null) {
            // 点击空白：取消要素选中高亮（对齐原主界面 VectorPickModel 点击空白取消选中）
            clearSelectedHighlight()
            return
        }
        if (hit.size < 2) return
        val layerIndex = hit[0].toInt()
        val fid = hit[1]
        // 业务叠加命中（测量/轨迹/拍照/查询高亮/选中高亮）：按 overlay 索引映射分流并消费点击（叠加层逆序优先于文件矢量）
        if (handleOverlayHit(layerIndex, fid)) return
        // 文件矢量命中：画黄色选中高亮（对齐原主界面 setHighlighted）+ 按 FID 回取属性弹只读详情层
        val info = layerManager.layerInfoByIndex(layerIndex) ?: return
        val path = info.path
        if (path.isNullOrEmpty()) return
        highlightSelectedFeature(layerIndex, fid, path)
        showVectorFeatureDetail(path, fid)
    }

    /**
     * 业务叠加命中分流：native pickVector 分两阶段（先叠加层后文件矢量层，见 [NativeMapView.pickVector]），
     * 测量/轨迹/拍照/样地叠加恒优先于矢量要素命中。按 overlay 索引映射判定命中类型：测量→路由弹测量详情、
     * 轨迹→路由弹轨迹详情、拍照（合一层，fid=点位主键）→路由弹 [PhotoSheetDialog]、样地→路由启动调查编辑；
     * SQL 查询高亮/选中高亮层已置 noPick 不参与 native 拾取（命中直接落源层），下方穿透分支仅作防护兜底。
     * @return 是否命中并消费了该点击
     */
    private fun handleOverlayHit(layerIndex: Int, fid: Long): Boolean {
        when (val h = overlayManager.hitTest(layerIndex, fid)) {
            is OverlayHit.Measure -> {
                router.onMeasureHit(h.recordId)
                return true
            }
            is OverlayHit.Track -> {
                router.onTrackHit(h.recordId)
                return true
            }
            is OverlayHit.Media -> {
                val placemark = MediaStore.getInstance(activity).findPlacemark(h.pointId)
                if (placemark != null) router.onMediaHit(placemark)
                return true
            }
            is OverlayHit.Survey -> {
                router.onSurveyHit(h.plotId)
                return true
            }
            OverlayHit.None -> Unit
        }
        // SQL 查询高亮层命中：点击穿透到源要素（该层已置 noPick，正常不会命中于此；本分支仅作防护兜底），弹源数据只读详情
        queryController.sourcePathIfHit(layerIndex)?.let {
            showVectorFeatureDetail(it, fid)
            return true
        }
        // 点击选中高亮层命中：穿透回源要素详情（该层已置 noPick，正常不会命中于此；本分支仅作防护兜底）
        if (layerIndex == selectedHighlightIndex) {
            selectedPath?.let { showVectorFeatureDetail(it, selectedFid) }
            return true
        }
        return false
    }

    /**
     * 画文件矢量要素的选中高亮（对齐原主界面 VectorPickModel 的 setHighlighted 黄色选中态）：
     * 经 [NativeMapView.featureGeometry] 取该要素经纬度几何（native 反投影 pickPrims，无文件 IO），
     * 用独立 overlay 层以黄色高亮样式覆盖渲染。切换选中先清旧高亮；高亮层置 noPick 不参与拾取
     * （其大面积面几何会遮蔽测量/拍照等业务叠加与源要素的点击），命中直接落到原要素层。
     */
    private fun highlightSelectedFeature(layerIndex: Int, fid: Long, path: String) {
        clearSelectedHighlight()
        val fg = mapView.featureGeometry(layerIndex, fid) ?: return
        val idx = mapView.addOverlayLayer(
            NativeVectorStyle(
                fillColor = MapStyleColors.SELECT_FILL_COLOR, outlineColor = MapStyleColors.SELECT_OUTLINE_COLOR, outlineWidth = MapStyleColors.SELECT_LINE_WIDTH,
                lineColor = MapStyleColors.SELECT_OUTLINE_COLOR, lineWidth = MapStyleColors.SELECT_LINE_WIDTH, pointColor = MapStyleColors.SELECT_OUTLINE_COLOR,
                pointRadiusDp = MapStyleColors.SELECT_POINT_RADIUS_DP
            )
        )
        if (idx < 0) return
        mapView.setOverlayNoPick(idx, true) // 瞬态视觉层退出拾取竞争，命中直接落源层
        selectedHighlightIndex = idx
        selectedPath = path
        selectedFid = fid
        when (fg.type) {
            0 -> mapView.updateOverlayPoints(idx, fg.lonlat, longArrayOf(fid))
            1 -> mapView.updateOverlayLines(
                idx, fg.lonlat, fg.ringCounts, LongArray(fg.ringCounts.size) { fid }
            )
            2 -> mapView.updateOverlayPolygons(
                idx, fg.lonlat, fg.ringCounts, fg.ringsPerFeature,
                LongArray(fg.ringsPerFeature.size) { fid }
            )
        }
    }

    /** 取消要素选中高亮（拆除高亮 overlay 层并清缓存，幂等）：点击空白或切换选中时调用 */
    fun clearSelectedHighlight() {
        if (selectedHighlightIndex >= 0) mapView.removeOverlayLayer(selectedHighlightIndex)
        selectedHighlightIndex = -1
        selectedPath = null
        selectedFid = -1
    }

    /** 叠加层重建（clearOverlayLayers）后调用：选中为瞬态交互，重建后取消选中、仅重置失效索引（对齐原主界面图层重载取消选中） */
    fun onOverlayLayersCleared() {
        selectedHighlightIndex = -1
    }

    /**
     * 弹文件矢量要素详情：弹层先行（几何/样式经 native 同步取，无文件 IO），全字段属性经
     * [GdalVectorReader.getFeatureAttributes] 后台回取后就地回填（[FeatureDetailDialog.pushAttributes]）——
     * KML/DXF 等格式首次回取需重开文件解析可达秒级，不再阻塞弹窗。KML/KMZ 的 <description> 字段
     * 与原主界面 LocalVectorLoader 同口径提取交 WebView 渲染。
     * 供 [onMapTap] 文件矢量命中与 SQL 查询高亮命中（穿透回源要素）复用。
     *
     * 调查编辑（取代原主界面全局调查模式）：shp/gpkg 开放样式编辑；可写格式开放属性编辑与（shp 面要素）样地调查入口，
     * 两者初始隐藏，由详情顶部“编辑”按键门控开放（[FeatureDetailDialog.editButtonVisible]）。
     * 几何类型与量算经 native [NativeMapView.featureGeometry] 取得（无文件 IO）。
     */
    fun showVectorFeatureDetail(path: String, fid: Long) {
        if (path.isEmpty() || fid < 0) return
        val layerInfo = DocumentManager.getInstance().getDocument()
            ?.vectorLayers?.firstOrNull { it.path == path }
        val layerName = layerInfo?.name
        val ext = path.substringAfterLast('.', "").lowercase(Locale.getDefault())
        // native 矢量层索引：供 featureGeometry 取几何类型（点/线/面）与量算
        val layerIndex = layerInfo?.let { layerManager.nativeIndexOf(it) } ?: -1
        // shp/gpkg/kml/kmz 均为 GDAL 索引矢量、拾取回传稳定 FID，支持整层/单要素颜色覆盖；dwg/dxf 只读不改样式
        val styleEditable = (ext == "shp" || ext == "gpkg" || ext == "kml" || ext == "kmz") && fid >= 0
        // 属性可编辑：可写格式 + 有效 FID（编辑权限由顶部“编辑”按键门控，此处只判定能力）
        val attrsEditable = fid >= 0 && isWritableFormat(ext) && ext != "kml" && ext != "kmz"
        // 几何类型与量算：经 native featureGeometry 取该要素经纬度几何（0 点 / 1 线 / 2 面），无文件 IO 可同步取
        val fg = if (layerIndex >= 0) mapView.featureGeometry(layerIndex, fid) else null
        val geomType = fg?.type ?: -1
        // 样地调查入口：shp 面要素（与原主界面 surveyEntryVisible 同口径，但不再依赖全局调查模式）
        val surveyEntry = ext == "shp" && geomType == 2 && fid >= 0
        // 样式初值：单要素覆盖优先，缺省继承整层样式（供控件回显）
        val layerStyle = layerInfo?.vectorStyle
        val featureStyle = layerStyle?.featureStyles?.get(fid)
        val merged = VectorStyle().apply {
            fillColor = featureStyle?.fillColor ?: layerStyle?.fillColor
            fillOpacity = featureStyle?.fillOpacity ?: layerStyle?.fillOpacity
            fillEnabled = featureStyle?.fillEnabled ?: layerStyle?.fillEnabled
            outlineColor = featureStyle?.outlineColor ?: layerStyle?.outlineColor
            outlineWidth = featureStyle?.outlineWidth ?: layerStyle?.outlineWidth
            labelField = featureStyle?.labelField ?: layerStyle?.labelField
            labelColor = featureStyle?.labelColor ?: layerStyle?.labelColor
            labelSize = featureStyle?.labelSize ?: layerStyle?.labelSize
            labelOutline = featureStyle?.labelOutline ?: layerStyle?.labelOutline
            labelOutlineColor = featureStyle?.labelOutlineColor ?: layerStyle?.labelOutlineColor
            iconKey = featureStyle?.iconKey ?: layerStyle?.iconKey
        }
        // 实际渲染色兜底（无文档样式覆盖时色块所见即所得）：优先取 native 逐要素实际渲染色
        // （含 KML 原色/单要素覆盖三级优先级算定结果，app 无从复现），取不到再回退整层解析色。
        val eff = if (layerIndex >= 0) mapView.featureRenderColor(layerIndex, fid) else null
        val renderColors = if (eff != null && eff.size >= 2)
            Pair(MapStyleColors.argbToHex(eff[0]), MapStyleColors.argbToHex(eff[1]))
        else layerManager.layerRenderColorsOf(path)
        // 弹层先行：属性回取后台进行，样地入口点击时取最新回填值（闭包捕获可变局部变量）
        var latestAttrs: Map<String, String>? = null
        val dialog = FeatureDetailDialog(
            R.layout.dialog_feature_detail,
            featureName = null,
            layerName = layerName,
            featureId = fid,
            attributes = null,
            attrsPending = true,
            description = null,
            initialStyle = merged.takeIf { !it.isEmpty() },
            styleEditable = styleEditable,
            fallbackFill = renderColors.first,
            fallbackOutline = renderColors.second,
            isPointFeature = geomType == 0,
            labelSectionVisible = ext != "kml" && ext != "kmz",
            geometryText = featureGeometryText(fg),
            onSave = { style ->
                val li = layerInfo
                if (li != null) {
                    val vs = li.vectorStyle ?: VectorStyle().also { li.vectorStyle = it }
                    if (style.isEmpty()) vs.featureStyles.remove(fid)
                    else vs.featureStyles[fid] = style
                    li.styleVersion++
                    // 保存后文档变更触发就地换层（样式版本入缓存键），旧选中高亮随层失效，先取消选中态
                    clearSelectedHighlight()
                    DocumentManager.getInstance().save(activity)
                }
            },
            attrsEditable = attrsEditable,
            onSaveAttributes = if (attrsEditable) { { pending -> persistAttributes(path, fid, pending) } } else null,
            surveyEntryVisible = surveyEntry,
            onSurveyEntry = if (surveyEntry) { { showSurveyEntryDialog(path, fid, latestAttrs, fg) } } else null,
            editButtonVisible = attrsEditable,
            surveyBypassEdit = surveyEntry
        )
        dialog.show(fm, "FeatureDetailDialog")
        // 属性异步回取 + 就地回填（固定高度内滚不改变弹层高度）；弹层可能已被用户关闭，isAdded 守卫
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val attrs = withContext(Dispatchers.IO) { GdalVectorReader.getFeatureAttributes(path, fid) }
            AppLog.i(
                TAG, "属性回取 file=${File(path).name} fid=$fid fields=${attrs?.size} " +
                    "cost=${SystemClock.elapsedRealtime() - t0}ms"
            )
            latestAttrs = attrs
            val descKey = attrs?.keys?.firstOrNull { it.equals("description", ignoreCase = true) }
            val description = descKey?.let { attrs[it]?.takeIf { v -> v.isNotBlank() } }
            if (dialog.isAdded) dialog.pushAttributes(attrs, description)
        }
    }

    /**
     * 属性写回（调查编辑）：调 GDAL 写回源文件，成功后 Toast 提示。
     * 本界面无原底层库 renderable 属性缓存，故仅写回源文件（下次拾取/重载即取到新值），不做就地缓存更新。
     * GDAL SetField+SetFeature 耗时可忽略，与原主界面一致同步写回（对话框等待结果决定是否关闭）。
     * @return 写回是否成功
     */
    private fun persistAttributes(path: String, fid: Long, pending: Map<String, String>): Boolean {
        if (pending.isEmpty()) return true
        var success = false
        try {
            success = GdalVectorReader.updateAttributes(path, fid, pending)
        } catch (e: Exception) {
            AppLog.e(TAG, "属性写回异常", e)
        }
        if (success) {
            Toast.makeText(activity, R.string.main_survey_edit_success, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(activity, R.string.main_survey_edit_failed, Toast.LENGTH_SHORT).show()
        }
        return success
    }

    /** 可写格式判定：shp/dxf/gpkg/geojson/kml/kmz 支持属性写回，dwg 只读 */
    private fun isWritableFormat(ext: String): Boolean = ext in WRITABLE_FORMATS

    /**
     * 样地调查入口对话框（复刻原主界面 MainActivity）：查询该要素已有样地记录，
     * 空则直接新建样地调查，非空则弹出单选列表（已有样地 + 新建）。定位未就绪时 Toast 提示并中止。
     * 进入前检查 GPS 是否在要素外环内，不在则提示“当前坐标不在当前小班内”但仍允许继续。
     */
    private fun showSurveyEntryDialog(path: String, fid: Long, attrs: Map<String, String>?, fg: FeatureGeometry?) {
        val loc = LocationManager.getInstance().getLastLocation()
        if (loc == null) {
            Toast.makeText(activity, R.string.main_survey_location_required, Toast.LENGTH_SHORT).show()
            return
        }
        // GPS 是否在要素外环内：射线法判定，不在时 Toast 提示但仍允许继续（外业可能需要在附近开始调查）
        if (fg != null && fg.type == 2 && fg.ringCounts.isNotEmpty()) {
            val outerCount = fg.ringCounts[0]
            if (outerCount >= 3 && outerCount * 2 <= fg.lonlat.size) {
                val outerRing = ArrayList<DoubleArray>(outerCount)
                for (i in 0 until outerCount) {
                    outerRing.add(doubleArrayOf(fg.lonlat[i * 2 + 1], fg.lonlat[i * 2]))
                }
                if (!GeoCalc.containsPoint(outerRing, loc.latitude, loc.longitude)) {
                    Toast.makeText(activity, "当前坐标不在当前小班内", Toast.LENGTH_LONG).show()
                }
            }
        }
        val plots = SampleSurveyStore.loadPlots(activity, path, fid)
        // 尝试从属性表提取小班编号（常见字段名），无则回退“未命名小班”
        val compartmentNo = attrs?.get("小班号")
            ?: attrs?.get("XBH")
            ?: attrs?.get("小班")
            ?: "未命名小班"
        if (plots.isEmpty()) {
            SampleSurveyActivity.start(activity, path, fid, compartmentNo, loc.latitude, loc.longitude)
            return
        }
        // 弹出单选列表：已有样地 + 新建样地调查
        val items = plots.map { "样地#${it.plotNo}" }.toMutableList()
        items.add("新建样地调查")
        AlertDialog.Builder(activity)
            .setTitle("样地调查")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < plots.size) {
                    // 编辑已有样地
                    SampleSurveyActivity.start(activity, path, fid, compartmentNo,
                        loc.latitude, loc.longitude, plots[which].id)
                } else {
                    // 新建样地
                    SampleSurveyActivity.start(activity, path, fid, compartmentNo,
                        loc.latitude, loc.longitude)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 要素几何量算（基于 native [NativeMapView.featureGeometry] 返回的经纬度几何）：
     * 面要素返回面积+周长（带洞多边形按各环带符号面积代数和），线要素返回长度；点/无几何返回 null（对话框隐藏该行）。
     * featureGeometry 的 lonlat 为 [lon,lat,…] 摊平，按 ringCounts 拆环后换序为 [lat,lon] 喂 [GeoCalc]。
     */
    private fun featureGeometryText(fg: FeatureGeometry?): String? {
        if (fg == null) return null
        val lonlat = fg.lonlat
        return when (fg.type) {
            2 -> {
                // 面：逐环累加带符号面积（洞为负）与周长
                var signedArea = 0.0
                var perimeter = 0.0
                var hasRing = false
                var offset = 0
                for (count in fg.ringCounts) {
                    if (count >= 3 && offset + count * 2 <= lonlat.size) {
                        val ring = ArrayList<DoubleArray>(count)
                        for (i in 0 until count) {
                            ring.add(doubleArrayOf(lonlat[offset + i * 2 + 1], lonlat[offset + i * 2]))
                        }
                        signedArea += GeoCalc.polygonSignedAreaSquareMeters(ring)
                        perimeter += GeoCalc.ringPerimeterMeters(ring)
                        hasRing = true
                    }
                    offset += count * 2
                }
                if (!hasRing) return null
                "面积：" + GeoCalc.formatArea(abs(signedArea)) +
                        "    周长：" + GeoCalc.formatDistance(perimeter)
            }
            1 -> {
                // 线：全部顶点作为一条折线量长
                val n = lonlat.size / 2
                if (n < 2) return null
                val degrees = ArrayList<DoubleArray>(n)
                for (i in 0 until n) {
                    degrees.add(doubleArrayOf(lonlat[i * 2 + 1], lonlat[i * 2]))
                }
                "长度：" + GeoCalc.formatDistance(GeoCalc.polylineLengthMeters(degrees))
            }
            else -> null
        }
    }

    companion object {
        // 保持与原 MainActivity 相同日志 tag，行为零变化
        private const val TAG = "MainActivity"

        // 支持属性写回的矢量格式（GDAL 驱动能力矩阵：dwg 只读，复刻原主界面 WRITABLE_FORMATS）
        private val WRITABLE_FORMATS = setOf("shp", "dxf", "gpkg", "geojson", "kml", "kmz")
    }
}
