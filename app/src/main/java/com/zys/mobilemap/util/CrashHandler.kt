package com.zys.mobilemap.util

import android.os.Build
import com.zys.mobilemap.BuildConfig

/**
 * 全局未捕获异常处理器：闪退时把线程、设备/版本/内存信息与完整堆栈同步写入日志文件，
 * 再交还系统默认处理器（保持系统原有崩溃行为），用于诊断实际使用中的偶发闪退。
 *
 * 安装时机：Application.onCreate 中、[AppLog.init] 之后调用 [install]，
 * 以尽量早地覆盖启动阶段（含原生库加载、文档初始化）的崩溃。
 */
class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {

    /** 保存系统/前一个默认处理器，崩溃记录完成后交还，保证进程按系统预期终止 */
    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // 先排空异步日志队列，保住崩溃前的用户操作记录，再同步写崩溃堆栈
            AppLog.drainBlocking()
            AppLog.writeCrashSync(thread, throwable, collectEnvInfo())
        } catch (ignored: Exception) {
            // 记录日志本身失败也不能阻断默认崩溃处理
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /** 采集崩溃现场环境信息：应用版本、系统版本、设备型号、运行时内存。 */
    private fun collectEnvInfo(): String {
        val sb = StringBuilder()
        sb.append("版本: ").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(')')
        sb.append('\n').append("系统: Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(')')
        sb.append('\n').append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        val rt = Runtime.getRuntime()
        val mb = 1048576L
        sb.append('\n').append("内存(MB): free=").append(rt.freeMemory() / mb)
            .append(", total=").append(rt.totalMemory() / mb)
            .append(", max=").append(rt.maxMemory() / mb)
        return sb.toString()
    }

    companion object {
        /** 安装为全局默认未捕获异常处理器。 */
        fun install() {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler())
        }
    }
}
