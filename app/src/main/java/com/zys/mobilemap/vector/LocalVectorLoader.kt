package com.zys.mobilemap.vector

import android.content.Context
import android.util.Log
import com.zys.mobilemap.doc.LayerInfo
import com.zys.mobilemap.doc.VectorStyle
import earth.worldwind.formats.kml.KmlLayerFactory
import earth.worldwind.formats.shapefile.DBaseFile
import earth.worldwind.formats.shapefile.PrjFile
import earth.worldwind.formats.shapefile.Shapefile
import earth.worldwind.formats.shapefile.ShapefileLayerFactory
import earth.worldwind.formats.shapefile.ShapefileRecord
import earth.worldwind.formats.shapefile.ShapefileShapeConfiguration
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.layer.Layer
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.shape.AbstractShape
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 本地矢量数据加载：按格式分发，产出可加入 WorldWindow 的图层列表。
 *  - kml/kmz：WorldWind 原生解析，按源文件配色渲染（坐标恒为 WGS84，无需 GDAL）；
 *  - shp：原生优先（地理坐标/UTM/无 prj），其余投影走 GDAL 重投影到 WGS84；
 *  - dwg/dxf：WorldWind 无读取器，走 GDAL。
 */
object LocalVectorLoader {

    private const val TAG = "LocalVectorLoader"

    /** 矢量图层标记：拾取时据此过滤出矢量要素（底图/注记不参与点选） */
    const val VECTOR_LAYER_KEY = "isVectorLayer"

    /** 渲染对象 userProperty 键：源数据要素标识（shp 记录号 / GDAL FID），供属性读取与回写定位 */
    const val FEATURE_ID_KEY = "vectorFeatureId"

    /** 图层 userProperty 键：文档图层路径，点击后据此定位 LayerInfo 写回样式 */
    const val LAYER_PATH_KEY = "vectorLayerPath"

    /** 渲染对象 userProperty 键：要素属性表（字段名 -> 值），供属性表显示 */
    const val FEATURE_ATTRS_KEY = "vectorFeatureAttrs"

    /** 多边形默认样式：半透明填充 + 白描边 */
    private val polygonAttributes = ShapeAttributes().apply {
        isDrawInterior = true
        interiorColor = Color(0.29f, 0.56f, 0.89f, 0.4f)
        isDrawOutline = true
        outlineColor = Color(1f, 1f, 1f, 1f)
    }

    /** 线默认样式：纯色贴地 */
    private val lineAttributes = ShapeAttributes().apply {
        isDrawOutline = true
        outlineColor = Color(0.9f, 0.42f, 0.2f, 1f)
    }

    /** 选中高亮样式（面/线/点）：黄色区分选中态 */
    private val highlightPolygonAttributes = ShapeAttributes().apply {
        isDrawInterior = true
        interiorColor = Color(1f, 1f, 0f, 0.45f)
        isDrawOutline = true
        outlineColor = Color(1f, 1f, 0f, 1f)
        outlineWidth = 3f
    }

    private val highlightLineAttributes = ShapeAttributes().apply {
        isDrawOutline = true
        outlineColor = Color(1f, 1f, 0f, 1f)
        outlineWidth = 3f
    }

    private val highlightPlacemarkAttributes = PlacemarkAttributes().apply {
        imageScale = 1.4
        imageColor = Color(1f, 1f, 0f, 1f)
    }

    /**
     * 按文档图层信息加载矢量图层，加载失败抛异常由调用方提示。
     * extent 为当前屏幕四至（WGS84 [minLon,minLat,maxLon,maxLat]），
     * GDAL 路径仅读取该范围内的要素（屏幕中心点要素及其相邻要素），从源头控制读取量防 OOM。
     */
    suspend fun loadLayers(context: Context, layerInfo: LayerInfo, extent: DoubleArray? = null): List<Layer> {
        val path = layerInfo.path ?: throw IllegalArgumentException("图层路径为空")
        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException("文件不存在: $path")

        val style = layerInfo.vectorStyle?.takeIf { !it.isEmpty() }
        val layers = when (file.extension.lowercase(Locale.getDefault())) {
            "kml" -> loadKml(context, file, style)
            "kmz" -> {
                // 正常导入流程文档记录的是解压后的 kml 路径，此处兜底解压
                val result = VectorFileImporter.importFile(context, path)
                    ?: throw IllegalArgumentException("kmz 解压失败: ${file.name}")
                loadKml(context, File(result.path), style)
            }
            "shp" -> loadShp(file, layerInfo.name, extent, style)
            "dwg", "dxf" -> loadWithGdal(file, layerInfo.name, extent, style)
            else -> throw IllegalArgumentException("不支持的矢量格式: ${file.extension}")
        }
        // 图层写入文档路径，点击拾取后据此定位 LayerInfo（样式写回）
        layers.forEach { it.putUserProperty(LAYER_PATH_KEY, path) }
        return layers
    }

