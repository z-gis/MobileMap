package com.zys.mobilemap.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.zys.mobilemap.util.AppDirectories
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 拍照数据包（自定义扩展名 [.EXT]，本质是 zip 压缩包）：把拍照点位的坐标、名称与其下全部照片
 * 打包成一个文件，经系统分享面板发出（可发到微信/文件管理器等）；接收方用本 app 打开该文件即导入
 * （与矢量数据外部加载同一入口 MainActivity.handleViewIntent），
 * 解包后按包内坐标/名称新建拍照点位。
 *
 * 包内结构：
 * - `metadata.json`：格式标识 + 可选分组名 + 各点位（名称/经纬度/创建时间/照片相对路径列表）
 * - `<点位序号>/<照片文件名>`：照片原文件（按点位分子目录，避免跨点位同名覆盖）
 *
 * 分享文件写在应用外部私有目录 `getExternalFilesDir/share` 下，已在 file_paths.xml 的
 * external-files-path(media, path=".") 覆盖范围内，可直接经 FileProvider 授权分享。
 */
object MediaPackage {

    private const val TAG = "MediaPackage"

    /** 自定义扩展名（不含点）：数据实为 zip，接收方用本 app 打开触发导入 */
    const val EXT = "mphoto"

    /** 包内元数据文件名 */
    private const val META_FILE = "metadata.json"

    /** 格式标识：导入时校验，避免把其它 zip 误当拍照数据包解析 */
    private const val FORMAT_TAG = "com.zys.mobilemap.media.package"

    /** 当前格式版本 */
    private const val FORMAT_VERSION = 1

    /** 分享 MIME：自定义扩展名无标准类型，用通用二进制流保证各接收端都能收下 */
    private const val SHARE_MIME = "application/octet-stream"

    /** 导出包在应用私有目录下的存放子目录名 */
    private const val SHARE_DIR = "share"

    /** 判定文件名/路径是否为本 app 拍照数据包 */
    fun isPackageFile(nameOrPath: String?): Boolean =
        nameOrPath != null && nameOrPath.endsWith(".$EXT", ignoreCase = true)

    // ==================== 分享（导出打包） ====================

    /**
     * 分享单个拍照点位：打包其坐标/名称/全部照片后经系统分享面板发出。
     */
    fun sharePlacemark(context: Context, placemark: MediaPlacemark) {
        val subject = placemark.name.ifBlank { "拍照点位" }
        share(context, buildPackage(context, null, listOf(placemark)), subject)
    }

    /**
     * 分享整个分组：打包组内全部点位（含各自坐标/照片）后经系统分享面板发出。
     * [groupName] 非空时写入包内，导入据此重建同名分组；未分组分享传 null，导入后点位仍归未分组。
     */
    fun shareGroup(context: Context, groupName: String?, placemarks: List<MediaPlacemark>) {
        val subject = groupName?.takeIf { it.isNotBlank() } ?: "拍照分组"
        share(context, buildPackage(context, groupName, placemarks), subject)
    }

