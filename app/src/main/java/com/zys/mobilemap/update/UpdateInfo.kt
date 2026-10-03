package com.zys.mobilemap.update

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
    val force: Boolean,
    val apkSizeBytes: Long
)
