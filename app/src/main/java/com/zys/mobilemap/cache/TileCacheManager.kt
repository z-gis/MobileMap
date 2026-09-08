@file:OptIn(LowLevelCacheApi::class)

package com.zys.mobilemap.cache

import android.content.Context
import android.util.Log
import com.zys.mobilemap.util.AppDirectories
import earth.worldwind.layer.Layer
import earth.worldwind.layer.cache.CachedTileSource
import earth.worldwind.layer.cache.LowLevelCacheApi
import earth.worldwind.layer.cache.TileSourceFactoryAdapter
import earth.worldwind.layer.cache.UrlTemplateImageTileSource
import earth.worldwind.layer.cache.buildTileSourceImageSource
import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.layer.mercator.MercatorTileFactory
import earth.worldwind.layer.mercator.WebMercatorImageLayer
import earth.worldwind.layer.source.TileSource
import earth.worldwind.render.image.ImageSource
import java.io.File

/** 图源数据默认最大级别（多数在线底图源为 18 级；超出由祖先瓦片拉伸兜底） */
private const val DEFAULT_SOURCE_MAX_LEVEL = 18

/**
 * [MercatorTileFactory] 适配层：将 [TileSourceFactoryAdapter]（瓦片缓存管线）接入
 * Mercator 图层的渲染管线。createTile 沿用接口默认实现（行序转换后调用 getImageSource），
 * getImageSource 经 [TileSource] 读取（先查文件缓存，未命中走网络并写回）。
 *
 * [sourceMaxLevel] 图源数据最大级别：超过时回退到对应祖先瓦片图源（上采样），
 * 避免请求不存在的超级别瓦片导致"此级别无影像"。
 */
class CachedMercatorTileFactory(
    private val adapter: TileSourceFactoryAdapter,
    private val imageFormat: String,
    private val sourceMaxLevel: Int = DEFAULT_SOURCE_MAX_LEVEL,
) : MercatorTileFactory {

    override val contentType = "Web Mercator"

    override fun getImageSource(x: Int, y: Int, z: Int): ImageSource {
        // 超过图源最大级别：映射到祖先瓦片（x、y 各右移级差），不再请求空瓦片；
        // 缓存管线以调整后的级别存取，祖先瓦片同样落盘复用。
        if (z > sourceMaxLevel) {
            val shift = z - sourceMaxLevel
            return buildTileSourceImageSource(adapter.source, sourceMaxLevel, x shr shift, y shr shift, imageFormat)
        }
        return buildTileSourceImageSource(adapter.source, z, x, y, imageFormat)
    }

    /** 释放底层网络资源（ktor 客户端） */
    fun close() = adapter.source.close()
}

/**
 * 瓦片离线缓存管理：创建带文件缓存的 WebMercator 图层。
 * 瓦片在下载渲染过程中保存到 /调查宝/tiles/<图源>/级别/，文件名为 x_y.tile。
 */
object TileCacheManager {

    /**
     * 创建带文件缓存的底图/注记图层。
     *
     * @param context        上下文，用于定位 /调查宝/tiles 目录
     * @param urlTemplate    瓦片网址模板（Token 占位符已解析）
     * @param name           图层名称，同时作为缓存子目录名（分图源设置目录）
     * @param imageFormat    图片格式（image/jpeg、image/png 等）
     * @param sourceMaxLevel 图源数据最大级别；金字塔以此为最后一级，
     *                       相机继续放大时末级瓦片纹理拉伸显示（无限放大，不再出现"此级别无影像"）
     */
    fun createCachedLayer(
        context: Context,
        urlTemplate: String,
        name: String,
        imageFormat: String,
        sourceMaxLevel: Int = DEFAULT_SOURCE_MAX_LEVEL,
    ): WebMercatorImageLayer {
        val cacheDir = File(AppDirectories.getTileCacheDir(context), sanitizeDirName(name))
        val store = FileTileStore(cacheDir)
        Log.i(TAG, "创建缓存图层[$name]，瓦片缓存目录: ${cacheDir.absolutePath}")
        val source = CachedTileSource(UrlTemplateImageTileSource(urlTemplate), store)
        val tileFactory = CachedMercatorTileFactory(
            TileSourceFactoryAdapter(source, imageFormat), imageFormat, sourceMaxLevel
        )
        // maxZoom 对齐图源最大级别：末级不再细分，纹理拉伸即"无限放大"（同上一版行为）
        val tiledSurfaceImage = MercatorTiledImageLayer.buildTiledSurfaceImage(
            tileFactory, maxZoom = sourceMaxLevel
        )
        return WebMercatorImageLayer(urlTemplate, imageFormat, false, name, tiledSurfaceImage)
    }

    /**
     * 图层移除后释放其瓦片源持有的网络资源。
     */
    fun closeLayerSource(layer: Layer?) {
        val tiledSurfaceImage = (layer as? MercatorTiledImageLayer)?.tiledSurfaceImage ?: return
        (tiledSurfaceImage.tileFactory as? CachedMercatorTileFactory)?.close()
    }

    private fun sanitizeDirName(name: String): String {
        val clean = name.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
        return clean.ifEmpty { "unknown" }
    }

    private const val TAG = "TileCacheManager"
}
