package com.zys.mobilemap.vector

import android.content.Context
import android.util.Log
import com.zys.mobilemap.doc.DocumentManager
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
import earth.worldwind.geom.Offset
import earth.worldwind.geom.Position
import earth.worldwind.layer.Layer
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.AbstractShape
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 本地矢量数据加载：按格式分发，产出可加入 WorldWindow 的图层列表。
 *  - shp：两条读取路径并存，由设置开关 [com.zys.mobilemap.doc.SystemConfig.shpUseGdal] 选定：
 *    默认 GDAL/OGR 读取并重投影到 WGS84（任意坐标系含 CGCS2000、北京54 等需基准转换的
 *    地理坐标系均能正确落位）；关闭时走 WorldWind 原生字节入口（原生不支持的投影仍自动回退 GDAL）；
 *  - dwg/dxf：WorldWind 无读取器，恒走 GDAL/OGR；
 *  - kml/kmz：WorldWind 原生解析优先（保留源文件配色/图标/地面覆盖物，为 LocalLayer.md
 *    明确要求的渲染效果，GDAL 通用要素读取无法保留），失败或超大文件时回退 GDAL。
 */
object LocalVectorLoader {

    private const val TAG = "LocalVectorLoader"

    /** 原生解析的 KML 文件大小上限：超过则直接走 GDAL（原生解析需整文读入+全文档建模，
     * 内存峰值约为文件十余倍，15MB 级 KML 极易 OOM，且失败后再回退相当于双倍消耗） */
    private const val NATIVE_KML_MAX_BYTES = 5L * 1024 * 1024

    /** 空间过滤生效的文件大小下限：小文件全量读取（平移/缩小后视野外要素仍在），
     * 大文件按屏幕四至限制读取量防 OOM（GDAL 路径平移不重载，过滤会造成要素缺失） */
    private const val SPATIAL_FILTER_MIN_BYTES = 6L * 1024 * 1024

    /** 矢量图层标记：拾取时据此过滤出矢量要素（底图/注记不参与点选） */
    const val VECTOR_LAYER_KEY = "isVectorLayer"

    /** 渲染对象 userProperty 键：源数据要素标识（shp 记录号 / GDAL FID），供属性读取与回写定位 */
    const val FEATURE_ID_KEY = "vectorFeatureId"

    /** 图层 userProperty 键：文档图层路径，点击后据此定位 LayerInfo 写回样式 */
    const val LAYER_PATH_KEY = "vectorLayerPath"

    /** 渲染对象 userProperty 键：要素属性表（字段名 -> 值），供属性表显示 */
    const val FEATURE_ATTRS_KEY = "vectorFeatureAttrs"

    /** 渲染对象 userProperty 键：KML/KMZ 要素 <description> HTML，供属性页 WebView 渲染（对齐 2.5.3） */
    const val FEATURE_DESC_KEY = "vectorFeatureDesc"

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

    /** 原生 shp 点要素选中高亮：同图标放大 1.4 倍 + 黄色着色 */
    private val highlightPlacemarkAttributes = PlacemarkAttributes().apply {
        imageScale = 1.4
        imageColor = Color(1f, 1f, 0f, 1f)
    }

    /** SQL 查询结果高亮样式：洋红半透明填充 + 洋红粗描边，与原图层默认/文档样式显著区分 */
    private const val QUERY_FILL_COLOR = "#59FF00FF"
    private const val QUERY_OUTLINE_COLOR = "#FFFF00FF"

    /**
     * 按文档图层信息加载矢量图层，加载失败抛异常由调用方提示。
     * extent 为当前屏幕四至（WGS84 [minLon,minLat,maxLon,maxLat]），
     * 仅对大文件生效：从源头控制读取量防 OOM；小文件全量读取，保证平移后要素不缺失。
     */
    suspend fun loadLayers(context: Context, layerInfo: LayerInfo, extent: DoubleArray? = null): List<Layer> {
        val path = layerInfo.path ?: throw IllegalArgumentException("图层路径为空")
        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException("文件不存在: $path")

        val style = layerInfo.vectorStyle?.takeIf { !it.isEmpty() }
        val filterExtent = extent?.takeIf { file.length() > SPATIAL_FILTER_MIN_BYTES }
        val layers = when (file.extension.lowercase(Locale.getDefault())) {
            "kml" -> loadKml(context, file, layerInfo.name, filterExtent, style)
            "kmz" -> {
                // 正常导入流程文档记录的是解压后的 kml 路径，此处兜底解压
                val result = VectorFileImporter.importFile(context, path)
                    ?: throw IllegalArgumentException("kmz 解压失败: ${file.name}")
                loadKml(context, File(result.path), layerInfo.name, filterExtent, style)
            }
            // shp 读取引擎由设置开关决定（默认 GDAL），两条路径并存不删除
            "shp" -> if (shpUseGdal()) loadWithGdal(file, layerInfo.name, filterExtent, style)
            else loadShpNative(file, layerInfo.name, filterExtent, style)
            // dwg/dxf 无 WorldWind 原生读取器，恒走 GDAL/OGR
            "dwg", "dxf" -> loadWithGdal(file, layerInfo.name, filterExtent, style)
            else -> throw IllegalArgumentException("不支持的矢量格式: ${file.extension}")
        }
        // 图层写入文档路径，点击拾取后据此定位 LayerInfo（样式写回）
        layers.forEach { it.putUserProperty(LAYER_PATH_KEY, path) }
        return layers
    }

