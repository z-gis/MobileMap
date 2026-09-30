package com.zys.mobilemap.ui.map

import android.app.Activity
import com.zys.mobilemap.R
import com.zys.mobilemap.db.MapDataStore
import com.zys.mobilemap.db.MeasurementRecord
import com.zys.mobilemap.db.TrackRecord
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.media.MediaPlacemark
import com.zys.mobilemap.media.MediaStore
import com.zys.mobilemap.survey.SampleSurveyStore
import com.zys.mobilemap.survey.SurveyPlot
import com.zys.mobilemap.util.AppLog
import com.zys.mobilemap.util.GeoCalc
import com.zys.globecore.NativeMapView
import com.zys.globecore.VectorStyle as NativeVectorStyle

/**
 * 业务叠加层显示与命中映射（自 MainActivity 拆出）：测量/轨迹/拍照/样地数据（存于 map.db / media.db /
 * sample_survey.db，非文档）经 overlay 地基渲染，按数据签名判断是否重建。
 *
 * 采集态门控、查询高亮缓存、失效索引重置均经宿主接线：
 *  - [isCapturing]：测量/轨迹采集中跳过重建（保护 live 层）；
 *  - [onOverlayLayersCleared]：clearOverlayLayers 后重置查询/选中失效索引（接线 SqlQuery + Pick）；
 *  - [restoreQueryHighlight]：重建末尾按缓存要素恢复 SQL 查询高亮（接线 SqlQueryController）。
 * 命中判定经 [hitTest] 返回 [OverlayHit]（仅分类，记录读取与弹层由 FeaturePickController/宿主完成）。
 * 测量标注锚点计算抽到 [MeasureLabels]（与测量采集控制器共用）。
 */
