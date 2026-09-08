package com.zys.mobilemap.update

object UpdateVersionPolicy {

    /** 升级元数据是否完整可用（版本号 > 0 且下载地址非空） */
    fun isValidMetadata(info: UpdateInfo?): Boolean {
        return info != null
                && info.versionCode > 0
                && info.apkUrl.isNotBlank()
    }

    /** 是否应提示升级：元数据完整且服务端版本号高于当前版本（先判空以便智能转换，免用 !!） */
    fun shouldPrompt(info: UpdateInfo?, currentVersionCode: Int): Boolean {
        return info != null && isValidMetadata(info) && info.versionCode > currentVersionCode
    }
}
