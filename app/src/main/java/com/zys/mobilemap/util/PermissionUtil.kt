package com.zys.mobilemap.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log

/**
 * 文件访问权限工具：Android 11（API 30）起“管理所有文件”须跳转系统设置页单独授权；
 * 低于该版本只需在清单中声明 WRITE_EXTERNAL_STORAGE（minSdk 24 已高于 6.0 动态权限起点）。
 */
object PermissionUtil {

    private const val TAG = "PermissionUtil"

    /** 是否已具备全部文件访问权限（Android 11 以下按清单权限即可，直接视为已具备） */
    fun isExternalStorageManager(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true
        }
        return Environment.isExternalStorageManager()
    }

    /**
     * 跳转 Android 11+ 的“所有文件访问权限”设置页；
     * 部分机型不支持按包名跳转（[ActivityNotFoundException]），回退到全局授权列表页。
     */
    fun goManagerFileAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        appIntent.data = Uri.parse("package:${activity.packageName}")
        try {
            activity.startActivityForResult(appIntent, 0)
        } catch (ex: ActivityNotFoundException) {
            Log.w(TAG, "按包名跳转文件访问权限页失败，回退全局授权列表页", ex)
            val allFileIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            activity.startActivityForResult(allFileIntent, 0)
        }
    }
}
