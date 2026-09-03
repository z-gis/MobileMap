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
import android.os.Build
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.zys.mobilemap.R
import com.zys.mobilemap.doc.DocumentManager
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
import kotlin.math.sqrt

/**
 * 主界面定位展示管理：负责定位权限申请、定位启动，
 * 以及定位坐标文本和地图定位标记的更新。
 */
@SuppressLint("MissingPermission")
class LocationModel(
    private val activity: Activity,
    private val worldWindow: WorldWindow,
    private val requestPermissions: (permissions: Array<String>, requestCode: Int) -> Unit
) {

    private val locationTextView: TextView = activity.findViewById(R.id.main_tv_location)
    private val scaleTextView: TextView = activity.findViewById(R.id.main_tv_scale)

    private var locationLayer: RenderableLayer? = null

    /** 定位罗盘标记：图标中心为定位点，随地图方向旋转保持指北 */
    private var compassPlacemark: Placemark? = null

    /** 移动方向箭头标记：叠加在罗盘中心，指向地理移动方位（参照 0.2.5.3） */
    private var headingPlacemark: Placemark? = null

    /** 上一次定位点，用于计算移动方位角 */
    private var lastMarkerPosition: Position? = null

    /** 最近一次有效移动方位角（度）：位移不足阈值时沿用，避免箭头抖动 */
    private var lastTrackHeadingDegrees: Double? = null

    /**
     * 初始化定位：已有权限则直接开始定位，否则申请定位权限
     */
    @RequiresApi(Build.VERSION_CODES.M)
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
        locationTextView.text = String.Companion.format(
            Locale.getDefault(),
            "定位: %.4f %.4f",
            location.longitude, location.latitude
        )

        if (location.hasAltitude()) {
            scaleTextView.text = String.Companion.format(
                Locale.getDefault(),
                "H: %.2f", location.altitude
            )
        } else {
            scaleTextView.text = "H: --"
        }

        updateLocationMarker(location.latitude, location.longitude)
    }

    private fun updateLocationMarker(lat: Double, lon: Double) {
        try {
            val current = Position(Angle.Companion.fromDegrees(lat), Angle.Companion.fromDegrees(lon), 0.0)
            var placemark = compassPlacemark
            if (placemark == null) {
                if (locationLayer == null) {
                    locationLayer = RenderableLayer()
                    locationLayer?.displayName = "Location"
                    worldWindow.engine.layers.addLayer(locationLayer ?: return)
                }

                val attrs = PlacemarkAttributes.createWithImage(
                    ImageSource.fromResource(R.drawable.ic_compass)
                )
                attrs.imageColor = Color(1f, 1f, 1f, 1f)
                // 图标中心对齐定位点（imageOffset 默认居中）；按屏幕密度放大，高分屏真机上更醒目
                attrs.imageScale = COMPASS_IMAGE_SCALE * activity.resources.displayMetrics.density.coerceAtLeast(1f).toDouble()

                placemark = Placemark(
                    Position(Angle.Companion.fromDegrees(lat), Angle.Companion.fromDegrees(lon), 0.0),
                    attrs
                )
                // 旋转基准相对地球：地图方向变化时图标随地图旋转，保持北与图标一致；
                // imageRotation 保持 0，图标始终指向真北，无需监听导航事件手动补偿。
                placemark.imageRotationReference = OrientationMode.RELATIVE_TO_GLOBE

                locationLayer?.addRenderable(placemark)
                compassPlacemark = placemark

                // 移动方向箭头：叠加在罗盘中心，箭头中心即定位点，指向地理移动方位
                val arrowAttrs = PlacemarkAttributes.createWithImage(
                    ImageSource.fromBitmap(createHeadingArrowIcon())
                )
                arrowAttrs.imageScale = ARROW_IMAGE_SCALE * activity.resources.displayMetrics.density.coerceAtLeast(1f).toDouble()
                val arrow = Placemark(
                    Position(Angle.Companion.fromDegrees(lat), Angle.Companion.fromDegrees(lon), 0.0),
                    arrowAttrs
                )
                // 相对地球旋转：imageRotation 设为移动方位角（顺时针自北），箭头即指向该地理方位
                arrow.imageRotationReference = OrientationMode.RELATIVE_TO_GLOBE
                lastTrackHeadingDegrees?.let { arrow.imageRotation = Angle.Companion.fromDegrees(it) }
                locationLayer?.addRenderable(arrow)
                headingPlacemark = arrow

                lastMarkerPosition = current
            } else {
                placemark.position = Position(Angle.Companion.fromDegrees(lat), Angle.Companion.fromDegrees(lon), 0.0)
                headingPlacemark?.let { arrow ->
                    // 位移超过阈值才更新方位，避免静止/漂移时箭头抖动
                    val heading = computeHeading(lastMarkerPosition, current)
                    if (heading != null) {
                        lastTrackHeadingDegrees = heading
                        arrow.imageRotation = Angle.Companion.fromDegrees(heading)
                    }
                    arrow.position = Position(Angle.Companion.fromDegrees(lat), Angle.Companion.fromDegrees(lon), 0.0)
                }
                lastMarkerPosition = current
            }
            worldWindow.requestRedraw()
        } catch (e: Exception) {
            // Placemark API may vary across WorldWind versions
        }
    }

    companion object {
        const val LOCATION_PERMISSION_REQUEST = 1002

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
            val halfHeight = size * 0.30f
            val halfBase = size * 0.18f
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

        /** 两定位点间地表距离（米，Haversine） */
        private fun distanceMeters(from: Position, to: Position): Double {
            val lat1 = Math.toRadians(from.latitude.inDegrees)
            val lat2 = Math.toRadians(to.latitude.inDegrees)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(to.longitude.inDegrees - from.longitude.inDegrees)
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return 6371000.0 * c
        }
    }
}