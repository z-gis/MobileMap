package com.zys.mobilemap.update

object UpdateVersionPolicy {

    fun isValidMetadata(info: UpdateInfo?): Boolean {
        return info != null
                && info.versionCode > 0
                && info.apkUrl.isNotBlank()
    }

    fun shouldPrompt(info: UpdateInfo?, currentVersionCode: Int): Boolean {
        return isValidMetadata(info) && info!!.versionCode > currentVersionCode
    }
}
