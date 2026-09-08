package com.zys.mobilemap

import android.app.Application
import com.zys.mobilemap.util.AppLog
import com.zys.mobilemap.util.CrashHandler

/**
 * 应用入口：在进程最早时机初始化日志模块并安装全局崩溃捕获，
 * 使实际使用中的偶发闪退可追溯到具体堆栈与崩溃前的操作序列。
 */
class MobileMapApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 顺序：先初始化日志（确定落盘目录）→ 安装崩溃捕获 → 记录进程启动
        AppLog.init(this)
        CrashHandler.install()
        AppLog.i(
            "App",
            "应用进程启动 v" + BuildConfig.VERSION_NAME + "(" + BuildConfig.VERSION_CODE + ")"
        )
    }
}
