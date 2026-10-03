package com.zys.mobilemap.ui.map

import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.zys.mobilemap.db.MapDataStore
import com.zys.mobilemap.db.TrackRecord
import com.zys.mobilemap.location.BackgroundLocationController
import com.zys.mobilemap.location.LocationManager
import com.zys.mobilemap.ui.dialog.MeasureDetailDialog
import com.zys.mobilemap.ui.dialog.TrackManageDialog
import com.zys.mobilemap.util.GeoCalc
import com.zys.globecore.NativeMapView
import com.zys.globecore.VectorStyle as NativeVectorStyle
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 轨迹采集控制器（自 MainActivity 拆出）：开始/暂停/继续/停止记录、定位追加点、草稿实时落库与进程重启恢复、
 * live 红线层实时渲染，以及轨迹详情/删除对话框。
 *
 * 采集态 [capturing] 由宿主组合进 [BusinessOverlayManager.rebuild] 门控。定位追加点经 [onLocation] 由宿主把
 * [LocationFollowController] 的 location 回调转接进来（记录中且未暂停时追加）。落库走独立单线程队列，
 * [onDestroy] 时关闭。live 渲染/缩放复用 [BusinessOverlayManager.Companion.flattenLonLat] 与 [CameraNavigator.zoomToExtent]。
 */
