package com.zys.mobilemap.update

class DownloadProgress(
    downloadedBytes: Long,
    val totalBytes: Long
) {
    val downloadedBytes: Long = maxOf(0L, downloadedBytes)

    companion object {
        fun completed(bytes: Long): DownloadProgress {
            val safeBytes = maxOf(0L, bytes)
            return DownloadProgress(safeBytes, safeBytes)
        }
    }

    fun hasTotalSize(): Boolean = totalBytes > 0L

    fun getProgressPercent(): Int {
        if (totalBytes <= 0L) return 0
        val ratio = (downloadedBytes * 100L) / totalBytes
        return maxOf(0, minOf(100, ratio.toInt()))
    }
}
