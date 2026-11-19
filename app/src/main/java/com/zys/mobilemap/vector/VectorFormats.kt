package com.zys.mobilemap.vector

import java.util.Locale

/**
 * 数据格式按扩展名分类的能力主表（与鸿蒙 entry/vector/VectorFormats.ets 逐项对齐）。
 *
 * 读取能力由内核静态链接的 GDAL/OGR 驱动决定：
 *  · 矢量：shp（依赖同目录 dbf/shx/prj/cpg）、kml、kmz（zip 内含 kml）、zip（shp 压缩包）、
 *          dwg/dxf（libopencad + DXF 驱动，只读）、gpkg（单文件 SQLite + R-tree 索引）；
 *  · 栅格：tif/tiff（GeoTIFF）、img（ERDAS HFA）；
 *  · 可回写属性：shp/gpkg/geojson/kml/kmz 等（dwg 等只读驱动写回必失败，界面据此隐藏「编辑属性」）。
 *
 * 散落各处的扩展名 if/else 一律改调本表，新增/调整格式只改这里。
 */
object VectorFormats {

    /** 矢量页签导入筛选 */
    val VECTOR_EXTS = listOf("shp", "kml", "kmz", "zip", "dwg", "dxf", "gpkg", "geojson", "json", "gdb")

    /** 栅格页签导入筛选 */
    val RASTER_EXTS = listOf("tif", "tiff", "img")

    /** 同名前缀附属文件（选 shp 时一并勾选，或压缩包内自带）；与 entry_pc DeskFormats 同口径 */
    val SIDE_CAR_EXTS = listOf("dbf", "shx", "prj", "cpg", "sbn", "sbx", "qix", "xml", "tfw", "ovr", "aux.xml", "hdr")

    /** 压缩包（导入时先解压再找主文件） */
    val ARCHIVE_EXTS = listOf("zip", "kmz")

    /** 属性可写回源文件的格式 */
    val WRITABLE_EXTS = listOf("shp", "gpkg", "geojson", "json", "kml", "kmz", "dxf", "gdb")

    /** 支持整层样式覆盖编辑的格式（kml 自带样式，改文档样式意义有限，故不列入） */
    val STYLE_EDITABLE_EXTS = listOf("shp", "gpkg", "dxf", "geojson", "json", "gdb")

    /** 支持单要素样式覆盖的格式（GDAL 索引矢量、有稳定 FID；较整层表更窄） */
    val FEATURE_STYLE_EXTS = listOf("shp", "gpkg", "gdb")

    /** 支持 SQL 属性查询的格式 */
    val SQL_QUERYABLE_EXTS = listOf("shp", "gpkg", "gdb")

    /** 支持几何编辑（分割/删除/合并，写前自动备份）的格式 */
    val GEOM_EDITABLE_EXTS = listOf("gdb", "shp")

    /** CAD 类格式：无坐标系信息时坐标不可信（内核回 error，须终止加载而非按 WGS84 错位显示） */
    val CAD_EXTS = listOf("dwg", "dxf")

    /** KML 系：名称标注受 systemConfig.showKmlLabel 额外门控 */
    val KML_EXTS = listOf("kml", "kmz")

    /**
     * 删除图层源文件时的同干附属后缀（带点）。较 [SIDE_CAR_EXTS] 更宽——清理须覆盖历史遗留附属
     * （.fbn/.fbx/.ain/.aih/.atx/.ixs/.mxs/.shp.xml/.rrd 等），漏删会残留垃圾文件，故独立成表。
     */
    val DELETE_SHP_EXTS = listOf(
        ".shp", ".shx", ".dbf", ".prj", ".cpg", ".sbn", ".sbx", ".fbn", ".fbx",
        ".ain", ".aih", ".atx", ".ixs", ".mxs", ".qix", ".shp.xml", ".xml"
    )
    val DELETE_RASTER_EXTS = listOf(".tfw", ".prj", ".ovr", ".rrd", ".aux.xml")
    val DELETE_CAD_EXTS = listOf(".prj")

    /** 取小写扩展名（无扩展名返回空串）；入参可为路径或文件名，兼容 / 与 \ 分隔 */
    fun extOf(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    fun isVector(path: String): Boolean = VECTOR_EXTS.contains(extOf(path))

    fun isRaster(path: String): Boolean = RASTER_EXTS.contains(extOf(path))

    fun isArchive(path: String): Boolean = ARCHIVE_EXTS.contains(extOf(path))

    fun isKml(path: String): Boolean = KML_EXTS.contains(extOf(path))

    fun isCad(path: String): Boolean = CAD_EXTS.contains(extOf(path))

    /** 是否 File Geodatabase 数据集目录（.gdb，path 为目录而非单文件） */
    fun isGdb(path: String): Boolean = extOf(path) == "gdb"

    /** 属性写回是否可能成功（仅按扩展名预判，真正成败仍以内核返回为准） */
    fun attributeWritable(path: String): Boolean = WRITABLE_EXTS.contains(extOf(path))

    /** 是否允许整层样式覆盖编辑 */
    fun styleEditable(path: String): Boolean = STYLE_EDITABLE_EXTS.contains(extOf(path))

    /** 是否支持单要素样式覆盖（须配合有效 FID 使用） */
    fun featureStyleEditable(path: String): Boolean = FEATURE_STYLE_EXTS.contains(extOf(path))

    /** 是否支持 SQL 属性查询 */
    fun sqlQueryable(path: String): Boolean = SQL_QUERYABLE_EXTS.contains(extOf(path))

    /** 是否支持几何编辑（分割/删除/合并） */
    fun geomEditable(path: String): Boolean = GEOM_EDITABLE_EXTS.contains(extOf(path))

    /**
     * kmz 是 zip 容器，GDAL 须走 /vsizip/ 虚拟文件系统打开内部 kml；其余格式原样返回。
     * 全工程凡需把路径交 GDAL 打开处统一经此函数，禁止再散落拼前缀。
     */
    fun virtualPathOf(path: String): String = if (extOf(path) == "kmz") "/vsizip/$path" else path

    /** 由类型标记导出导入筛选扩展名（页签 → 文件选择器 filter） */
    fun pickFilter(type: String): List<String> {
        // 选择器必须放行伴随文件，否则 shp 族在系统选择器里根本选不中（只剩孤 .shp，GDAL 缺 shx 打开即失败）
        val base = if (type == LayerType.RASTER) RASTER_EXTS else VECTOR_EXTS
        return base + SIDE_CAR_EXTS
    }

    /** 扩展名 → 中文格式名（图层列表副标题） */
    fun labelOf(path: String): String = when (extOf(path)) {
        "shp" -> "Shapefile"
        "kml" -> "KML"
        "kmz" -> "KMZ"
        "zip" -> "Shapefile 压缩包"
        "gpkg" -> "GeoPackage"
        "gdb" -> "File Geodatabase"
        "dwg" -> "AutoCAD DWG"
        "dxf" -> "AutoCAD DXF"
        "geojson", "json" -> "GeoJSON"
        "tif", "tiff" -> "GeoTIFF"
        "img" -> "ERDAS IMG"
        else -> extOf(path).uppercase(Locale.ROOT)
    }
}

/** 图层类型常量（与 LayerInfo.type / document.json 取值一致） */
object LayerType {
    const val VECTOR = "vector"
    const val RASTER = "raster"
}