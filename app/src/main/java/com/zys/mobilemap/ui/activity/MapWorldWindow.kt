package com.zys.mobilemap.ui.activity

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.zys.mobilemap.util.GeoCalc
import earth.worldwind.WorldWindow
import earth.worldwind.frame.Frame
import earth.worldwind.geom.Angle
import kotlin.math.PI
import kotlin.math.sin

/**
 * 地图渲染窗口：在标准 [WorldWindow] 基础上捕获“首屏就绪”信号。
 *
 * WorldWind 渲染循环中 `engine.renderFrame(frame)` 返回 true 表示仍需重绘（可见瓦片尚在加载/下载），
 * 返回 false 表示当前视野已无需再请求重绘（瓦片均已加载或入缓存）。据此判定：连续多帧不再请求重绘
 * 即视为首屏底图就绪，回调 [onFirstFrameSettled]，供主界面淡出加载遮罩（消除启动白屏）。
 *
 * 采用“连续 [SETTLED_FRAME_THRESHOLD] 帧稳定”而非单次判定：既避免瓦片尚未开始加载时的过早触发，
 * 又能在瓦片全部命中缓存时快速就绪。
 */
class MapWorldWindow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : WorldWindow(context, attrs) {

    /** 首屏就绪回调（一次性，主线程触发） */
    var onFirstFrameSettled: (() -> Unit)? = null

    /** 连续“无需重绘”的非拾取帧计数 */
    private var settledFrameCount = 0

    /** 就绪回调是否已触发（一次性） */
    private var settledFired = false

    /** 相机平滑移动动画（同一时刻仅一个，新动画启动前取消旧动画） */
    private var cameraAnimator: ValueAnimator? = null

    override fun renderFrame(frame: Frame) {
        // 复制父类渲染逻辑，同时捕获 engine.renderFrame 返回值作为就绪判定依据
        val needsRedraw = engine.renderFrame(frame)
        if (needsRedraw) doRequestRedraw()

        // 尽早入队并唤醒 OpenGL 线程（与父类一致）
        if (frame.isPickMode) pickQueue.offer(frame) else frameQueue.offer(frame)
        requestRender()

        if (!frame.isPickMode) {
            navigatorEvents.onFrameRendered(
                frame.modelview, frame.projection, engine.globe.elevationModel.timestamp
            )
            if (!settledFired) {
                if (needsRedraw) {
                    settledFrameCount = 0
                } else if (++settledFrameCount >= SETTLED_FRAME_THRESHOLD) {
                    settledFired = true
                    post { onFirstFrameSettled?.invoke() }
                }
            }
        }
    }

    /**
     * 相机平滑移动到目标位置/高度/航向（移植 0.2.5.3 MainWorldWindow.smoothMoveTo 并增强）。
     *
     * 按起点与终点的地表距离决定动画形态：
     * - 远距离（> [FLY_ARC_THRESHOLD_METERS]）：飞行中段以 sin 弧线抬升高度（先升高再移动再落下），时长更长；
     * - 近距离：保持高度直接平移。
     * 航向按最短路径插值（避免 350°→10° 反向绕整圈）。
     */
    fun smoothMoveTo(targetLat: Double, targetLon: Double, targetAlt: Double, targetHeading: Double) {
        val camera = engine.camera
        cancelCameraAnimation()

        val startLat = camera.position.latitude.inDegrees
        val startLon = camera.position.longitude.inDegrees
        val startAlt = camera.position.altitude
        val startHeading = camera.heading.inDegrees
        val altMode = camera.altitudeMode
        val tilt = camera.tilt
        val roll = camera.roll

        val distance = GeoCalc.distanceMeters(startLat, startLon, targetLat, targetLon)
        val far = distance > FLY_ARC_THRESHOLD_METERS
        val arcHeight = if (far) minOf(distance * FLY_ARC_FACTOR, FLY_ARC_MAX_METERS) else 0.0
        val duration = if (far) FLY_FAR_DURATION_MS else FLY_NEAR_DURATION_MS
        val headingDelta = shortestAngleDelta(startHeading, targetHeading)

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = duration
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.addUpdateListener { a ->
            val f = (a.animatedValue as Float).toDouble()
            val lat = startLat + (targetLat - startLat) * f
            val lon = startLon + (targetLon - startLon) * f
            val alt = startAlt + (targetAlt - startAlt) * f + arcHeight * sin(PI * f)
            val heading = startHeading + headingDelta * f
            camera.set(
                Angle.fromDegrees(lat), Angle.fromDegrees(lon), alt, altMode,
                Angle.fromDegrees(heading), tilt, roll
            )
            requestRedraw()
        }
        animator.start()
        cameraAnimator = animator
    }

