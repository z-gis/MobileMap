package com.zys.mobilemap.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.zys.mobilemap.BuildConfig

object APKVersionUtil {

    /**
     * 获取当前本地apk的版本
     */
    @JvmStatic
    fun getVersionCode(context: Context): Int {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            BuildConfig.VERSION_CODE
        }
    }

    /**
     * 获取版本号名称
     */
    @JvmStatic
    fun getVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w("APKVersionUtil", "failed to get version name", e)
            ""
        }
    }
}
