package com.zys.mobilemap.track

import android.app.Activity
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.zys.mobilemap.databinding.ActivityMainBinding
import com.zys.mobilemap.db.MapDataStore
import com.zys.mobilemap.db.TrackRecord
import com.zys.mobilemap.location.LocationManager
import com.zys.mobilemap.ui.dialog.TrackManageDialog
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
        for (record in records) {
            if (record.points.size < 2) continue
            // 逐条可见性：隐藏的轨迹不渲染（管理界面开关控制）
            if (!record.visible) continue
            val positions = record.points.map {
                Position.fromDegrees(it[0], it[1], 0.0)
            }
            layer.addRenderable(createTrackLine(positions, false))
        }
        activeTrackLine?.let { layer.addRenderable(it) }
        worldWindow.requestRedraw()
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
