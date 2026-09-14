package com.zys.mobilemap.util

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 应用日志模块：Logcat + 文件双写，异步单线程落盘，避免阻塞 UI/GL 线程。
 *
 * 设计目标是诊断实际使用中的偶发闪退：
 * - 日志写入 调查宝/log（外部不可写时回退应用内部 filesDir/log），按天分文件
 *   （mobilemap-yyyy-MM-dd.log），仅保留最近 [MAX_LOG_FILES] 个。
 * - 全局未捕获异常由 [CrashHandler] 处理：先 [drainBlocking] 排空异步队列保住崩溃前的操作日志，
 *   再调用 [writeCrashSync] 同步落盘堆栈，确保进程终止前不丢。
 *
 * 用法：Application.onCreate 中 [init] 之后，业务侧调用 [v]/[d]/[i]/[w]/[e] 或 [event] 记录，
 * 后期逐步在各模块补充关键操作埋点。
 */
object AppLog {

    /** 日志级别 */
    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    private const val GLOBAL_TAG = "AppLog"
    private const val FILE_PREFIX = "mobilemap-"
    private const val FILE_SUFFIX = ".log"
    private const val MAX_LOG_FILES = 7
    private const val DRAIN_TIMEOUT_MS = 1000L

    private val lineDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 保护 writer/logDir 的锁；异步写入与崩溃同步写入互斥，避免交错 */
    private val lock = Any()

    /** 单线程守护写入队列（FIFO），保证日志顺序且不阻塞调用线程 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLogWriter").apply { isDaemon = true }
    }

    @Volatile
    private var logDir: File? = null

    /** 当前打开的写入器与对应文件名（按天切换时重开）——仅在持有 [lock] 时访问 */
    private var writer: BufferedWriter? = null
    private var writerFileName: String? = null

    /**
     * 初始化：解析日志目录并异步清理过期文件。应在 Application.onCreate 调用。
     */
    fun init(context: Context) {
        refreshLogDir(context)
        executor.execute { cleanupOldLogs() }
    }

    /**
     * 重新解析日志目录：优先外部 调查宝/log（可写时），否则回退内部 filesDir/log。
     * 全文件访问权限授予后（如 MainActivity 初始化阶段）可再次调用，
     * 将后续日志切到外部目录，便于用户取出反馈。
     */
    fun refreshLogDir(context: Context) {
        val dir = resolveWritableDir(context) ?: return
        synchronized(lock) {
            if (dir.absolutePath != logDir?.absolutePath) {
                closeWriterLocked()
                logDir = dir
            }
        }
    }

    /** 当前日志目录（供后期日志查看/导出界面使用） */
    fun currentLogDir(): File? = logDir