    /**
     * KML：原生解析 <Style>/<StyleMap>，按源文件配色渲染。
     * 开启拾取并打矢量图层标记；KML 转换仅为线/面设了高亮属性，点（Placemark）补放大高亮。
     * 样式覆盖仅支持整层（kml 无要素 id，单要素覆盖待二期自建解析补齐）。
     * 注：wwd 的 KML 管线不保留 Placemark 的 xml id，要素标识暂以名称/展示层反馈，
     * 后续属性表阶段再自建解析补齐。
     */
    private suspend fun loadKml(context: Context, file: File, style: VectorStyle?): List<Layer> {
        val text = withContext(Dispatchers.IO) { file.readText() }
        val density = context.resources.displayMetrics.density
        val layers = KmlLayerFactory.createLayers(
            text,
            KmlLayerFactory.Options(density = density, renderLabelsForShapes = true)
        )
        if (layers.isEmpty()) throw IllegalArgumentException("KML 无可渲染内容: ${file.name}")
        for (layer in layers) {
            layer.isPickEnabled = true
            layer.putUserProperty(VECTOR_LAYER_KEY, true)
            for (r in layer) {
                if (r is AbstractShape) {
                    // KML 转换器将 isPickInterior 置 false（仅轮廓可拾取），
                    // 导致点击要素内部无拾取对象；恢复整体可拾取。
                    r.attributes.isPickInterior = true
                    // KML 转换器的高亮仅复制源配色加粗描边（无样式时为红边白填充），
                    // 统一替换为项目黄色高亮样式。
                    r.highlightAttributes = if (r is Polygon) highlightPolygonAttributes else highlightLineAttributes
                    // 文档整层样式覆盖（优先级高于源文件样式）
                    if (style != null) applyShapeStyle(r, style)
                }
                if (r is Placemark && r.highlightAttributes == null) {
                    r.highlightAttributes = PlacemarkAttributes(r.attributes).apply {
                        imageScale = r.attributes.imageScale * 1.4
                    }
                }
            }
        }
        return layers
    }

    /**
     * SHP：读 .prj 判定坐标系——地理坐标/UTM/无 prj 用原生字节入口加载；
     * 其余投影坐标系（如 CGCS2000 高斯-克吕格）原生会原样透传坐标导致错位，改走 GDAL 重投影。
     * 原生路径经 emitRecordRenderables 逐记录生成渲染对象，记录号作为要素标识（后期属性表/编辑定位）。
     * 样式覆盖按记录号区分单要素（featureStyles），标注取 DBF 属性。
     */
    private suspend fun loadShp(file: File, name: String?, extent: DoubleArray?, style: VectorStyle?): List<Layer> {
        val dbf = findSidecar(file, "dbf")
        val prj = findSidecar(file, "prj")
        val cpg = findSidecar(file, "cpg")

        //
        val prjBytes = prj?.let { withContext(Dispatchers.IO) { it.readBytes() } }
        val prjFile = prjBytes?.let { runCatching { PrjFile(it.decodeToString()) }.getOrNull() }
        val needGdal = prjFile != null &&
                prjFile.isProjectedCoordinateSystem && prjFile.projection !is PrjFile.Projection.Utm
        if (needGdal) {
            Log.i(TAG, "shp 使用原生不支持的投影坐标系，走 GDAL 重投影: ${file.name}")
            return loadWithGdal(file, name, extent, style)
        }

        return withContext(Dispatchers.Default) {
            val shpBytes = file.readBytes()
            val dbfFile = dbf?.let {
                runCatching { DBaseFile(it.readBytes(), cpgText = cpg?.readText()) }.getOrNull()
            }
            val shapefile = Shapefile(shpBytes, projection = prjFile, attributes = dbfFile)
            val layer = RenderableLayer(name).apply {
                isPickEnabled = true
                putUserProperty(VECTOR_LAYER_KEY, true)
            }
            ShapefileLayerFactory.emitRecordRenderables(shapefile, ::defaultShapeConfig) { record, renderable ->
                val id = record.recordNumber.toLong()
                renderable.putUserProperty(FEATURE_ID_KEY, id)
                // 属性表随行存储，点击后直接读取（DBF 字段值转文本）
                if (record.attributes.isNotEmpty()) {
                    renderable.putUserProperty(FEATURE_ATTRS_KEY,
                        LinkedHashMap(record.attributes.mapValues { it.value?.toString() ?: "" }))
                }
                if (style != null) {
                    // 单要素样式优先，缺省继承整层样式；仅线/面应用颜色样式（点保持默认）
                    val s = style.featureStyles[id] ?: style
                    if (renderable is AbstractShape) applyShapeStyle(renderable, s)
                    // 标注：按标注字段取 DBF 属性，置于要素质心（线/面）
                    val text = labelTextOf(s.labelField) { record.attributes[it]?.toString() }
                    if (text != null) {
                        centroidOf(record)?.let { layer.addRenderable(makeLabel(it, text, s)) }
                    }
                }
                layer.addRenderable(renderable)
            }
            listOf(layer)
        }
    }

