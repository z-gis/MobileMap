package com.zys.mobilemap.ui.activity

import android.content.Intent
import android.os.Build

/**
 * 需要"安装未知来源应用"权限的页面基类。
 * 子类通过 [Settings_ACTION_MANAGE_UNKNOWN_APP_SOURCES] 请求码发起权限设置，
 * 用户授权返回后回调 [appSourcesInstallCallBack]。
 */
abstract class AppSourcesActivity : BaseStyledActivity() {

    /**
     * 用户授予"安装未知来源应用"权限后的回调。
     */
    abstract fun appSourcesInstallCallBack()

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Settings_ACTION_MANAGE_UNKNOWN_APP_SOURCES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && packageManager.canRequestPackageInstalls()
            ) {
                appSourcesInstallCallBack()
            }
        }
    }

    companion object {
        /** startActivityForResult 请求码 - 安装未知来源应用权限设置 */
        const val Settings_ACTION_MANAGE_UNKNOWN_APP_SOURCES: Int = 1001
    }
}
