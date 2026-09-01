package com.zys.mobilemap.update

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL

class ApkDownloadService(context: Context) {

    interface Callback {
        fun onProgress(progress: DownloadProgress)
        fun onCompleted(apkFile: File)
        fun onCancelled()
        fun onFailed(e: Exception)
    }

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cancelled = false
    private var workerThread: Thread? = null

    @Synchronized
    fun start(request: DownloadRequest, callback: Callback): Boolean {
        if (isRunning()) return false

        cancelled = false
        workerThread = Thread({ runDownload(request, callback) }, "app-update-direct-download")
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
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
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
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        ensureNotCancelled()
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmit >= 250L) {
                            val progress = DownloadProgress(downloadedBytes, totalBytes)
                            mainHandler.post { callback.onProgress(progress) }
                            lastProgressEmit = now
                        }
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
            mainHandler.post { callback.onCancelled() }
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

    private fun safeDelete(file: File?) {
        if (file != null && file.exists()) {
            file.delete()
        }
    }
}