    private fun share(context: Context, file: File?, subject: String) {
        if (file == null) {
            Toast.makeText(context, "没有可分享的照片", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrElse {
            Log.e(TAG, "拍照数据包授权 Uri 失败", it)
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
     * 构建数据包：仅打包有照片的点位（过滤非照片附件与失效文件），写入 metadata.json 与各点位照片，
     * 返回 `.[EXT]` 文件；无任何照片时返回 null（调用方提示）。
     */
    private fun buildPackage(context: Context, groupName: String?, placemarks: List<MediaPlacemark>): File? {
        val entries = placemarks.mapNotNull { p ->
            val photos = p.attachments.filter {
                it.type == MediaAttachment.TYPE_PHOTO && File(it.path).let { f -> f.exists() && f.length() > 0 }
            }
            if (photos.isEmpty()) null else p to photos
        }
        if (entries.isEmpty()) return null

        val shareDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, SHARE_DIR)
        if (!shareDir.exists()) shareDir.mkdirs()
        cleanStalePackages(shareDir)

        val stamp = System.currentTimeMillis()
        val base = sanitize((groupName ?: entries.first().first.name).ifBlank { "拍照数据包" })
        val packageFile = File(shareDir, base + "_" + stamp + "." + EXT)

        return try {
            ZipOutputStream(packageFile.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(META_FILE))
                zip.write(buildMetadata(groupName, entries, stamp).toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                entries.forEachIndexed { index, entry ->
                    entry.second.forEach { att ->
                        val src = File(att.path)
                        zip.putNextEntry(ZipEntry(index.toString() + "/" + src.name))
                        src.inputStream().buffered().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            packageFile
        } catch (e: Exception) {
            Log.e(TAG, "打包拍照数据失败", e)
            packageFile.delete()
            null
        }
    }

    /** 组装 metadata.json：格式标识/版本/导出时间 + 可选分组名 + 各点位与其照片相对路径。 */
    private fun buildMetadata(
        groupName: String?,
        entries: List<Pair<MediaPlacemark, List<MediaAttachment>>>,
        stamp: Long
    ): JSONObject {
        val meta = JSONObject()
        meta.put("format", FORMAT_TAG)
        meta.put("version", FORMAT_VERSION)
        meta.put("exportTime", stamp)
        if (!groupName.isNullOrBlank()) meta.put("groupName", groupName)
        val arr = JSONArray()
        entries.forEachIndexed { index, entry ->
            val p = entry.first
            val po = JSONObject()
            po.put("name", p.name)
            po.put("latitude", p.latitude)
            po.put("longitude", p.longitude)
            po.put("createdTime", p.createdTime)
            val names = JSONArray()
            entry.second.forEach { names.put(index.toString() + "/" + File(it.path).name) }
            po.put("photos", names)
            arr.put(po)
        }
        meta.put("placemarks", arr)
        return meta
    }

    /** 清理超过一天未动的旧导出包，避免私有目录里累积占用空间（不影响刚生成/在途的包）。 */
    private fun cleanStalePackages(shareDir: File) {
        val cutoff = System.currentTimeMillis() - STALE_PACKAGE_MS
        runCatching {
            shareDir.listFiles()?.forEach {
                if (it.isFile && isPackageFile(it.name) && it.lastModified() < cutoff) it.delete()
            }
        }
    }

    // ==================== 导入（解包新建点位） ====================

    /**
     * 导入数据包：解包 metadata.json 与照片，按包内坐标/名称新建拍照点位（照片复制到
     * `/调查宝/Media/<新点位id>/`），返回新建点位列表（失败或空包返回空列表）。
     *
     * 与矢量外部导入一致，本方法只做 IO，须在工作线程调用；[packageFile] 由调用方负责善后。
     */
    fun importPackage(context: Context, packageFile: File): List<MediaPlacemark> {
        if (!packageFile.exists()) return emptyList()
        val tmpDir = File(
            context.getExternalFilesDir(null) ?: context.cacheDir,
            "import_" + System.currentTimeMillis()
        )
        return try {
            unzipTo(packageFile, tmpDir)
            val metaFile = File(tmpDir, META_FILE)
            if (!metaFile.exists()) {
                Log.w(TAG, "数据包缺少 metadata.json，非本 app 拍照数据包")
                emptyList()
            } else {
                val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
                if (meta.optString("format") != FORMAT_TAG) {
                    Log.w(TAG, "数据包格式标识不匹配")
                    emptyList()
                } else {
                    createPlacemarks(context, tmpDir, meta)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "导入拍照数据包失败", e)
            emptyList()
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** 按元数据在库中新建点位并复制照片，返回新建点位（分组包先建同名分组承接）。 */
    private fun createPlacemarks(context: Context, tmpDir: File, meta: JSONObject): List<MediaPlacemark> {
        val store = MediaStore.getInstance(context)
        val mediaDir = AppDirectories.getMediaDir(context)

        // 分组包：新建同名分组承接导入点位；单点位包（无 groupName）归入未分组
        val groupName = meta.optString("groupName", "").takeIf { it.isNotBlank() }
        val groupId = groupName?.let { store.insertGroup(it, System.currentTimeMillis()) }
            ?: MediaStore.UNGROUPED_ID

        val created = mutableListOf<MediaPlacemark>()
        val arr = meta.optJSONArray("placemarks") ?: return created
        for (i in 0 until arr.length()) {
            val po = arr.optJSONObject(i) ?: continue
            val lat = po.optDouble("latitude", Double.NaN)
            val lon = po.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val name = po.optString("name", "")
            val createdTime = po.optLong("createdTime", System.currentTimeMillis())

            val newId = store.insertPlacemark(name, lat, lon, createdTime, groupId)
            if (newId < 0) continue
            val destDir = File(mediaDir, newId.toString())
            if (!destDir.exists()) destDir.mkdirs()

            val result = MediaPlacemark(newId, name, lat, lon, createdTime, true, groupId)
            val photos = po.optJSONArray("photos")
            if (photos != null) {
                for (j in 0 until photos.length()) {
                    val rel = photos.optString(j)
                    if (rel.isNullOrEmpty()) continue
                    val src = File(tmpDir, rel)
                    // 防 zip-slip：解包后的照片必须落在临时目录内
                    if (!src.exists() || !src.canonicalPath.startsWith(tmpDir.canonicalPath)) continue
                    val target = uniqueFile(destDir, src.name)
                    src.copyTo(target, overwrite = true)
                    // 附件时间按序递增，保证导入后照片顺序与打包时一致
                    val attTime = createdTime + j
                    val attId = store.insertAttachment(
                        newId, MediaAttachment.TYPE_PHOTO, target.name, target.absolutePath, attTime
                    )
                    result.attachments.add(
                        MediaAttachment(attId, newId, MediaAttachment.TYPE_PHOTO, target.name, target.absolutePath, attTime)
                    )
                }
            }
            created.add(result)
        }
        return created
    }

    /** 解包到目标目录（含 zip-slip 防护）；条目名均为 ASCII（点位序号/IMG_xxx.jpg），UTF-8 足够。 */
    private fun unzipTo(archive: File, destDir: File) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val ze = entries.nextElement()
                val out = File(destDir, ze.name)
                if (!out.canonicalPath.startsWith(destDir.canonicalPath)) continue
                if (ze.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(ze).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    /** 目标目录下取不冲突的文件名：同名时在扩展名前追加 _序号。 */
    private fun uniqueFile(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var k = 1
        while (target.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            target = File(dir, stem + "_" + k + suffix)
            k++
        }
        return target
    }

    /** 过滤文件名字符：反斜杠/斜杠/冒号等非法字符与空白统一替换为下划线。 */
    private fun sanitize(name: String): String =
        name.trim().ifEmpty { "package" }.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")

    /** 旧导出包保留时长（毫秒）：超过则在下次导出时清理 */
    private const val STALE_PACKAGE_MS = 24 * 60 * 60 * 1000L
}
