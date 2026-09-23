package com.zys.mobilemap.ui.map

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import android.widget.Toast
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.MapDocument
import com.zys.mobilemap.location.MercatorZoom
import com.zys.mobilemap.media.MediaPlacemark
import com.zys.mobilemap.util.AppLog
import com.zys.mobilemap.util.CoordFormatter
import com.zys.worldwindjni.Camera
import com.zys.worldwindjni.NativeLayerInfo
import com.zys.worldwindjni.NativeMapView
import com.zys.worldwindjni.VectorExtent
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

/**
 * 相机导航与状态栏（自 MainActivity 拆出）：缩放/四至定位/移动相机、级别与中心坐标轮询刷新、
 * 相机视角的独立持久化（[saveCameraState]/[initialCamera]）。
 *
 * 相机优先级口径不变：本界面上次保存的独立视角 > 文档相机 > 兜底默认（北京上空 20km）。
 * 独立保存为迁移期历史成因：原 wwd 主界面每次 onPause 都会用 wwd 相机覆盖共享的文档相机，两界面写文档
 * 会互相冲掉；该界面已删除，独立 prefs 保留以兼容既有已存视角。
 */
internal class CameraNavigator(
    private val activity: Activity,
    private val mapView: NativeMapView,
    private val levelText: TextView,
    private val centerText: TextView
) {

    /**
     * 级别/中心文本轮询刷新：手势平移/缩放/fling 期间 native 相机持续变化，而 worldwindjni 暂无相机变更回调，
     * 故定时读回 native 相机刷新显示（getCamera 仅读回相机快照，开销可忽略）；onResume 启动、onPause 停止。
     */
    private val handler = Handler(Looper.getMainLooper())
    private val statusPoll = object : Runnable {
        override fun run() {
            updateStatusText()
            detectCameraSettled()
            handler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    /**
     * 相机静止回调：位姿（经度/纬度/高度）连续≥[SETTLE_MS] 不变后触发一次，供宿主按新屏幕范围
     * 重载可见的大数据矢量层（见 MapLayerManager）。仅在位姿实际变化后的稳定沿触发，同一视角不重复。
     */
    var onCameraSettled: ((Camera) -> Unit)? = null

    // 静止检测态：上次位姿签名 + 该签名首次观测时刻 + 本签名是否已触发（防抖动/重复）
    private var lastPoseSig: Triple<Double, Double, Double>? = null
    private var stableSinceMs = 0L
    private var settledFiredForCurrentPose = false

    /** 每轮询比对相机签名：变化则重置稳定计时并清除已触发标志；连续不变累计≥[SETTLE_MS] 且未触发→回调一次。 */
    private fun detectCameraSettled() {
        val cam = mapView.getCamera() ?: return
        val sig = Triple(cam.longitude, cam.latitude, cam.altitude)
        val now = SystemClock.elapsedRealtime()
        if (sig != lastPoseSig) {
            lastPoseSig = sig
            stableSinceMs = now
            settledFiredForCurrentPose = false
            return
        }
        if (!settledFiredForCurrentPose && now - stableSinceMs >= SETTLE_MS) {
            settledFiredForCurrentPose = true
            AppLog.i(TAG, "[VecReload] 相机静止触发: lon=%.6f lat=%.6f alt=%.1f 静止历时=%dms(阈值%d)".format(
                cam.longitude, cam.latitude, cam.altitude, now - stableSinceMs, SETTLE_MS))
            onCameraSettled?.invoke(cam)
        }
    }

    /**
     * 当前屏幕的**实际可见** WGS84 矩形（屏幕过滤范围）：把视口四角像素经 native [NativeMapView.screenToGeo]
     * 反算为地理坐标后取经纬度极值，即真实屏幕范围——不再用「高度×缓冲」近似、也不做任何向外扩大。
     * 返回矩形供 native 按**空间相交**查询（凡包络与矩形相交的要素都纳入，屏边跨界的要素不会被漏掉）。
     * 视口尚未就绪（宽高<=0，screenToGeo 返回 null）时，回退到按相机高度的等距估算（同样不放大）。
     */
    fun computeVisibleExtent(camera: Camera): VectorExtent {
        val w = mapView.width
        val h = mapView.height
        val corners = if (w > 0 && h > 0) {
            listOf(
                mapView.screenToGeo(0f, 0f),
                mapView.screenToGeo(w.toFloat(), 0f),
                mapView.screenToGeo(0f, h.toFloat()),
                mapView.screenToGeo(w.toFloat(), h.toFloat()),
            )
        } else {
            emptyList()
        }.filterNotNull()
        if (corners.isEmpty()) {
            // 回退：纬度半跨 = 高度/111320（约 1 度=111.32km），经度半跨按 cos(lat) 放大到等距；不乘缓冲
            val dLat = abs(camera.altitude) / 111320.0
            val cosLat = cos(Math.toRadians(camera.latitude)).coerceAtLeast(1e-6)
            val dLon = dLat / cosLat
            return VectorExtent(
                minLon = camera.longitude - dLon,
                minLat = camera.latitude - dLat,
                maxLon = camera.longitude + dLon,
                maxLat = camera.latitude + dLat,
            )
        }
        return VectorExtent(
            minLon = corners.minOf { it.longitude },
            minLat = corners.minOf { it.latitude },
            maxLon = corners.maxOf { it.longitude },
            maxLat = corners.maxOf { it.latitude },
        )
    }

    /** 装配初期相机恢复：独立 prefs 视角 > 文档相机 > 兜底默认（自 MainActivity.setupMapUi 相机段拆入） */
    fun initialCamera(doc: MapDocument?): Camera {
        var lon = DEFAULT_LON
        var lat = DEFAULT_LAT
        var alt = DEFAULT_ALTITUDE
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LON)) {
            lon = Double.fromBits(prefs.getLong(KEY_LON, 0L))
            lat = Double.fromBits(prefs.getLong(KEY_LAT, 0L))
            alt = Double.fromBits(prefs.getLong(KEY_ALT, 0L))
        } else if (doc != null && !doc.camLongitude.isNaN() && !doc.camLatitude.isNaN() && !doc.camAltitude.isNaN()) {
            lon = doc.camLongitude
            lat = doc.camLatitude
            alt = doc.camAltitude
        }
        return Camera(latitude = lat, longitude = lon, altitude = alt)
    }

    fun zoomIn() = zoomBy(ZOOM_IN_FACTOR)

    fun zoomOut() = zoomBy(ZOOM_OUT_FACTOR)

    /**
     * 缩放：读回相机高度按系数缩放（<1 拉近、>1 拉远），并限幅到 [MercatorZoom.MIN_CAMERA_ALTITUDE]
     * （对应 20 级地面高度）防止超级别放大致末级瓦片撕裂/黑屏——与原主界面 NavigatorModel 同口径。
     */
    fun zoomBy(factor: Double) {
        val cam = mapView.getCamera() ?: return
        val newAlt = (cam.altitude * factor).coerceAtLeast(MercatorZoom.MIN_CAMERA_ALTITUDE)
        mapView.setCamera(cam.copy(altitude = newAlt))
        updateStatusText()
    }

    /**
     * 缩放到四至范围（复刻原主界面 zoomToExtent）：按经纬度跨度估算合适相机高度
     * （span × 每度约 111320 米 × 1.5 留边），限幅到 [MercatorZoom.MIN_CAMERA_ALTITUDE]，相机移到范围中心。
     * 供图层管理「缩放到图层」、轨迹「缩放至」与查询结果定位复用；本界面无相机动画能力，
     * 故即时定位（与 [zoomBy]/[moveToLocation] 同口径）。
     */
    fun zoomToExtent(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) {
        val span = maxOf(maxLon - minLon, maxLat - minLat).coerceAtLeast(0.01)
        val altitude = (span * 111320.0 * 1.5).coerceAtLeast(MercatorZoom.MIN_CAMERA_ALTITUDE)
        mapView.setCamera(
            Camera(
                latitude = (minLat + maxLat) / 2.0,
                longitude = (minLon + maxLon) / 2.0,
                altitude = altitude,
            )
        )
        updateStatusText()
    }

    /**
     * 缩放到图层四至范围（GDAL 读取，kmz 走 /vsizip/ 虚拟文件）：经 worldwindjni [NativeLayerInfo.getLayerExtent] 读取。
     * 文档新增图层后 save 会触发界面重建，故先 setCamera（本方法）；onPause 会将该视角存入 prefs，重建后跨 recreate 保留缩放。
     */
    fun zoomToLayerExtent(path: String) {
        val openPath = if (path.lowercase(Locale.getDefault()).endsWith(".kmz")) "/vsizip/$path" else path
        val extent = NativeLayerInfo.getLayerExtent(openPath)
        if (extent != null && extent.size == 4) {
            zoomToExtent(extent[0], extent[1], extent[2], extent[3])
        }
    }

    /** 缩放至拍照点位：平移到点位经纬度，保留当前缩放级别 */
    fun zoomToMediaPlacemark(placemark: MediaPlacemark) {
        val cam = mapView.getCamera()
        val alt = if (cam != null && cam.altitude > 0.0) cam.altitude else GOTO_ALTITUDE
        mapView.setCamera(
            Camera(
                latitude = placemark.latitude,
                longitude = placemark.longitude,
                altitude = alt,
            )
        )
        updateStatusText()
    }

    /**
     * 移动相机到定位点：只锁定经纬度、保留用户当前缩放级别（相机高度无效时才回退 [GOTO_ALTITUDE]）。
     * 单次平移，不做逐帧动画（后续需要平滑跟随时再增强）。
     */
    fun moveToLocation(lat: Double, lon: Double, notify: Boolean) {
        val cam = mapView.getCamera()
        val alt = if (cam != null && cam.altitude > 0.0) cam.altitude else GOTO_ALTITUDE
        mapView.setCamera(Camera(latitude = lat, longitude = lon, altitude = alt))
        updateStatusText()
        if (notify) Toast.makeText(activity, R.string.jni_map_located, Toast.LENGTH_SHORT).show()
    }

    /**
     * 读回 native 相机刷新级别数字与中心经纬度文本：级别换算与坐标格式均与原主界面同源
     * （[MercatorZoom.altitudeToLevel] / [CoordFormatter]），保证升级前后显示一致。
     */
    fun updateStatusText() {
        val cam = mapView.getCamera() ?: return
        levelText.text = MercatorZoom.altitudeToLevel(cam.altitude).toString()
        centerText.text = CoordFormatter.format("中心", cam.longitude, cam.latitude)
    }

    /** 启动级别/中心文本轮询刷新（onResume） */
    fun startPolling() {
        handler.removeCallbacks(statusPoll)
        handler.postDelayed(statusPoll, STATUS_POLL_MS)
    }

    /** 停止轮询（onPause/onDestroy） */
    fun stopPolling() {
        handler.removeCallbacks(statusPoll)
    }

    /**
     * 把本界面当前相机（经纬度 + 高度米）存到独立的 SharedPreferences，下次进入本界面恢复。
     * 不写共享文档相机：原 wwd 主界面（已删除）每次 onPause 都会用 wwd 相机覆盖文档相机，
     * 共用会互相冲掉；独立存储让本界面记住自己的视角。
     * double 以 [Double.toRawBits] 存为 Long 保精度（SharedPreferences 无 double 类型）。
     */
    fun saveCameraState() {
        val cam = mapView.getCamera() ?: return
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LON, cam.longitude.toRawBits())
            .putLong(KEY_LAT, cam.latitude.toRawBits())
            .putLong(KEY_ALT, cam.altitude.toRawBits())
            .putInt(KEY_VIEW_MODE, if (mapView.getViewMode() == NativeMapView.ViewMode.THREE_D) 1 else 0)
            .apply()
    }

    /** 启动装配时读回持久化的视图模式（随相机状态一同保存）：1 → 3D 球体，否则 2D 平面墨卡托 */
    fun savedViewMode(): NativeMapView.ViewMode {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.getInt(KEY_VIEW_MODE, 0) == 1) NativeMapView.ViewMode.THREE_D else NativeMapView.ViewMode.TWO_D
    }

    companion object {
        private const val TAG = "CameraNavigator"

        // 文档无有效视角时的兜底相机（北京上空 20km）
        const val DEFAULT_LON = 116.391
        const val DEFAULT_LAT = 39.907
        const val DEFAULT_ALTITUDE = 20000.0

        // 缩放步进系数（与原主界面一致：每次点击把相机高度乘/除约 1.25 倍）
        private const val ZOOM_IN_FACTOR = 0.8
        private const val ZOOM_OUT_FACTOR = 1.2

        // 级别/中心文本轮询刷新间隔（毫秒）
        private const val STATUS_POLL_MS = 250L

        // 相机静止判定阈值（毫秒）：位姿连续不变达此值即判“停下”，触发一次屏幕范围重载
        private const val SETTLE_MS = 500L

        // 「移到定位点」时相机高度无效的兑底观察高度（米，与原主界面 LocationModel.LOCK_ALTITUDE 一致）
        const val GOTO_ALTITUDE = 1200.0

        // 本界面独立保存的相机视角（与原主界面共享文档相机隔离，避免互相覆盖）
        private const val PREFS_NAME = "jni_map_camera"
        private const val KEY_LON = "lon"
        private const val KEY_LAT = "lat"
        private const val KEY_ALT = "alt"

        // 视图模式（2D/3D）随相机状态一同持久化：下次进入界面恢复上次选择的模式
        private const val KEY_VIEW_MODE = "view_mode"
    }
}
