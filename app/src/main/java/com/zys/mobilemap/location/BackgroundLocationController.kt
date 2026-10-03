package com.zys.mobilemap.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zys.mobilemap.doc.DocumentManager

/**
 * 后台定位保活的统一门控：集中判定前置条件并驱动 [BackgroundLocationService] 启停。
 *
 * 语义（0.3.0.3 约定）：「后台定位」开关只是**记录期许可**——保活服务仅在
 * 「轨迹记录中 且 开关为开」时运行；轨迹停止（或开关关闭）即刻停服务，不做记录之外的后台定位。
 * 调用点：轨迹开始/恢复/停止/草稿恢复（[onTrackRecordingChanged]）、
 * 定位详情对话框与设置页的开关切换（[onSettingChanged]）。
 *
 * 保活以 location 型前台服务实现——服务运行期间系统视应用处于前台，息屏 / 退后台仍能持续
 * 定位，无需强依赖 Android 10+ 的「始终允许」后台定位权限；定位权限不足时静默跳过（仅由界面正常定位）。
 */
object BackgroundLocationController {

    /** 轨迹记录态（含暂停——暂停属于记录会话中，保持保活；开始/恢复与草稿恢复置 true，停止置 false） */
    @Volatile
    private var trackRecording = false

    /** 轨迹记录态变化：开始/恢复记录传 true，停止记录传 false；服务按「记录态 && 开关」启停 */
    fun onTrackRecordingChanged(context: Context, recording: Boolean) {
        trackRecording = recording
        syncService(context)
    }

    /** 「后台定位」开关值变化（定位详情/设置页，配置须先落盘再调用）：服务按「记录态 && 开关」启停 */
    fun onSettingChanged(context: Context) {
        syncService(context)
    }

    /** 门控合一处：记录中且开关为开且具备定位权限 → 起服务（幂等）；任一不满足 → 停服务 */
    private fun syncService(context: Context) {
        val enabled = trackRecording &&
                DocumentManager.getInstance().getDocument()?.systemConfig?.backgroundLocation == true
        if (enabled) {
            if (canStart(context)) {
                BackgroundLocationService.start(context)
                requestNotificationPermissionIfNeeded(context)
            }
        } else {
            BackgroundLocationService.stop(context)
        }
    }

    /**
     * 是否可启动保活服务：精确定位或粗略定位权限至少授予其一（前台服务订阅定位的前提）。
     * 通知权限（API 33+）缺失不阻断启动——前台服务本身仍可运行，仅通知栏提示受系统约束。
     */
    private fun canStart(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * API 33+ 未授予通知权限时发起申请（fire-and-forget，不阻断服务启动）。
     * 通知权限缺失不影响前台服务保活能力，仅影响常驻通知的可见性；需 Activity 载体发起，非 Activity 时跳过。
     */
    private fun requestNotificationPermissionIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (granted(context, Manifest.permission.POST_NOTIFICATIONS)) return
        (context as? Activity)?.let {
            ActivityCompat.requestPermissions(
                it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFY_PERMISSION_REQUEST_CODE
            )
        }
    }

    private const val NOTIFY_PERMISSION_REQUEST_CODE = 0x4210
}
