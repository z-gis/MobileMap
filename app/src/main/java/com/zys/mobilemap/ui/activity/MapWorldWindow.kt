package com.zys.mobilemap.ui.activity

import android.content.Context
import android.util.AttributeSet
import earth.worldwind.WorldWindow
import earth.worldwind.frame.Frame

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

    companion object {
        /** 判定首屏就绪所需的连续稳定帧数 */
        private const val SETTLED_FRAME_THRESHOLD = 3
    }
}
