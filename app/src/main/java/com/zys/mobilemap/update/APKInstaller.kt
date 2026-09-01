package com.zys.mobilemap.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class APKInstaller(private val activity: AppCompatActivity) {

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

    fun install(apkFile: File?): Boolean {
        if (apkFile == null || !apkFile.exists()) {
            return false
        }

        val apkUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return try {
            activity.startActivity(installIntent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
