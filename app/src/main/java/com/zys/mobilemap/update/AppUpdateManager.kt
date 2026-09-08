package com.zys.mobilemap.update

import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zys.mobilemap.BuildConfig
import com.zys.mobilemap.R
import java.io.File
import java.util.Locale

/**
 * 应用内升级编排：检查元数据 -> 提示升级 -> 下载 APK -> 拉起安装。
 * 各子步骤拆到 [UpdateChecker] / [ApkDownloadService] / [APKInstaller] /
 * [DownloadProgressPresenter] / [UpdateUiNotifier]，本类只管流程与宿主生命周期联动。
 */
class AppUpdateManager(private val activity: AppCompatActivity) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val apkDownloadService = ApkDownloadService(activity)
    private val apkInstaller = APKInstaller(activity)
    private val progressPresenter = DownloadProgressPresenter(activity)
    private val uiNotifier = UpdateUiNotifier(activity)

    private var pendingFileName: String? = null
    private var cancelRequested = false
    private var latestProgress: DownloadProgress? = null

    fun checkForUpdates(userInitiated: Boolean) {
        val metadataUrl = BuildConfig.UPDATE_METADATA_URL
        if (metadataUrl.trim().isEmpty()) {
            if (userInitiated) {
                uiNotifier.showConfigMissing()
            }
            return
        }

        if (userInitiated) {
            uiNotifier.showChecking()
        }

        UpdateChecker.check(metadataUrl, object : UpdateChecker.Callback {
            override fun onSuccess(info: UpdateInfo) {
                mainHandler.post { handleUpdateInfo(info, userInitiated) }
            }

            override fun onError(e: Exception) {
                mainHandler.post {
                    if (userInitiated) {
                        uiNotifier.showCheckFailed()
                    }
                }
            }
        })
    }

    fun release() {
        apkDownloadService.cancel()
        progressPresenter.dismiss()
        latestProgress = null
        cleanDownloadCache()
    }

    /** 宿主回到前台：下载仍在进行时重建进度框并回显最后一次进度 */
    fun onHostResume() {
        if (!apkDownloadService.isRunning()) return
        ensureProgressDialogVisible()
        val progress = latestProgress
        if (progress != null) {
            progressPresenter.update(progress)
        } else {
            progressPresenter.setUnknown()
        }
    }

    fun onHostPause() {
        if (apkDownloadService.isRunning()) {
            progressPresenter.dismiss()
        }
    }

    private fun handleUpdateInfo(info: UpdateInfo, userInitiated: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (!UpdateVersionPolicy.shouldPrompt(info, APKVersionUtil.getVersionCode(activity))) {
            if (userInitiated) {
                uiNotifier.showLatest()
            }
            return
        }

        showUpdateDialog(info)
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val changelog = if (info.changelog.isEmpty()) {
            activity.getString(R.string.update_no_changelog)
        } else {
            info.changelog
        }

        val message = activity.getString(
            R.string.update_dialog_message,
            info.versionName,
            info.versionCode,
            changelog
        )

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.update_dialog_title)
            .setMessage(message)
            .setCancelable(!info.force)
            .setPositiveButton(R.string.update_now) { _, _ ->
                if (apkInstaller.ensureInstallPermission()) {
                    startDownload(info)
                } else {
                    uiNotifier.showInstallPermissionRequired()
                }
            }

        if (!info.force) {
            builder.setNegativeButton(R.string.update_later, null)
        }

        builder.show()
    }

    private fun startDownload(info: UpdateInfo) {
        if (apkDownloadService.isRunning()) return

        val suffix = if (!BuildConfig.DEBUG) "" else "-debug"
        // 先存局部变量再赋字段：pendingFileName 为 String? 无法智能转换，直接引用会被迫写 !!
        val fileName = "mobilemap-v%s%s.apk".format(
            Locale.US, sanitizeVersion(info.versionName), suffix
        )
        pendingFileName = fileName
        cancelRequested = false
        latestProgress = null

        ensureProgressDialogVisible()
        uiNotifier.showDownloadStarted()

        val request = DownloadRequest(info.apkUrl, fileName, info.apkSizeBytes)
        val started = apkDownloadService.start(request, object : ApkDownloadService.Callback {
            override fun onProgress(progress: DownloadProgress) {
                latestProgress = progress
                ensureProgressDialogVisible()
                progressPresenter.update(progress)
            }

            override fun onCompleted(apkFile: File) {
                val done = DownloadProgress.completed(apkFile.length())
                latestProgress = done
                progressPresenter.update(done)
                installDownloadedApk(apkFile)
            }

            override fun onCancelled() {
                resetDownloadTracking()
                if (cancelRequested) {
                    uiNotifier.showDownloadCancelled()
                }
            }

            override fun onFailed(e: Exception) {
                // 下载失败只弹一个笼统提示，异常详情必须落日志，否则无从查因
                Log.e(TAG, "APK 下载失败: url=${info.apkUrl}", e)
                resetDownloadTracking()
                uiNotifier.showDownloadFailedNetwork()
            }
        })

        if (!started) {
            resetDownloadTracking()
            uiNotifier.showDownloadStartFailed()
        }
    }

    private fun cancelDownload() {
        cancelRequested = true
        apkDownloadService.cancel()
    }

    private fun resetDownloadTracking() {
        progressPresenter.dismiss()
        pendingFileName = null
        cancelRequested = false
        latestProgress = null
    }

    private fun ensureProgressDialogVisible() {
        if (!progressPresenter.isShowing()) {
            progressPresenter.show { cancelDownload() }
        }
    }

    private fun installDownloadedApk(apkFile: File) {
        progressPresenter.dismiss()

        if (!apkInstaller.install(apkFile)) {
            resetDownloadTracking()
            uiNotifier.showInstallPrepareFailed()
            return
        }

        resetDownloadTracking()
    }

    private fun cleanDownloadCache() {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        if (dir.exists()) {
            dir.listFiles { _, name -> name.endsWith(".apk") }?.forEach { it.delete() }
        }
    }

    /** 版本号净化为可入文件名的形式；为空时退而用 versionCode */
    private fun sanitizeVersion(versionName: String): String {
        if (versionName.isEmpty()) {
            return APKVersionUtil.getVersionCode(activity).toString()
        }
        return versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")
    }

    companion object {
        private const val TAG = "AppUpdateManager"
    }
}