    /**
     * GDAL 路径：读取要素（已重投影 WGS84）构建渲染图层；
     * extent 非空时仅读取屏幕四至范围内的要素。
     * 要素携带源数据 FID（写入 userProperty），供点击拾取后读取属性/回写定位。
     * 样式覆盖按 FID 区分单要素，标注取 GDAL 属性字段。
     */
    private suspend fun loadWithGdal(file: File, name: String?, extent: DoubleArray?, style: VectorStyle?): List<Layer> = withContext(Dispatchers.Default) {
        val features = GdalVectorReader.readFeatures(file.absolutePath, extent)
            ?: throw IllegalStateException("GDAL 读取失败（格式不支持或数据为空）: ${file.name}")
        if (features.isEmpty()) throw IllegalStateException("矢量数据无可渲染要素: ${file.name}")

        val layer = RenderableLayer(name).apply {
            isPickEnabled = true
            putUserProperty(VECTOR_LAYER_KEY, true)
        }
        for (f in features) {
            // 单要素样式优先，缺省继承整层样式
            val s = style?.let { it.featureStyles[f.fid] ?: it }
            // 属性表随行存储（GDAL 字段），点击后直接读取
            val attrs = f.fields.takeIf { it.isNotEmpty() }
            when (f.geom) {
                "POINT" -> layer.addRenderable(
                    Placemark(Position.fromDegrees(f.y, f.x, 0.0), PlacemarkAttributes()).apply {
                        highlightAttributes = highlightPlacemarkAttributes
                        if (s != null) {
                            // 点标注：指定标注字段时以属性值作为标注文本，颜色/缩放随样式
                            labelTextOf(s.labelField) { f.fields[it] }?.let { label = it }
                            s.labelColor?.let { attributes.labelAttributes.textColor = parseColor(it) }
                            s.labelSize?.let { attributes.labelAttributes.scale = it.toDouble() }
                            if (s.labelOutline == true) attributes.labelAttributes.isOutlineEnabled = true
                            s.labelOutlineColor?.let { attributes.labelAttributes.outlineColor = parseColor(it) }
                        }
                        if (attrs != null) putUserProperty(FEATURE_ATTRS_KEY, attrs)
                    }.withFeatureId(f.fid)
                )
                "LINE" -> f.parts?.forEach { part ->
                    layer.addRenderable(Path(flattenToPositions(part), lineAttributes).apply {
                        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                        isFollowTerrain = true
                        highlightAttributes = highlightLineAttributes
                        if (s != null) applyShapeStyle(this, s)
                        if (attrs != null) putUserProperty(FEATURE_ATTRS_KEY, attrs)
                    }.withFeatureId(f.fid))
                }
                "POLYGON" -> {
                    val outer = f.outer
                    if (outer != null) {
                        val polygon = Polygon(flattenToPositions(outer), polygonAttributes).apply {
                            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                            isFollowTerrain = true
                            highlightAttributes = highlightPolygonAttributes
                            if (s != null) applyShapeStyle(this, s)
                            if (attrs != null) putUserProperty(FEATURE_ATTRS_KEY, attrs)
                        }
                        f.holes?.forEach { hole -> polygon.addBoundary(flattenToPositions(hole)) }
                        layer.addRenderable(polygon.withFeatureId(f.fid))
                    }
                }
            }
            // 线/面标注：取属性字段文本置于要素质心（点已由 Placemark 标注承担）
            if (s != null && f.geom != "POINT") {
                val text = labelTextOf(s.labelField) { f.fields[it] }
                val flat = f.outer ?: f.parts?.firstOrNull()
                if (text != null && flat != null) {
                    layer.addRenderable(makeLabel(centroidOfFlat(flat), text, s))
                }
            }
        }
        listOf(layer)
    }

