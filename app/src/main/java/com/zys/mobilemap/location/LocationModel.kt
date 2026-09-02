package com.zys.mobilemap.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
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
            } else {
                placemark.position = Position(
                    Angle.Companion.fromDegrees(lat), Angle.Companion.fromDegrees(lon), 0.0
                )
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
    }
}