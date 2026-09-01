package com.zys.mobilemap.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

class PermissionUtil {

    companion object{

        /**
         * WRITE_EXTERNAL_STORAGE
         */
        fun isExternalStorageManager(): Boolean {
            // Android 6.0 下在AndroidManifest中申请权限就可以

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { // 提示错误原因是minSdkVersion大于23
                return true
            }

            if (!Environment.isExternalStorageManager()) {
                return false
            }

            return true
        }

        /**
         * 进入Android 11或更高版本的文件访问权限页面
         */
        fun goManagerFileAccess(activity: Activity) {
            // Android 11 (Api 30)或更高版本的写文件权限需要特殊申请，需要动态申请管理所有文件的权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                appIntent.setData(Uri.parse("package:" + activity.getPackageName()))
                //appIntent.setData(Uri.fromParts("package", activity.getPackageName(), null));
                try {
                    activity.startActivityForResult(appIntent, 0)
                } catch (ex: ActivityNotFoundException) {
                    ex.printStackTrace()
                    val allFileIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    activity.startActivityForResult(allFileIntent, 0)
                }
            }
        }
    }
}