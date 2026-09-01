package com.zys.mobilemap.measure

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zys.mobilemap.databinding.ActivityMainBinding
import com.zys.mobilemap.db.MapDataStore
import com.zys.mobilemap.db.MeasurementRecord
import com.zys.mobilemap.ui.dialog.MeasureDetailDialog
import com.zys.mobilemap.util.GeoCalc
import earth.worldwind.PickedObjectList
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 测量工具模块（参照上一版本）：点（位置）/距离/面积三种测量模式。
 * 测量过程点击地图加点实时渲染，结束后保存至 /调查宝/map/map.db，
 * 启动时读取数据库渲染历史测量。
 * 图层可见性由设置（[com.zys.mobilemap.doc.SystemConfig.showMeasureLayer]）驱动。
 */
class MeasureModel(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val worldWindow: WorldWindow,
    private val scope: CoroutineScope
) {

    private enum class Mode { NONE, POINT, DISTANCE, AREA }

    /** 已保存测量渲染图层 */
    val savedLayer: RenderableLayer = RenderableLayer().apply {
        displayName = "测量"
        putUserProperty(MEASURE_LAYER_KEY, true)
    }

    /** 当前测量过程渲染图层 */
    private val liveLayer: RenderableLayer = RenderableLayer().apply {
        displayName = "测量(进行中)"
        putUserProperty(MEASURE_LAYER_KEY, true)
    }

    /** 当前测量模式 */
    private var mode: Mode = Mode.NONE

    /** 当前测量点列表 */
    private val measurePoints = mutableListOf<Position>()

    /** 已保存测量记录缓存（点击命中判定用，与数据库同步） */
    private var recordsCache: List<MeasurementRecord> = emptyList()

    /** 进入测量模式前的非测量控件可见性快照，退出时恢复 */
    private val visibilitySnapshot = LinkedHashMap<View, Int>()

    /** 图标密度缩放系数：高分屏按密度放大像素尺寸，保证可见大小与可点性 */
    private val iconDensityScale: Double
        get() = activity.resources.displayMetrics.density.toDouble().coerceAtLeast(1.0)

    /**
     * 将图层加入地图并绑定控制按钮（幂等）。
     */
    fun setup() {
        if (!worldWindow.engine.layers.contains(savedLayer)) {
            worldWindow.engine.layers.addLayer(savedLayer)
            worldWindow.engine.layers.addLayer(liveLayer)
        }

        binding.mainIvMeasure.setOnClickListener { showMeasureMenu() }
        binding.mainIvMeasureUndo.setOnClickListener { undoLastPoint() }
        binding.mainIvMeasureCenter.setOnClickListener { addCenterPoint() }
        binding.mainIvMeasureFinish.setOnClickListener { finishCurrentMeasurement() }
        setMeasureControlsVisible(false)
    }

    /**
     * 按设置可见性显示/隐藏。
     */
    fun setEnabled(visible: Boolean) {
        savedLayer.isEnabled = visible
        liveLayer.isEnabled = visible
        worldWindow.requestRedraw()
    }

    /**
     * 从数据库加载全部历史测量并渲染（同步命中判定缓存）。
     */
    fun reloadSaved() {
        val records = MapDataStore.getInstance(activity).loadMeasurements()
        recordsCache = records
        savedLayer.clearRenderables()
        for (record in records) {
            appendRecordRenderables(record, savedLayer)
        }
        worldWindow.requestRedraw()
    }

    /**
     * 已保存测量几何点击拾取（参照上一版 MainMeasureModule）：
     * 非测量模式下按地表命中点与高度换算容差判定（点取距离、面取包含+边界、
     * 距离取线邻域），命中弹出测量详情 BottomSheet（标题+样式编辑+按键）并消费该点击。
     */
    fun handlePickedGeometry(x: Float, y: Float, picked: PickedObjectList): Boolean {
        if (mode != Mode.NONE || !savedLayer.isEnabled) return false
        val ground = picked.terrainPickedObject?.terrainPosition ?: return false
        val threshold = computeTapToleranceMeters()
        val record = recordsCache.firstOrNull {
            isHitRecord(it, ground.latitude.inDegrees, ground.longitude.inDegrees, threshold)
        } ?: return false
        showMeasureDetail(record)
        return true
    }

    /**
     * 处理地图单击：测量模式激活时按拾取的地表位置加点。
     *
     * @return 是否消费了该点击（测量模式下拦截矢量要素拾取）
     */
    fun onMapTap(x: Float, y: Float): Boolean {
        if (mode == Mode.NONE) return false
        scope.launch {
            val picked = try {
                worldWindow.pickAsync(x, y).await()
            } catch (e: Exception) {
                return@launch
            }
            val ground = picked.terrainPickedObject?.terrainPosition ?: return@launch
            addMeasurePoint(ground.latitude.inDegrees, ground.longitude.inDegrees)
        }
        return true
    }

    // ==================== 已保存测量几何点击（命中判定 + 操作菜单） ====================

    /**
     * 高度换算点击容差：相当于屏幕 32 像素的地面距离（垂直 fov≈45°），下限 20 米。
     * 上限放宽至 10000 米（旧 260 米上限导致高空缩小后单点地面跨度远超容差，
     * 视觉上点在几何上也无法命中，表现为必须放大后才能点击）。
     */
    private fun computeTapToleranceMeters(): Double {
        val altitude = worldWindow.engine.camera.position.altitude
        val screenH = worldWindow.height.coerceAtLeast(1)
        val meters = altitude * 0.8284 * 32.0 / screenH
        return meters.coerceIn(20.0, 10000.0)
    }

    private fun isHitRecord(record: MeasurementRecord, lat: Double, lon: Double, threshold: Double): Boolean {
        if (record.points.isEmpty()) return false
        val pointThreshold = maxOf(18.0, threshold * 0.85)
        val edgeThreshold = maxOf(20.0, threshold)
        return when (record.type) {
            MeasurementRecord.TYPE_POINT -> {
                val p = record.points[0]
                GeoCalc.distanceMeters(p[0], p[1], lat, lon) <= pointThreshold
            }
            MeasurementRecord.TYPE_AREA ->
                containsInRing(record.points, lat, lon) ||
                        minEdgeDistanceMeters(record.points, lat, lon, closed = true) <= edgeThreshold
            else -> minEdgeDistanceMeters(record.points, lat, lon, closed = false) <= edgeThreshold
        }
    }

    /** 点到折线各段最小距离（面要素含闭合边） */
    private fun minEdgeDistanceMeters(points: List<DoubleArray>, lat: Double, lon: Double, closed: Boolean): Double {
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

    /** 点到线段距离（局部等角平面近似，同上一版） */
    private fun distanceToSegmentMeters(lat: Double, lon: Double, a: DoubleArray, b: DoubleArray): Double {
        val lat0 = Math.toRadians((a[0] + b[0] + lat) / 3.0)
        val r = 6371000.0
        val px = r * Math.toRadians(lon) * cos(lat0)
        val py = r * Math.toRadians(lat)
        val ax = r * Math.toRadians(a[1]) * cos(lat0)
        val ay = r * Math.toRadians(a[0])
        val bx = r * Math.toRadians(b[1]) * cos(lat0)
        val by = r * Math.toRadians(b[0])
        val dx = bx - ax
        val dy = by - ay
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

    /** 射线法判定点是否在多边形内（经纬度平面近似） */
    private fun containsInRing(points: List<DoubleArray>, lat: Double, lon: Double): Boolean {
        if (points.size < 3) return false
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val yi = points[i][0]
            val xi = points[i][1]
            val yj = points[j][0]
            val xj = points[j][1]
            if ((yi > lat) != (yj > lat)) {
                val intersectLon = (xj - xi) * (lat - yi) / (yj - yi) + xi
                if (lon < intersectLon) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * 测量详情 BottomSheet（与要素详情弹层雷同）：标题=测量名称，
     * 中间样式区（颜色选色器 + 线宽步进，点测量无线要素隐藏线宽），
     * 底部按键：缩放至/删除/保存。
     */
    private fun showMeasureDetail(record: MeasurementRecord) {
        val fm = (activity as? AppCompatActivity)?.supportFragmentManager ?: return
        MeasureDetailDialog(
            title = record.name,
            typeText = measurementTypeDisplayName(record.type),
            geometryText = measurementGeometryText(record),
            widthEditable = record.type != MeasurementRecord.TYPE_POINT,
            initialColor = record.color,
            initialWidth = record.lineWidth,
            onZoom = { zoomToRecord(record) },
            onSave = { color, width ->
                MapDataStore.getInstance(activity).updateMeasurementStyle(record.id, color, width)
                reloadSaved()
                Toast.makeText(activity, "样式已更新", Toast.LENGTH_SHORT).show()
            },
            onDelete = { confirmDeleteRecord(record) }
        ).show(fm, "MeasureDetailDialog")
    }

    /** 测量类型显示名（详情弹层信息卡片） */
    private fun measurementTypeDisplayName(type: String): String = when (type) {
        MeasurementRecord.TYPE_POINT -> "点位测量"
        MeasurementRecord.TYPE_DISTANCE -> "距离测量"
        MeasurementRecord.TYPE_AREA -> "面积测量"
        else -> "测量"
    }

    /** 几何量算文本：距离=总长，面积=面积+周长，点=坐标 */
    private fun measurementGeometryText(record: MeasurementRecord): String? {
        if (record.points.isEmpty()) return null
        return when (record.type) {
            MeasurementRecord.TYPE_POINT -> {
                val p = record.points[0]
                String.format(Locale.getDefault(), "坐标: %.6f, %.6f", p[0], p[1])
            }
            MeasurementRecord.TYPE_DISTANCE -> {
                "距离: " + GeoCalc.formatDistance(
                    GeoCalc.polylineLengthMeters(record.points.map { doubleArrayOf(it[0], it[1]) })
                )
            }
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

    /** 相机移动到测量几何四至中心（高度按跨度换算） */
    private fun zoomToRecord(record: MeasurementRecord) {
        if (record.points.isEmpty()) return
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (p in record.points) {
            minLat = minOf(minLat, p[0])
            maxLat = maxOf(maxLat, p[0])
            minLon = minOf(minLon, p[1])
            maxLon = maxOf(maxLon, p[1])
        }
        val span = maxOf(maxLon - minLon, maxLat - minLat).coerceAtLeast(0.001)
        val altitude = maxOf(span * 111320.0 * 1.5, 300.0)
        val camera = worldWindow.engine.camera
        camera.set(
            Angle.fromDegrees((minLat + maxLat) / 2.0),
            Angle.fromDegrees((minLon + maxLon) / 2.0),
            altitude,
            AltitudeMode.ABSOLUTE,
            Angle.fromDegrees(0.0),
            Angle.fromDegrees(0.0),
            Angle.fromDegrees(0.0)
        )
        worldWindow.requestRedraw()
    }

    /** 删除确认：删库并重载图层 */
    private fun confirmDeleteRecord(record: MeasurementRecord) {
        AlertDialog.Builder(activity)
            .setTitle("删除确认")
            .setMessage("确定删除测量数据：${record.name} ？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                MapDataStore.getInstance(activity).deleteMeasurement(record.id)
                reloadSaved()
                Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showMeasureMenu() {
        AlertDialog.Builder(activity)
            .setItems(arrayOf("点（位置）", "距离", "面积")) { _, which ->
                when (which) {
                    0 -> activateMode(Mode.POINT, "点位测量：点击地图获取坐标")
                    1 -> activateMode(Mode.DISTANCE, "距离测量：连续点击地图添加测点")
                    2 -> activateMode(Mode.AREA, "面积测量：连续点击地图围成区域")
                }
            }
            .show()
    }

    private fun activateMode(newMode: Mode, tip: String) {
        mode = newMode
        measurePoints.clear()
        clearLiveRenderables()
        hideNonMeasureControls(true)
        setMeasureControlsVisible(true)
        Toast.makeText(activity, tip, Toast.LENGTH_SHORT).show()
    }

    private fun undoLastPoint() {
        if (mode == Mode.NONE || measurePoints.isEmpty()) return
        measurePoints.removeAt(measurePoints.size - 1)
        renderMeasurement()
    }

    /** 以屏幕中心点作为测量点（十字丝辅助加点） */
    private fun addCenterPoint() {
        if (mode == Mode.NONE) return
        if (worldWindow.width <= 0 || worldWindow.height <= 0) return
        scope.launch {
            val picked = try {
                worldWindow.pickAsync(worldWindow.width * 0.5f, worldWindow.height * 0.5f).await()
            } catch (e: Exception) {
                return@launch
            }
            val ground = picked.terrainPickedObject?.terrainPosition
            if (ground == null) {
                Toast.makeText(activity, "中心点未命中地表", Toast.LENGTH_SHORT).show()
                return@launch
            }
            addMeasurePoint(ground.latitude.inDegrees, ground.longitude.inDegrees)
        }
    }

    private fun addMeasurePoint(lat: Double, lon: Double) {
        val point = Position.fromDegrees(lat, lon, 0.0)

        if (mode == Mode.POINT) {
            measurePoints.clear()
            measurePoints.add(point)
            renderMeasurement()
            Toast.makeText(
                activity,
                String.format(Locale.getDefault(), "位置: %.6f, %.6f", lat, lon),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        measurePoints.add(point)
        renderMeasurement()

        val degrees = pointsAsDegrees(measurePoints)
        if (mode == Mode.DISTANCE) {
            Toast.makeText(
                activity,
                "距离: " + GeoCalc.formatDistance(GeoCalc.polylineLengthMeters(degrees)),
                Toast.LENGTH_SHORT
            ).show()
        } else if (mode == Mode.AREA && measurePoints.size >= 3) {
            Toast.makeText(
                activity,
                "面积: " + GeoCalc.formatArea(GeoCalc.polygonAreaSquareMeters(degrees)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 结束当前测量：校验点数后保存到数据库并渲染到历史图层。
     */
    private fun finishCurrentMeasurement() {
        if (mode == Mode.NONE || measurePoints.isEmpty()) {
            exitMeasurementMode()
            return
        }

        if (mode == Mode.DISTANCE && measurePoints.size < 2) {
            Toast.makeText(activity, "至少需要两个点", Toast.LENGTH_SHORT).show()
            return
        }
        if (mode == Mode.AREA && measurePoints.size < 3) {
            Toast.makeText(activity, "至少需要三个点", Toast.LENGTH_SHORT).show()
            return
        }

        val type = when (mode) {
            Mode.POINT -> MeasurementRecord.TYPE_POINT
            Mode.AREA -> MeasurementRecord.TYPE_AREA
            else -> MeasurementRecord.TYPE_DISTANCE
        }
        val now = System.currentTimeMillis()
        val name = String.format(
            Locale.getDefault(),
            "%s %2\$tY-%2\$tm-%2\$td %2\$tH:%2\$tM:%2\$tS",
            modeDisplayName(mode), Date(now)
        )
        val points = measurePoints.map {
            doubleArrayOf(it.latitude.inDegrees, it.longitude.inDegrees, it.altitude)
        }
        val id = MapDataStore.getInstance(activity)
            .addMeasurement(name, type, defaultColorForMode(mode), now, points)
        if (id >= 0) {
            appendRecordRenderables(
                MeasurementRecord(id, name, type, defaultColorForMode(mode), now, points,
                    MeasurementRecord.DEFAULT_LINE_WIDTH),
                savedLayer
            )
            worldWindow.requestRedraw()
            Toast.makeText(activity, "测量已保存", Toast.LENGTH_SHORT).show()
        }

        exitMeasurementMode()
    }

    private fun exitMeasurementMode() {
        mode = Mode.NONE
        measurePoints.clear()
        clearLiveRenderables()
        setMeasureControlsVisible(false)
        hideNonMeasureControls(false)
    }

    private fun setMeasureControlsVisible(visible: Boolean) {
        val state = if (visible) View.VISIBLE else View.GONE
        binding.mainIvMeasureUndo.visibility = state
        binding.mainIvMeasureCenter.visibility = state
        binding.mainIvMeasureFinish.visibility = state
    }

    /**
     * 测量模式进入时隐藏非测量控件（快照），退出时恢复。
     */
    private fun hideNonMeasureControls(hidden: Boolean) {
        if (hidden) {
            captureAndHide(binding.mainIvLayerManage)
            captureAndHide(binding.mainIvPoto)
            captureAndHide(binding.mainIvZoomIn)
            captureAndHide(binding.mainIvZoomOut)
            captureAndHide(binding.mainIvSetting)
            captureAndHide(binding.mainIvGotoLocation)
            captureAndHide(binding.mainIvTrk)
            captureAndHide(binding.mainIvTrkStart)
            captureAndHide(binding.mainIvTrkPause)
            captureAndHide(binding.mainIvTrkStop)
            return
        }
        for ((view, visibility) in visibilitySnapshot) {
            view.visibility = visibility
        }
        visibilitySnapshot.clear()
    }

    private fun captureAndHide(view: View) {
        if (!visibilitySnapshot.containsKey(view)) {
            visibilitySnapshot[view] = view.visibility
        }
        view.visibility = View.GONE
    }

    private fun clearLiveRenderables() {
        liveLayer.clearRenderables()
        worldWindow.requestRedraw()
    }

    /**
     * 渲染当前测量过程到临时图层。
     */
    private fun renderMeasurement() {
        liveLayer.clearRenderables()

        if (mode == Mode.POINT) {
            measurePoints.firstOrNull()?.let {
                liveLayer.addRenderable(createPointPlacemark(it, "测量点"))
            }
            worldWindow.requestRedraw()
            return
        }

        for (point in measurePoints) {
            liveLayer.addRenderable(createPointPlacemark(point, ""))
        }

        if (mode == Mode.DISTANCE && measurePoints.size >= 2) {
            liveLayer.addRenderable(createMeasurePath(measurePoints, LIVE_LINE_COLOR, LIVE_LINE_WIDTH))
            addDistanceLabels(liveLayer, measurePoints)
        }

        if (mode == Mode.AREA) {
            if (measurePoints.size >= 2) {
                liveLayer.addRenderable(createMeasurePath(measurePoints, LIVE_LINE_COLOR, LIVE_LINE_WIDTH))
                addAreaEdgeLabels(liveLayer, measurePoints)
            }
            if (measurePoints.size >= 3) {
                val closed = ArrayList(measurePoints).apply { add(Position(measurePoints.first())) }
                liveLayer.addRenderable(createMeasurePolygon(closed, LIVE_LINE_COLOR, LIVE_LINE_WIDTH))
                addAreaLabel(liveLayer, measurePoints)
            }
        }

        worldWindow.requestRedraw()
    }

    /**
     * 已保存测量记录渲染：点/线/面 + 测点标记 + 距离/面积标注。
     */
    private fun appendRecordRenderables(record: MeasurementRecord, layer: RenderableLayer) {
        val positions = record.points.map { Position.fromDegrees(it[0], it[1], 0.0) }
        if (positions.isEmpty()) return

        val color = argbToColor(record.color)

        if (record.type == MeasurementRecord.TYPE_POINT) {
            layer.addRenderable(createPointPlacemark(positions.first(), record.name))
            return
        }

        layer.addRenderable(createMeasurePath(positions, color, record.lineWidth.toFloat()))
        for (point in positions) {
            layer.addRenderable(createPointPlacemark(point, ""))
        }

        if (record.type == MeasurementRecord.TYPE_DISTANCE) {
            addDistanceLabels(layer, positions)
            return
        }

        if (record.type == MeasurementRecord.TYPE_AREA) {
            addAreaEdgeLabels(layer, positions)
            if (positions.size >= 3) {
                val closed = ArrayList(positions).apply { add(Position(positions.first())) }
                layer.addRenderable(createMeasurePolygon(closed, color, record.lineWidth.toFloat()))
                addAreaLabel(layer, positions)
            }
        }
    }

    private fun createPointPlacemark(position: Position, name: String): Placemark {
        val attributes = PlacemarkAttributes.createWithImage(POINT_ICON)
        // 拾取/显示尺寸按屏幕密度放大，高分屏真机上原 0.9 倍仅约 32 像素过小
        attributes.imageScale = 0.9 * iconDensityScale
        val placemark = Placemark(position, attributes, name)
        placemark.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        return placemark
    }

    private fun createMeasurePath(points: List<Position>, lineColor: Color, lineWidth: Float): Path {
        val attributes = ShapeAttributes().apply {
            isDrawInterior = false
            isDrawOutline = true
            isDepthTest = false
            outlineColor = lineColor
            outlineWidth = lineWidth
        }
        return Path(ArrayList(points), attributes).apply {
            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            isFollowTerrain = true
        }
    }

    private fun createMeasurePolygon(points: List<Position>, lineColor: Color, lineWidth: Float): Polygon {
        val attributes = ShapeAttributes().apply {
            isDepthTest = false
            isDrawInterior = true
            isDrawOutline = true
            interiorColor = Color(lineColor.red, lineColor.green, lineColor.blue, 0.22f)
            outlineColor = lineColor
            outlineWidth = lineWidth
        }
        return Polygon(ArrayList(points), attributes).apply {
            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            isFollowTerrain = true
        }
    }

    /** 距离测量标注：各测点处显示累计距离 */
    private fun addDistanceLabels(layer: RenderableLayer, points: List<Position>) {
        var cumulative = 0.0
        for ((index, point) in points.withIndex()) {
            if (index > 0) {
                cumulative += GeoCalc.distanceMeters(
                    points[index - 1].latitude.inDegrees, points[index - 1].longitude.inDegrees,
                    point.latitude.inDegrees, point.longitude.inDegrees
                )
            }
            layer.addRenderable(createMeasureLabel(point, GeoCalc.formatDistance(cumulative)))
        }
    }

    /** 面积测量标注：各边中点显示边长 */
    private fun addAreaEdgeLabels(layer: RenderableLayer, points: List<Position>) {
        if (points.size < 2) return
        for (i in 1 until points.size) {
            val start = points[i - 1]
            val end = points[i]
            layer.addRenderable(createMeasureLabel(
                midpoint(start, end),
                GeoCalc.formatDistance(edgeLength(start, end))
            ))
        }
        if (points.size >= 3) {
            val last = points.last()
            val first = points.first()
            layer.addRenderable(createMeasureLabel(
                midpoint(last, first),
                GeoCalc.formatDistance(edgeLength(last, first))
            ))
        }
    }

    /** 面积总量标注：置于多边形质心 */
    private fun addAreaLabel(layer: RenderableLayer, points: List<Position>) {
        if (points.size < 3) return
        val area = GeoCalc.polygonAreaSquareMeters(pointsAsDegrees(points))
        layer.addRenderable(createMeasureLabel(polygonCentroid(points), GeoCalc.formatArea(area)))
    }

    private fun createMeasureLabel(position: Position, text: String): Label =
        Label(Position(position), text).apply {
            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            attributes.isDepthTest = false
        }

    private fun modeDisplayName(mode: Mode): String = when (mode) {
        Mode.POINT -> "点位测量"
        Mode.DISTANCE -> "距离测量"
        Mode.AREA -> "面积测量"
        else -> "测量"
    }

    private fun defaultColorForMode(mode: Mode): Int = when (mode) {
        Mode.AREA -> COLOR_GREEN
        Mode.POINT -> COLOR_YELLOW
        else -> COLOR_BLUE
    }

    // ==================== 几何计算（面积/距离量算复用 [GeoCalc]） ====================

    /** Position 列表转 [纬度, 经度] 点串（与数据库存储格式一致） */
    private fun pointsAsDegrees(points: List<Position>): List<DoubleArray> =
        points.map { doubleArrayOf(it.latitude.inDegrees, it.longitude.inDegrees) }

    private fun edgeLength(a: Position, b: Position): Double =
        GeoCalc.distanceMeters(a.latitude.inDegrees, a.longitude.inDegrees, b.latitude.inDegrees, b.longitude.inDegrees)

    /** 球面多边形质心（标注位置） */
    private fun polygonCentroid(points: List<Position>): Position {
        if (points.size < 3) return Position(points.firstOrNull() ?: Position())

        var lat0 = 0.0
        for (p in points) lat0 += Math.toRadians(p.latitude.inDegrees)
        lat0 /= points.size

        val r = 6371000.0
        var area2 = 0.0
        var cx = 0.0
        var cy = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val ax = r * Math.toRadians(a.longitude.inDegrees) * cos(lat0)
            val ay = r * Math.toRadians(a.latitude.inDegrees)
            val bx = r * Math.toRadians(b.longitude.inDegrees) * cos(lat0)
            val by = r * Math.toRadians(b.latitude.inDegrees)
            val cross = ax * by - bx * ay
            area2 += cross
            cx += (ax + bx) * cross
            cy += (ay + by) * cross
        }

        if (abs(area2) < 1e-9) {
            var lat = 0.0
            var lon = 0.0
            for (p in points) {
                lat += p.latitude.inDegrees
                lon += p.longitude.inDegrees
            }
            return Position.fromDegrees(lat / points.size, lon / points.size, 0.0)
        }

        cx /= 3.0 * area2
        cy /= 3.0 * area2
        return Position.fromDegrees(Math.toDegrees(cy / r), Math.toDegrees(cx / (r * cos(lat0))), 0.0)
    }

    private fun midpoint(a: Position, b: Position): Position = Position.fromDegrees(
        (a.latitude.inDegrees + b.latitude.inDegrees) * 0.5,
        (a.longitude.inDegrees + b.longitude.inDegrees) * 0.5,
        0.0
    )

    // ==================== 格式化 ====================

    private fun argbToColor(argb: Int): Color = Color(
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
        ((argb ushr 24) and 0xFF) / 255f
    )

    companion object {
        /** 测量图层标记 */
        const val MEASURE_LAYER_KEY = "mobilemap.measure.layer"

        private const val COLOR_BLUE = 0xFF57ACFD.toInt()
        private const val COLOR_GREEN = 0xFF2ECC71.toInt()
        private const val COLOR_YELLOW = 0xFFF1C40F.toInt()
        private const val COLOR_RED = 0xFFE74C3C.toInt()
        private const val COLOR_PURPLE = 0xFF9B59B6.toInt()

        /** 测量过程线颜色与线宽 */
        private val LIVE_LINE_COLOR = Color(0.34f, 0.76f, 1f, 1f)
        private const val LIVE_LINE_WIDTH = 3f

        /** 测量点图标：白底蓝心圆点 */
        private val POINT_ICON = ImageSource.fromBitmap(createPointBitmap())

        private fun createPointBitmap(): Bitmap {
            val size = 36
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val center = size / 2f
            val outer = Paint(Paint.ANTI_ALIAS_FLAG)
            outer.style = Paint.Style.FILL
            outer.color = -0x1 // 0xFFFFFFFF
            canvas.drawCircle(center, center, size * 0.24f, outer)

            val inner = Paint(Paint.ANTI_ALIAS_FLAG)
            inner.style = Paint.Style.FILL
            inner.color = COLOR_BLUE
            canvas.drawCircle(center, center, size * 0.16f, inner)

            return bitmap
        }
    }
}
