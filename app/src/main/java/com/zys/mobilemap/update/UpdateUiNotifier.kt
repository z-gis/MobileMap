package com.zys.mobilemap.update

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zys.mobilemap.R

class UpdateUiNotifier(private val activity: AppCompatActivity) {

    fun showConfigMissing() = toast(R.string.update_config_missing)
    fun showChecking() = toast(R.string.update_checking)
    fun showCheckFailed() = toast(R.string.update_check_failed)
    fun showLatest() = toast(R.string.update_latest)
    fun showInstallPermissionRequired() = toast(R.string.update_install_permission_required)
    fun showDownloadStarted() = toast(R.string.update_download_started)
    fun showDownloadCancelled() = toast(R.string.update_download_cancelled)
    fun showDownloadStartFailed() = toast(R.string.update_download_start_failed)
    fun showDownloadFailedNetwork() = toast(R.string.update_download_failed_network)
    fun showInstallPrepareFailed() = toast(R.string.update_install_prepare_failed)

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }
}
