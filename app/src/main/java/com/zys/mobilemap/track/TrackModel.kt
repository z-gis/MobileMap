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
import java.util.concurrent.Executors

/**
 * 轨迹记录模块（参照上一版本）：
 * 开始/暂停/继续/停止轨迹记录，活动轨迹实时绘制，
 * 停止后轨迹保存至 /调查宝/map/map.db，启动时读取数据库渲染历史轨迹。
 * 图层可见性由设置（[com.zys.mobilemap.doc.SystemConfig.showTrackLayer]）驱动。
 *
 * 采集数据不丢失：
 *  - 横竖屏切换：MainActivity 已声明 configChanges，Activity 不重建，本模块内存状态与地图视角全部保留；
 *  - 进程被系统回收/异常退出：采集开始即建立“记录中”草稿轨迹，轨迹点实时落库，
 *    重启后 [restoreDraftTrack] 从数据库恢复采集状态与已采集但未保存的轨迹点。
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

    /** 记录中的草稿轨迹主键（-1 表示无）：采集点实时落库，进程重启后据此恢复 */
    private var draftTrackId = -1L

    /** 本次采集起始时间：停止时据此生成轨迹名称（与开始时间一致） */
    private var draftCreatedTime = 0L

    /** 轨迹点落库线程：单线程串行写入，避免每点一次 SQLite 写入阻塞主线程 */
    private val dbExecutor = Executors.newSingleThreadExecutor()

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

        // 上次采集未结束（进程被回收/异常退出）时恢复采集状态与已采集轨迹点
        restoreDraftTrack()
    }

    /**
     * 恢复未结束的草稿轨迹：读回已落库的采集点重建活动轨迹线，并恢复“记录中”状态，
     * 保证采集过程被中断后原采集但未保存的数据不丢失。
     */
    private fun restoreDraftTrack() {
        val store = MapDataStore.getInstance(activity)
        val draft = store.loadDraftTrack() ?: return
        if (draft.points.isEmpty()) {
            // 无采集点的空草稿（刚开始就中断）直接清理，避免残留
            store.deleteTrack(draft.id)
            return
        }

        draftTrackId = draft.id
        draftCreatedTime = draft.createdTime
        activePoints.clear()
        for (p in draft.points) {
            activePoints.add(Position.fromDegrees(p[0], p[1], 0.0))
        }
        activeTrackLine = createTrackLine(activePoints, true)
        recording = true
        paused = false

        reloadSavedTracks()
        updateControlButtons()
        Toast.makeText(
            activity,
            "已恢复上次未完成的轨迹记录（${activePoints.size} 个点）",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 按设置可见性显示/隐藏。
     */
    fun setEnabled(visible: Boolean) {
        layer.isEnabled = visible
        worldWindow.requestRedraw()
    }

    /**
     * 释放落库线程（宿主 Activity 销毁时调用）：已入队的采集点写入仍会执行完毕。
     */
    fun shutdown() {
        dbExecutor.shutdown()
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

    /** 轨迹为折线：点到各段最小距离在阈值内即命中（量算走 GeoCalc，与测量模块同一实现） */
    private fun isHitTrack(record: TrackRecord, lat: Double, lon: Double, threshold: Double): Boolean {
        if (record.points.size < 2) return false
        val edgeThreshold = maxOf(20.0, threshold)
        return GeoCalc.minEdgeDistanceMeters(record.points, lat, lon) <= edgeThreshold
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

        // 建立“记录中”草稿轨迹：采集点随采集实时落库，中断后不丢失未保存数据
        draftCreatedTime = System.currentTimeMillis()
        draftTrackId = MapDataStore.getInstance(activity)
            .beginDraftTrack(trackNameOf(draftCreatedTime), draftCreatedTime)

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
     * 停止轨迹记录：采集点已实时落库，此处仅结束草稿（写入最终名称 + 清除“记录中”标记）
     * 并清理活动轨迹状态；采集点不足时删除草稿。
     */
    private fun stopTrackRecording() {
        if (!recording) return

        recording = false
        paused = false
        updateControlButtons()

        val draftId = draftTrackId
        draftTrackId = -1L
        val store = MapDataStore.getInstance(activity)

        if (activePoints.size < 2) {
            if (draftId > 0) dbExecutor.execute { store.deleteTrack(draftId) }
            clearActiveTrack()
            reloadSavedTracks()
            Toast.makeText(activity, "轨迹点不足，未生成轨迹线", Toast.LENGTH_SHORT).show()
            return
        }

        // 名称取采集起始时间（与开始记录时刻一致，而非停止时刻）
        val name = trackNameOf(draftCreatedTime)
        if (draftId > 0) {
            // 与待写入的采集点同一单线程队列，结束后再回主线程重载，保证渲染含全部点
            dbExecutor.execute {
                store.finishDraftTrack(draftId, name)
                activity.runOnUiThread { onTrackSaved() }
            }
        } else {
            // 草稿建立失败的兜底：整串一次性保存
            val points = activePoints.map {
                doubleArrayOf(it.latitude.inDegrees, it.longitude.inDegrees, it.altitude)
            }
            dbExecutor.execute {
                store.addTrack(name, draftCreatedTime, points)
                activity.runOnUiThread { onTrackSaved() }
            }
        }
    }

    /** 轨迹保存完成：清理活动轨迹并重载历史轨迹（主线程） */
    private fun onTrackSaved() {
        clearActiveTrack()
        reloadSavedTracks()
        Toast.makeText(activity, "轨迹记录已停止", Toast.LENGTH_SHORT).show()
    }

    /** 轨迹默认名称：轨迹 yyyy-MM-dd HH:mm:ss */
    private fun trackNameOf(time: Long): String = String.format(
        Locale.getDefault(),
        "轨迹 %1\$tY-%1\$tm-%1\$td %1\$tH:%1\$tM:%1\$tS",
        Date(time)
    )

    /**
     * 追加轨迹点：按地表米制距离过滤过近的点（消除步走/静止时的 GPS 抖动摆动），
     * 并实时写入草稿轨迹（单线程队列，不阻塞主线程）。
     */
    private fun appendTrackPoint(lat: Double, lon: Double, alt: Double) {
        val point = Position.fromDegrees(lat, lon, alt)
        val last = activePoints.lastOrNull()
        // 最小间距过滤：消除静止/步走时 GPS 抖动导致的轨迹来回摆动（行车点间距远大于阈值不受影响）
        if (last != null &&
            GeoCalc.distanceMeters(last.latitude.inDegrees, last.longitude.inDegrees, lat, lon)
            < MIN_TRACK_POINT_DISTANCE_METERS) {
            return
        }

        activePoints.add(point)
        // 采集点实时落库：seq 与活动点序号一致，恢复时按 seq 升序读回
        val draftId = draftTrackId
        if (draftId > 0) {
            val seq = activePoints.size - 1
            dbExecutor.execute {
                MapDataStore.getInstance(activity).appendTrackPoint(draftId, seq, lat, lon, alt)
            }
        }
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

        /** 轨迹点最小间距（米）：与上一点距离小于此值的点视为 GPS 抖动/步走摆动，不记录 */
        const val MIN_TRACK_POINT_DISTANCE_METERS = 5.0
    }
}
