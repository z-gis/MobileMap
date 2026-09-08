package com.zys.mobilemap.update

/**
 * 下载进度快照：[downloadedBytes] 归一为非负；[totalBytes] <= 0 表示服务端未给出总大小，
 * 此时进度条走不定量动画、仅显示已接收字节数。
 */
class DownloadProgress(
    downloadedBytes: Long,
    val totalBytes: Long
) {
    val downloadedBytes: Long = maxOf(0L, downloadedBytes)

    /** 总大小是否已知 */
    val hasTotalSize: Boolean get() = totalBytes > 0L

    /** 下载百分比（0..100）；总大小未知时为 0 */
    val progressPercent: Int
        get() {
            if (totalBytes <= 0L) return 0
            val ratio = (downloadedBytes * 100L) / totalBytes
            return maxOf(0, minOf(100, ratio.toInt()))
        }

    companion object {
        /** 完成态：已下载与总大小同为 [bytes]（归一为非负） */
        fun completed(bytes: Long): DownloadProgress {
            val safeBytes = maxOf(0L, bytes)
            return DownloadProgress(safeBytes, safeBytes)
        }
    }
}
