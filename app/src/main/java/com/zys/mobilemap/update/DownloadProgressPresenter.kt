package com.zys.mobilemap.update

import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zys.mobilemap.R
import java.util.Locale

class DownloadProgressPresenter(private val activity: AppCompatActivity) {

    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    fun show(onCancel: Runnable) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (isShowing()) return

        val contentView = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_update_download_progress, null, false)
        progressBar = contentView.findViewById(R.id.update_progress_bar)
        progressText = contentView.findViewById(R.id.update_progress_text)
        setUnknown()

        progressDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_download_title)
            .setView(contentView)
            .setCancelable(false)
            .setNegativeButton(R.string.update_cancel_download) { _, _ -> onCancel.run() }
            .show()
    }

    fun isShowing(): Boolean = progressDialog?.isShowing == true

    fun dismiss() {
        progressDialog?.dismiss()
        progressDialog = null
        progressBar = null
        progressText = null
    }

    fun update(progress: DownloadProgress?) {
        if (activity.isFinishing || activity.isDestroyed) return
        val bar = progressBar ?: return
        val text = progressText ?: return
        if (progress == null) return

        if (progress.hasTotalSize()) {
            val percent = progress.getProgressPercent()
            bar.isIndeterminate = false
            bar.progress = percent
            text.setText(activity.getString(R.string.update_downloading_percent, percent))
        } else {
            setDownloadedBytes(progress.downloadedBytes)
        }
    }

    fun setUnknown() {
        progressBar?.isIndeterminate = true
        progressText?.setText(R.string.update_downloading_unknown)
    }

    private fun setDownloadedBytes(downloadedBytes: Long) {
        progressBar?.isIndeterminate = true
        progressText?.setText(
            activity.getString(R.string.update_downloading_received, formatBytes(downloadedBytes))
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }
}
