package com.zys.mobilemap.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装：先确保具备“安装未知应用”权限（Android 8+），
 * 再经 FileProvider 授权 URI 拉起系统安装器。
 */
class APKInstaller(private val activity: AppCompatActivity) {

    /**
     * 确保已授予“安装未知应用”权限；未授予时跳转系统授权页并返回 false（本次安装中止）。
     */
    fun ensureInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !activity.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
            return false
        }
        return true
    }

    /** 拉起系统安装器安装 [apkFile]；文件缺失或无可用安装器时返回 false */
    fun install(apkFile: File): Boolean {
        if (!apkFile.exists()) {
            Log.w(TAG, "待安装 APK 不存在: ${apkFile.absolutePath}")
            return false
        }

        val apkUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return try {
            activity.startActivity(installIntent)
            true
        } catch (e: ActivityNotFoundException) {
            // 不抛异常，但必须留日志：否则用户只见“安装准备失败”而无从查因
            Log.e(TAG, "未找到可用的 APK 安装器: ${apkFile.absolutePath}", e)
            false
        }
    }

    companion object {
        private const val TAG = "APKInstaller"

        /** 系统安装器识别的 APK MIME 类型 */
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
