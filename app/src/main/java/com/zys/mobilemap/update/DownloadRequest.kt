package com.zys.mobilemap.update

class DownloadRequest(
    val apkUrl: String,
    val fileName: String,
    val expectedSizeBytes: Long
) {
    init {
        require(apkUrl.isNotBlank()) { "apkUrl is empty" }
        require(fileName.isNotBlank()) { "fileName is empty" }
    }
}
