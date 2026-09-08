package com.zys.mobilemap.ui.activity

import android.content.Intent
import android.os.Build

/**
 * 需要"安装未知来源应用"权限的页面基类。
 * 子类通过 [REQUEST_CODE_INSTALL_UNKNOWN_SOURCES] 请求码发起权限设置，
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
        // 仅处理本基类发起的权限设置返回：确已授予才回调，否则不重试
        if (requestCode == REQUEST_CODE_INSTALL_UNKNOWN_SOURCES
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && packageManager.canRequestPackageInstalls()
        ) {
            appSourcesInstallCallBack()
        }
    }

    companion object {
        /** startActivityForResult 请求码 - 安装未知来源应用权限设置 */
        const val REQUEST_CODE_INSTALL_UNKNOWN_SOURCES = 1001
    }
}
