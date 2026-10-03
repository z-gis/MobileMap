package com.zys.mobilemap.update

import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zys.mobilemap.R

/**
 * 下载进度对话框：定量时显百分比，服务端未给出总大小时走不定量动画并显已接收字节数。
 * 宿主 Activity 已销毁/正在结束时所有展示操作均空转，避免 WindowLeaked。
 */
class DownloadProgressPresenter(private val activity: AppCompatActivity) {

    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    /** 弹出进度对话框；[onCancel] 为“取消下载”按键回调 */
    fun show(onCancel: () -> Unit) {
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
            .setNegativeButton(R.string.update_cancel_download) { _, _ -> onCancel() }
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

        if (progress.hasTotalSize) {
            val percent = progress.progressPercent
            bar.isIndeterminate = false
            bar.progress = percent
            text.text = activity.getString(R.string.update_downloading_percent, percent)
        } else {
            setDownloadedBytes(progress.downloadedBytes)
        }
    }

    /** 回到“总大小未知”形态：不定量动画 + 提示文案 */
    fun setUnknown() {
        progressBar?.isIndeterminate = true
        progressText?.setText(R.string.update_downloading_unknown)
    }

    private fun setDownloadedBytes(downloadedBytes: Long) {
        progressBar?.isIndeterminate = true
        progressText?.text =
            activity.getString(R.string.update_downloading_received, formatBytes(downloadedBytes))
    }

    /** 已接收字节数人性化显示：B / KB / MB / GB */
    private fun formatBytes(bytes: Long): String {
        if (bytes < UNIT_STEP) return "$bytes B"
        val kb = bytes / UNIT_STEP
        if (kb < UNIT_STEP) return "%.1f KB".format(kb)
        val mb = kb / UNIT_STEP
        if (mb < UNIT_STEP) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / UNIT_STEP)
    }

    companion object {
        /** 字节单位进制：1 KB = 1024 B */
        private const val UNIT_STEP = 1024.0
    }
}
