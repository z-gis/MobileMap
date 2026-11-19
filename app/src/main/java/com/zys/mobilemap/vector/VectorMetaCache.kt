package com.zys.mobilemap.vector

import com.zys.globecore.NativeLayerInfo
import com.zys.globecore.VectorLayerMeta
import com.zys.mobilemap.doc.DocumentManager
import com.zys.mobilemap.doc.LayerInfo
import java.io.File

/**
 * 矢量图层元数据缓存（P1）：把 native 读取链顺手产出的全量元数据（几何类型/要素总数/数据四至）
 * 持久化到 document.json 的 [LayerInfo]，以源文件修改时间指纹判新鲜度——冷启动与图层管理逐行回显
 * 不再为元数据另开 GDAL；源文件被外部修改/几何编辑/重导入（指纹变）自动失效回落直读。
 *
 * 口径：native [VectorLayerMeta] 给的是「数据全量」值（屏幕过滤重载也不会拿子集冒充），
 * 未知项（featureCount=-1 / hasExtent=false）不写缓存、不覆盖既有好值，访问器逐项判有效后回落直读。
 * 几何类型存 app 口径（0 点 / 1 线 / 2 面 / -1 未知或混合），与 [GdalVectorReader.geomTypeOf] 一致；
 * KML/KMZ 不缓存几何类型（其层声明恒 wkbUnknown，样式分节走 [GdalVectorReader.geomMaskOf] 采样，口径不变）。
 */
object VectorMetaCache {

    /** shp 附属族：改属性只动 .dbf、改几何动 .shp/.shx，故指纹取族内最大 mtime（.qix 索引不算数据） */
    private val SHP_FAMILY = arrayOf(".shp", ".shx", ".dbf", ".prj", ".cpg")

    /**
     * 源文件修改时间指纹：gdb 目录扫内层文件取最大（要素类编辑不改目录 mtime）；
     * shp 取附属族最大；gpkg/kmz/其余单文件取自身。路径不存在或读不到返回 0（=无指纹，永不信缓存）。
     * 1s 内复用上次结果：图层管理逐行回显与元数据收口轮询免重复 walk 大目录。
     */
    fun sourceMtime(path: String): Long {
        if (path.isEmpty()) return 0L
        val now = System.currentTimeMillis()
        mtimeCache[path]?.let { if (now - it.second < MTIME_TTL_MS) return it.first }
        val m = computeMtime(path)
        mtimeCache[path] = m to now
        return m
    }

    private const val MTIME_TTL_MS = 1000L
    private val mtimeCache = HashMap<String, Pair<Long, Long>>()

    private fun computeMtime(path: String): Long {
        val f = File(path)
        if (!f.exists()) return 0L
        if (f.isDirectory) {
            // gdb：内层文件最大 mtime（含子目录，如 a00000001.gdbtable 所在目录）
            var max = f.lastModified()
            f.walkTopDown().forEach { if (it.isFile) max = maxOf(max, it.lastModified()) }
            return max
        }
        if (VectorFormats.extOf(path) == "shp") {
            val stem = path.substring(0, path.length - 4)
            var max = f.lastModified()
            for (ext in SHP_FAMILY) {
                val sib = File(stem + ext)
                if (sib.isFile) max = maxOf(max, sib.lastModified())
            }
            return max
        }
        return f.lastModified()
    }

    /** 缓存是否新鲜：指纹有效且与当前源文件一致（0=无指纹，一律不信） */
    fun isFresh(info: LayerInfo, mtime: Long): Boolean = mtime > 0L && info.metaMtime == mtime

    /** 要素总数（全量）：缓存命中直接返回，否则开 GDAL 统计；未知 -1 */
    fun featureCountOf(info: LayerInfo): Long {
        val path = info.path ?: return -1L
        if (path.isEmpty()) return -1L
        if (isFresh(info, sourceMtime(path)) && info.featureCount >= 0L) return info.featureCount
        return GdalVectorReader.countFeatures(path, info.layerName).toLong()
    }

    /** 几何类型（app 口径 0 点 / 1 线 / 2 面 / -1 未知或混合）：缓存命中直接返回，否则直读 */
    fun geomTypeOf(info: LayerInfo): Int {
        val path = info.path ?: return -1
        if (path.isEmpty()) return -1
        if (isFresh(info, sourceMtime(path)) && info.geomType >= 0) return info.geomType
        return GdalVectorReader.geomTypeOf(path, info.layerName)
    }

