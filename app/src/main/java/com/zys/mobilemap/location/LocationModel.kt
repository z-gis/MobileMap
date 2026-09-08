package com.zys.mobilemap.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.ui.activity.MapWorldWindow
import com.zys.mobilemap.util.CoordFormatter
import com.zys.mobilemap.util.GeoCalc
import earth.worldwind.WorldWindow
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.OrientationMode
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 主界面定位展示管理：负责定位权限申请、定位启动，
 * 以及定位坐标文本和地图定位标记的更新。
 * 定位坐标文本格式（十进制度 / 米 CGCS2000）与中心坐标一致，由系统设置驱动。
 */
@SuppressLint("MissingPermission")
class LocationModel(
    private val activity: Activity,
    private val worldWindow: WorldWindow,
    private val requestPermissions: (permissions: Array<String>, requestCode: Int) -> Unit
) {

    private val locationTextView: TextView = activity.findViewById(R.id.main_tv_location)
    private val scaleTextView: TextView = activity.findViewById(R.id.main_tv_scale)

    /** 定位锁定（跟随）开关按键：锁定时切换为锁定图标 */
    private val gotoButton: ImageView = activity.findViewById(R.id.main_iv_goto_location)

    /** 是否处于定位锁定（跟随）模式：锁定后每次定位更新动画跟随 */
    private var locationFollowEnabled = false

    /** 锁定后等待首个定位点的待处理标记 */
    private var pendingLocateAction = false

    private var locationLayer: RenderableLayer? = null

    /** 定位罗盘标记：图标中心为定位点，随地图方向旋转保持指北 */
    private var compassPlacemark: Placemark? = null

    /** 移动方向箭头标记：叠加在罗盘中心，指向地理移动方位（参照 0.2.5.3） */
    private var headingPlacemark: Placemark? = null

    /** 上一次定位点，用于计算移动方位角 */
    private var lastMarkerPosition: Position? = null

    /** 最近一次有效移动方位角（度）：位移不足阈值时沿用，避免箭头抖动 */
    private var lastTrackHeadingDegrees: Double? = null

    /** 定位标记更新失败是否已记过日志（只记首次，避免 1Hz 回调灌满日志） */
    private var markerErrorLogged = false

    /**
     * 初始化定位：已有权限则直接开始定位，否则申请定位权限
     */
    fun init() {
        val locationManager = LocationManager.getInstance()
        locationManager.init(activity.application)
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val doc = DocumentManager.getInstance().getDocument()
            val background = doc != null && doc.systemConfig.backgroundLocation

            locationManager.startLocation(background)
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST
            )
        }

        locationManager.setOnLocationChangedListener { location: Location? ->
            activity.runOnUiThread { updateLocationUI(location ?: return@runOnUiThread) }
        }
    }

    /**
     * 处理权限申请结果，返回 true 表示已消费该结果
     */
    fun handlePermissionResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode == LOCATION_PERMISSION_REQUEST
            && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            LocationManager.getInstance().startLocation(false)
            return true
        }
        return false
    }

    private fun updateLocationUI(location: Location) {
        // 定位坐标：按设置在十进制度与米（CGCS2000 3 度带）之间切换（与中心坐标同格式）
        locationTextView.text = CoordFormatter.format("定位", location.longitude, location.latitude)

        if (location.hasAltitude()) {
            scaleTextView.text = String.format(
                Locale.getDefault(),
                "H: %.2f", location.altitude
            )
        } else {
            scaleTextView.text = "H: --"
        }

        updateLocationMarker(location.latitude, location.longitude)

        // 定位锁定：首次待定位点对齐正北并提示，其后每次更新只平移经纬度跟随
        if (pendingLocateAction) {
            moveToLocation(location.latitude, location.longitude, alignNorth = true, notify = true)
            pendingLocateAction = false
        } else if (locationFollowEnabled) {
            moveToLocation(location.latitude, location.longitude, alignNorth = false, notify = false)
        }
    }

    /**
     * 按当前配置刷新定位坐标文本（坐标显示格式切换后由宿主立即调用，
     * 无需等待下一次定位更新）。
     */
    fun refresh() {
        LocationManager.getInstance().getLastLocation()?.let {
            locationTextView.text = CoordFormatter.format("定位", it.longitude, it.latitude)
        }
    }

    private fun updateLocationMarker(lat: Double, lon: Double) {
        try {
            val current = Position(Angle.fromDegrees(lat), Angle.fromDegrees(lon), 0.0)
            var placemark = compassPlacemark
            if (placemark == null) {
                // 定位图层只建一次：罗盘图标与方向箭头都挂在这一层上
                val layer = locationLayer ?: RenderableLayer().also { created ->
                    created.displayName = "Location"
                    locationLayer = created
                    worldWindow.engine.layers.addLayer(created)
                }

                val attrs = PlacemarkAttributes.createWithImage(
                    ImageSource.fromResource(R.drawable.ic_compass)
                )
                attrs.imageColor = Color(1f, 1f, 1f, 1f)
                // 图标中心对齐定位点（imageOffset 默认居中）；按屏幕密度放大，高分屏真机上更醒目
                attrs.imageScale = COMPASS_IMAGE_SCALE * activity.resources.displayMetrics.density.coerceAtLeast(1f).toDouble()

                placemark = Placemark(
                    Position(Angle.fromDegrees(lat), Angle.fromDegrees(lon), 0.0),
                    attrs
                )
                // 旋转基准相对地球：地图方向变化时图标随地图旋转，保持北与图标一致；
                // imageRotation 保持 0，图标始终指向真北，无需监听导航事件手动补偿。
                placemark.imageRotationReference = OrientationMode.RELATIVE_TO_GLOBE

                layer.addRenderable(placemark)
                compassPlacemark = placemark

                // 移动方向箭头：叠加在罗盘中心，箭头中心即定位点，指向地理移动方位
                val arrowAttrs = PlacemarkAttributes.createWithImage(
                    ImageSource.fromBitmap(createHeadingArrowIcon())
                )
                arrowAttrs.imageScale = ARROW_IMAGE_SCALE * activity.resources.displayMetrics.density.coerceAtLeast(1f).toDouble()
                val arrow = Placemark(
                    Position(Angle.fromDegrees(lat), Angle.fromDegrees(lon), 0.0),
                    arrowAttrs
                )
                // 相对地球旋转：imageRotation 设为移动方位角（顺时针自北），箭头即指向该地理方位
                arrow.imageRotationReference = OrientationMode.RELATIVE_TO_GLOBE
                lastTrackHeadingDegrees?.let { arrow.imageRotation = Angle.fromDegrees(it) }
                layer.addRenderable(arrow)
                headingPlacemark = arrow

                lastMarkerPosition = current
            } else {
                placemark.position = Position(Angle.fromDegrees(lat), Angle.fromDegrees(lon), 0.0)
                headingPlacemark?.let { arrow ->
                    // 位移超过阈值才更新方位，避免静止/漂移时箭头抖动
                    val heading = computeHeading(lastMarkerPosition, current)
                    if (heading != null) {
                        lastTrackHeadingDegrees = heading
                        arrow.imageRotation = Angle.fromDegrees(heading)
                    }
                    arrow.position = Position(Angle.fromDegrees(lat), Angle.fromDegrees(lon), 0.0)
                }
                lastMarkerPosition = current
            }
            worldWindow.requestRedraw()
        } catch (e: Exception) {
            // 定位标记更新失败不得影响定位文本与相机跟随；但必须留痕，
            // 否则“定位图标不显示”无从排查（不同 WorldWind 版本的 Placemark API 存在差异）。
            // 定位回调约 1Hz，只记首次，避免失败时灌满日志文件
            if (!markerErrorLogged) {
                markerErrorLogged = true
                Log.w(TAG, "更新定位标记失败（本次运行不再重复记录）", e)
            }
        }
    }

    /**
     * 切换定位锁定：未锁定则锁定（换锁定图标 + 动画移到定位点、北向归零），
     * 已锁定则解锁（换回普通图标，停止相机跟随）。参照 0.2.5.3 MainLocationModule。
     */
    fun toggleLocationLock() {
        if (locationFollowEnabled) {
            locationFollowEnabled = false
            pendingLocateAction = false
            gotoButton.setImageResource(R.drawable.nav_location)
            return
        }

        locationFollowEnabled = true
        gotoButton.setImageResource(R.drawable.nav_lock_location)

        val last = LocationManager.getInstance().getLastLocation()
        if (last != null) {
            moveToLocation(last.latitude, last.longitude, alignNorth = true, notify = true)
        } else {
            pendingLocateAction = true
            Toast.makeText(activity, "等待定位...", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 移动相机到定位点：只锁定纬度与经度，不锁定高度。
     * @param alignNorth true：首次锁定——动画飞至定位点并对齐正北，观察高度沿用用户当前缩放级别
     *                   （无效时才回退默认高度 [LOCK_ALTITUDE]）；
     *                   false：跟随——仅平移经纬度，高度与航向逐帧取相机当前值，
     *                   用户的缩放/旋转不被跟随动画覆盖。
     */
    private fun moveToLocation(lat: Double, lon: Double, alignNorth: Boolean, notify: Boolean) {
        val ww = worldWindow as? MapWorldWindow ?: return
        if (!alignNorth) {
            ww.smoothFollowTo(lat, lon)
            return
        }
        val camera = worldWindow.engine.camera
        val alt = camera.position.altitude.takeIf { it > 0.0 } ?: LOCK_ALTITUDE
        ww.smoothMoveTo(lat, lon, alt, 0.0)
        if (notify) Toast.makeText(activity, "已定位到当前位置", Toast.LENGTH_SHORT).show()
    }

    companion object {

        private const val TAG = "LocationModel"

        const val LOCATION_PERMISSION_REQUEST = 1002

        /** 首次锁定定位时的兜底观察高度（米）：仅在相机高度无效时使用，不覆盖用户缩放级别 */
        private const val LOCK_ALTITUDE = 1200.0

        /** 罗盘图标基准缩放（原图 150×150 像素，乘以屏幕密度：密度 3 真机上约 225 像素/75dp） */
        private const val COMPASS_IMAGE_SCALE = 0.5f

        /** 方向箭头图标基准缩放（128×128 位图，乘以屏幕密度） */
        private const val ARROW_IMAGE_SCALE = 0.5f

        /** 方向箭头位图边长（像素） */
        private const val ARROW_ICON_SIZE_PX = 128

        /** 方向箭头颜色（淡蓝，与 0.2.5.3 一致） */
        private const val ARROW_COLOR = 0xFF57ACFD.toInt()

        /** 更新方位所需的最小位移（米）：小于此值视为静止，不刷新箭头方向 */
        private const val MIN_HEADING_DISTANCE_METERS = 1.5

        /**
         * 程序化绘制移动方向箭头：三角形包围盒居中于位图中心（即定位点），顶点朝上为正北。
         */
        private fun createHeadingArrowIcon(): Bitmap {
            val size = ARROW_ICON_SIZE_PX
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val center = size / 2f
            val halfHeight = size * 0.16f
            val halfBase = size * 0.14f
            val path = Path()
            path.moveTo(center, center - halfHeight)            // 顶点（指向北）
            path.lineTo(center + halfBase, center + halfHeight) // 右底角
            path.lineTo(center, center + halfHeight * 0.5f)     // 内凹
            path.lineTo(center - halfBase, center + halfHeight) // 左底角
            path.close()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.FILL
            paint.color = ARROW_COLOR
            canvas.drawPath(path, paint)
            return bitmap
        }

        /**
         * 计算从 [from] 到 [to] 的移动方位角（顺时针自北，0-360 度）；
         * 位移小于 [MIN_HEADING_DISTANCE_METERS] 时返回 null（不更新方向）。
         */
        private fun computeHeading(from: Position?, to: Position): Double? {
            if (from == null) return null
            if (distanceMeters(from, to) < MIN_HEADING_DISTANCE_METERS) return null
            val lat1 = Math.toRadians(from.latitude.inDegrees)
            val lat2 = Math.toRadians(to.latitude.inDegrees)
            val dLon = Math.toRadians(to.longitude.inDegrees - from.longitude.inDegrees)
            val x = sin(dLon) * cos(lat2)
            val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            val heading = Math.toDegrees(atan2(x, y))
            return (heading + 360.0) % 360.0
        }

        /** 两定位点间地表距离（米）：委托 [GeoCalc.distanceMeters]，全项目统一球面量算实现 */
        private fun distanceMeters(from: Position, to: Position): Double = GeoCalc.distanceMeters(
            from.latitude.inDegrees, from.longitude.inDegrees,
            to.latitude.inDegrees, to.longitude.inDegrees
        )
    }
}