internal class BusinessOverlayManager(
    private val activity: Activity,
    private val mapView: NativeMapView,
    private val isCapturing: () -> Boolean,
    private val onOverlayLayersCleared: () -> Unit,
    private val restoreQueryHighlight: () -> Unit
) {

    /**
     * 业务叠加层（测量/轨迹/拍照）数据签名：三类数据摘要 + 四个可见性开关。仅当签名变化才 clearOverlayLayers 重建，
     * 避免每次 onResume/文档变更无谓重建——叠加层 append-only，拆除的层以 dead 空壳留存至界面销毁（GL 资源已回收）。
     */
    private var overlaySignature: String? = null

    /** overlay 命中映射：叠加层索引 → 业务记录主键（rebuild 重建时刷新），供拾取命中分流业务详情 */
    private val measureOverlayIds = mutableMapOf<Int, Long>()
    private val trackOverlayIds = mutableMapOf<Int, Long>()
    private var mediaOverlayIndex = -1

    /** 样地点位叠加层索引（-1 无）：命中时按 fid=样地主键打开样地调查编辑（对齐原主界面 SurveyOverlayModel） */
    private var surveyOverlayIndex = -1

    /**
     * 重建业务叠加层：读设置可见性开关 + 三类独立数据库，按数据签名判断是否需重建（签名未变直接返回，避免累积墓碑层）。
     * 在 onCreate/onResume/文档变更（开关落盘）时调用。
     */
    fun rebuild() {
        // 采集进行中（测量/轨迹 live 层存在）：跳过重建，避免 clearOverlayLayers 清掉 live 层（采集退出时再重建）
        if (isCapturing()) return
        val cfg = DocumentManager.getInstance().getDocument()?.systemConfig
        val showMeasure = cfg?.showMeasureLayer ?: false
        val showTrack = cfg?.showTrackLayer ?: false
        val showMedia = cfg?.showMediaLayer ?: false
        val showPhotoName = cfg?.showPhotoName ?: false

        val measures: List<MeasurementRecord> =
            if (showMeasure) MapDataStore.getInstance(activity).loadMeasurements() else emptyList()
        val tracks: List<TrackRecord> =
            if (showTrack) MapDataStore.getInstance(activity).loadTracks() else emptyList()
        val photos: List<MediaPlacemark> =
            if (showMedia) MediaStore.getInstance(activity).loadPlacemarks() else emptyList()
        // 样地点位：无全局调查模式与显隐开关，常显（读 sample_survey.db）
        val plots: List<SurveyPlot> = SampleSurveyStore.loadAllPlots(activity)

        val sig = overlayDataSignature(showMeasure, showTrack, showMedia, showPhotoName, measures, tracks, photos, plots)
        if (sig == overlaySignature) return
        overlaySignature = sig

        mapView.clearOverlayLayers()
        // 刷新 overlay 命中映射（clearOverlayLayers 后叠加层索引重置，须随重建重新记录 index→业务主键）
        measureOverlayIds.clear()
        trackOverlayIds.clear()
        mediaOverlayIndex = -1
        surveyOverlayIndex = -1
        // 查询高亮层亦被清除：清空其索引记录；选中为瞬态交互，重建后取消选中（对齐原主界面图层重载取消选中）——
        // 两态分属 SqlQuery/Pick 域，经回调一并重置失效索引
        onOverlayLayersCleared()
        for (record in measures) addMeasurementOverlay(record)
        for (record in tracks) {
            if (record.visible && record.points.size >= 2) addTrackOverlay(record)
        }
        addMediaOverlay(photos, showPhotoName)
        addSurveyOverlay(plots)
        // 恢复 SQL 查询高亮（若有）：查询结果为临时叠加、不写文档，故缓存 features 于重建后重新渲染
        restoreQueryHighlight()
    }

    /**
     * 叠加数据签名：四个开关 + 各记录 id/样式/可见性/点数（拍照含坐标与名称）摘要拼接。
     * 测量/轨迹几何写后不可变（保存即定型），故点数足以捕获增删；样式/可见性变化亦纳入。任一变化即触发重建。
     */
    private fun overlayDataSignature(
        showMeasure: Boolean, showTrack: Boolean, showMedia: Boolean, showPhotoName: Boolean,
        measures: List<MeasurementRecord>, tracks: List<TrackRecord>, photos: List<MediaPlacemark>,
        plots: List<SurveyPlot>
    ): String = buildString {
        append(if (showMeasure) 'M' else '-').append(if (showTrack) 'T' else '-')
        append(if (showMedia) 'P' else '-').append(if (showPhotoName) 'N' else '-')
        for (m in measures) {
            append("|m").append(m.id).append(':').append(m.type).append(':')
                .append(m.color).append(':').append(m.lineWidth).append(':').append(m.points.size)
        }
        for (t in tracks) {
            append("|t").append(t.id).append(':').append(if (t.visible) 1 else 0).append(':')
                .append(t.color).append(':').append(t.lineWidth).append(':').append(t.points.size)
        }
        for (p in photos) {
            append("|p").append(p.id).append(':').append(if (p.visible) 1 else 0).append(':')
                .append(p.name).append(':').append(p.latitude).append(':').append(p.longitude)
        }
        for (s in plots) {
            append("|s").append(s.id).append(':').append(s.plotNo).append(':')
                .append(s.centerLatitude).append(':').append(s.centerLongitude).append(':').append(s.updatedAt)
        }
    }

    /**
     * 测量记录 → 叠加层（对齐原主界面 MeasureModel）：点位=彩心圆点 + 名称标注；
     * 距离=折线（几何层）+ 各测点累计距离（纯文字标注层）；面积=alpha 0.22 填充 + 描边（几何层）+ 各边中点边长
     * 与质心面积（纯文字标注层）。样式取记录自身 color/lineWidth；线/面顶点圆点标记从略（仅画主几何 + 标注）。
     */
    private fun addMeasurementOverlay(record: MeasurementRecord) {
        val pts = record.points
        if (pts.isEmpty()) return
        val color = record.color
        val lineW = record.lineWidth.toFloat()
        when (record.type) {
            MeasurementRecord.TYPE_POINT -> {
                val idx = mapView.addOverlayLayer(
                    NativeVectorStyle(
                        fillColor = color, outlineColor = color, outlineWidth = lineW,
                        lineColor = color, lineWidth = lineW, pointColor = color,
                        pointRadiusDp = MapStyleColors.MEASURE_POINT_RADIUS_DP,
                        labelColor = MapStyleColors.DEFAULT_LABEL_COLOR, labelSize = MapStyleColors.DEFAULT_LABEL_SIZE
                    )
                )
                if (idx < 0) return
                measureOverlayIds[idx] = record.id
                val p = pts[0]
                mapView.updateOverlayPoints(
                    idx, doubleArrayOf(p[1], p[0]), longArrayOf(record.id), arrayOf(record.name)
                )
            }
            MeasurementRecord.TYPE_DISTANCE -> {
                if (pts.size < 2) return
                val idx = mapView.addOverlayLayer(
                    NativeVectorStyle(
                        fillColor = color, outlineColor = color, outlineWidth = lineW,
                        lineColor = color, lineWidth = lineW, pointColor = color,
                        pointRadiusDp = MapStyleColors.MEASURE_POINT_RADIUS_DP,
                        labelColor = MapStyleColors.DEFAULT_LABEL_COLOR, labelSize = MapStyleColors.DEFAULT_LABEL_SIZE
                    )
                )
                if (idx < 0) return
                measureOverlayIds[idx] = record.id
                // 折线本体不挂整体标注；逐点累计距离由纯文字标注层承载（对齐原主界面 addDistanceLabels）
                mapView.updateOverlayLines(
                    idx, flattenLonLat(pts), intArrayOf(pts.size), longArrayOf(record.id), emptyArray()
                )
                addMeasureLabelOverlay(record.id, MeasurementRecord.TYPE_DISTANCE, pts)
            }
            MeasurementRecord.TYPE_AREA -> {
                if (pts.size < 3) return
                val fill = (color and 0x00FFFFFF) or
                        (((MapStyleColors.MEASURE_AREA_FILL_ALPHA * 255f).toInt() and 0xFF) shl 24)
                val idx = mapView.addOverlayLayer(
                    NativeVectorStyle(
                        fillColor = fill, outlineColor = color, outlineWidth = lineW,
                        lineColor = color, lineWidth = lineW, pointColor = color,
                        pointRadiusDp = MapStyleColors.MEASURE_POINT_RADIUS_DP,
                        labelColor = MapStyleColors.DEFAULT_LABEL_COLOR, labelSize = MapStyleColors.DEFAULT_LABEL_SIZE
                    )
                )
                if (idx < 0) return
                measureOverlayIds[idx] = record.id
                // 面环须闭合（首点补末尾）：native appendLineStrip 仅对首尾重合的环封口，开环缺末→首描边
                val ring = ArrayList<DoubleArray>(pts.size + 1).apply {
                    addAll(pts)
                    add(pts[0])
                }
                // 面本体不挂整体标注；各边中点边长 + 质心面积由纯文字标注层承载（对齐原主界面 addAreaEdgeLabels/addAreaLabel）
                mapView.updateOverlayPolygons(
                    idx, flattenLonLat(ring), intArrayOf(ring.size), intArrayOf(1),
                    longArrayOf(record.id), emptyArray()
                )
                addMeasureLabelOverlay(record.id, MeasurementRecord.TYPE_AREA, pts)
            }
        }
    }

    /**
     * 为已保存测量叠加“纯文字多标注层”（pointRadiusDp=0 → native 不画圆点，仅渲染标注文字）：
     * 与几何层同映射到 [recordId]，点击标注亦打开该测量详情。距离/面积外的类型无附加标注（直接返回）。
     */
    private fun addMeasureLabelOverlay(recordId: Long, type: String, pts: List<DoubleArray>) {
        val (lonlat, labels) = MeasureLabels.anchors(type, pts)
        if (lonlat.isEmpty()) return
        val idx = mapView.addOverlayLayer(
            NativeVectorStyle(
                fillColor = 0, outlineColor = 0, outlineWidth = 0f,
                lineColor = 0, lineWidth = 0f, pointColor = 0, pointRadiusDp = 0f,
                labelColor = MapStyleColors.DEFAULT_LABEL_COLOR, labelSize = MapStyleColors.DEFAULT_LABEL_SIZE
            )
        )
        if (idx < 0) return
        measureOverlayIds[idx] = recordId
        mapView.updateOverlayPoints(idx, lonlat, LongArray(labels.size) { recordId }, labels)
    }

    /** 轨迹记录 → 一线叠加层（对齐原主界面 TrackModel）：颜色/线宽取记录自身，无标注 */
    private fun addTrackOverlay(record: TrackRecord) {
        val color = record.color
        val lineW = record.lineWidth.toFloat()
        val idx = mapView.addOverlayLayer(
            NativeVectorStyle(
                fillColor = color, outlineColor = color, outlineWidth = lineW,
                lineColor = color, lineWidth = lineW, pointColor = color,
                pointRadiusDp = MapStyleColors.MEASURE_POINT_RADIUS_DP
            )
        )
        if (idx < 0) return
        trackOverlayIds[idx] = record.id
        mapView.updateOverlayLines(
            idx, flattenLonLat(record.points), intArrayOf(record.points.size), longArrayOf(record.id)
        )
    }

    /**
     * 拍照点位 → 一点叠加层（全部可见点合一层，对齐原主界面 MediaOverlayModel）：图标复用
     * ic_media（[decodeIconArgb] 解码为 ARGB 传入 native 作 billboard），fid=点位主键（供交互阶段查附件）；名称标注受
     * showPhotoName 控制、空名回退「拍照(附件数)」。分组可见性已由管理对话框写入各点 visible，故仅过滤 visible。
     */
    private fun addMediaOverlay(photos: List<MediaPlacemark>, showName: Boolean) {
        val visible = photos.filter { it.visible }
        if (visible.isEmpty()) return
        val icon = decodeIconArgb(activity, R.drawable.ic_media)
        val idx = mapView.addOverlayLayer(
            NativeVectorStyle(
                fillColor = MapStyleColors.DEFAULT_LINE_COLOR, outlineColor = MapStyleColors.DEFAULT_LINE_COLOR, outlineWidth = 1.0f,
                lineColor = MapStyleColors.DEFAULT_LINE_COLOR, lineWidth = 1.0f, pointColor = MapStyleColors.DEFAULT_LINE_COLOR,
                pointRadiusDp = MapStyleColors.PHOTO_POINT_RADIUS_DP,
                labelColor = MapStyleColors.DEFAULT_LABEL_COLOR, labelSize = MapStyleColors.DEFAULT_LABEL_SIZE
            ),
            iconArgb = icon?.first, iconW = icon?.second ?: 0, iconH = icon?.third ?: 0
        )
        if (idx < 0) return
        mediaOverlayIndex = idx
        val lonlat = DoubleArray(visible.size * 2)
        val fids = LongArray(visible.size)
        val labels = Array(visible.size) { "" }
        for (i in visible.indices) {
            val p = visible[i]
            lonlat[i * 2] = p.longitude
            lonlat[i * 2 + 1] = p.latitude
            fids[i] = p.id
            labels[i] = if (showName) mediaDisplayName(p) else ""
        }
        mapView.updateOverlayPoints(idx, lonlat, fids, labels)
    }

    /** 拍照点位显示名（对齐原主界面 MediaOverlayModel）：有名用名，空名回退「拍照(附件数)」/「拍照」 */
    private fun mediaDisplayName(p: MediaPlacemark): String {
        if (p.name.isNotBlank()) return p.name
        val count = p.attachments.size
        return if (count > 1) "拍照($count)" else "拍照"
    }

    /**
     * 样地点位 → 一点叠加层（全部样地合一层，对齐原主界面 SurveyOverlayModel）：
     * 图标复用 ic_sample（[decodeIconArgb] 解码为 ARGB 传入 native 作 billboard），fid=样地主键（供命中编辑），
     * 标注“样地#编号”。MainActivity 无全局调查模式，样地点位常显（SystemConfig 无对应开关）；
     * 无有效中心坐标的样地跳过（新建未定位的草稿不显示）。
     */
    private fun addSurveyOverlay(plots: List<SurveyPlot>) {
        val valid = plots.filter { !it.centerLatitude.isNaN() && !it.centerLongitude.isNaN() }
        if (valid.isEmpty()) return
        val icon = decodeIconArgb(activity, R.drawable.ic_sample)
        val idx = mapView.addOverlayLayer(
            NativeVectorStyle(
                fillColor = MapStyleColors.DEFAULT_LINE_COLOR, outlineColor = MapStyleColors.DEFAULT_LINE_COLOR, outlineWidth = 1.0f,
                lineColor = MapStyleColors.DEFAULT_LINE_COLOR, lineWidth = 1.0f, pointColor = MapStyleColors.DEFAULT_LINE_COLOR,
                pointRadiusDp = MapStyleColors.SURVEY_POINT_RADIUS_DP,
                labelColor = MapStyleColors.DEFAULT_LABEL_COLOR, labelSize = MapStyleColors.DEFAULT_LABEL_SIZE
            ),
            iconArgb = icon?.first, iconW = icon?.second ?: 0, iconH = icon?.third ?: 0
        )
        if (idx < 0) return
        surveyOverlayIndex = idx
        val lonlat = DoubleArray(valid.size * 2)
        val fids = LongArray(valid.size)
        val labels = Array(valid.size) { "" }
        for (i in valid.indices) {
            val p = valid[i]
            lonlat[i * 2] = p.centerLongitude
            lonlat[i * 2 + 1] = p.centerLatitude
            fids[i] = p.id
            labels[i] = "样地#${p.plotNo}"
        }
        mapView.updateOverlayPoints(idx, lonlat, fids, labels)
    }

    /** 业务点串 [lat,lon,(alt)] → overlay 摊平 [lon,lat,...]（换序：overlay/native 经度在前，业务库纬度在前）；见文件级 flattenLonLat */
    private fun flattenLonLat(points: List<DoubleArray>): DoubleArray = Companion.flattenLonLat(points)

    /**
     * 业务叠加命中分类：pickVector 逆序遍历使后加入的叠加层优先命中（对齐原主界面「测量/轨迹/拍照优先于文件矢量」）。
     * 仅按 overlay 索引映射判定命中业务类型并回传主键，DB 记录读取与详情弹层由 [FeaturePickController]/宿主完成。
     * 查询高亮层/选中高亮层的穿透不参与本分类（由 FeaturePickController 结合 SqlQuery 处理）。
     */
    fun hitTest(layerIndex: Int, fid: Long): OverlayHit {
        measureOverlayIds[layerIndex]?.let { return OverlayHit.Measure(it) }
        trackOverlayIds[layerIndex]?.let { return OverlayHit.Track(it) }
        if (layerIndex == mediaOverlayIndex && fid >= 0) return OverlayHit.Media(fid)
        if (layerIndex == surveyOverlayIndex && fid >= 0) return OverlayHit.Survey(fid)
        return OverlayHit.None
    }

    companion object {
        // 保持与原 MainActivity 相同日志 tag，行为零变化
        private const val TAG = "MainActivity"

        /**
         * 业务点串 [lat,lon,(alt)] → overlay 摊平 [lon,lat,...]（换序：overlay/native 经度在前，业务库纬度在前）。
         * 业务叠加显示、测量/轨迹 live 渲染共用（自 MainActivity 拆出提升为共享函数）。
         */
        internal fun flattenLonLat(points: List<DoubleArray>): DoubleArray {
            val out = DoubleArray(points.size * 2)
            for (i in points.indices) {
                out[i * 2] = points[i][1]      // lon
                out[i * 2 + 1] = points[i][0]  // lat
            }
            return out
        }
    }
}

