package com.zys.mobilemap.update

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * APK 直连下载：单独工作线程拉流写入应用外部私有下载目录，
 * 进度经主线程 Handler 节流回调；[cancel] 可随时中断并清理半成品文件。
 */
class ApkDownloadService(context: Context) {

    interface Callback {
        fun onProgress(progress: DownloadProgress)
        fun onCompleted(apkFile: File)
        fun onCancelled()
        fun onFailed(e: Exception)
    }

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 取消标记：主线程写、下载线程读，必须 @Volatile 保证跨线程可见 */
    @Volatile
    private var cancelled = false

    private var workerThread: Thread? = null

    @Synchronized
    fun start(request: DownloadRequest, callback: Callback): Boolean {
        if (isRunning()) return false

        cancelled = false
        workerThread = Thread({ runDownload(request, callback) }, WORKER_THREAD_NAME)
        workerThread?.start()
        return true
    }

    @Synchronized
    fun cancel() {
        cancelled = true
        workerThread?.interrupt()
    }

    @Synchronized
    fun isRunning(): Boolean = workerThread?.isAlive == true

    private fun runDownload(request: DownloadRequest, callback: Callback) {
        var connection: HttpURLConnection? = null
        var outputFile: File? = null

        try {
            val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IllegalStateException("Download directory unavailable")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("Failed to create download directory")
            }

            outputFile = File(directory, request.fileName)

            connection = URL(request.apkUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.connect()

            val code = connection.responseCode
            if (code < 200 || code >= 300) {
                throw IllegalStateException("Download failed with HTTP code $code")
            }

            var totalBytes = connection.contentLengthLong
            if (totalBytes <= 0L) {
                totalBytes = request.expectedSizeBytes
            }

            var downloadedBytes = 0L
            var lastProgressEmit = 0L

            connection.inputStream.use { input ->
                FileOutputStream(outputFile, false).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read = input.read(buffer)
                    while (read != -1) {
                        ensureNotCancelled()
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmit >= PROGRESS_THROTTLE_MS) {
                            val progress = DownloadProgress(downloadedBytes, totalBytes)
                            mainHandler.post { callback.onProgress(progress) }
                            lastProgressEmit = now
                        }
                        read = input.read(buffer)
                    }
                    output.flush()
                }
            }

            ensureNotCancelled()
            val completedFile = outputFile
            val finalDownloaded = downloadedBytes
            val finalTotal = totalBytes
            mainHandler.post {
                callback.onProgress(DownloadProgress(finalDownloaded, finalTotal))
                callback.onCompleted(completedFile)
            }
        } catch (e: InterruptedIOException) {
            safeDelete(outputFile)
            // SocketTimeoutException 也是 InterruptedIOException 子类：仅确为用户取消才报“已取消”，
            // 读超时须按网络失败处理，否则用户看到的是“已取消”而非“下载失败”
            // （cancelled 在 finally 里会被重置，故先取到局部变量）
            val userCancelled = cancelled
            mainHandler.post {
                if (userCancelled) callback.onCancelled() else callback.onFailed(e)
            }
        } catch (e: Exception) {
            safeDelete(outputFile)
            mainHandler.post { callback.onFailed(e) }
        } finally {
            connection?.disconnect()
            synchronized(this) {
                workerThread = null
                cancelled = false
            }
        }
    }

    private fun ensureNotCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Download cancelled")
        }
    }

    /** 删除下载半成品；删除失败仅影响磁盘占用，不中断流程，但留日志便于排查 */
    private fun safeDelete(file: File?) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "下载临时文件删除失败: ${file.absolutePath}")
        }
    }

    companion object {
        private const val TAG = "ApkDownloadService"

        /** 工作线程名：便于在 ANR / 崩溃日志中辨认 */
        private const val WORKER_THREAD_NAME = "app-update-direct-download"

        /** 连接 / 读取超时（毫秒）：APK 体积大，读超时须宽于元数据检查 */
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000

        /** 拷贝缓冲区大小（字节） */
        private const val BUFFER_SIZE = 8192

        /** 进度回调节流间隔（毫秒）：避免每读一块都 post 主线程 */
        private const val PROGRESS_THROTTLE_MS = 250L
    }
}
