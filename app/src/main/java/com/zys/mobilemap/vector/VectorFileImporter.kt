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
 *  - zip：shp 压缩包，解压后递归收集包内全部 shp，逐个连同附属文件整组搬平到
 *    /调查宝/layer/<文件名>/ 主目录（消除 zip 内无意义中间目录），多个 shp 全部导入；
 *  - kmz：解压后查找内部 kml（优先 doc.kml）。
 */
object VectorFileImporter {

    private const val TAG = "VectorFileImporter"

    /** zip 解压临时目录后缀（整组搬平到主目录后即删；同名残留下次导入先清再解） */
    private const val UNZIP_TMP_SUFFIX = "_unzip_tmp"

    /**
     * 导入矢量文件，返回全部图层（zip 内多个 shp 时逐个导入），失败返回空列表。
     */
    fun importFiles(context: Context, path: String): List<ImportResult> {
        // 转换为File，并判断文件是否存在
        val file = File(path)
        if (!file.exists()) return emptyList()
        // 获取文件名（不带扩展名，已过滤非法文件名字符）
        val name = AppDirectories.deriveBaseName(path)
        // 根据文件扩展名分发处理
        return when (file.extension.lowercase(Locale.getDefault())) {
            "shp", "kml", "dwg", "dxf", "gpkg" -> listOf(ImportResult(path, name))
            "zip" -> extractZipShapefiles(context, file, name)
            // kmz 显示名用压缩包本身文件名，而非内部 doc.kml 的名称
            "kmz" -> extractAndFind(context, file, listOf("kml"), name)?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    /**
     * 导入矢量文件（单结果入口，多图层时取首个），失败返回 null。
     */
    fun importFile(context: Context, path: String): ImportResult? =
        importFiles(context, path).firstOrNull()

    /**
     * shp 压缩包导入：解压到临时目录后递归收集全部 shp（跳过 __MACOSX/隐藏文件等垃圾条目），
     * 每个 shp 连同同目录同名附属文件（dbf/shx/prj/cpg 等）整组搬平到主目录 /layer/<压缩包名>/，
     * 主目录已有同名 shp 时整组 stem 追加序号区分，搬完删除临时目录。
     *
     * 复用判定只看主目录**根级**是否已有 shp（既往已搬平）：旧版解压残留在子目录深处的 shp
     * 不视为复用（仍重新解压搬平），且不删除——可能仍被旧文档图层路径引用。
     *
     * 显示名：单个 shp 沿用压缩包名（与旧行为一致）；多个 shp 各用自己的文件名。
     */
    private fun extractZipShapefiles(context: Context, archive: File, displayName: String): List<ImportResult> {
        val layerDir = AppDirectories.getLayerDir(context)
        val destDir = File(layerDir, archive.nameWithoutExtension)
        try {
            var shps = rootShapefiles(destDir)
            if (shps.isEmpty()) {
                val tmpDir = File(layerDir, archive.nameWithoutExtension + UNZIP_TMP_SUFFIX)
                if (tmpDir.exists()) tmpDir.deleteRecursively()
                unzip(archive, tmpDir)
                shps = collectShapefiles(tmpDir)
                for (shp in shps) moveShapefileGroup(shp, destDir)
                tmpDir.deleteRecursively()
                shps = rootShapefiles(destDir)
            }
            if (shps.isEmpty()) {
                Log.w(TAG, "压缩包内未找到 shp 数据: ${archive.path}")
                return emptyList()
            }
            val single = shps.size == 1
            return shps.map {
                ImportResult(it.absolutePath, if (single) displayName else it.nameWithoutExtension)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解压压缩包失败: ${archive.name}", e)
            return emptyList()
        }
    }

    /** 主目录根级 shp 列表（不递归，复用判定与最终结果口径一致）；目录不存在返回空。 */
    private fun rootShapefiles(dir: File): List<File> =
        dir.listFiles { f ->
            f.isFile && f.extension.equals("shp", ignoreCase = true)
        }?.toList() ?: emptyList()

    /** 递归收集目录下全部 shp（进入时跳过 __MACOSX/隐藏目录，过滤隐藏文件）。 */
    private fun collectShapefiles(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown()
            .onEnter { it.name != "__MACOSX" && !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") && it.extension.equals("shp", ignoreCase = true) }
            .toList()
    }

    /**
     * 把 shp 及其同目录全部同名附属文件（dbf/shx/prj/cpg 等，任意扩展名）整组搬到 destDir：
     * 主目录已有同名 shp 时整组 stem 追加序号（<stem>_2 起），附属文件随组改名，保证 shp 依赖完整；
     * renameTo 失败（跨目录等罕见情形）回退复制+删源。
     */
    private fun moveShapefileGroup(shp: File, destDir: File) {
        destDir.mkdirs()
        val stem = shp.nameWithoutExtension
        val sourceDir = shp.parentFile ?: return
        // 主目录同名 shp 冲突时整组换 stem（只比对 shp，不误判残留的孤立附属文件）
        var targetStem = stem
        var seq = 2
        while (hasShpNamed(destDir, targetStem)) {
            targetStem = "${stem}_$seq"
            seq++
        }
        val companions = sourceDir.listFiles { f ->
            f.isFile && f.nameWithoutExtension == stem
        } ?: arrayOf(shp)
        for (src in companions) {
            val ext = src.name.substringAfterLast('.', "")
            val target = File(destDir, if (ext.isEmpty()) targetStem else "$targetStem.$ext")
            if (!src.renameTo(target)) {
                src.copyTo(target, overwrite = true)
                src.delete()
            }
        }
    }

    /** destDir 根级是否已有指定 stem 的 shp（扩展名大小写不敏感）。 */
    private fun hasShpNamed(dir: File, stem: String): Boolean =
        dir.listFiles()?.any {
            it.isFile && it.nameWithoutExtension == stem &&
                it.extension.equals("shp", ignoreCase = true)
        } == true

    /**
     * kmz 专用：解压压缩包到 /调查宝/layer/<文件名(去扩展名)>/，并查找指定扩展名的内部文件。
     * displayName 为图层显示名（压缩包文件名）。
     * kmz 解压出的约定入口 doc.kml 重命名为与目录同名（<displayName>.kml），便于阅读与后续定位。
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