    /** 数据全量四至（WGS84 [minLon,minLat,maxLon,maxLat]）：缓存命中直接返回，否则直读；失败 null */
    fun extentOf(info: LayerInfo): DoubleArray? {
        val path = info.path ?: return null
        if (path.isEmpty()) return null
        if (isFresh(info, sourceMtime(path))) {
            val e = info.extent
            if (e != null && e.size == 4) return e
        }
        return readExtent(path, info.layerName)
    }

    /** 按路径查文档层后取四至（图层管理/缩放到图层等只持有路径的 UI 消费点用） */
    fun extentOf(path: String, layerName: String): DoubleArray? {
        val info = findInfo(path, layerName) ?: return readExtent(path, layerName)
        return extentOf(info)
    }

    /** 按路径查文档层后取几何类型（同上，未加入文档的路径回落直读） */
    fun geomTypeOf(path: String, layerName: String): Int {
        val info = findInfo(path, layerName) ?: return GdalVectorReader.geomTypeOf(path, layerName)
        return geomTypeOf(info)
    }

    private val labelSampleCache = HashMap<String, Pair<Long, String>>()

    /**
     * 标注样本值（图层管理预览用）：取首条非空要素的字段值作示例文本，与地图实际渲染内容同口径
     * （内核逐要素渲染字段值）。顺读前 3 个 FID（shp/gdb 0 起、gpkg 1 起），经 getFeatureAttributes
     * 复用句柄 LRU；按源文件指纹缓存（指纹变自动失效），空结果也缓存免反复读。
     * 取不到返回空串（调用方回退显示字段名）。与鸿蒙 VectorMetaCache.labelSampleOf 同构。
     */
    fun labelSampleOf(path: String, layerName: String, field: String): String {
        if (path.isEmpty() || field.isEmpty()) return ""
        val key = "$path|$layerName|$field"
        val mtime = sourceMtime(path)
        labelSampleCache[key]?.let { if (mtime > 0L && it.first == mtime) return it.second }
        var value = ""
        val openPath = VectorFormats.virtualPathOf(path)
        for (fid in 0L..2L) {
            if (value.isNotEmpty()) break
            val attrs = GdalVectorReader.getFeatureAttributes(openPath, fid, layerName) ?: continue
            for ((k, v) in attrs) {
                if (k.equals(field, ignoreCase = true) && v.trim().isNotEmpty()) {
                    value = v.trim()
                    break
                }
            }
        }
        labelSampleCache[key] = mtime to value
        return value
    }

    /**
     * 把一次 native 元数据写回文档层（只写已知项，绝不以未知覆盖既有好值）。
     * 返回是否有字段变化（供宿主决定要不要 save 一次）。mtime=0（stat 失败）时不写指纹。
     */
    fun applyMeta(info: LayerInfo, meta: VectorLayerMeta, mtime: Long): Boolean {
        var changed = false
        val path = info.path ?: ""
        val isKml = VectorFormats.isKml(path)
        if (!isKml) {
            val appGeom = when (meta.geomType) {
                1 -> 0
                2 -> 1
                3 -> 2
                else -> -1
            }
            if (appGeom >= 0 && info.geomType != appGeom) {
                info.geomType = appGeom
                changed = true
            }
        }
        if (meta.featureCount >= 0L && info.featureCount != meta.featureCount) {
            info.featureCount = meta.featureCount
            changed = true
        }
        if (meta.hasExtent && meta.extent.size == 4) {
            val old = info.extent
            if (old == null || old.size != 4 || !old.contentEquals(meta.extent)) {
                info.extent = meta.extent.copyOf()
                changed = true
            }
        }
        if (mtime > 0L && info.metaMtime != mtime) {
            info.metaMtime = mtime
            changed = true
        }
        return changed
    }

    /** 直读四至（kmz 走 /vsizip/ 虚拟文件，与既有消费点同口径） */
    private fun readExtent(path: String, layerName: String): DoubleArray? {
        val openPath = VectorFormats.virtualPathOf(path)
        val e = try {
            NativeLayerInfo.getLayerExtent(openPath, layerName)
        } catch (e: Exception) {
            null
        } ?: return null
        return if (e.size == 4) e else null
    }

    private fun findInfo(path: String, layerName: String): LayerInfo? {
        if (path.isEmpty()) return null
        val layers = DocumentManager.getInstance().getDocument()?.vectorLayers ?: return null
        return layers.firstOrNull { it.path == path && it.layerName == layerName }
    }
}