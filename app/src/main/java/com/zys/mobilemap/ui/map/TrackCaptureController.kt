package com.zys.mobilemap.ui.map

import android.location.Location
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.zys.mobilemap.location.BackgroundLocationController
import com.zys.mobilemap.location.GateInput
import com.zys.mobilemap.location.LocationManager
import com.zys.mobilemap.location.RejectReason
import com.zys.mobilemap.location.TrackPointGate
import com.zys.mobilemap.location.Verdict
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
 * [LocationFollowController] 的 location 回调转接进来（记录中且未暂停时经 [TrackPointGate] 门控后才追加）。
 * 落库走独立单线程队列，[onDestroy] 时关闭。live 渲染/缩放复用 [BusinessOverlayManager.Companion.flattenLonLat]
 * 与 [CameraNavigator.zoomToExtent]。
 *
 * 起点不直接吃 `getLastLocation()` 缓存（历史版本的第一根甩出长线就来自它）：开始/恢复记录都置 [awaitingFirstFix]，
 * 由门控挑首个可信定位落起点，等待超 [FIRST_FIX_TIMEOUT_MS] 才退化用最佳候选点兜底。
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

    // ── 采集门控状态（一个记录会话一套，开始/恢复记录时重置；判据口径见 [TrackPointGate]）──────────
    /** 门控实例：持有"连续被拒簇"计数，会话起点/恢复点须 reset 以免沿用上一次的簇状态 */
    private val trackGate = TrackPointGate()

    /** 上一个已接受点的单调时间戳（毫秒）；-1 表示无可靠时间基准（起录后首点、恢复历史草稿），此时不判速度 */
    private var lastAcceptedElapsedMs = -1L

    /** 等待首个可信定位（开始记录/恢复记录后为 true）：此期间拒掉的点不落库，仅攒兜底候选 */
    private var awaitingFirstFix = false

    /** 等待首点的起始时刻（[SystemClock.elapsedRealtime]），用于 [FIRST_FIX_TIMEOUT_MS] 兜底 */
    private var firstFixWaitStartedMs = 0L

    /** 等待首点期间质量最好的候选（精度半径最小）：超时后据它落起点，胜过完全没有起点 */
    private var bestWaitCandidate: Location? = null

    /** 本次记录会话被门控拒掉的定位条数（停止记录时随日志报出，供现场校准阈值） */
    private var rejectedPointCount = 0

    /** 本次记录会话的间隙次数（连续被拒后升级接受 / 恢复记录后位移过大） */
    private var gapCount = 0

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
     * 开始轨迹记录：定位检查后清空活动点、建 live 红线层、置记录态（capturing 转 true）、建立草稿轨迹，
     * 并进入"等待首个可信定位"状态落起点（不吃 `getLastLocation()` 的历史缓存）。
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
        beginAwaitingFirstFix(fromCache = lm.getLastLocation())
        // 记录开始 → 后台定位保活服务随记录态启动（仅当开关为开，由 Controller 门控）——息屏后仍持续定位不缺点
        BackgroundLocationController.onTrackRecordingChanged(activity, true)
        updateTrackControlButtons()
    }

    /**
     * 进入等待首个可信定位状态并尝试用缓存定位立即落起点：缓存够新、来源可信、精度达标就当场有起点，
     * 否则挂 [awaitingFirstFix] 等后续回调，超 [FIRST_FIX_TIMEOUT_MS] 才用最佳候选兜底。
     */
    private fun beginAwaitingFirstFix(fromCache: Location?) {
        trackGate.reset()
        lastAcceptedElapsedMs = -1L
        awaitingFirstFix = true
        firstFixWaitStartedMs = SystemClock.elapsedRealtime()
        bestWaitCandidate = null
        rejectedPointCount = 0
        gapCount = 0
        if (fromCache != null) onLocation(fromCache)
        if (trackActivePoints.isEmpty()) {
            Toast.makeText(activity, "开始记录轨迹（等待可信定位中…）", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(activity, "开始记录轨迹", Toast.LENGTH_SHORT).show()
        }
    }

    /** 暂停轨迹记录：置暂停态（定位监听据 trackPaused 停止追加）并刷新控件 */
    fun pauseTrackRecording() {
        if (!trackRecording || trackPaused) return
        trackPaused = true
        updateTrackControlButtons()
        Toast.makeText(activity, "轨迹记录已暂停", Toast.LENGTH_SHORT).show()
    }

    /**
     * 继续轨迹记录：解除暂停后重新等一个可信定位作恢复点——不再直接补 `getLastLocation()`：暂停期间人可能
     * 已经移动/漂移，硬连上就是一根斜穿的尖角（成因方案里的根因 4）。恢复位移过大时只记日志与提示，
     * 断段留待阶段 2（TrackRecord 加 parts）。
     */
    fun resumeTrackRecording() {
        if (!trackRecording || !trackPaused) return
        trackPaused = false
        trackGate.reset()
        lastAcceptedElapsedMs = -1L   // 暂停期间的时长不能当速度判据的分母
        awaitingFirstFix = true
        firstFixWaitStartedMs = SystemClock.elapsedRealtime()
        bestWaitCandidate = null
        // 恢复记录 → 幂等补保活（覆盖暂停期间用户关闭又打开开关导致服务已停的情形）
        BackgroundLocationController.onTrackRecordingChanged(activity, true)
        updateTrackControlButtons()
        Toast.makeText(activity, "轨迹记录继续（等待可信定位…）", Toast.LENGTH_SHORT).show()
        LocationManager.getInstance().getLastLocation()?.let { onLocation(it) }
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
            AppLog.i(TAG, "轨迹点不足未入库：accepted=${points.size} 拒$rejectedPointCount 条 间隙$gapCount 次")
            Toast.makeText(
                activity,
                if (rejectedPointCount > 0) "轨迹点不足，未生成轨迹线（已剔除 $rejectedPointCount 条低质量定位）"
                else "轨迹点不足，未生成轨迹线",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        ProjectManager.get(activity).addTrack(trackNameOf(createdTime), createdTime, points)
        // 立即同步落盘后再删草稿：防抖窗口内进程被杀不丢整条轨迹
        ProjectManager.get(activity).saveNow()
        AppLog.i(TAG, "轨迹入库耗时 addTrack+saveNow=${SystemClock.elapsedRealtime() - t0}ms points=${points.size}")
        AppLog.i(TAG, "本次轨迹质量：入库 ${points.size} 点，门控剔除 $rejectedPointCount 条，间隙 $gapCount 次")
        deleteDraftTrack()
        onTrackSaved(rejectedPointCount, gapCount)
    }

    /** 轨迹保存完成（主线程）：重建叠加层显示已存轨迹并提示（带本次剔除统计，便于现场校准门控阈值） */
    private fun onTrackSaved(rejected: Int, gaps: Int) {
        rebuildOverlays()
        val tail = when {
            rejected == 0 && gaps == 0 -> ""
            gaps > 0 -> "　·　剔除 $rejected 条低质量定位，$gaps 处信号中断"
            else -> "　·　剔除 $rejected 条低质量定位"
        }
        Toast.makeText(activity, if (tail.isEmpty()) "轨迹记录已停止" else "轨迹记录已停止$tail", Toast.LENGTH_LONG).show()
    }

    /** 米数展示：整米即可，日志与提示共用 */
    private fun fmt(meters: Double): String = String.format(Locale.getDefault(), "%.0f", meters)

    /**
     * 定位更新入口（记录中且未暂停时生效）：先过 [TrackPointGate] 五判据（来源/新鲜度/精度/间距/速度），
     * 通过才追加为活动轨迹点。宿主把 [LocationFollowController] 的 location 回调整条转接进来。
     *
     * 判据时间一律取单调时钟：`Location.time` 是墙钟时间会被网络校时拉扯，`elapsedRealtimeNanos` 与
     * [SystemClock.elapsedRealtime] 同为开机起算，可直接相减。
     */
    fun onLocation(loc: Location) {
        if (!trackRecording || trackPaused) return
        val now = SystemClock.elapsedRealtime()
        val fixAgeMs = (now - loc.elapsedRealtimeNanos / 1_000_000L).coerceAtLeast(0L)
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else -1.0
        val last = trackActivePoints.lastOrNull()
        val distance = last?.let { GeoCalc.distanceMeters(it[0], it[1], loc.latitude, loc.longitude) } ?: -1.0
        val gapMs = if (lastAcceptedElapsedMs >= 0) now - lastAcceptedElapsedMs else -1L

        when (val verdict = trackGate.decide(
            GateInput(loc.latitude, loc.longitude, loc.provider, accuracy, fixAgeMs, gapMs, distance)
        )) {
            Verdict.Accept -> acceptLocation(loc, now, gapDistance = null)

            is Verdict.AcceptGap -> {
                // 连续被拒后确认"真的到了新区域"（隧道/林下失锁后重定位）：接受并记一次间隙
                acceptLocation(loc, now, gapDistance = verdict.distanceMeters)
                AppLog.w(
                    TAG, "轨迹间隙接受：连续 ${verdict.consecutiveRejects} 点被拒后落新点，" +
                        "距上一点 ${fmt(verdict.distanceMeters)}（阶段 2 在此断段）"
                )
            }

            is Verdict.Reject -> onRejected(loc, verdict.reason, accuracy, fixAgeMs, gapMs, distance, now)
        }
    }

    /** 接受一个定位并入轨迹：清等待态、刷新时间基准、落草稿、重绘 live 红线；间隙在此提示 */
    private fun acceptLocation(loc: Location, nowMs: Long, gapDistance: Double?) {
        awaitingFirstFix = false
        bestWaitCandidate = null
        lastAcceptedElapsedMs = nowMs
        // 无 hasAltitude 时仍写 0.0：草稿 JSON 与工程库按有限双精度存，NaN 会让 JSONObject.put 抛异常
        val alt = if (loc.hasAltitude()) loc.altitude else 0.0
        appendTrackPoint(loc.latitude, loc.longitude, alt)
        if (gapDistance != null) {
            gapCount++
            Toast.makeText(
                activity, "信号中断后重新定位，新点距上一段 ${GeoCalc.formatDistance(gapDistance)}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 被门控拒掉：计数 + 记现场可诊断日志；等待首点期间另攒最佳候选做超时兜底 */
    private fun onRejected(
        loc: Location, reason: RejectReason,
        accuracy: Double, fixAgeMs: Long, gapMs: Long, distance: Double, nowMs: Long
    ) {
        rejectedPointCount++
        AppLog.w(
            TAG, "轨迹点拒[$reason] prov=${loc.provider} " +
                "acc=${if (accuracy < 0) "n/a" else fmt(accuracy)}m age=${fixAgeMs}ms " +
                "gap=${if (gapMs < 0) "n/a" else "${gapMs}ms"} " +
                "d=${if (distance < 0) "首点" else fmt(distance)}m"
        )
        if (!awaitingFirstFix) return
        // 起点等待中：陈旧点与 network/passive 转发不做候选（那正是本要治的粗差来源），宁可再等
        if (reason == RejectReason.STALE || reason == RejectReason.UNTRUSTED_PROVIDER) return
        val best = bestWaitCandidate
        val bestAcc = if (best != null && best.hasAccuracy()) best.accuracy.toDouble() else Double.MAX_VALUE
        if (best == null || (accuracy >= 0 && accuracy < bestAcc)) bestWaitCandidate = loc
        if (nowMs - firstFixWaitStartedMs > FIRST_FIX_TIMEOUT_MS && bestWaitCandidate != null) {
            val fallback = bestWaitCandidate!!
            AppLog.w(
                TAG, "起点等待超时 ${FIRST_FIX_TIMEOUT_MS}ms，退化用最佳候选 " +
                    "prov=${fallback.provider} acc=${if (fallback.hasAccuracy()) fmt(fallback.accuracy.toDouble()) else "n/a"}m"
            )
            acceptLocation(fallback, nowMs, gapDistance = null)
        }
    }

    /**
     * 追加轨迹点（[lat,lon,alt]）：实时重写草稿文件（单线程队列）并重绘 live 红线。
     * 质量判定已全部前移到 [onLocation] 的 [TrackPointGate]，此处只负责落地，不再自行过滤。
     */
    private fun appendTrackPoint(lat: Double, lon: Double, alt: Double) {
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
        // 草稿是历史会话的点：与末点之间没有可靠的单调时间基准，首个新点不判速度（间距/精度/来源仍判）
        trackGate.reset()
        lastAcceptedElapsedMs = -1L
        awaitingFirstFix = false
        bestWaitCandidate = null
        rejectedPointCount = 0
        gapCount = 0
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
        ).show(fm, TAG_TRACK_DETAIL)
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

    /** 轨迹重命名：弹输入框修改名称，保存后重建叠加层并就地刷新详情弹层标题 */
    private fun promptRenameTrack(record: Project.TrackRecord) {
        NameEditDialog.show(
            activity, "重命名轨迹", record.name ?: "", "轨迹名称"
        ) { newName ->
            // updateTrackName 已就地把新名写回内存记录（record 即该实例），此处只需刷新前台弹层标题
            ProjectManager.get(activity).updateTrackName(record.id, newName)
            rebuildOverlays()
            // 详情弹层仍在前台时标题就地刷新（按 tag 定位，未打开则忽略）
            (fm.findFragmentByTag(TAG_TRACK_DETAIL) as? MeasureDetailDialog)?.updateTitle(newName)
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

        /** 轨迹详情弹层 tag：重命名后就地刷新标题按此取回弹层实例 */
        private const val TAG_TRACK_DETAIL = "TrackDetailDialog"

        /** 轨迹草稿文件名（崩溃恢复用，存于工程目录下，隐藏文件不参与工程列表） */
        private const val DRAFT_FILE_NAME = ".track_draft.json"

        /**
         * 起点等待超时（毫秒）：超过此时长仍没有通过门控的定位，就退化用等待期间精度最好的候选点落起点。
         * 没有起点比起点偏差更难补救（记录按钮会一直"等待可信定位"），但兜底必进日志以便现场分辨。
         */
        private const val FIRST_FIX_TIMEOUT_MS = 12_000L
    }
}
