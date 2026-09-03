package com.zys.mobilemap.location

import android.app.Activity
import android.widget.ImageView
import android.widget.TextView
import com.zys.mobilemap.R
import earth.worldwind.WorldWindow
import earth.worldwind.geom.Camera
import earth.worldwind.navigator.NavigatorEvent
import earth.worldwind.navigator.NavigatorListener
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 主界面导航管理：负责导航事件监听、中心点/层级文本展示，
 * 以及缩放操作。
 */
class NavigatorModel(
    private val activity: Activity,
    private val worldWindow: WorldWindow
) {

    private val centerTextView: TextView = activity.findViewById(R.id.main_tv_center)
    private val levelTextView: TextView = activity.findViewById(R.id.main_tv_level)

    /** 北朝向按键：图标随地图航向反向旋转，始终指向屏幕上的真北方向 */
    private val northButton: ImageView = activity.findViewById(R.id.main_iv_north)

    /**
     * 注册导航事件监听，并刷新一次初始显示
     */
    fun setup() {
        val listener: NavigatorListener = object : NavigatorListener {
            override fun onNavigatorEvent(wwd: WorldWindow, event: NavigatorEvent) {
                event.camera?.let { updateNavigatorDisplay(it) }
            }
        }
        worldWindow.navigatorEvents.addNavigatorListener(listener)

        updateNavigatorDisplay(worldWindow.engine.camera)
    }

    fun zoomIn() {
        val camera = worldWindow.engine.camera
        camera.position.altitude = camera.position.altitude * 0.8
        worldWindow.requestRedraw()
    }

    fun zoomOut() {
        val camera = worldWindow.engine.camera
        camera.position.altitude = camera.position.altitude * 1.2
        worldWindow.requestRedraw()
    }

    private fun updateNavigatorDisplay(camera: Camera) {
        centerTextView.text = String.format(
            Locale.getDefault(),
            "中心: %.4f %.4f",
            camera.position.longitude.inDegrees.toFloat(),
            camera.position.latitude.inDegrees.toFloat()
        )

        levelTextView.text = altitudeToLevel(camera.position.altitude).toString()

        // 北朝向按键随航向旋转：heading 为屏幕上方相对正北的顺时针方位，
        // 图标反转该角度即指向真北；点击纠正时航向动画归零，图标随之转回朝上
        northButton.rotation = -camera.heading.inDegrees.toFloat()
    }

    private fun altitudeToLevel(altitude: Double): Int {
        if (altitude <= 0) return 0
        val circumference = 2.0 * Math.PI * 6378137.0
        val level = (ln(circumference / altitude) / ln(2.0)).toInt()
        return max(0, min(level, 20))
    }
}
