package com.zys.mobilemap.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zys.mobilemap.R
import com.zys.mobilemap.ui.activity.MainActivity

/**
 * 后台定位保活前台服务（location 型）。
 *
 * 目标：轨迹记录期间息屏 / 应用退到后台但进程未退出时，保持 [LocationManager] 的单例定位订阅不被
 * Doze/待机限制切断，通知栏常驻提示「后台定位中」，从而避免轨迹缺点。
 *
 * 生命周期只随轨迹记录态：由 [BackgroundLocationController] 在「记录中且后台定位开关为开」时启动，
 * 停止记录（或记录中关开关 / 记录中界面销毁）即停——不做记录之外的后台定位。
 *
 * 服务不重复注册定位监听——定位由进程级单例 [LocationManager] 统一管理，界面侧
 * [com.zys.mobilemap.ui.map.LocationFollowController] 的轨迹追加链路在进程存活期间持续生效；
 * 本服务仅负责把进程拉到前台优先级并持有前台通知。停止时仅释去前台通知，
 * 定位订阅仍交回单例由界面生命周期统一管理。
 */
class BackgroundLocationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        // 确保定位订阅已建立（幂等：已在定位中直接返回）
        LocationManager.getInstance().apply {
            init(application)
            startLocation(true)
        }
        // 被系统杀死后自动重建（重建经 onStartCommand 空 intent 走启动分支恢复前台通知与定位）
        return START_STICKY
    }

    /** 提升为 location 型前台服务并挂常驻通知；API 29+ 需显式传 location 类型（直用框架 API，不依赖高版 androidx.core）。 */
    private fun startAsForeground() {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_location)
            .setContentTitle(getString(R.string.bg_location_notify_title))
            .setContentText(getString(R.string.bg_location_notify_text))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    /** 低优先级通知渠道（静音、不打扰），Android 8.0 以下无需创建。 */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.bg_location_channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.bg_location_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        // 前台服务销毁时系统会自动移除常驻通知，无需显式 stopForeground（该重载需 API 26，minSdk 24）；
        // 定位订阅交回 [LocationManager] 单例，由界面生命周期统一管理，本服务不主动 stopLocation。
    }

    companion object {
        private const val CHANNEL_ID = "background_location"
        private const val NOTIFICATION_ID = 0x4201

        /** 启动保活服务：前台调用，需已授予定位权限（息屏前轨迹记录开始时或后台定位开关打开时触发）。 */
        fun start(context: Context) {
            val intent = Intent(context, BackgroundLocationService::class.java)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        /** 停止保活服务：关闭后台定位开关时调用（stopService 直接销毁，触发 onDestroy 释去通知）。 */
        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context, BackgroundLocationService::class.java))
        }
    }
}