    /** 渲染对象写入源数据要素标识 */
    private fun <T : earth.worldwind.render.Renderable> T.withFeatureId(fid: Long): T = apply {
        putUserProperty(FEATURE_ID_KEY, fid)
    }

    /** 解析 #AARRGGBB 十六进制颜色，格式非法时回退白色 */
    private fun parseColor(hex: String): Color =
        runCatching { Color.fromHexString(hexString = hex, argb = true) }
            .getOrElse { Color(1f, 1f, 1f, 1f) }

    /**
     * 应用线/面样式覆盖：基于现有样式拷贝（避免污染多要素共享的样式实例），
     * 仅覆盖设置过的字段，未设置字段保持源样式。
     */
    private fun applyShapeStyle(shape: AbstractShape, s: VectorStyle) {
        val a = ShapeAttributes(shape.attributes)
        s.fillColor?.let { a.interiorColor = parseColor(it) }
        s.fillOpacity?.let { a.interiorColor.alpha = it.coerceIn(0f, 1f) }
        s.outlineColor?.let { a.outlineColor = parseColor(it) }
        s.outlineWidth?.let { a.outlineWidth = it }
        shape.attributes = a
    }

    /** 标注文本：未设标注字段、字段不存在或值为空返回 null */
    private fun labelTextOf(labelField: String?, valueOf: (String) -> String?): String? {
        labelField ?: return null
        return valueOf(labelField)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 构造贴地标注，颜色/缩放/轮廓随样式 */
    private fun makeLabel(position: Position, text: String, s: VectorStyle): Label =
        Label(position, text).apply {
            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            s.labelColor?.let { attributes.textColor = parseColor(it) }
            s.labelSize?.let { attributes.scale = it.toDouble() }
            if (s.labelOutline == true) attributes.isOutlineEnabled = true
            s.labelOutlineColor?.let { attributes.outlineColor = parseColor(it) }
        }

    /** shp 记录质心：取全部 part 坐标均值（标注定位用，不追求几何精确质心） */
    private fun centroidOf(record: ShapefileRecord): Position? {
        var lon = 0.0
        var lat = 0.0
        var count = 0
        for (part in record.parts) {
            var i = 0
            while (i + 1 < part.size) {
                lon += part[i]
                lat += part[i + 1]
                count++
                i += 2
            }
        }
        if (count == 0) return null
        return Position.fromDegrees(lat / count, lon / count, 0.0)
    }

    /** 摊平坐标数组质心 [lon0,lat0,lon1,lat1,…] */
    private fun centroidOfFlat(flat: DoubleArray): Position {
        var lon = 0.0
        var lat = 0.0
        var i = 0
        var count = 0
        while (i + 1 < flat.size) {
            lon += flat[i]
            lat += flat[i + 1]
            count++
            i += 2
        }
        if (count == 0) return Position.fromDegrees(0.0, 0.0, 0.0)
        return Position.fromDegrees(lat / count, lon / count, 0.0)
    }

    /**
     * 原生 shp 默认样式回调：多边形半透明填充+白描边、线纯色贴地、点默认 Placemark，
     * 均携带黄色高亮样式（选中态）。
     */
    private fun defaultShapeConfig(record: ShapefileRecord): ShapefileShapeConfiguration? {
        return when {
            record.isPointType || record.isMultiPointType ->
                ShapefileShapeConfiguration(
                    placemarkAttributes = PlacemarkAttributes(),
                    highlightPlacemarkAttributes = highlightPlacemarkAttributes
                )
            record.isPolylineType ->
                ShapefileShapeConfiguration(attributes = lineAttributes, highlightAttributes = highlightLineAttributes)
            record.isPolygonType ->
                ShapefileShapeConfiguration(attributes = polygonAttributes, highlightAttributes = highlightPolygonAttributes)
            else -> ShapefileShapeConfiguration()
        }
    }

    /** 附属文件查找（忽略大小写）：与 shp 同名的 dbf/prj/cpg */
    private fun findSidecar(shp: File, ext: String): File? {
        val base = shp.nameWithoutExtension
        val dir = shp.parentFile ?: return null
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.nameWithoutExtension.equals(base, ignoreCase = true) &&
                    it.extension.equals(ext, ignoreCase = true)
        }
    }

    /** 摊平数组 [lon0,lat0,lon1,lat1,…] -> Position 列表 */
    private fun flattenToPositions(flat: DoubleArray): List<Position> {
        val list = ArrayList<Position>(flat.size / 2)
        var i = 0
        while (i + 1 < flat.size) {
            list.add(Position.fromDegrees(flat[i + 1], flat[i], 0.0))
            i += 2
        }
        return list
    }
}