/** 业务叠加命中结果（[BusinessOverlayManager.hitTest] 返回）：仅分类 + 业务主键，不加载记录。 */
internal sealed class OverlayHit {
    object None : OverlayHit()
    data class Measure(val recordId: Long) : OverlayHit()
    data class Track(val recordId: Long) : OverlayHit()
    data class Media(val pointId: Long) : OverlayHit()
    data class Survey(val plotId: Long) : OverlayHit()
}

/**
 * 测量标注锚点计算（自 MainActivity.measureLabelAnchors 抽为共用 object，业务叠加显示与测量采集 live 标注共用）。
 * 对齐原主界面 MeasureModel 的 addDistanceLabels / addAreaEdgeLabels / addAreaLabel：
 *  - 距离：各测点处标注从起点累计的距离（首点为 0）；
 *  - 面积：各边中点标注该边长（含末→首闭合边）+ 质心标注总面积。
 * [pts] 为 [lat,lon,...] 点串。返回 (摊平经纬度 [lon,lat,...], 标注文本)，无标注时返回空数组。
 */
internal object MeasureLabels {
    fun anchors(type: String, pts: List<DoubleArray>): Pair<DoubleArray, Array<String>> {
        val lonlat = ArrayList<Double>()
        val labels = ArrayList<String>()
        fun put(lat: Double, lon: Double, text: String) {
            lonlat.add(lon); lonlat.add(lat); labels.add(text)
        }
        fun putEdge(a: DoubleArray, b: DoubleArray) {
            val len = GeoCalc.distanceMeters(a[0], a[1], b[0], b[1])
            put((a[0] + b[0]) * 0.5, (a[1] + b[1]) * 0.5, GeoCalc.formatDistance(len))
        }
        when (type) {
            MeasurementRecord.TYPE_DISTANCE -> {
                var cumulative = 0.0
                for (i in pts.indices) {
                    if (i > 0) {
                        cumulative += GeoCalc.distanceMeters(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
                    }
                    put(pts[i][0], pts[i][1], GeoCalc.formatDistance(cumulative))
                }
            }
            MeasurementRecord.TYPE_AREA -> {
                if (pts.size >= 2) {
                    for (i in 1 until pts.size) putEdge(pts[i - 1], pts[i])
                    if (pts.size >= 3) putEdge(pts.last(), pts.first()) // 闭合边（末→首）
                }
                if (pts.size >= 3) {
                    val degrees = pts.map { doubleArrayOf(it[0], it[1]) }
                    val c = GeoCalc.polygonCentroid(degrees) // [lat, lon]
                    put(c[0], c[1], GeoCalc.formatArea(GeoCalc.polygonAreaSquareMeters(degrees)))
                }
            }
        }
        return Pair(lonlat.toDoubleArray(), labels.toTypedArray())
    }
}