    /** 动画纠正到正北：保持当前中心与高度，航向平滑归零（北朝向按键使用）。 */
    fun orientNorth() {
        val camera = engine.camera
        smoothMoveTo(
            camera.position.latitude.inDegrees,
            camera.position.longitude.inDegrees,
            camera.position.altitude,
            0.0
        )
    }

    /**
     * 定位跟随平移：只插值经纬度，**高度/航向/俯仰/翻滚每帧取相机当前值**。
     *
     * 用于定位锁定后的跟随：只锁定经纬度，不锁定高度——跟随过程中用户的缩放（高度）
     * 与旋转（航向）不被动画覆盖（[smoothMoveTo] 会把动画启动时捕获的高度插值回写，
     * 与用户手势互相覆盖表现为“高度被锁定”）。位移小于 [FOLLOW_MIN_DISTANCE_METERS]
     * 时不平移，避免 GPS 静止漂移引发画面抖动。
     */
    fun smoothFollowTo(targetLat: Double, targetLon: Double) {
        val camera = engine.camera
        val startLat = camera.position.latitude.inDegrees
        val startLon = camera.position.longitude.inDegrees
        if (GeoCalc.distanceMeters(startLat, startLon, targetLat, targetLon) < FOLLOW_MIN_DISTANCE_METERS) return

        cancelCameraAnimation()
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = FOLLOW_DURATION_MS
        // 持续跟随用线性插值：多次定位更新衔接时速度连续，无加减速顶帧感
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { a ->
            val f = (a.animatedValue as Float).toDouble()
            val lat = startLat + (targetLat - startLat) * f
            val lon = startLon + (targetLon - startLon) * f
            camera.set(
                Angle.fromDegrees(lat), Angle.fromDegrees(lon),
                camera.position.altitude, camera.altitudeMode,
                camera.heading, camera.tilt, camera.roll
            )
            requestRedraw()
        }
        animator.start()
        cameraAnimator = animator
    }

    override fun onDetachedFromWindow() {
        cancelCameraAnimation()
        super.onDetachedFromWindow()
    }

    private fun cancelCameraAnimation() {
        cameraAnimator?.cancel()
        cameraAnimator = null
    }

    companion object {
        /** 判定首屏就绪所需的连续稳定帧数 */
        private const val SETTLED_FRAME_THRESHOLD = 3

        /** 近距离平移时长（毫秒） */
        private const val FLY_NEAR_DURATION_MS = 800L

        /** 远距离飞越时长（毫秒） */
        private const val FLY_FAR_DURATION_MS = 1600L

        /** 触发“先升高再移动”飞越弧线的地表距离阈值（米） */
        private const val FLY_ARC_THRESHOLD_METERS = 20000.0

        /** 飞越弧线抬升高度系数（相对地表距离） */
        private const val FLY_ARC_FACTOR = 0.3

        /** 飞越弧线抬升高度上限（米） */
        private const val FLY_ARC_MAX_METERS = 500000.0

        /** 定位跟随时长（毫秒）：短于定位更新间隔，两次跟随之间不重叠 */
        private const val FOLLOW_DURATION_MS = 600L

        /** 定位跟随最小位移（米）：小于此值视为 GPS 静止漂移，不平移避免画面抖动 */
        private const val FOLLOW_MIN_DISTANCE_METERS = 0.5

        /** 航向最短夹角差（-180,180]，保证按最短方向旋转 */
        private fun shortestAngleDelta(from: Double, to: Double): Double {
            var d = (to - from) % 360.0
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            return d
        }
    }
}
