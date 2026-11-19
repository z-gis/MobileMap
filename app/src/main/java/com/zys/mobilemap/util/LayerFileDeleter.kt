package com.zys.mobilemap.util

import android.content.Context
import com.zys.mobilemap.vector.VectorFormats
import java.io.File

/**
 * 图层源文件删除（图层管理「移除」对话框勾选「同时删除图层文件」时调用）。
 *
 * 删除目标按路径形态推导：
 *  - layer 根目录下的单文件：文件本身 + 同干附属文件（shp 族/栅格族/ CAD .prj / gpkg WAL）；
 *  - layer 根目录下的 .gdb 目录：整目录递归删除；
 *  - layer 根的直接子目录内（zip/kmz/gdb.zip 导入产物）：整个导入子目录 + layer 根下同名原压缩包；
 *  - 其余位置（用户原路径引用、工程 gpkg 等）：只删文件本身 + 附属文件，绝不删目录。
 *
 * 共享保护：文档中还有其他图层落在同一删除目标内（同 GDB 其他要素类、同 gpkg 其他层、
 * 同压缩包其他 shp）时不提供删除选项（[offerDelete] 返回 false），避免删掉别的图层源。
 */
object LayerFileDeleter {

    /**
     * 是否提供「同时删除图层文件」选项：路径有效（存在）且无其他图层共享删除目标。
     * [otherLayerPaths] 为文档中除本图层外全部图层路径（矢量+栅格）。
     */
    fun offerDelete(context: Context, path: String?, otherLayerPaths: List<String>): Boolean {
        if (path.isNullOrEmpty()) return false
        val f = File(path)
        if (!f.exists()) return false
        val layerRoot = AppDirectories.getLayerDir(context)
        val base = importDirOf(f, layerRoot)
        if (base != null) {
            // 导入子目录整删：其他图层落在目录内即共享（含目录本身）
            return otherLayerPaths.none { under(it, base) }
        }
        // 单文件/根下 gdb：其他图层同路径即共享（同 gdb 要素类 / 同 gpkg 多层）
        return otherLayerPaths.none { it == path }
    }

    /**
     * 删除图层源文件（尽力而为，单项失败仅记日志）：先按 [offerDelete] 同一口径推导目标再逐个删。
     * 调用方须已确认无共享（对话框不勾选共享项）；此处不再重复判定，避免列表刷新时差误判。
     */
    fun deleteSource(context: Context, path: String?) {
        if (path.isNullOrEmpty()) return
        val f = File(path)
        val layerRoot = AppDirectories.getLayerDir(context)
        val base = importDirOf(f, layerRoot)
        if (base != null) {
            AppLog.i(TAG, "删除图层导入目录: ${base.absolutePath}")
            base.deleteRecursively()
            // 原压缩包（kmz/zip/gdb.zip）与导入目录同名，留在 layer 根
            for (ext in listOf(".kmz", ".zip")) {
                val archive = File(layerRoot, base.name + ext)
                if (archive.exists()) {
                    AppLog.i(TAG, "删除图层原压缩包: ${archive.absolutePath}")
                    archive.delete()
                }
            }
            return
        }
        if (f.isDirectory) {
            AppLog.i(TAG, "删除图层数据集目录: ${f.absolutePath}")
            f.deleteRecursively()
            return
        }
        for (target in fileTargets(f)) {
            if (target.exists()) {
                AppLog.i(TAG, "删除图层源文件: ${target.absolutePath}")
                target.delete()
            }
        }
    }

    /**
     * 导入产物子目录判定：仅当文件/目录位于 layer 根的**直接子目录**内才视为可整删的导入目录
     * （zip/kmz 解压、gdb.zip 展开）；工程 gpkg（projects/）或用户原路径引用一律不碰目录。
     */
    private fun importDirOf(f: File, layerRoot: File): File? {
        val parent = f.parentFile ?: return null
        if (parent == layerRoot) return null
        if (parent.parentFile == layerRoot) return parent
        return null
    }

    /** 单文件删除目标：本身 + 按扩展名的同干附属文件（附属后缀见能力主表 DELETE_* 族） */
    private fun fileTargets(f: File): List<File> {
        val ext = VectorFormats.extOf(f.name)
        val stem = f.nameWithoutExtension
        val dir = f.parentFile ?: return listOf(f)
        val targets = mutableListOf(f)
        when {
            ext == "shp" -> VectorFormats.DELETE_SHP_EXTS.filter { it != ".shp" }.mapTo(targets) { File(dir, stem + it) }
            ext == "tif" || ext == "tiff" -> {
                VectorFormats.DELETE_RASTER_EXTS.mapTo(targets) { File(dir, stem + it) }
                targets.add(File(dir, f.name + ".aux.xml"))
            }
            VectorFormats.isCad(f.name) -> VectorFormats.DELETE_CAD_EXTS.mapTo(targets) { File(dir, stem + it) }
            ext == "gpkg" -> {
                targets.add(File(dir, f.name + "-wal"))
                targets.add(File(dir, f.name + "-shm"))
            }
        }
        return targets
    }

    /** [path] 是否落在目录 [dir] 内（含目录本身） */
    private fun under(path: String, dir: File): Boolean =
        path == dir.absolutePath || path.startsWith(dir.absolutePath + File.separator)

    private const val TAG = "LayerFileDeleter"
}