    /**
     * KML：优先 WorldWind 原生解析（保留源文件配色），
     * 失败时回退 GDAL（LIBKML）读取。
     * WorldWind 的 KML 模型 Polygon.innerBoundaryIs 为单值（KML 规范允许多个内环），
     * 带多孔多边形的文件解析会抛 XmlException（Duplicate child innerBoundaryIs）导致整层失败；
     * GDAL 回退保留全部孔洞，但丢失源配色（按项目默认样式/文档样式渲染）。
     */
    private suspend fun loadKml(
        context: Context, file: File, name: String?, extent: DoubleArray?, style: VectorStyle?
    ): List<Layer> {
        // 大文件跳过原生解析直接走 GDAL（空间过滤限制读取量），避免整文建模的内存峰值
        if (file.length() > NATIVE_KML_MAX_BYTES) {
            Log.i(TAG, "KML 文件较大(${file.length() / 1024 / 1024}MB)，直接走 GDAL 读取: ${file.name}")
            return loadWithGdal(file, name, extent, style)
        }
        return try {
            loadKmlNative(context, file, style)
        } catch (e: Exception) {
            Log.w(TAG, "KML 原生解析失败，回退 GDAL 读取: ${file.name}", e)
            loadWithGdal(file, name, extent, style)
        }
    }

