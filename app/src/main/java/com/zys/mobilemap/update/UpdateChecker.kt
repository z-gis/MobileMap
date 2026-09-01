package com.zys.mobilemap.update

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

object UpdateChecker {

    interface Callback {
        fun onSuccess(info: UpdateInfo)
        fun onError(e: Exception)
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun check(metadataUrl: String, callback: Callback) {
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(metadataUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
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

    private fun readResponse(connection: HttpURLConnection): String {
        val payload = readFully(connection.inputStream)

        val charsetName = extractCharset(connection.contentType)
        if (!charsetName.isNullOrEmpty()) {
            try {
                return String(payload, Charset.forName(charsetName))
            } catch (_: Exception) {
                // Fall through to heuristic decoding
            }
        }

        val utf8Text = String(payload, StandardCharsets.UTF_8)
        if (!utf8Text.contains("\uFFFD")) {
            return utf8Text
        }

        // Some endpoints still use GBK/GB18030 without declaring charset
        return String(payload, Charset.forName("GB18030"))
    }

    private fun extractCharset(contentType: String?): String? {
        if (contentType == null) return null
        for (part in contentType.split(";")) {
            val token = part.trim()
            if (token.lowercase().startsWith("charset=")) {
                return token.substring("charset=".length).trim()
            }
        }
        return null
    }

    private fun readFully(inputStream: InputStream): ByteArray {
        return inputStream.use { input ->
            ByteArrayOutputStream().use { out ->
                val buffer = ByteArray(4096)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }
    }
}
