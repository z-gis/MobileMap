package com.zys.mobilemap.ui.map

import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.zys.mobilemap.util.AppLog
import com.zys.mobilemap.util.PermissionUtil
import com.zys.mobilemap.util.UiUtil
import com.zys.globecore.NativeSrs

/**
 * 启动初始化器（自 MainActivity 拆出）：外部文件权限门、proj 数据初始化，
 * 以及启动加载遮罩的显示/淡出/安全超时（复刻原主界面启动链路，行为零变化）。
 *
 * proj 解压实现已下沉 globecore（assets/proj 随模块分发，见 [NativeSrs.initProjData]），
 * 本类仅保留启动链路接线。
 *
 * `waitingForAllFilesPermission` / `initialized` 两个跨生命周期状态仍由 Activity 持有（编排职责），
 * 本类只提供无副作用判定与遮罩/解压的具体动作。遮罩 View 由 Activity 在装配期传入。
 */
internal class StartupInitializer(private val activity: AppCompatActivity) {

    /** 启动加载遮罩（由 [beginLoadingOverlay] 传入）；未传入前淡出/超时为空操作 */
    private var loadingOverlay: View? = null

    /** 加载遮罩安全超时兜底：初始化/首屏长时间未就绪也强制淡出，避免卡在启动画面 */
    private val loadingOverlayTimeout = Runnable { hideLoadingOverlay() }

    /** 是否已具备“所有文件访问”权限：Android 11 以下按清单权限即可（视为已具备），11+ 须系统设置页单独授权 */
    fun hasAllFilesPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || PermissionUtil.isExternalStorageManager()
    }

    /** 初始化 proj 数据（globecore 内置 assets/proj 解压到应用私有目录并设置 PROJ 搜索路径） */
    fun extractProjData() {
        try {
            NativeSrs.initProjData(activity)
        } catch (e: Exception) {
            AppLog.e(TAG, "解压 proj 数据失败", e)
        }
    }

    /**
     * 进入启动遮罩显示：盖住 native/proj/文档初始化与首屏瓦片异步加载的白屏。
     * 遮罩显示阶段进入真·全屏（再隐藏状态栏），白底遮罩铺满整屏不留接缝；淡出后由 [hideLoadingOverlay] 恢复常态样式。
     * 仅冷启动首装路径调用（recreate/恢复场景由 Activity 侧判定跳过）。
     */
    fun beginLoadingOverlay(overlay: View) {
        loadingOverlay = overlay
        UiUtil.enterLoadingFullscreen(activity)
        overlay.postDelayed(loadingOverlayTimeout, LOADING_OVERLAY_TIMEOUT_MS)
    }

    /** 恢复常态：非首装（recreate/恢复）场景直接隐藏遮罩、挂到字段上但不显示、不挂超时 */
    fun skipLoadingOverlay(overlay: View) {
        loadingOverlay = overlay
        overlay.visibility = View.GONE
    }

    /** 移除安全超时回调（onDestroy 清理，避免界面销毁后遮罩回调泄漏） */
    fun cancelLoadingOverlayTimeout() {
        loadingOverlay?.removeCallbacks(loadingOverlayTimeout)
    }

    /** 淡出并隐藏加载遮罩（初始化完成或安全超时触发，幂等） */
    fun hideLoadingOverlay() {
        val overlay = loadingOverlay ?: return
        overlay.removeCallbacks(loadingOverlayTimeout)
        if (overlay.visibility == View.GONE) return
        overlay.animate().alpha(0f).setDuration(300).withEndAction {
            overlay.visibility = View.GONE
            // 遮罩已隐藏：从“全屏遮罩”切回地图常态样式（恢复透明状态栏，导航栏仍隐藏）
            UiUtil.restoreStandardImmersive(activity)
        }.start()
    }

    companion object {
        // 保持与原 MainActivity 相同日志 tag，行为零变化
        private const val TAG = "MainActivity"

        // 启动加载遮罩安全超时（毫秒）：初始化/首屏迟迟未就绪时兑底淡出（复刻原主界面）
        private const val LOADING_OVERLAY_TIMEOUT_MS = 8000L
    }
}
