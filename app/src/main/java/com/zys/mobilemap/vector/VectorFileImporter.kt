package com.zys.mobilemap.vector

import android.content.Context
import android.util.Log
import com.zys.mobilemap.util.AppDirectories
import java.io.File
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 导入结果：加载/渲染使用的实际文件路径与显示名称。
 * zip/kmz 记录解压后内部文件的路径，保证重启后按文档路径可复现加载。
 */
class ImportResult(val path: String, val name: String)

/**
 * 矢量数据导入器，按扩展名分发：
 *  - shp/kml/dwg/dxf/gpkg：直接引用原路径（shp 依赖同目录的 dbf/prj/cpg 附属文件；gpkg 为单文件 SQLite 容器）；
 *  - zip：shp 压缩包，解压到 /调查宝/layer/<文件名>/ 后查找内部 shp；
 *  - kmz：解压后查找内部 kml（优先 doc.kml）。
 */
object VectorFileImporter {

    private const val TAG = "VectorFileImporter"

    /**
     * 导入矢量文件，失败返回 null。
     */
    fun importFile(context: Context, path: String): ImportResult? {
        // 转换为File，并判断文件是否存在
        val file = File(path)
        if (!file.exists()) return null
        // 获取文件名（不带扩展名，已过滤非法文件名字符）
        val name = AppDirectories.deriveBaseName(path)
        // 根据文件扩展名分发处理
        return when (file.extension.lowercase(Locale.getDefault())) {
            "shp", "kml", "dwg", "dxf", "gpkg" -> ImportResult(path, name)
            "zip" -> extractAndFind(context, file, listOf("shp"), name)
            // kmz 显示名用压缩包本身文件名，而非内部 doc.kml 的名称
            "kmz" -> extractAndFind(context, file, listOf("kml"), name)
            else -> null
        }
    }

    /**
     * 解压压缩包到 /调查宝/layer/<文件名(去扩展名)>/，并查找指定扩展名的内部文件。
     * displayName 为图层显示名（压缩包文件名）。
     * kmz 解压出的约定入口 doc.kml 重命名为与目录同名（<displayName>.kml），便于阅读与后续定位（zip 内 shp 名不改）。
     */
    private fun extractAndFind(context: Context, archive: File, exts: List<String>, displayName: String): ImportResult? {
        val layerDir = AppDirectories.getLayerDir(context)
        val destDir = File(layerDir, archive.nameWithoutExtension)
        try {
            // 复用既有解压结果；目录缺失、或存在但无目标内部文件（历史解压失败/同名目录冲突残留）时重新解压，
            // 避免仅凭 destDir.exists() 跳过 unzip 而误报“压缩包内无可用数据”
            var target = if (destDir.exists()) findFirst(destDir, exts) else null
            if (target == null) {
                unzip(archive, destDir)
                target = findFirst(destDir, exts)
            }
            if (target == null) {
                Log.w(TAG, "压缩包内未找到 ${exts} 内部文件: ${archive.path}")
                return null
            }
            // kmz 入口 doc.kml 改名为与目录同名，消除无语义的 doc.kml（仅在解压目标位于本目录、且名为 doc.kml 时处理）
            val finalTarget = if (target.name.equals("doc.kml", ignoreCase = true) &&
                target.parentFile?.absolutePath == destDir.absolutePath
            ) {
                val renamed = File(destDir, "$displayName.kml")
                if (target.renameTo(renamed)) renamed else target
            } else target
            return ImportResult(finalTarget.absolutePath, displayName)
        } catch (e: Exception) {
            Log.e(TAG, "解压压缩包失败: ${archive.name}", e)
            return null
        }
    }

    /**
     * 解压 zip：条目文件名先按 UTF-8 解码，出现乱码（替换字符）时回退 GBK（国产环境常见）。
     */
    private fun unzip(archive: File, destDir: File) {
        val charset = detectZipCharset(archive)
        ZipFile(archive, charset).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val ze = entries.nextElement()
                val out = File(destDir, ze.name)
                // 防止 zip-slip 路径穿越
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

    private fun detectZipCharset(archive: File): Charset {
        try {
            ZipFile(archive, Charsets.UTF_8).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    if (entries.nextElement().name.contains('\uFFFD')) {
                        return Charset.forName("GBK")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "UTF-8 检测压缩包失败，回退 GBK: ${e.message}")
            return Charset.forName("GBK")
        }
        return Charsets.UTF_8
    }

    private fun findFirst(dir: File, exts: List<String>): File? {
        val all = dir.walkTopDown().filter { it.isFile }.toList()
        // kmz 约定入口文件优先取 doc.kml
        if (exts == listOf("kml")) {
            all.firstOrNull { it.name.equals("doc.kml", ignoreCase = true) }?.let { return it }
        }
        for (ext in exts) {
            all.firstOrNull { it.extension.equals(ext, ignoreCase = true) }?.let { return it }
        }
        return null
    }
}