    /**
     * KML 原生解析 <Style>/<StyleMap>，按源文件配色渲染。
     * 开启拾取并打矢量图层标记；KML 转换仅为线/面设了高亮属性，点（Placemark）补放大高亮。
     * 样式覆盖仅支持整层（kml 无要素 id，单要素覆盖待二期自建解析补齐）。
     * 注：wwd 的 KML 管线不保留 Placemark 的 xml id，要素标识暂以名称/展示层反馈，
     * 后续属性表阶段再自建解析补齐。
     */
    private suspend fun loadKmlNative(context: Context, file: File, style: VectorStyle?): List<Layer> {
        val text = withContext(Dispatchers.IO) { file.readText() }
        val density = context.resources.displayMetrics.density
        val layers = KmlLayerFactory.createLayers(
            text,
            KmlLayerFactory.Options(density = density, renderLabelsForShapes = true)
        )
        if (layers.isEmpty()) throw IllegalArgumentException("KML 无可渲染内容: ${file.name}")
        // WorldWind 原生 KML 转换器只保留 name、丢弃 <description>，此处自建轻量 DOM 解析
        // 按 Placemark name 补齐 description，供点击要素后属性页以 WebView 渲染 HTML（对齐 2.5.3）
        val descriptions = withContext(Dispatchers.Default) { extractKmlDescriptions(text) }
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
                // KML 形状标注（renderLabelsForShapes）默认底边中点锚定，改为文字中心对齐几何中心
                if (r is Label) r.attributes.textOffset = Offset.center()
                // 按 displayName（= KML Placemark name）匹配附加 <description>
                val dn = r.displayName
                if (!dn.isNullOrEmpty()) descriptions[dn]?.let { r.putUserProperty(FEATURE_DESC_KEY, it) }
            }
        }
        return layers
    }

    /** shp 读取引擎开关：文档未加载时缺省按 GDAL 读取（新功能统一走 GDAL） */
    private fun shpUseGdal(): Boolean =
        DocumentManager.getInstance().getDocument()?.systemConfig?.shpUseGdal ?: true

    /**
     * SHP 原生路径（WorldWind 字节入口，设置关闭 GDAL 时使用）：
     * 读 .prj 判定坐标系——地理坐标/UTM/无 prj 用原生加载；其余投影坐标系（如 CGCS2000
     * 高斯-克吕格）原生会原样透传坐标导致错位，仍自动回退 GDAL 重投影。
     * 原生路径经 emitRecordRenderables 逐记录生成渲染对象，记录号（1 基）作为要素标识；
     * 注意与 GDAL 路径的 FID（0 基）相差 1，切换引擎后已保存的单要素样式会整体错位一位。
     * 样式覆盖按记录号区分单要素（featureStyles），标注取 DBF 属性。
     * 原生入口需整文件读入内存，不参与屏幕四至空间过滤。
     */
    private suspend fun loadShpNative(
        file: File, name: String?, extent: DoubleArray?, style: VectorStyle?
    ): List<Layer> {
        val dbf = findSidecar(file, "dbf")
        val prj = findSidecar(file, "prj")
        val cpg = findSidecar(file, "cpg")

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
                // 单要素样式优先，缺省继承整层样式（style 为 null 时 s 为 null，点要素仍应用默认图标）
                val s = style?.let { it.featureStyles[id] ?: it }
                // 点要素：应用标识图标（缺省默认针形），改善原 1px 白方块不可见/难点
                if (renderable is Placemark) applyPointIcon(renderable, s?.iconKey)
                if (s != null) {
                    // 仅线/面应用颜色样式（点已由图标承担）
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
     */
    private suspend fun loadWithGdal(file: File, name: String?, extent: DoubleArray?, style: VectorStyle?): List<Layer> = withContext(Dispatchers.Default) {
        val features = GdalVectorReader.readFeatures(file.absolutePath, extent)
            ?: throw IllegalStateException("GDAL 读取失败（格式不支持或数据为空）: ${file.name}")
        if (features.isEmpty()) throw IllegalStateException("矢量数据无可渲染要素: ${file.name}")
        Log.i(TAG, "GDAL 读取 ${features.size} 个要素: ${file.name}")

        val layer = RenderableLayer(name).apply {
            isPickEnabled = true
            putUserProperty(VECTOR_LAYER_KEY, true)
        }
        buildRenderables(layer, features, style)
        listOf(layer)
    }

    /**
     * SQL 查询结果图层：以醒目高亮样式叠加在原图层之上，仅呈现筛选命中的要素。
     * 不打矢量图层标记（[VECTOR_LAYER_KEY]）且关闭拾取——点击穿透到下方原始图层要素，
     * 弹出的仍是源数据属性与要素标识（可继续编辑样式），避免结果图层遮蔽交互。
     */
    fun buildQueryResultLayer(name: String?, features: List<VectorFeature>): RenderableLayer {
        val layer = RenderableLayer(name).apply { isPickEnabled = false }
        val style = VectorStyle().apply {
            fillColor = QUERY_FILL_COLOR
            outlineColor = QUERY_OUTLINE_COLOR
            outlineWidth = 3f
        }
        buildRenderables(layer, features, style)
        // 点要素由图标承担视觉，单独着色为查询高亮色（buildRenderables 仅对线/面应用填充色）
        val tint = parseColor(QUERY_OUTLINE_COLOR)
        for (r in layer) {
            if (r is Placemark) r.attributes.imageColor = tint
        }
        return layer
    }

    /**
     * 按要素列表构建渲染对象加入图层（矢量读取与 SQL 查询结果共用）：
     * 要素携带源数据 FID（写入 userProperty），供点击拾取后读取属性/回写定位；
     * 样式覆盖按 FID 区分单要素（featureStyles），标注取 GDAL 属性字段。
     */
    private fun buildRenderables(layer: RenderableLayer, features: List<VectorFeature>, style: VectorStyle?) {
        for (f in features) {
            // 单要素样式优先，缺省继承整层样式
            val s = style?.let { it.featureStyles[f.fid] ?: it }
            // KML/KMZ 经 GDAL(LIBKML) 时 <description> 常作为名为 description 的字段返回，
            // 单独取出供属性页 WebView 渲染，其余字段仍作键值属性表
            val descKey = f.fields.keys.firstOrNull { it.equals("description", ignoreCase = true) }
            val description = descKey?.let { f.fields[it]?.takeIf { v -> v.isNotBlank() } }
            val restFields = if (descKey != null) f.fields - descKey else f.fields
            // 属性表随行存储（GDAL 字段），点击后直接读取
            val attrs = restFields.takeIf { it.isNotEmpty() }
            when (f.geom) {
                "POINT" -> layer.addRenderable(
                    Placemark(Position.fromDegrees(f.y, f.x, 0.0), PlacemarkAttributes()).apply {
                        if (s != null) {
                            // 点标注：指定标注字段时以属性值作为标注文本，颜色/缩放随样式
                            labelTextOf(s.labelField) { f.fields[it] }?.let { label = it }
                            s.labelColor?.let { attributes.labelAttributes.textColor = parseColor(it) }
                            s.labelSize?.let { attributes.labelAttributes.scale = it.toDouble() }
                            if (s.labelOutline == true) attributes.labelAttributes.isOutlineEnabled = true
                            s.labelOutlineColor?.let { attributes.labelAttributes.outlineColor = parseColor(it) }
                        }
                        if (attrs != null) putUserProperty(FEATURE_ATTRS_KEY, attrs)
                        if (description != null) putUserProperty(FEATURE_DESC_KEY, description)
                        // 点要素应用标识图标（缺省默认针形）并据当前属性生成放大高亮：
                        // 置于末尾使高亮拷贝继承上面设置的标注样式；改善原 1px 白方块不可见/难点
                        applyPointIcon(this, s?.iconKey)
                    }.withFeatureId(f.fid)
                )
                "LINE" -> f.parts?.forEach { part ->
                    layer.addRenderable(Path(flattenToPositions(part), lineAttributes).apply {
                        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                        isFollowTerrain = true
                        // 关闭中间点细分（默认 64 会让每边插入最多 63 点，千级要素时顶点量数十倍放大致 OOM），
                        // 与 WorldWind 官方 KmlToRenderableConverter 一致
                        maximumNumEdgeIntervals = 0
                        highlightAttributes = highlightLineAttributes
                        if (s != null) applyShapeStyle(this, s)
                        if (attrs != null) putUserProperty(FEATURE_ATTRS_KEY, attrs)
                        if (description != null) putUserProperty(FEATURE_DESC_KEY, description)
                    }.withFeatureId(f.fid))
                }
                "POLYGON" -> {
                    val outer = f.outer
                    if (outer != null) {
                        val polygon = Polygon(flattenToPositions(outer), polygonAttributes).apply {
                            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                            isFollowTerrain = true
                            // 关闭中间点细分，避免 GLU 曲面细分顶点爆炸（详见 LINE 分支说明）
                            maximumNumEdgeIntervals = 0
                            highlightAttributes = highlightPolygonAttributes
                            if (s != null) applyShapeStyle(this, s)
                            if (attrs != null) putUserProperty(FEATURE_ATTRS_KEY, attrs)
                            if (description != null) putUserProperty(FEATURE_DESC_KEY, description)
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

    /**
     * 点要素应用标识图标：按 iconKey 取内置矢量图标（缺省默认针形），选中态用同图标放大 1.4 倍。
     * WorldWind 支持矢量 drawable（ImageDecoder 先 BitmapFactory 解码，矢量 XML 返回 null 后
     * 回退 AppCompatResources.getDrawable + Canvas 渲染位图）。改善原 imageSource=null 时
     * 仅画 1px 白方块（几乎不可见、拾取热区极小难点）的问题。
     */
    private fun applyPointIcon(placemark: Placemark, iconKey: String?) {
        val src = ImageSource.fromResource(FeatureIcons.resOf(iconKey))
        placemark.attributes.imageSource = src
        placemark.highlightAttributes = PlacemarkAttributes(placemark.attributes).apply { imageScale = 1.4 }
    }

    /** 标注文本：未设标注字段、字段不存在或值为空返回 null */
    private fun labelTextOf(labelField: String?, valueOf: (String) -> String?): String? {
        labelField ?: return null
        return valueOf(labelField)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 构造贴地标注，颜色/缩放/轮廓随样式；文字中心对齐几何中心（默认底边中点锚定会使文字偏上） */
    private fun makeLabel(position: Position, text: String, s: VectorStyle): Label =
        Label(position, text).apply {
            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            attributes.textOffset = Offset.center()
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

    /**
     * 解析 KML 文本提取各 Placemark 的 name -> description 映射（命名空间无关）。
     * WorldWind 原生转换器丢弃 <description>，此处自建轻量 DOM 解析补齐，
     * description 常含 HTML/CDATA，getTextContent 自动取其文本；重名以首个为准。
     */
    private fun extractKmlDescriptions(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // 关闭外部实体，避免 XXE（解析器不支持时忽略）
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val doc = factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            doc.documentElement.normalize()
            val nodes = doc.getElementsByTagName("*")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node !is Element || !isTag(node, "Placemark")) continue
                val name = textOfChild(node, "name")
                val desc = textOfChild(node, "description")
                if (name.isNotEmpty() && desc.isNotEmpty() && !map.containsKey(name)) map[name] = desc
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "KML description 解析失败", e)
            map
        }
    }

    /** 节点标签名匹配（忽略命名空间前缀与大小写） */
    private fun isTag(node: Node, expected: String): Boolean {
        var name = node.nodeName ?: return false
        val idx = name.indexOf(':')
        if (idx >= 0 && idx + 1 < name.length) name = name.substring(idx + 1)
        return name.equals(expected, ignoreCase = true)
    }

    /** 取父元素下首个指定标签的直接子元素 */
    private fun firstChildElement(parent: Element, tag: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n is Element && isTag(n, tag)) return n
        }
        return null
    }

    /** 取父元素下指定标签子元素的文本内容（trim） */
    private fun textOfChild(parent: Element, tag: String): String =
        firstChildElement(parent, tag)?.textContent?.trim() ?: ""
}
