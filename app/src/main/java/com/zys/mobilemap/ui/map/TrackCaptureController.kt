package com.zys.mobilemap.ui.map

import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.zys.mobilemap.location.BackgroundLocationController
import com.zys.mobilemap.location.LocationManager
import com.zys.mobilemap.project.Project
import com.zys.mobilemap.project.ProjectManager
import com.zys.mobilemap.track.TrackSharePackage
import com.zys.mobilemap.ui.dialog.MeasureDetailDialog
import com.zys.mobilemap.ui.dialog.NameEditDialog
import com.zys.mobilemap.ui.dialog.TrackManageDialog
import com.zys.mobilemap.util.AppLog
import com.zys.mobilemap.util.GeoCalc
import com.zys.globecore.NativeMapView
import com.zys.globecore.VectorStyle as NativeVectorStyle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    /** 轨迹采集：记录/暂停状态 + 活动点串（[lat,lon,alt]）+ live 叠加层索引 + 草稿起始时间 + 草稿写盘单线程 */
    private var trackRecording = false
    private var trackPaused = false
    private val trackActivePoints = mutableListOf<DoubleArray>()
    private var trackLiveIndex = -1
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
        val hasRecords = ProjectManager.get(activity).hasTracks()
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
        writeDraftTrack()  // 建立草稿文件（进程崩溃可恢复）
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
     * 停止轨迹记录：采集点已实时写入草稿文件，此处把整串定型进当前工程并删除草稿；点数不足则只删草稿。
     * 先同步拆 live 层并置 trackRecording=false（capturing 转 false），再重建叠加层显示新轨迹。
     */
    fun stopTrackRecording() {
        if (!trackRecording) return
        trackRecording = false
        trackPaused = false
        // 停止记录 → 后台定位保活即刻停服务（不做记录之外的后台定位）
        BackgroundLocationController.onTrackRecordingChanged(activity, false)
        updateTrackControlButtons()

        val createdTime = trackDraftCreatedTime
        val insufficient = trackActivePoints.size < 2
        val points = trackActivePoints.map { doubleArrayOf(it[0], it[1], it.getOrElse(2) { 0.0 }) }
        clearActiveTrack()  // 拆 live 层 + 清活动点（capturing 转 false，后续重建可正常执行）

        if (insufficient) {
            deleteDraftTrack()
            rebuildOverlays()
            Toast.makeText(activity, "轨迹点不足，未生成轨迹线", Toast.LENGTH_SHORT).show()
            return
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        ProjectManager.get(activity).addTrack(trackNameOf(createdTime), createdTime, points)
        // 立即同步落盘后再删草稿：防抖窗口内进程被杀不丢整条轨迹
        ProjectManager.get(activity).saveNow()
        AppLog.i(TAG, "轨迹入库耗时 addTrack+saveNow=${android.os.SystemClock.elapsedRealtime() - t0}ms points=${points.size}")
        deleteDraftTrack()
        onTrackSaved()
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
     * 追加轨迹点（[lat,lon,alt]）：按最小间距过滤 GPS 抖动，实时重写草稿文件（单线程队列），并重绘 live 红线。
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
        writeDraftTrack()
        renderLiveTrack()
    }

    // ---- 草稿文件（崩溃恢复）：/调查宝/projects/.track_draft.json，{name,createdTime,points:[[lat,lon,alt],...]} ----

    private fun draftFile(): File = File(ProjectManager.get(activity).projectsDir, DRAFT_FILE_NAME)

    /** 把当前活动点串整体重写进草稿文件（单线程队列串行化；先写临时文件再 rename，防半写文件） */
    private fun writeDraftTrack() {
        val name = trackNameOf(trackDraftCreatedTime)
        val created = trackDraftCreatedTime
        val snapshot = trackActivePoints.toList()
        val file = draftFile()
        trackDbExecutor.execute {
            runCatching {
                val pts = JSONArray()
                for (p in snapshot) {
                    pts.put(JSONArray().apply {
                        put(p[0]); put(p[1]); put(if (p.size > 2) p[2] else 0.0)
                    })
                }
                val json = JSONObject()
                    .put("name", name)
                    .put("createdTime", created)
                    .put("points", pts)
                    .toString()
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "$DRAFT_FILE_NAME.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    /** 删除草稿文件（定型入库或丢弃时调用） */
    private fun deleteDraftTrack() {
        val file = draftFile()
        trackDbExecutor.execute { runCatching { file.delete() } }
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
     * 恢复未结束的草稿轨迹（进程被回收/异常退出后）：读回草稿文件中的采集点重建 live 红线并恢复记录态。
     * 空草稿（刚开始就中断）直接清理。在 onCreate 装配叠加层后调用。
     */
    fun restoreDraftTrack() {
        val file = draftFile()
        if (!file.exists()) return
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val arr = json.optJSONArray("points")
        val pts = mutableListOf<DoubleArray>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                if (p.length() < 2) continue
                pts.add(
                    doubleArrayOf(
                        p.getDouble(0), p.getDouble(1),
                        if (p.length() > 2) p.getDouble(2) else 0.0
                    )
                )
            }
        }
        if (pts.isEmpty()) {
            deleteDraftTrack()
            return
        }
        trackDraftCreatedTime = json.optLong("createdTime", System.currentTimeMillis())
        trackActivePoints.clear()
        trackActivePoints.addAll(pts)
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
     * 轨迹详情 BottomSheet（复用 [MeasureDetailDialog]，typeText=轨迹）：样式编辑写库后重建叠加层，删除走确认，
     * 分享按导出 shp 数据包（折线 + WGS84，见 [TrackSharePackage]）经系统分享面板发出。
     */
    fun showTrackDetail(record: Project.TrackRecord) {
        MeasureDetailDialog(
            title = record.name ?: "",
            typeText = "轨迹",
            geometryText = trackGeometryText(record),
            widthEditable = true,
            initialColor = ProjectManager.parseColor(record.color, 0xFF33CCFF.toInt()),
            initialWidth = record.lineWidth,
            onSave = { color, width ->
                ProjectManager.get(activity).updateTrackStyle(record.id, color, width)
                rebuildOverlays()
                Toast.makeText(activity, "样式已更新", Toast.LENGTH_SHORT).show()
            },
            onDelete = { confirmDeleteTrack(record) },
            onShare = { TrackSharePackage.shareTrack(activity, record) },
            onRename = { promptRenameTrack(record) }
        ).show(fm, "TrackDetailDialog")
    }

    /** 轨迹量算文本：总长 + 点数 */
    private fun trackGeometryText(record: Project.TrackRecord): String {
        val degrees = record.points.map { doubleArrayOf(it[0], it[1]) }
        val length = GeoCalc.polylineLengthMeters(degrees)
        return "距离: " + GeoCalc.formatDistance(length) + "　·　" + record.points.size + "个点"
    }

    /** 删除轨迹确认：删库并重建叠加层 */
    private fun confirmDeleteTrack(record: Project.TrackRecord) {
        AlertDialog.Builder(activity)
            .setTitle("删除确认")
            .setMessage("确定删除轨迹：${record.name ?: ""} ？删除后该轨迹及其全部坐标点将一并删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                ProjectManager.get(activity).removeTrack(record.id)
                rebuildOverlays()
                Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 轨迹重命名：弹输入框修改名称，保存后重建叠加层 */
    private fun promptRenameTrack(record: Project.TrackRecord) {
        NameEditDialog.show(
            activity, "重命名轨迹", record.name ?: "", "轨迹名称"
        ) { newName ->
            ProjectManager.get(activity).updateTrackName(record.id, newName)
            rebuildOverlays()
            Toast.makeText(activity, "已重命名", Toast.LENGTH_SHORT).show()
        }
    }

    /** 轨迹默认名称：轨迹 yyyy-MM-dd HH:mm:ss（取采集起始时间） */
    private fun trackNameOf(time: Long): String = String.format(
        Locale.getDefault(), "轨迹 %1\$tY-%1\$tm-%1\$td %1\$tH:%1\$tM:%1\$tS", Date(time)
    )

    /** 缩放至轨迹：按轨迹点四至计算中心与合适高度（对齐原主界面 TrackModel.zoomToTrack） */
    private fun zoomToTrack(track: Project.TrackRecord) {
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

    companion object {
        private const val TAG = "TrackCapture"

        /** 轨迹草稿文件名（崩溃恢复用，存于工程目录下，隐藏文件不参与工程列表） */
        private const val DRAFT_FILE_NAME = ".track_draft.json"
    }
}
