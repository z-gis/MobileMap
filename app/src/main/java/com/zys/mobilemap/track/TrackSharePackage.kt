package com.zys.mobilemap.track

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.zys.mobilemap.project.Project
import com.zys.mobilemap.project.ProjectManager
import com.zys.mobilemap.util.GeoCalc
import com.zys.mobilemap.util.ShapefileWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 轨迹分享包：把一条轨迹导出为标准 Shapefile（折线 + WGS84 投影 + 属性表），
 * 打包成单个 `.zip` 文件（shp 为多文件构成的数据集，必须整组分发才能被接收端识别，
 * 单发 .shp 无意义），经系统分享面板发出（微信/文件管理器等，与 [com.zys.mobilemap.media.MediaPackage]
 * 同一套 FileProvider 授权路径，落在 `getExternalFilesDir(share)` 下）。
 *
 * 接收端（QGIS/ArcGIS/本 app 外部导入入口）解压后即可作为普通 shp 图层加载，
 * 属性表含名称/采集时间/长度/点数四项。
 */
object TrackSharePackage {

    private const val TAG = "TrackSharePackage"

    /** 分享 ZIP 扩展名：标准 zip，接收端无需本 app 也能解压使用 */
    private const val EXT = "zip"

    /** 分享 MIME：zip 标准类型 */
    private const val SHARE_MIME = "application/zip"

    /** 导出包在应用私有目录下的存放子目录名（与 [com.zys.mobilemap.media.MediaPackage] 同目录，共用清理策略） */
    private const val SHARE_DIR = "share"

    /** 旧导出包保留时长（毫秒）：超过则在下次导出时清理 */
    private const val STALE_PACKAGE_MS = 24 * 60 * 60 * 1000L

    private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 分享一条轨迹：后台线程调用（IO + zip 打包可能耗时），完成后经系统分享面板发出；
     * 失败（点数不足/写盘异常/无可用分享应用）在主线程 Toast 提示，不抛出。
     */
    fun shareTrack(context: Context, track: Project.TrackRecord) {
        if (track.points.size < 2) {
            Toast.makeText(context, "轨迹点数不足，无法分享", Toast.LENGTH_SHORT).show()
            return
        }
        val app = context.applicationContext
        Thread {
            val file = runCatching { buildPackage(app, track) }.getOrElse {
                Log.e(TAG, "轨迹打包分享失败", it)
                null
            }
            mainHandler.post { share(app, file, track.name?.ifBlank { null } ?: "轨迹") }
        }.start()
    }

    private fun share(context: Context, file: File?, subject: String) {
        if (file == null) {
            Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrElse {
            Log.e(TAG, "轨迹分享包授权 Uri 失败", it)
            Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = SHARE_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "未找到可用的分享应用", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 构建分享包：写出 shp 文件组到临时目录，压成单个 `.zip`；写盘失败或文件组缺失返回 null。
     * 临时目录打包后即删除，只留最终 zip 文件。
     */
    private fun buildPackage(context: Context, track: Project.TrackRecord): File? {
        val shareDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, SHARE_DIR)
        if (!shareDir.exists()) shareDir.mkdirs()
        cleanStalePackages(shareDir)

        val stamp = System.currentTimeMillis()
        val base = sanitize(track.name?.ifBlank { null } ?: "轨迹")
        val tmpDir = File(shareDir, "tmp_$base" + "_" + stamp)
        val packageFile = File(shareDir, base + "_" + stamp + "." + EXT)

        // 内部存储点串为 [纬度, 经度, 高程]，Shapefile 轴序 x=经度、y=纬度，需换轴
        val lonLatPoints = track.points.map { doubleArrayOf(it[1], it[0]) }
        val lengthMeters = GeoCalc.polylineLengthMeters(track.points.map { doubleArrayOf(it[0], it[1]) })
        val fields = listOf(
            ShapefileWriter.DbfField("NAME", 100, track.name ?: "轨迹"),
            ShapefileWriter.DbfField("CREATED", 20, TIME_FORMAT.format(Date(ProjectManager.parseTime(track.date)))),
            ShapefileWriter.DbfField("LENGTH_M", 20, String.format(Locale.US, "%.1f", lengthMeters)),
            ShapefileWriter.DbfField("POINTS", 10, track.points.size.toString())
        )

        val shpFiles = ShapefileWriter.writePolyline(File(tmpDir, base), lonLatPoints, fields)
        if (shpFiles.isEmpty()) {
            tmpDir.deleteRecursively()
            return null
        }

        return try {
            ZipOutputStream(packageFile.outputStream().buffered()).use { zip ->
                shpFiles.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.name))
                    f.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            packageFile
        } catch (e: Exception) {
            Log.e(TAG, "轨迹打包 zip 失败", e)
            packageFile.delete()
            null
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** 清理超过一天未动的旧导出包，避免私有目录里累积占用空间（不影响刚生成/在途的包）。 */
    private fun cleanStalePackages(shareDir: File) {
        val cutoff = System.currentTimeMillis() - STALE_PACKAGE_MS
        runCatching {
            shareDir.listFiles()?.forEach {
                if (it.isFile && it.name.endsWith(".$EXT", ignoreCase = true) && it.lastModified() < cutoff) {
                    it.delete()
                }
            }
        }
    }

    /** 过滤文件名字符：反斜杠/斜杠/冒号等非法字符与空白统一替换为下划线。 */
    private fun sanitize(name: String): String =
        name.trim().ifEmpty { "track" }.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
}
