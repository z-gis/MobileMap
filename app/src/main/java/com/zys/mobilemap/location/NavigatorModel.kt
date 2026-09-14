package com.zys.mobilemap.location

import android.app.Activity
import android.widget.ImageView
import android.widget.TextView
import com.zys.mobilemap.R
import com.zys.mobilemap.util.CoordFormatter
import earth.worldwind.WorldWindow
import earth.worldwind.geom.Camera
import earth.worldwind.navigator.NavigatorEvent
import earth.worldwind.navigator.NavigatorListener
import kotlin.math.PI
import kotlin.math.ln

/**
 * 主界面导航管理：负责导航事件监听、中心点/层级文本展示，
 * 以及缩放操作。中心坐标文本格式（十进制度 / 米 CGCS2000）由系统设置驱动。
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

    /**
     * 按当前配置刷新一轮显示（坐标显示格式切换后由宿主立即调用，无需等待下一次导航事件）。
     */
    fun refresh() {
        updateNavigatorDisplay(worldWindow.engine.camera)
    }

    fun zoomIn() {
        scaleAltitude(ZOOM_IN_FACTOR)
    }

    fun zoomOut() {
        scaleAltitude(ZOOM_OUT_FACTOR)
    }

    private fun updateNavigatorDisplay(camera: Camera) {
        // 中心坐标：按设置在十进制度与米（CGCS2000 3 度带）之间切换
        centerTextView.text = CoordFormatter.format(
            "中心",
            camera.position.longitude.inDegrees,
            camera.position.latitude.inDegrees
        )

        levelTextView.text = altitudeToLevel(camera.position.altitude).toString()

        // 北朝向按键随航向旋转：heading 为屏幕上方相对正北的顺时针方位，
        // 图标反转该角度即指向真北；点击纠正时航向动画归零，图标随之转回朝上
        northButton.rotation = -camera.heading.inDegrees.toFloat()
    }

    /** 按系数缩放相机高度实现缩放（<1 拉近，>1 拉远）；拉近时限幅到 [MIN_CAMERA_ALTITUDE] 防止超级别黑屏 */
    private fun scaleAltitude(factor: Double) {
        val camera = worldWindow.engine.camera
        camera.position.altitude = (camera.position.altitude * factor)
            .coerceAtLeast(MIN_CAMERA_ALTITUDE)
        worldWindow.requestRedraw()
    }

    companion object {
        /** 缩放步进系数：每次点击把相机高度乘/除约 1.25 倍 */
        private const val ZOOM_IN_FACTOR = 0.8
        private const val ZOOM_OUT_FACTOR = 1.2

        /**
         * WGS84 赤道半径（米）。层级换算属 Web Mercator 金字塔口径，用赤道半径；
         * 与 [com.zys.mobilemap.util.GeoCalc] 量算用的平均半径 6371000 米不是同一个值，勿混用。
         */
        private const val WGS84_EQUATORIAL_RADIUS = 6378137.0

        /** 层级显示上限（超出后底图走祖先瓦片拉伸，层级号不再增长） */
        private const val MAX_LEVEL = 20

        /**
         * 相机高度下限（米）：对应 [MAX_LEVEL] 级的地面高度，手势/按键放大到此高度后不再拉近。
         * 防止相机高度趋近 0 时底图末级瓦片过度拉伸（撕裂）乃至无瓦片可绘（黑屏），
         * 并避免从黑屏状态缩小恢复时相机中心漂移。
         * 计算：赤道周长 / 2^MAX_LEVEL = 2π×6378137 / 2^20 ≈ 38.2m。
         */
        val MIN_CAMERA_ALTITUDE: Double =
            2.0 * PI * WGS84_EQUATORIAL_RADIUS / Math.pow(2.0, MAX_LEVEL.toDouble())

        /**
         * 相机高度 -> Web Mercator 瓦片层级：层级每加 1 地面分辨率减半，
         * 故 level = log2(赤道周长 / 高度)，并限幅到 0..[MAX_LEVEL]。
         * 提为 companion 公用：级别可见性判定（BaseMapActivity）与本处层级显示文本同源。
         */
        fun altitudeToLevel(altitude: Double): Int {
            if (altitude <= 0) return 0
            val circumference = 2.0 * PI * WGS84_EQUATORIAL_RADIUS
            val level = (ln(circumference / altitude) / ln(2.0)).toInt()
            return level.coerceIn(0, MAX_LEVEL)
        }
    }
}