    fun v(tag: String, msg: String, tr: Throwable? = null) = log(Level.VERBOSE, tag, msg, tr)
    fun d(tag: String, msg: String, tr: Throwable? = null) = log(Level.DEBUG, tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null) = log(Level.INFO, tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(Level.WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Level.ERROR, tag, msg, tr)

    /** 记录一次用户操作/业务事件（INFO 级），供后期逐步埋点使用。 */
    fun event(msg: String) = log(Level.INFO, "Event", msg, null)

    private fun log(level: Level, tag: String, msg: String, tr: Throwable?) {
        // Logcat 镜像，便于连接调试时实时查看
        when (level) {
            Level.VERBOSE -> Log.v(tag, msg, tr)
            Level.DEBUG -> Log.d(tag, msg, tr)
            Level.INFO -> Log.i(tag, msg, tr)
            Level.WARN -> Log.w(tag, msg, tr)
            Level.ERROR -> Log.e(tag, msg, tr)
        }
        val line = format(level, tag, msg, tr)
        try {
            executor.execute { appendLine(line) }
        } catch (ignored: Exception) {
            // 队列不可用（如已关闭）时忽略，日志失败不得影响业务
        }
    }

    /**
     * 排空异步队列：提交栅栏任务并等待其执行完成。
     * 单线程 FIFO 保证栅栏执行时此前入队的日志均已落盘。崩溃前调用，避免守护线程随进程终止丢日志。
     */
    fun drainBlocking() {
        try {
            val latch = CountDownLatch(1)
            executor.execute { latch.countDown() }
            latch.await(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (ignored: Exception) {
            // 忽略：排空失败也应继续写崩溃堆栈
        }
    }

    /**
     * 同步写入崩溃信息（供 [CrashHandler] 在进程终止前调用，绕过异步队列并立即 flush）。
     */
    fun writeCrashSync(thread: Thread, throwable: Throwable, envInfo: String?) {
        val sb = StringBuilder()
        sb.append(lineDateFormat.format(Date()))
            .append(" [E/Crash] ====== 未捕获异常，进程即将终止 ======")
        if (!envInfo.isNullOrEmpty()) {
            sb.append('\n').append(envInfo)
        }
        sb.append('\n').append("线程: ").append(thread.name)
        sb.append('\n').append(Log.getStackTraceString(throwable))
        // 崩溃信息可能来自任意线程，直接同步落盘（appendLine 内部持锁，与异步写入互斥）
        appendLine(sb.toString())
    }

    private fun format(level: Level, tag: String, msg: String, tr: Throwable?): String {
        val sb = StringBuilder()
        sb.append(lineDateFormat.format(Date()))
            .append(" [").append(level.name[0]).append('/').append(tag).append("] ")
            .append(Thread.currentThread().name)
            .append("  ").append(msg)
        if (tr != null) {
            sb.append('\n').append(Log.getStackTraceString(tr))
        }
        return sb.toString()
    }

    /** 追加一行并 flush；持有 [lock]，文件按天切换时重开写入器。失败仅降级到 Logcat。 */
    private fun appendLine(line: String) {
        synchronized(lock) {
            try {
                val w = ensureWriterLocked() ?: return
                w.write(line)
                w.newLine()
                w.flush()
            } catch (e: Exception) {
                Log.w(GLOBAL_TAG, "写入日志文件失败: " + e.message)
            }
        }
    }

    private fun ensureWriterLocked(): BufferedWriter? {
        val dir = logDir ?: return null
        val name = FILE_PREFIX + fileDateFormat.format(Date()) + FILE_SUFFIX
        if (writer == null || writerFileName != name) {
            closeWriterLocked()
            writer = BufferedWriter(FileWriter(File(dir, name), true))
            writerFileName = name
        }
        return writer
    }

    private fun closeWriterLocked() {
        try {
            writer?.close()
        } catch (ignored: Exception) {
            // 日志模块自身的失败无处可报（再记日志会递归），只能静默丢弃
        }
        writer = null
        writerFileName = null
    }

    private fun resolveWritableDir(context: Context): File? {
        val app = context.applicationContext
        val external = AppDirectories.getLogDir(app)
        if (external.canWrite()) {
            return external
        }
        val internal = File(app.filesDir, AppDirectories.LOG_DIR_NAME)
        return if (internal.exists() || internal.mkdirs()) internal else null
    }

    /** 仅保留最近 [MAX_LOG_FILES] 个日志文件；文件名含日期，字典序即时间序，删除最旧的。 */
    private fun cleanupOldLogs() {
        synchronized(lock) {
            val dir = logDir ?: return
            val logs = dir.listFiles { f ->
                f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
            } ?: return
            if (logs.size <= MAX_LOG_FILES) return
            logs.sortBy { it.name }
            val deleteCount = logs.size - MAX_LOG_FILES
            for (i in 0 until deleteCount) {
                try {
                    logs[i].delete()
                } catch (ignored: Exception) {
                    // 旧日志删不掉不影响新日志写入，下次清理会再试
                }
            }
        }
    }
}
