package com.zys.mobilemap.ui.map

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.location.LocationManager
import com.zys.mobilemap.util.CoordFormatter
import com.zys.mobilemap.util.GeoCalc
import com.zys.worldwindjni.NativeMapView
import com.zys.worldwindjni.Position
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 定位展示与跟随（自 MainActivity 拆出）：注册 [LocationManager] 监听、喂 native 绘制罗盘定位标记（含方向箭头）、
 * 刷新底部定位经纬度文本、跟随锁定时把相机平移到定位点。
 *
 * 定位权限申请器（`locationPermissionLauncher`）仍留在 MainActivity——registerForActivityResult 必须在
 * Activity 属性初始化期注册；授权结果回调转 [startLocating]。轨迹采集的定位追加点经 [onTrackLocation]
 * 由 Activity 接线到 TrackCaptureController，本类不直接依赖轨迹域。相机平移复用 [CameraNavigator.moveToLocation]。
 */
internal class LocationFollowController(
    private val activity: Activity,
    private val mapView: NativeMapView,
    private val locationText: TextView,
    private val locateButton: ImageView,
    private val navigator: CameraNavigator
) {

    /** 定位跟随锁定：锁定后每次定位更新把相机平移到定位点（对齐原主界面 LocationModel.locationFollowEnabled） */
    private var followEnabled = false

    /** 锁定后等待首个定位点的待处理标记：拿到首点时对齐并提示 */
    private var pendingLocate = false

    /** 上一次定位点（用于计算移动方位角）；NaN 表示尚无历史点 */
    private var lastMarkerLat = Double.NaN
    private var lastMarkerLon = Double.NaN

    /** 最近一次有效移动方位角（度）：位移不足阈值时沿用，避免箭头抖动；null 表示尚无方向 */
    private var lastHeading: Double? = null

    /** 定位更新时的轨迹追加回调（记录中且未暂停时触发）；由 Activity 接线到 TrackCaptureController */
    var onTrackLocation: ((lat: Double, lon: Double, alt: Double) -> Unit)? = null

    /** 定位更新监听：回调在主线程（[LocationManager] 以主 Looper 注册），直接刷新罗盘定位标记与定位文本 */
    private val locationListener = LocationManager.OnLocationChangedListener { location ->
        if (location != null) onLocationChanged(location)
    }

    /** 已授权则直接开始定位，否则经 [requestPermissions]（Activity 侧权限申请器）申请定位权限 */
    fun initLocation(requestPermissions: () -> Unit) {
        val granted = ActivityCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startLocating()
        } else {
            requestPermissions()
        }
    }

    /** 注册定位监听并启动定位（复用与原主界面同源的 [LocationManager] 单例），并用最后已知位置立即刷新一次蓝点 */
    fun startLocating() {
        val lm = LocationManager.getInstance()
        lm.init(activity.application)
        lm.addLocationListener(locationListener)
        val doc = DocumentManager.getInstance().getDocument()
        val background = doc != null && doc.systemConfig.backgroundLocation
        lm.startLocation(background)
        lm.getLastLocation()?.let { onLocationChanged(it) }
    }

    /**
     * 定位更新：刷新底部定位经纬度文本（与中心坐标同格式）+ 计算移动方位角 + 喂 native 绘制定位蓝点（含方向箭头）。
     * 若处于跟随锁定，把相机平移到定位点（首点提示，其后静默跟随）。
     */
    fun onLocationChanged(location: Location) {
        locationText.text = CoordFormatter.format("定位", location.longitude, location.latitude)

        // 移动方位角：位移超过阈值才更新，避免静止/漂移时箭头抖动；无更新时沿用上次方位
        computeHeading(location.latitude, location.longitude)?.let { lastHeading = it }
        mapView.setLocationMarker(
            Position(latitude = location.latitude, longitude = location.longitude),
            true, lastHeading ?: -1.0
        )
        lastMarkerLat = location.latitude
        lastMarkerLon = location.longitude

        if (pendingLocate) {
            navigator.moveToLocation(location.latitude, location.longitude, notify = true)
            pendingLocate = false
        } else if (followEnabled) {
            navigator.moveToLocation(location.latitude, location.longitude, notify = false)
        }

        // 轨迹采集：记录中且未暂停时把定位点追加为活动轨迹点（对齐原主界面 TrackModel 定位监听追加：实时落库 + live 层渲染）
        onTrackLocation?.invoke(location.latitude, location.longitude, location.altitude)
    }

    /**
     * 切换定位跟随锁定：未锁定则锁定（换锁定图标 + 移到定位点，无定位点时等待首点），
     * 已锁定则解锁（换回普通图标，停止相机跟随）。参照原主界面 LocationModel.toggleLocationLock。
     */
    fun toggleFollow() {
        if (followEnabled) {
            followEnabled = false
            pendingLocate = false
            locateButton.setImageResource(R.drawable.nav_location)
            return
        }
        followEnabled = true
        locateButton.setImageResource(R.drawable.nav_lock_location)
        val last = LocationManager.getInstance().getLastLocation()
        if (last != null) {
            navigator.moveToLocation(last.latitude, last.longitude, notify = true)
        } else {
            pendingLocate = true
            Toast.makeText(activity, R.string.jni_map_waiting_location, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 计算从上一次定位点到当前点的移动方位角（顺时针自北 0..360）；无历史点或位移小于
     * [MIN_HEADING_DISTANCE_METERS] 时返回 null（不更新方向）。复刻原主界面 LocationModel 的球面方位角算法。
     */
    private fun computeHeading(toLat: Double, toLon: Double): Double? {
        if (lastMarkerLat.isNaN() || lastMarkerLon.isNaN()) return null
        if (GeoCalc.distanceMeters(lastMarkerLat, lastMarkerLon, toLat, toLon) < MIN_HEADING_DISTANCE_METERS) return null
        val lat1 = Math.toRadians(lastMarkerLat)
        val lat2 = Math.toRadians(toLat)
        val dLon = Math.toRadians(toLon - lastMarkerLon)
        val x = sin(dLon) * cos(lat2)
        val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val heading = Math.toDegrees(atan2(x, y))
        return (heading + 360.0) % 360.0
    }

    /** 注销定位监听（onDestroy 调用；不调 stopLocation：定位由 LocationManager 单例统一管理，其他模块可能仍在使用） */
    fun onDestroy() {
        LocationManager.getInstance().removeLocationListener(locationListener)
    }

    companion object {
        // 保持与原 MainActivity 相同日志 tag，行为零变化
        private const val TAG = "MainActivity"

        // 更新方位角所需的最小位移（米）：小于此值视为静止，不刷新箭头方向（与原主界面一致）
        private const val MIN_HEADING_DISTANCE_METERS = 1.5
    }
}
