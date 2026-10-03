package com.zys.mobilemap.ui.map

import android.widget.Button
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.zys.mobilemap.db.MapDataStore
import com.zys.mobilemap.db.MeasurementRecord
import com.zys.mobilemap.ui.dialog.MeasureDetailDialog
import com.zys.mobilemap.util.GeoCalc
import com.zys.globecore.NativeMapView
import com.zys.globecore.VectorStyle as NativeVectorStyle
import java.util.Date
import java.util.Locale

/**
 * 测量采集控制器（自 MainActivity 拆出）：点/距离/面积三模式的菜单入口、单击/中心点加点、撤销、结束入库、退出，
 * live 叠加层（几何层 + 测点圆点标记层 + 纯文字标注层）实时渲染，以及测量详情/删除对话框。
 *
 * 采集态 [capturing] 由宿主组合进 [BusinessOverlayManager.rebuild] 的门控；结束/退出经注入的 [rebuildOverlays]
 * 刷新叠加层显示已存测量。采集子控件（撤销/中心点/结束）显隐由构造传入的按钮驱动。标注锚点计算共用 [MeasureLabels]。
 * 公开 [handleTap]：测量态消费点击（加点）返回 true，宿主拾取分发据此优先于矢量拾取。
 */
internal class MeasureCaptureController(
    private val activity: AppCompatActivity,
    private val mapView: NativeMapView,
    private val fm: FragmentManager,
    private val measureUndoButton: Button,
    private val measureCenterButton: Button,
    private val measureFinishButton: Button,
    private val rebuildOverlays: () -> Unit
) {

    /** 测量采集：模式 + 当前点串（[lat,lon]）+ live 叠加层索引（-1 无） */
    enum class MeasureMode { NONE, POINT, DISTANCE, AREA }

    private var measureMode = MeasureMode.NONE
    private val measurePoints = mutableListOf<DoubleArray>()
    private var measureLiveIndex = -1

    /** 测量 live 纯文字标注层索引（pointRadiusDp=0 → 不画圆点，仅渲染标注文字）：距离=各测点累计距离、面积=各边中点边长+质心面积 */
    private var measureLabelLiveIndex = -1

    /**
     * 测量 live 测点圆点标记层索引：DISTANCE/AREA 采集态下把每个已点击测点作为点要素即时画出圆点，
     * 给出「点已加上」的即时反馈（对齐原主界面 renderMeasurement 逐点 createPointPlacemark）。几何层此时被线/面占用
     * （native setData 整层替换，单层单帧只能一种几何类型），故顶点圆点须独立成层。
     */
    private var measureMarkerLiveIndex = -1

    /** 测量采集进行中（模式非 NONE）：宿主据此组合 capturing，保护 live 层不被 rebuild 清除 */
    val capturing: Boolean
        get() = measureMode != MeasureMode.NONE

    /** 测量菜单入口：点（位置）/ 距离 / 面积三模式（对齐原主界面 showMeasureMenu） */
    fun showMeasureMenu() {
        AlertDialog.Builder(activity)
            .setItems(arrayOf("点（位置）", "距离", "面积")) { _, which ->
                when (which) {
                    0 -> activateMeasureMode(MeasureMode.POINT, "点位测量：点击地图获取坐标")
                    1 -> activateMeasureMode(MeasureMode.DISTANCE, "距离测量：连续点击地图添加测点")
                    2 -> activateMeasureMode(MeasureMode.AREA, "面积测量：连续点击地图围成区域")
                }
            }
            .show()
    }

    /**
     * 进入测量模式：清空点串、按模式默认色新建 live 叠加层、显示采集子控件（撤销/中心点/结束）。
     * 进入后 [capturing] 转为 true（measureMode != NONE），[BusinessOverlayManager.rebuild] 期间被门控跳过以保护 live 层。
     */
    private fun activateMeasureMode(mode: MeasureMode, tip: String) {
        measureMode = mode
        measurePoints.clear()
        val color = defaultColorForMode(mode)
        // 面积模式 live 面填充改半透明（对齐已保存记录 BusinessOverlayManager 的 0.22 口径）：绘制中的面不再实心遮挡底图；
        // 描边/线/点仍保持不透明色，轮廓清晰。其余模式（点/距离）无面填充，不改动。
        val fillArgb = if (mode == MeasureMode.AREA) {
            val a = (MapStyleColors.MEASURE_AREA_FILL_ALPHA * 255f).toInt() and 0xFF
            (color and 0x00FFFFFF) or (a shl 24)
        } else color
        measureLiveIndex = mapView.addOverlayLayer(
            NativeVectorStyle(
                fillColor = fillArgb, outlineColor = color, outlineWidth = MapStyleColors.MEASURE_LIVE_WIDTH,
                lineColor = color, lineWidth = MapStyleColors.MEASURE_LIVE_WIDTH, pointColor = color,
                pointRadiusDp = MapStyleColors.MEASURE_POINT_RADIUS_DP,
                labelColor = MapStyleColors.DEFAULT_LABEL_COLOR, labelSize = MapStyleColors.DEFAULT_LABEL_SIZE
            )
        )
        // 纯文字标注层（pointRadiusDp=0 → 不画圆点，仅渲染标注文字）：承载逐点/逐边距离标注，对齐原主界面
        measureLabelLiveIndex = mapView.addOverlayLayer(
            NativeVectorStyle(
                fillColor = color, outlineColor = color, outlineWidth = MapStyleColors.MEASURE_LIVE_WIDTH,
                lineColor = color, lineWidth = MapStyleColors.MEASURE_LIVE_WIDTH, pointColor = color,
                pointRadiusDp = 0f,
                labelColor = MapStyleColors.DEFAULT_LABEL_COLOR, labelSize = MapStyleColors.DEFAULT_LABEL_SIZE
            )
        )
        // 测点圆点标记层（pointRadiusDp>0 → 画圆点）：DISTANCE/AREA 每次点击即在顶点画点，给出即时反馈
        measureMarkerLiveIndex = mapView.addOverlayLayer(
            NativeVectorStyle(
                fillColor = color, outlineColor = color, outlineWidth = MapStyleColors.MEASURE_LIVE_WIDTH,
                lineColor = color, lineWidth = MapStyleColors.MEASURE_LIVE_WIDTH, pointColor = color,
                pointRadiusDp = MapStyleColors.MEASURE_POINT_RADIUS_DP,
                labelColor = MapStyleColors.DEFAULT_LABEL_COLOR, labelSize = MapStyleColors.DEFAULT_LABEL_SIZE
            )
        )
        setMeasureControlsVisible(true)
        Toast.makeText(activity, tip, Toast.LENGTH_SHORT).show()
    }

    /** 测量采集子控件（撤销/中心点/结束）显隐：仅测量模式激活时可见 */
    private fun setMeasureControlsVisible(visible: Boolean) {
        val state = if (visible) View.VISIBLE else View.GONE
        measureUndoButton.visibility = state
        measureCenterButton.visibility = state
        measureFinishButton.visibility = state
    }

    /**
     * 测量态点击拦截：模式非 NONE 时把点击用于加点并返回 true（消费），否则返回 false 交宿主继续矢量拾取
     * （优先于拾取，对齐原主界面 VectorPickModel.onBeforePick 的测量拦截）。
     */
    fun handleTap(x: Float, y: Float): Boolean {
        if (measureMode == MeasureMode.NONE) return false
        val geo = mapView.screenToGeo(x, y) ?: return true
        addMeasurePoint(geo.latitude, geo.longitude)  // screenToGeo 返回 Position，入库点串用 [lat,lon]
        return true
    }

    /** 以屏幕中心（十字丝）作为测量点：中心像素 → 经纬度 → 加点 */
    fun addMeasureCenterPoint() {
        if (measureMode == MeasureMode.NONE) return
        val w = mapView.width
        val h = mapView.height
        if (w <= 0 || h <= 0) return
        val geo = mapView.screenToGeo(w * 0.5f, h * 0.5f)
        if (geo == null) {
            Toast.makeText(activity, "中心点未命中地表", Toast.LENGTH_SHORT).show()
            return
        }
        addMeasurePoint(geo.latitude, geo.longitude)
    }

    /** 撤销上一个测量点（点模式仅一点，撤销即清空），并实时重绘 live 层 */
    fun undoLastMeasurePoint() {
        if (measureMode == MeasureMode.NONE || measurePoints.isEmpty()) return
        measurePoints.removeAt(measurePoints.size - 1)
        renderLiveMeasure()
    }

    /**
     * 追加测量点（[lat,lon]）：点模式覆盖为单点，距离/面积模式累加；每次加点后实时重绘 live 层并按模式提示量算。
     */
    private fun addMeasurePoint(lat: Double, lon: Double) {
        if (measureMode == MeasureMode.POINT) {
            measurePoints.clear()
            measurePoints.add(doubleArrayOf(lat, lon, 0.0))
            renderLiveMeasure()
            Toast.makeText(
                activity, String.format(Locale.getDefault(), "位置: %.6f, %.6f", lat, lon), Toast.LENGTH_SHORT
            ).show()
            return
        }
        measurePoints.add(doubleArrayOf(lat, lon, 0.0))
        renderLiveMeasure()
        val degrees = measurePoints.map { doubleArrayOf(it[0], it[1]) }
        if (measureMode == MeasureMode.DISTANCE) {
            Toast.makeText(
                activity, "距离: " + GeoCalc.formatDistance(GeoCalc.polylineLengthMeters(degrees)), Toast.LENGTH_SHORT
            ).show()
        } else if (measureMode == MeasureMode.AREA && measurePoints.size >= 3) {
            Toast.makeText(
                activity, "面积: " + GeoCalc.formatArea(GeoCalc.polygonAreaSquareMeters(degrees)), Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 实时重绘测量 live 层（几何层 + 测点圆点标记层 + 纯文字标注层，均覆盖式）：点模式=点、
     * 距离=逐点圆点+折线（≥2 点连线）、面积=逐点圆点+开链折线（2 点）/闭环面（≥3 点）。每次点击先在顶点画圆点
     * 给出即时反馈（首点即有），再按点数阈值连线/成面。点数不足时推送空几何清除上一帧残留（撤销后回退到阈值以下）；
     * 距离/面积的逐点累计距离 / 逐边边长+质心面积标注由纯文字标注层承载（对齐原主界面），几何本体不再挂整体标注。
     */
    private fun renderLiveMeasure() {
        val idx = measureLiveIndex
        if (idx < 0) return
        // 每个已点击测点即时画圆点标记（DISTANCE/AREA），给出「点已加上」的即时反馈（对齐原主界面逐点 placemark）
        renderLiveMarkers()
        when (measureMode) {
            MeasureMode.POINT -> {
                val p = measurePoints.firstOrNull()
                if (p == null) {
                    mapView.updateOverlayPoints(idx, DoubleArray(0), LongArray(0), emptyArray())
                } else {
                    mapView.updateOverlayPoints(
                        idx, doubleArrayOf(p[1], p[0]), longArrayOf(0L), arrayOf("测量点")
                    )
                }
                clearMeasureLiveLabels()
            }
            MeasureMode.DISTANCE -> {
                if (measurePoints.size < 2) {
                    // 仅 1 点：暂不连线（圆点标记已给出反馈），清线 + 清标注
                    mapView.updateOverlayLines(idx, DoubleArray(0), IntArray(0), LongArray(0), emptyArray())
                    clearMeasureLiveLabels()
                    return
                }
                // 折线本体不挂整体标注，改由纯文字标注层在各测点显示累计距离（对齐原主界面 addDistanceLabels）
                mapView.updateOverlayLines(
                    idx, BusinessOverlayManager.flattenLonLat(measurePoints), intArrayOf(measurePoints.size), longArrayOf(0L), emptyArray()
                )
                updateMeasureLiveLabels(MeasurementRecord.TYPE_DISTANCE, measurePoints)
            }
            MeasureMode.AREA -> {
                if (measurePoints.size < 2) {
                    // 0/1 点：清几何（圆点标记已给出反馈）+ 清标注
                    mapView.updateOverlayPolygons(
                        idx, DoubleArray(0), IntArray(0), IntArray(0), LongArray(0), emptyArray()
                    )
                    clearMeasureLiveLabels()
                    return
                }
                if (measurePoints.size == 2) {
                    // 2 点：先画开链折线给出「连线」反馈（对齐原主界面 ≥2 画 path），标注层显示该边边长
                    mapView.updateOverlayLines(
                        idx, BusinessOverlayManager.flattenLonLat(measurePoints), intArrayOf(2), longArrayOf(0L), emptyArray()
                    )
                    updateMeasureLiveLabels(MeasurementRecord.TYPE_AREA, measurePoints)
                    return
                }
                val ring = ArrayList<DoubleArray>(measurePoints.size + 1).apply {
                    addAll(measurePoints)
                    add(measurePoints[0])  // 面环闭合（首点补末尾）
                }
                // 面本体不挂整体标注，改由纯文字标注层显示各边中点边长 + 质心面积（对齐原主界面）
                mapView.updateOverlayPolygons(
                    idx, BusinessOverlayManager.flattenLonLat(ring), intArrayOf(ring.size), intArrayOf(1), longArrayOf(0L), emptyArray()
                )
                updateMeasureLiveLabels(MeasurementRecord.TYPE_AREA, measurePoints)
            }
            MeasureMode.NONE -> Unit
        }
    }

    /**
     * 测点圆点标记层（DISTANCE/AREA 采集态）：把每个已点击测点作为点要素推入，即时给出「点已加上」的反馈，
     * 对齐原主界面 renderMeasurement 里对每个 measurePoint 的 createPointPlacemark。点模式由几何层画点，此处清空标记层。
     */
    private fun renderLiveMarkers() {
        val idx = measureMarkerLiveIndex
        if (idx < 0) return
        val showDots = (measureMode == MeasureMode.DISTANCE || measureMode == MeasureMode.AREA) && measurePoints.isNotEmpty()
        if (showDots) {
            mapView.updateOverlayPoints(idx, BusinessOverlayManager.flattenLonLat(measurePoints))
        } else {
            mapView.updateOverlayPoints(idx, DoubleArray(0), LongArray(0), emptyArray())
        }
    }

    /** 用测量多标注刷新 live 纯文字标注层（采集态，fid 统一 0：采集期点击用于加点、不参与拾取分流） */
    private fun updateMeasureLiveLabels(type: String, pts: List<DoubleArray>) {
        val idx = measureLabelLiveIndex
        if (idx < 0) return
        val (lonlat, labels) = MeasureLabels.anchors(type, pts)
        if (lonlat.isEmpty()) {
            mapView.updateOverlayPoints(idx, DoubleArray(0), LongArray(0), emptyArray())
        } else {
            mapView.updateOverlayPoints(idx, lonlat, LongArray(labels.size) { 0L }, labels)
        }
    }

    /** 清空 live 纯文字标注层（点数不足/点模式时调用，避免残留标注） */
    private fun clearMeasureLiveLabels() {
        val idx = measureLabelLiveIndex
        if (idx >= 0) mapView.updateOverlayPoints(idx, DoubleArray(0), LongArray(0), emptyArray())
    }

    /**
     * 结束当前测量：校验点数（距离≥2 / 面积≥3）后入库，退出测量模式（拆 live 层 + 重建叠加层显示新测量）。
     * 名称格式与原主界面一致：模式名 + 起始时间戳。
     */
    fun finishMeasurement() {
        if (measureMode == MeasureMode.NONE || measurePoints.isEmpty()) {
            exitMeasureMode()
            return
        }
        if (measureMode == MeasureMode.DISTANCE && measurePoints.size < 2) {
            Toast.makeText(activity, "至少需要两个点", Toast.LENGTH_SHORT).show()
            return
        }
        if (measureMode == MeasureMode.AREA && measurePoints.size < 3) {
            Toast.makeText(activity, "至少需要三个点", Toast.LENGTH_SHORT).show()
            return
        }
        val type = when (measureMode) {
            MeasureMode.POINT -> MeasurementRecord.TYPE_POINT
            MeasureMode.AREA -> MeasurementRecord.TYPE_AREA
            else -> MeasurementRecord.TYPE_DISTANCE
        }
        val now = System.currentTimeMillis()
        val name = String.format(
            Locale.getDefault(), "%s %2\$tY-%2\$tm-%2\$td %2\$tH:%2\$tM:%2\$tS",
            measureModeDisplayName(measureMode), Date(now)
        )
        val points = measurePoints.map { doubleArrayOf(it[0], it[1], it.getOrElse(2) { 0.0 }) }
        val id = MapDataStore.getInstance(activity)
            .addMeasurement(name, type, defaultColorForMode(measureMode), now, points)
        if (id >= 0) Toast.makeText(activity, "测量已保存", Toast.LENGTH_SHORT).show()
        exitMeasureMode()
    }

    /** 退出测量模式：清状态、拆 live 层、隐藏采集子控件，并重建叠加层（此时 capturing 转 false）显示已存测量 */
    private fun exitMeasureMode() {
        measureMode = MeasureMode.NONE
        measurePoints.clear()
        if (measureLiveIndex >= 0) {
            mapView.removeOverlayLayer(measureLiveIndex)
            measureLiveIndex = -1
        }
        if (measureLabelLiveIndex >= 0) {
            mapView.removeOverlayLayer(measureLabelLiveIndex)
            measureLabelLiveIndex = -1
        }
        if (measureMarkerLiveIndex >= 0) {
            mapView.removeOverlayLayer(measureMarkerLiveIndex)
            measureMarkerLiveIndex = -1
        }
        setMeasureControlsVisible(false)
        rebuildOverlays()
    }

    /**
     * 测量详情 BottomSheet（复用原主界面 [MeasureDetailDialog]）：标题=名称，样式区（颜色 + 线宽，点测量无线要素隐藏线宽），
     * 保存写库后经 [rebuildOverlays] 依签名变化重渲染，删除走 [confirmDeleteMeasurement]。
     */
    fun showMeasureDetail(record: MeasurementRecord) {
        MeasureDetailDialog(
            title = record.name,
            typeText = measureTypeDisplayName(record.type),
            geometryText = measureGeometryText(record),
            widthEditable = record.type != MeasurementRecord.TYPE_POINT,
            initialColor = record.color,
            initialWidth = record.lineWidth,
            onSave = { color, width ->
                MapDataStore.getInstance(activity).updateMeasurementStyle(record.id, color, width)
                rebuildOverlays()
                Toast.makeText(activity, "样式已更新", Toast.LENGTH_SHORT).show()
            },
            onDelete = { confirmDeleteMeasurement(record) }
        ).show(fm, "MeasureDetailDialog")
    }

    /** 删除测量确认：删库并重建叠加层 */
    private fun confirmDeleteMeasurement(record: MeasurementRecord) {
        AlertDialog.Builder(activity)
            .setTitle("删除确认")
            .setMessage("确定删除测量数据：${record.name} ？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                MapDataStore.getInstance(activity).deleteMeasurement(record.id)
                rebuildOverlays()
                Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 测量类型显示名（详情卡片） */
    private fun measureTypeDisplayName(type: String): String = when (type) {
        MeasurementRecord.TYPE_POINT -> "点位测量"
        MeasurementRecord.TYPE_DISTANCE -> "距离测量"
        MeasurementRecord.TYPE_AREA -> "面积测量"
        else -> "测量"
    }

    /** 测量模式显示名（用于生成记录名称前缀） */
    private fun measureModeDisplayName(mode: MeasureMode): String = when (mode) {
        MeasureMode.POINT -> "点位测量"
        MeasureMode.DISTANCE -> "距离测量"
        MeasureMode.AREA -> "面积测量"
        else -> "测量"
    }

    /** 几何量算文本：点=坐标，距离=总长，面积=面积 + 周长（复用 [GeoCalc]） */
    private fun measureGeometryText(record: MeasurementRecord): String? {
        if (record.points.isEmpty()) return null
        return when (record.type) {
            MeasurementRecord.TYPE_POINT -> {
                val p = record.points[0]
                String.format(Locale.getDefault(), "坐标: %.6f, %.6f", p[0], p[1])
            }
            MeasurementRecord.TYPE_DISTANCE ->
                "距离: " + GeoCalc.formatDistance(
                    GeoCalc.polylineLengthMeters(record.points.map { doubleArrayOf(it[0], it[1]) })
                )
            MeasurementRecord.TYPE_AREA -> {
                if (record.points.size < 3) return null
                val degrees = record.points.map { doubleArrayOf(it[0], it[1]) }
                val closed = ArrayList(degrees).apply { add(degrees.first()) }
                "面积: " + GeoCalc.formatArea(GeoCalc.polygonAreaSquareMeters(degrees)) +
                        "  周长: " + GeoCalc.formatDistance(GeoCalc.polylineLengthMeters(closed))
            }
            else -> null
        }
    }

    /** 测量模式默认色：距离=蓝、面积=绿、点位=黄（对齐原主界面 MeasureModel.defaultColorForMode） */
    private fun defaultColorForMode(mode: MeasureMode): Int = when (mode) {
        MeasureMode.AREA -> MapStyleColors.MEASURE_COLOR_AREA
        MeasureMode.POINT -> MapStyleColors.MEASURE_COLOR_POINT
        else -> MapStyleColors.MEASURE_COLOR_DISTANCE
    }
}
