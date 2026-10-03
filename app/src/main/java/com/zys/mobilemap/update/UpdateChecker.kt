package com.zys.mobilemap.update

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 升级元数据检查：后台单线程拉取 [URL] 指向的 JSON，解析为 [UpdateInfo] 后经 [Callback] 回调。
 * 回调在拉取线程触发，由调用方自行切回主线程。
 */
object UpdateChecker {

    interface Callback {
        fun onSuccess(info: UpdateInfo)
        fun onError(e: Exception)
    }

    /** 连接 / 读取超时（毫秒）：元数据为小 JSON，超时宜短，避免启动时长时间阻塞 */
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** Content-Type 中的字符集声明前缀 */
    private const val CHARSET_PREFIX = "charset="

    private val executor = Executors.newSingleThreadExecutor()

    fun check(metadataUrl: String, callback: Callback) {
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(metadataUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.useCaches = false

                val code = connection.responseCode
                if (code < 200 || code >= 300) {
                    throw IllegalStateException("Update check failed with HTTP code $code")
                }

                val response = readResponse(connection)
                val json = JSONObject(response)
                val info = UpdateInfo(
                    versionCode = json.optInt("versionCode", 0),
                    versionName = json.optString("versionName", ""),
                    apkUrl = json.optString("apkUrl", ""),
                    changelog = json.optString("changelog", ""),
                    force = json.optBoolean("force", false),
                    apkSizeBytes = json.optLong("apkSizeBytes", -1L)
                )

                if (!UpdateVersionPolicy.isValidMetadata(info)) {
                    throw IllegalStateException("Invalid update metadata payload")
                }

                callback.onSuccess(info)
            } catch (e: Exception) {
                callback.onError(e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * 读取响应体并解码：优先用响应头声明的字符集，其次 UTF-8；
     * UTF-8 解出替换符（U+FFFD）时回退 GB18030（部分服务端未声明字符集却用 GBK 系编码）。
     */
    private fun readResponse(connection: HttpURLConnection): String {
        val payload = readFully(connection.inputStream)

        val charsetName = extractCharset(connection.contentType)
        if (!charsetName.isNullOrEmpty()) {
            try {
                return String(payload, Charset.forName(charsetName))
            } catch (ignored: IllegalArgumentException) {
                // 声明的字符集名不受支持：继续走下方启发式解码
            }
        }

        val utf8Text = String(payload, StandardCharsets.UTF_8)
        if (!utf8Text.contains('\uFFFD')) {
            return utf8Text
        }

        return String(payload, Charset.forName("GB18030"))
    }

    /** 从 Content-Type 取出声明的字符集名（前缀大小写不敏感）；未声明时返回 null */
    private fun extractCharset(contentType: String?): String? {
        contentType ?: return null
        return contentType.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith(CHARSET_PREFIX, ignoreCase = true) }
            // 按下标截取（非 substringAfter）：声明可能写成 CHARSET=gbk，分隔符大小写不定
            ?.let { it.substring(CHARSET_PREFIX.length).trim() }
    }

    /** 读完整个响应流（元数据为小 JSON，一次性读入内存即可） */
    private fun readFully(inputStream: InputStream): ByteArray = inputStream.use { it.readBytes() }
}
