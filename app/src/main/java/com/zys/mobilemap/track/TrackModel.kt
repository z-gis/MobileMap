package com.zys.mobilemap.track

import android.app.Activity
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zys.mobilemap.databinding.ActivityMainBinding
import com.zys.mobilemap.db.MapDataStore
import com.zys.mobilemap.db.TrackRecord
import com.zys.mobilemap.location.LocationManager
import com.zys.mobilemap.ui.dialog.MeasureDetailDialog
import com.zys.mobilemap.ui.dialog.TrackManageDialog
import com.zys.mobilemap.util.GeoCalc
import earth.worldwind.PickedObjectList
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.shape.Path
import earth.worldwind.shape.ShapeAttributes
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 轨迹记录模块（参照上一版本）：
 * 开始/暂停/继续/停止轨迹记录，活动轨迹实时绘制，
 * 停止后轨迹保存至 /调查宝/map/map.db，启动时读取数据库渲染历史轨迹。
 * 图层可见性由设置（[com.zys.mobilemap.doc.SystemConfig.showTrackLayer]）驱动。
 */
class TrackModel(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val worldWindow: WorldWindow
) {

    /** 轨迹覆盖物图层（历史轨迹 + 当前活动轨迹） */
    val layer: RenderableLayer = RenderableLayer().apply {
        displayName = "轨迹记录"
        putUserProperty(TRACK_LAYER_KEY, true)
    }

    /** 当前活动轨迹坐标点 */
    private val activePoints = mutableListOf<Position>()

    /** 当前活动轨迹线 */
    private var activeTrackLine: Path? = null

    /** 是否正在记录 */
    private var recording = false

    /** 是否暂停 */
    private var paused = false

    /** 已保存轨迹缓存（点击命中判定用，与数据库同步） */
    private var recordsCache: List<TrackRecord> = emptyList()

    /** 位置更新监听（记录中且未暂停时追加轨迹点） */
    private val locationListener = LocationManager.OnLocationChangedListener { location ->
        if (location != null && recording && !paused) {
            activity.runOnUiThread { appendTrackPoint(location.latitude, location.longitude, location.altitude) }
        }
    }

    /**
     * 将图层加入地图并绑定控制按钮（幂等）。
     */
    fun setup() {
        if (!worldWindow.engine.layers.contains(layer)) {
            worldWindow.engine.layers.addLayer(layer)
        }
        LocationManager.getInstance().addLocationListener(locationListener)

        binding.mainIvTrk.setOnClickListener { onTrackButtonClick() }
        binding.mainIvTrkPause.setOnClickListener { pauseTrackRecording() }
        binding.mainIvTrkStart.setOnClickListener { resumeTrackRecording() }
        binding.mainIvTrkStop.setOnClickListener { stopTrackRecording() }
        updateControlButtons()
    }

    /**
     * 按设置可见性显示/隐藏。
     */
    fun setEnabled(visible: Boolean) {
        layer.isEnabled = visible
        worldWindow.requestRedraw()
    }

    /**
     * 从数据库加载全部历史轨迹并渲染。
     */
    fun reloadSavedTracks() {
        layer.clearRenderables()
        val records = MapDataStore.getInstance(activity).loadTracks()
        recordsCache = records
        for (record in records) {
            if (record.points.size < 2) continue
            // 逐条可见性：隐藏的轨迹不渲染（管理界面开关控制）
            if (!record.visible) continue
            val positions = record.points.map {
                Position.fromDegrees(it[0], it[1], 0.0)
            }
            layer.addRenderable(createSavedTrackLine(positions, record.color, record.lineWidth.toFloat()))
        }
        activeTrackLine?.let { layer.addRenderable(it) }
        worldWindow.requestRedraw()
    }

    /**
     * 已保存轨迹几何点击拾取（参照测量模块）：按地表命中点与高度换算容差
     * 判定（线邻域），命中弹出轨迹详情 BottomSheet（标题+颜色/线宽编辑+删除）并消费该点击。
     */
    fun handlePickedGeometry(x: Float, y: Float, picked: PickedObjectList): Boolean {
        if (!layer.isEnabled) return false
        val ground = picked.terrainPickedObject?.terrainPosition ?: return false
        val threshold = computeTapToleranceMeters()
        val record = recordsCache.firstOrNull {
            isHitTrack(it, ground.latitude.inDegrees, ground.longitude.inDegrees, threshold)
        } ?: return false
        showTrackDetail(record)
        return true
    }

    /**
     * 高度换算点击容差：相当于屏幕 32 像素的地面距离（垂直 fov≈45°），与测量模块一致。
     */
    private fun computeTapToleranceMeters(): Double {
        val altitude = worldWindow.engine.camera.position.altitude
        val screenH = worldWindow.height.coerceAtLeast(1)
        val meters = altitude * 0.8284 * 32.0 / screenH
        return meters.coerceIn(20.0, 10000.0)
    }

    /** 轨迹为折线：点到各段最小距离在阈值内即命中 */
    private fun isHitTrack(record: TrackRecord, lat: Double, lon: Double, threshold: Double): Boolean {
        if (record.points.size < 2) return false
        val edgeThreshold = maxOf(20.0, threshold)
        return minEdgeDistanceMeters(record.points, lat, lon) <= edgeThreshold
    }

    /** 点到折线各段最小距离 */
    private fun minEdgeDistanceMeters(points: List<DoubleArray>, lat: Double, lon: Double): Double {
        if (points.size < 2) return Double.MAX_VALUE
        var min = Double.MAX_VALUE
        for (i in 1 until points.size) {
            min = minOf(min, distanceToSegmentMeters(lat, lon, points[i - 1], points[i]))
        }
        return min
    }

    /** 点到线段距离（局部等角平面近似，同测量模块） */
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

    /**
     * 轨迹详情 BottomSheet（复用测量详情弹层）：标题=轨迹名称，样式区（颜色选色器 + 线宽步进），
     * 底部按键：删除/保存（点击轨迹时几何已在屏幕内，无需缩放至）。
     */
    private fun showTrackDetail(record: TrackRecord) {
        val fm = (activity as? AppCompatActivity)?.supportFragmentManager ?: return
        MeasureDetailDialog(
            title = record.name,
            typeText = "轨迹",
            geometryText = trackGeometryText(record),
            widthEditable = true,
            initialColor = record.color,
            initialWidth = record.lineWidth,
            onSave = { color, width ->
                MapDataStore.getInstance(activity).updateTrackStyle(record.id, color, width)
                reloadSavedTracks()
                Toast.makeText(activity, "样式已更新", Toast.LENGTH_SHORT).show()
            },
            onDelete = { confirmDeleteTrack(record) }
        ).show(fm, "TrackDetailDialog")
    }

    /** 轨迹量算文本：总长 + 点数 */
    private fun trackGeometryText(record: TrackRecord): String {
        val degrees = record.points.map { doubleArrayOf(it[0], it[1]) }
        val length = GeoCalc.polylineLengthMeters(degrees)
        return "距离: " + GeoCalc.formatDistance(length) + "　·　" + record.points.size + "个点"
    }

    /** 删除确认：删库并重载图层 */
    private fun confirmDeleteTrack(record: TrackRecord) {
        AlertDialog.Builder(activity)
            .setTitle("删除确认")
            .setMessage("确定删除轨迹：${record.name} ？删除后该轨迹及其全部坐标点将一并删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                MapDataStore.getInstance(activity).deleteTrack(record.id)
                reloadSavedTracks()
                Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * 轨迹按键入口：记录中提示；无历史轨迹直接开始记录；
     * 有历史轨迹弹出菜单（开始轨迹 / 管理），与拍照一致。
     */
    private fun onTrackButtonClick() {
        if (recording) {
            Toast.makeText(activity, "轨迹记录进行中", Toast.LENGTH_SHORT).show()
            return
        }
        val hasRecords = MapDataStore.getInstance(activity).loadTracks().isNotEmpty()
        if (!hasRecords) {
            startTrackRecording()
        } else {
            showTrackMenu()
        }
    }

    /**
     * 轨迹菜单（有历史记录时）：开始轨迹直接进入记录，管理弹出轨迹管理界面。
     */
    private fun showTrackMenu() {
        AlertDialog.Builder(activity)
            .setItems(arrayOf("开始轨迹", "管理")) { _, which ->
                when (which) {
                    0 -> startTrackRecording()
                    1 -> TrackManageDialog.show(
                        activity,
                        onChanged = { reloadSavedTracks() },
                        onZoomTo = { track -> zoomToTrack(track) }
                    )
                }
            }
            .show()
    }

    /**
     * 缩放至轨迹：按轨迹点四至计算中心与合适高度，北向归 0，重绘地图。
     */
    private fun zoomToTrack(track: TrackRecord) {
        if (track.points.isEmpty()) return
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (p in track.points) {
            val lat = p[0]
            val lon = p[1]
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
        }
        val span = maxOf(maxLon - minLon, maxLat - minLat).coerceAtLeast(0.01)
        val altitude = span * 111320.0 * 1.5
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

    /**
     * 开始轨迹记录（入口）：定位权限与定位服务检查后启动。
     */
    private fun startTrackRecording() {
        if (recording) {
            Toast.makeText(activity, "轨迹记录进行中", Toast.LENGTH_SHORT).show()
            return
        }

        val locationManager = LocationManager.getInstance()
        if (locationManager.getLastLocation() == null && !locationManager.isLocating()) {
            Toast.makeText(activity, "尚未获取到定位信息，请先开启定位", Toast.LENGTH_SHORT).show()
            return
        }

        // 清空旧活动轨迹，创建新轨迹线
        activePoints.clear()
        activeTrackLine = createTrackLine(activePoints, true)

        recording = true
        paused = false

        // 当前位置作为轨迹起点
        locationManager.getLastLocation()?.let {
            appendTrackPoint(it.latitude, it.longitude, it.altitude)
        }

        reloadSavedTracks()
        updateControlButtons()
        Toast.makeText(activity, "开始记录轨迹", Toast.LENGTH_SHORT).show()
    }

    private fun pauseTrackRecording() {
        if (!recording || paused) return
        paused = true
        updateControlButtons()
        Toast.makeText(activity, "轨迹记录已暂停", Toast.LENGTH_SHORT).show()
    }

    private fun resumeTrackRecording() {
        if (!recording || !paused) return
        paused = false

        // 当前位置作为恢复后的第一个点
        LocationManager.getInstance().getLastLocation()?.let {
            appendTrackPoint(it.latitude, it.longitude, it.altitude)
        }

        updateControlButtons()
        Toast.makeText(activity, "轨迹记录继续", Toast.LENGTH_SHORT).show()
    }

    /**
     * 停止轨迹记录：点串保存到数据库，清理活动轨迹状态。
     */
    private fun stopTrackRecording() {
        if (!recording) return

        recording = false
        paused = false
        updateControlButtons()

        if (activePoints.size < 2) {
            clearActiveTrack()
            reloadSavedTracks()
            Toast.makeText(activity, "轨迹点不足，未生成轨迹线", Toast.LENGTH_SHORT).show()
            return
        }

        val now = System.currentTimeMillis()
        val name = String.format(
            Locale.getDefault(),
            "轨迹 %1\$tY-%1\$tm-%1\$td %1\$tH:%1\$tM:%1\$tS",
            Date(now)
        )
        val points = activePoints.map { doubleArrayOf(it.latitude.inDegrees, it.longitude.inDegrees, it.altitude) }
        MapDataStore.getInstance(activity).addTrack(name, now, points)

        clearActiveTrack()
        reloadSavedTracks()
        Toast.makeText(activity, "轨迹记录已停止", Toast.LENGTH_SHORT).show()
    }

    /**
     * 追加轨迹点：过滤距离过近的重复点。
     */
    private fun appendTrackPoint(lat: Double, lon: Double, alt: Double) {
        val point = Position.fromDegrees(lat, lon, 0.0)
        val last = activePoints.lastOrNull()
        if (last != null) {
            val latDiff = kotlin.math.abs(last.latitude.inDegrees - lat)
            val lonDiff = kotlin.math.abs(last.longitude.inDegrees - lon)
            if (latDiff < 1e-7 && lonDiff < 1e-7) return
        }

        activePoints.add(point)
        // Path 需要至少 2 个点才渲染，重建共享同一位置列表的轨迹线
        activeTrackLine?.let { line ->
            line.positions = activePoints.toList()
            if (activePoints.size == 2 && !layer.contains(line)) {
                layer.addRenderable(line)
            }
        }
        worldWindow.requestRedraw()
    }

    /**
     * 创建轨迹线：活动轨迹红色加粗，历史轨迹蓝色。
     */
    private fun createTrackLine(positions: List<Position>, active: Boolean): Path {
        val attributes = ShapeAttributes().apply {
            isDrawOutline = true
            outlineColor = if (active) Color(1f, 0.35f, 0.2f, 1f) else Color(0.2f, 0.8f, 1f, 1f)
            outlineWidth = if (active) 4f else 2.5f
            isDepthTest = false
        }
        return Path(positions.toList(), attributes).apply {
            displayName = if (active) "当前轨迹" else "历史轨迹"
            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            isFollowTerrain = true
        }
    }

    /**
     * 创建已保存轨迹线：颜色/线宽取自记录（轨迹详情 BottomSheet 可编辑）。
     */
    private fun createSavedTrackLine(positions: List<Position>, colorArgb: Int, lineWidth: Float): Path {
        val attributes = ShapeAttributes().apply {
            isDrawOutline = true
            outlineColor = argbToColor(colorArgb)
            outlineWidth = lineWidth
            isDepthTest = false
        }
        return Path(positions.toList(), attributes).apply {
            displayName = "历史轨迹"
            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            isFollowTerrain = true
        }
    }

    /** ARGB Int 转 WorldWind Color（与测量模块一致） */
    private fun argbToColor(argb: Int): Color = Color(
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
        ((argb ushr 24) and 0xFF) / 255f
    )

    private fun clearActiveTrack() {
        activePoints.clear()
        activeTrackLine = null
    }

    /**
     * 轨迹控制按钮状态：非记录状态只显示"轨迹"按钮，
     * 记录状态显示停止按钮并按暂停状态切换暂停/继续。
     */
    private fun updateControlButtons() {
        if (!recording) {
            binding.mainIvTrk.visibility = View.VISIBLE
            binding.mainIvTrkPause.visibility = View.GONE
            binding.mainIvTrkStart.visibility = View.GONE
            binding.mainIvTrkStop.visibility = View.GONE
            return
        }

        binding.mainIvTrk.visibility = View.GONE
        binding.mainIvTrkStop.visibility = View.VISIBLE
        binding.mainIvTrkPause.visibility = if (paused) View.GONE else View.VISIBLE
        binding.mainIvTrkStart.visibility = if (paused) View.VISIBLE else View.GONE
    }

    companion object {
        /** 轨迹图层标记 */
        const val TRACK_LAYER_KEY = "mobilemap.track.layer"
    }
}
