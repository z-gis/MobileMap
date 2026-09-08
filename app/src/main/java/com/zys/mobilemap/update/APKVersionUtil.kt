package com.zys.mobilemap.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.zys.mobilemap.BuildConfig

/**
 * 本应用版本信息读取：优先取 PackageManager 中的实际安装信息，
 * 读取失败时版本号回退 [BuildConfig.VERSION_CODE]、版本名回退空串。
 */
object APKVersionUtil {

    private const val TAG = "APKVersionUtil"

    /** 当前本地 apk 的版本号（versionCode） */
    @JvmStatic
    fun getVersionCode(context: Context): Int {
        return try {
            val packageInfo = packageInfo(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取版本号失败，回退 BuildConfig.VERSION_CODE", e)
            BuildConfig.VERSION_CODE
        }
    }

    /** 当前本地 apk 的版本名（versionName）；读取失败返回空串 */
    @JvmStatic
    fun getVersionName(context: Context): String {
        return try {
            packageInfo(context).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "读取版本名失败", e)
            ""
        }
    }

    /** 读取本应用 PackageInfo：Android 13+ 用 PackageInfoFlags 重载（int flags 重载已弃用） */
    private fun packageInfo(context: Context): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
}