internal class TrackCaptureController(
    private val activity: AppCompatActivity,
    private val mapView: NativeMapView,
    private val fm: FragmentManager,
    private val navigator: CameraNavigator,
    private val trackButton: ImageView,
    private val startButton: ImageView,
    private val pauseButton: ImageView,
    private val stopButton: ImageView,
    private val rebuildOverlays: () -> Unit
) {

    /** 轨迹采集：记录/暂停状态 + 活动点串（[lat,lon,alt]）+ live 叠加层索引 + 草稿主键/起始时间 + 落库单线程 */
    private var trackRecording = false
    private var trackPaused = false
    private val trackActivePoints = mutableListOf<DoubleArray>()
    private var trackLiveIndex = -1
    private var trackDraftId = -1L
    private var trackDraftCreatedTime = 0L
    private val trackDbExecutor = Executors.newSingleThreadExecutor()

    /** 轨迹记录进行中：宿主据此组合 capturing，保护 live 红线层不被 rebuild 清除 */
    val capturing: Boolean
        get() = trackRecording

    /**
     * 轨迹记录态变化回调（true=开始记录/恢复草稿，false=停止记录）：宿主据此驱动定位跟随——
     * 轨迹模式下地图中心随定位移动、定位图标切为锁定样式（接线到 LocationFollowController.setFollowEnabled）。
     */
    var onRecordingStateChanged: ((Boolean) -> Unit)? = null

    /** 轨迹按键入口：记录中提示；无历史轨迹直接开始；有历史轨迹弹菜单（开始轨迹 / 管理） */
    fun onTrackButtonClick() {
        if (trackRecording) {
            Toast.makeText(activity, "轨迹记录进行中", Toast.LENGTH_SHORT).show()
            return
        }
        val hasRecords = MapDataStore.getInstance(activity).loadTracks().isNotEmpty()
        if (!hasRecords) startTrackRecording() else showTrackMenu()
    }

    /** 轨迹菜单（有历史记录时）：开始轨迹直接进入记录，管理弹出 [TrackManageDialog] */
    private fun showTrackMenu() {
        AlertDialog.Builder(activity)
            .setItems(arrayOf("开始轨迹", "管理")) { _, which ->
                when (which) {
                    0 -> startTrackRecording()
                    1 -> TrackManageDialog.show(
                        activity,
                        onChanged = { rebuildOverlays() },
                        onZoomTo = { track -> zoomToTrack(track) }
                    )
                }
            }
            .show()
    }

    /**
     * 开始轨迹记录：定位检查后清空活动点、建 live 红线层、置记录态（capturing 转 true）、建立草稿轨迹并落起点。
     */
    fun startTrackRecording() {
        if (trackRecording) {
            Toast.makeText(activity, "轨迹记录进行中", Toast.LENGTH_SHORT).show()
            return
        }
        val lm = LocationManager.getInstance()
        if (lm.getLastLocation() == null && !lm.isLocating()) {
            Toast.makeText(activity, "尚未获取到定位信息，请先开启定位", Toast.LENGTH_SHORT).show()
            return
        }
        trackActivePoints.clear()
        trackRecording = true
        trackPaused = false
        ensureTrackLiveLayer()
        trackDraftCreatedTime = System.currentTimeMillis()
        trackDraftId = MapDataStore.getInstance(activity)
            .beginDraftTrack(trackNameOf(trackDraftCreatedTime), trackDraftCreatedTime)
        lm.getLastLocation()?.let { appendTrackPoint(it.latitude, it.longitude, it.altitude) }
        // 记录开始 → 后台定位保活服务随记录态启动（仅当开关为开，由 Controller 门控）——息屏后仍持续定位不缺点
        BackgroundLocationController.onTrackRecordingChanged(activity, true)
        updateTrackControlButtons()
        Toast.makeText(activity, "开始记录轨迹", Toast.LENGTH_SHORT).show()
    }

    /** 暂停轨迹记录：置暂停态（定位监听据 trackPaused 停止追加）并刷新控件 */
    fun pauseTrackRecording() {
        if (!trackRecording || trackPaused) return
        trackPaused = true
        updateTrackControlButtons()
        Toast.makeText(activity, "轨迹记录已暂停", Toast.LENGTH_SHORT).show()
    }

    /** 继续轨迹记录：解除暂停并以当前定位为恢复后首点 */
    fun resumeTrackRecording() {
        if (!trackRecording || !trackPaused) return
        trackPaused = false
        LocationManager.getInstance().getLastLocation()?.let {
            appendTrackPoint(it.latitude, it.longitude, it.altitude)
        }
        // 恢复记录 → 幂等补保活（覆盖暂停期间用户关闭又打开开关导致服务已停的情形）
        BackgroundLocationController.onTrackRecordingChanged(activity, true)
        updateTrackControlButtons()
        Toast.makeText(activity, "轨迹记录继续", Toast.LENGTH_SHORT).show()
    }

    /**
     * 停止轨迹记录：采集点已实时落库，此处结束草稿（写最终名称 + 清 recording 标记）；点数不足则删草稿。
     * 先同步拆 live 层并置 trackRecording=false（capturing 转 false），落库/定型在单线程完成后回主线程重建叠加层。
     */
    fun stopTrackRecording() {
        if (!trackRecording) return
        trackRecording = false
        trackPaused = false
        // 停止记录 → 后台定位保活即刻停服务（不做记录之外的后台定位）
        BackgroundLocationController.onTrackRecordingChanged(activity, false)
        updateTrackControlButtons()

        val draftId = trackDraftId
        trackDraftId = -1L
        val store = MapDataStore.getInstance(activity)
        val insufficient = trackActivePoints.size < 2
        val points = trackActivePoints.toList()
        clearActiveTrack()  // 拆 live 层 + 清活动点（capturing 转 false，后续重建可正常执行）

        if (insufficient) {
            if (draftId > 0) trackDbExecutor.execute { store.deleteTrack(draftId) }
            rebuildOverlays()
            Toast.makeText(activity, "轨迹点不足，未生成轨迹线", Toast.LENGTH_SHORT).show()
            return
        }
        val name = trackNameOf(trackDraftCreatedTime)
        if (draftId > 0) {
            // 与待写入的采集点同一单线程队列，定型后回主线程重建，保证渲染含全部点
            trackDbExecutor.execute {
                store.finishDraftTrack(draftId, name)
                activity.runOnUiThread { onTrackSaved() }
            }
        } else {
            // 草稿建立失败的兜底：整串一次性保存
            val pts = points.map { doubleArrayOf(it[0], it[1], it.getOrElse(2) { 0.0 }) }
            trackDbExecutor.execute {
                store.addTrack(name, trackDraftCreatedTime, pts)
                activity.runOnUiThread { onTrackSaved() }
            }
        }
    }

    /** 轨迹保存完成（主线程）：重建叠加层显示已存轨迹并提示 */
    private fun onTrackSaved() {
        rebuildOverlays()
        Toast.makeText(activity, "轨迹记录已停止", Toast.LENGTH_SHORT).show()
    }

    /**
     * 定位更新转接：记录中且未暂停时把定位点追加为活动轨迹点（宿主把 LocationFollowController 回调接线到此，
     * 对齐原主界面 TrackModel 定位监听追加）。
     */
    fun onLocation(lat: Double, lon: Double, alt: Double) {
        if (trackRecording && !trackPaused) appendTrackPoint(lat, lon, alt)
    }

    /**
     * 追加轨迹点（[lat,lon,alt]）：按最小间距过滤 GPS 抖动，实时写入草稿（单线程队列），并重绘 live 红线。
     * 由 [onLocation]（记录中且未暂停）与开始/继续时的定位首点调用。
     */
    private fun appendTrackPoint(lat: Double, lon: Double, alt: Double) {
        val last = trackActivePoints.lastOrNull()
        if (last != null &&
            GeoCalc.distanceMeters(last[0], last[1], lat, lon) < MapStyleColors.MIN_TRACK_POINT_DISTANCE_METERS
        ) {
            return
        }
        trackActivePoints.add(doubleArrayOf(lat, lon, alt))
        val draftId = trackDraftId
        if (draftId > 0) {
            val seq = trackActivePoints.size - 1
            trackDbExecutor.execute {
                MapDataStore.getInstance(activity).appendTrackPoint(draftId, seq, lat, lon, alt)
            }
        }
        renderLiveTrack()
    }

    /** 实时重绘活动轨迹 live 红线层（≥2 点才成线，覆盖式更新） */
    private fun renderLiveTrack() {
        val idx = trackLiveIndex
        if (idx < 0 || trackActivePoints.size < 2) return
        mapView.updateOverlayLines(
            idx, BusinessOverlayManager.flattenLonLat(trackActivePoints), intArrayOf(trackActivePoints.size), longArrayOf(0L)
        )
    }

    /** 确保活动轨迹 live 红线层已建（红色加粗，无标注），幂等 */
    private fun ensureTrackLiveLayer() {
        if (trackLiveIndex < 0) {
            trackLiveIndex = mapView.addOverlayLayer(
                NativeVectorStyle(
                    fillColor = MapStyleColors.TRACK_ACTIVE_COLOR, outlineColor = MapStyleColors.TRACK_ACTIVE_COLOR, outlineWidth = MapStyleColors.TRACK_ACTIVE_WIDTH,
                    lineColor = MapStyleColors.TRACK_ACTIVE_COLOR, lineWidth = MapStyleColors.TRACK_ACTIVE_WIDTH, pointColor = MapStyleColors.TRACK_ACTIVE_COLOR,
                    pointRadiusDp = MapStyleColors.MEASURE_POINT_RADIUS_DP
                )
            )
        }
    }

    /** 清理活动轨迹：清活动点 + 拆 live 层（墓碑，GL 线程下帧回收） */
    private fun clearActiveTrack() {
        trackActivePoints.clear()
        if (trackLiveIndex >= 0) {
            mapView.removeOverlayLayer(trackLiveIndex)
            trackLiveIndex = -1
        }
    }

    /**
     * 恢复未结束的草稿轨迹（进程被回收/异常退出后）：读回已落库采集点重建 live 红线并恢复记录态。
     * 空草稿（刚开始就中断）直接清理。在 onCreate 装配叠加层后调用。
     */
    fun restoreDraftTrack() {
        val store = MapDataStore.getInstance(activity)
        val draft = store.loadDraftTrack() ?: return
        if (draft.points.isEmpty()) {
            store.deleteTrack(draft.id)
            return
        }
        trackDraftId = draft.id
        trackDraftCreatedTime = draft.createdTime
        trackActivePoints.clear()
        for (p in draft.points) {
            trackActivePoints.add(doubleArrayOf(p[0], p[1], p.getOrElse(2) { 0.0 }))
        }
        trackRecording = true
        trackPaused = false
        // 草稿恢复即记录会话延续 → 按「记录态 && 开关」重新拉起保活服务
        BackgroundLocationController.onTrackRecordingChanged(activity, true)
        ensureTrackLiveLayer()
        renderLiveTrack()
        updateTrackControlButtons()
        Toast.makeText(
            activity, "已恢复上次未完成的轨迹记录（${trackActivePoints.size} 个点）", Toast.LENGTH_LONG
        ).show()
    }

    /** 轨迹控件状态：非记录态只显示「轨迹」键；记录态显示停止键并按暂停态切换暂停/继续键 */
    private fun updateTrackControlButtons() {
        // 记录态变化通知宿主（幂等：宿主 setFollowEnabled 内部按值判重）
        onRecordingStateChanged?.invoke(trackRecording)
        if (!trackRecording) {
            trackButton.visibility = View.VISIBLE
            startButton.visibility = View.GONE
            pauseButton.visibility = View.GONE
            stopButton.visibility = View.GONE
            return
        }
        trackButton.visibility = View.GONE
        stopButton.visibility = View.VISIBLE
        pauseButton.visibility = if (trackPaused) View.GONE else View.VISIBLE
        startButton.visibility = if (trackPaused) View.VISIBLE else View.GONE
    }

    /**
     * 轨迹详情 BottomSheet（复用 [MeasureDetailDialog]，typeText=轨迹）：样式编辑写库后重建叠加层，删除走确认。
     */
    fun showTrackDetail(record: TrackRecord) {
        MeasureDetailDialog(
            title = record.name,
            typeText = "轨迹",
            geometryText = trackGeometryText(record),
            widthEditable = true,
            initialColor = record.color,
            initialWidth = record.lineWidth,
            onSave = { color, width ->
                MapDataStore.getInstance(activity).updateTrackStyle(record.id, color, width)
                rebuildOverlays()
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

    /** 删除轨迹确认：删库并重建叠加层 */
    private fun confirmDeleteTrack(record: TrackRecord) {
        AlertDialog.Builder(activity)
            .setTitle("删除确认")
            .setMessage("确定删除轨迹：${record.name} ？删除后该轨迹及其全部坐标点将一并删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                MapDataStore.getInstance(activity).deleteTrack(record.id)
                rebuildOverlays()
                Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 轨迹默认名称：轨迹 yyyy-MM-dd HH:mm:ss（取采集起始时间） */
    private fun trackNameOf(time: Long): String = String.format(
        Locale.getDefault(), "轨迹 %1\$tY-%1\$tm-%1\$td %1\$tH:%1\$tM:%1\$tS", Date(time)
    )

    /** 缩放至轨迹：按轨迹点四至计算中心与合适高度（对齐原主界面 TrackModel.zoomToTrack） */
    private fun zoomToTrack(track: TrackRecord) {
        if (track.points.isEmpty()) return
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (p in track.points) {
            if (p[0] < minLat) minLat = p[0]
            if (p[0] > maxLat) maxLat = p[0]
            if (p[1] < minLon) minLon = p[1]
            if (p[1] > maxLon) maxLon = p[1]
        }
        navigator.zoomToExtent(minLon, minLat, maxLon, maxLat)
    }

    /** 关闭轨迹落库线程（onDestroy：已入队的采集点写入仍会执行完毕；采集点已实时写库，下次启动可恢复未完成记录） */
    fun onDestroy() {
        // 界面销毁时仍在记录（退出 App 等）：停保活服务——追加链路已随监听注销而断，无点可采；
        // 下次启动由 restoreDraftTrack 恢复记录态时再据开关拉起
        if (trackRecording) BackgroundLocationController.onTrackRecordingChanged(activity, false)
        trackDbExecutor.shutdown()
    }
}
