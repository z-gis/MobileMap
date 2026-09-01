package com.zys.mobilemap.raster

import android.content.Context
import earth.worldwind.WorldWindow
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.image.ImageConfig
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTile
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.Level
import earth.worldwind.util.LevelSet
import earth.worldwind.util.Tile
import earth.worldwind.util.TileFactory
import com.zys.mobilemap.util.AppDirectories
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 基于 GDAL 的本地 GeoTIFF 瓦片图层（旧版 GdalRasterLayer 的 KMP 版迁移）。
 *
 * 构造时经 JNI 读取栅格 WGS84 四至，按四至构造独立 [LevelSet] 瓦片金字塔；
 * 渲染所需瓦片由 [createTile] 提供：优先命中已解析图源与磁盘缓存，
 * 未命中时提交后台线程池经 [RasterEx.nativeRenderTile] 生成透明 PNG 瓦片，
 * 生成完成后回填图源并请求重绘。
 *
 * 透明度由 C++ 层保证：瓦片与栅格范围求交集裁剪，交集外像素 alpha=0，
 * 范围外不再出现旧版的不透明黑色矩形。
 */
class GdalRasterLayer(
    context: Context,
    private val rasterPath: String,
    private val worldWindow: WorldWindow
) : RenderableLayer(File(rasterPath).name), TileFactory {

    /** 栅格 WGS84 四至 [minLon, minLat, maxLon, maxLat] */
    val envelope: DoubleArray

    private val tileCacheDir: File

    private val resolvedSources = ConcurrentHashMap<String, ImageSource>()
    private val liveTiles = ConcurrentHashMap<String, WeakReference<ImageTile>>()
    private val inProgress = ConcurrentHashMap.newKeySet<String>()

    init {
        require(rasterPath.isNotBlank()) { "missing raster path" }
        envelope = RasterEx.nativeGetRasterEnvelope(rasterPath)
            ?: throw IllegalArgumentException("无法解析栅格范围: $rasterPath")
        val (minLon, minLat, maxLon, maxLat) = envelope
        require(maxLon > minLon && maxLat > minLat) { "empty raster sector: $rasterPath" }

        tileCacheDir = prepareCacheDir(context, rasterPath)

        isPickEnabled = false

        val sector = Sector.fromDegrees(minLat, minLon, maxLat - minLat, maxLon - minLat)
        // 顶层瓦片分辨率上限：栅格范围很大时若首级瓦片跨度取整个范围，
        // 瓦片像素分辨率远超栅格自身分辨率，重采样后接近空白导致低级别不可见；
        // 首级跨度封顶 FIRST_DELTA_MAX（约 8.7m/px @256），并按分辨率展开级数。
        val span = maxOf(maxLat - minLat, maxLon - minLon)
        val delta = span.coerceIn(FIRST_DELTA_MIN, FIRST_DELTA_MAX)
        var numLevels = 1
        var d = delta
        while (d / TILE_SIZE > BEST_DEG_PER_PIXEL && numLevels < NUM_LEVELS_MAX) {
            d /= 2.0
            numLevels++
        }
        val levelSet = LevelSet(
            sector = sector,
            tileOrigin = sector,
            firstLevelDelta = Location.fromDegrees(delta, delta),
            numLevels = numLevels,
            tileWidth = TILE_SIZE,
            tileHeight = TILE_SIZE
        )
        val surfaceImage = TiledSurfaceImage(this, levelSet).also {
            it.imageOptions = ImageOptions(ImageConfig.RGBA_8888)
        }
        addRenderable(surfaceImage)
    }

    /**
     * 瓦片三级来源：内存图源缓存 → 磁盘瓦片文件 → 后台异步生成。
     * 生成中的瓦片图源为 null，渲染端暂用父级瓦片纹理兜底。
     */
    override fun createTile(sector: Sector, level: Level, row: Int, column: Int): Tile {
        trimCachesIfNeeded()
        val tile = ImageTile(sector, level, row, column)
        val key = tile.tileKey
        liveTiles[key] = WeakReference(tile)

        resolvedSources[key]?.let {
            tile.imageSource = it
            return tile
        }

        val tileFile = tileFile(key)
        if (tileFile.isFile) {
            val source = ImageSource.fromFile(tileFile)
            resolvedSources[key] = source
            tile.imageSource = source
            return tile
        }

        scheduleTileGeneration(tile, tileFile, key)
        return tile
    }

    override val contentType: String get() = "raster/$rasterPath"

    private fun scheduleTileGeneration(tile: ImageTile, output: File, key: String) {
        if (!inProgress.add(key)) return
        try {
            tileExecutor.execute {
                try {
                    val sector = tile.sector
                    val generated = RasterEx.nativeRenderTile(
                        rasterPath,
                        sector.minLongitude.inDegrees,
                        sector.minLatitude.inDegrees,
                        sector.maxLongitude.inDegrees,
                        sector.maxLatitude.inDegrees,
                        TILE_SIZE, TILE_SIZE,
                        output.absolutePath
                    )
                    // 无交集瓦片不落盘；重新进入可视范围时再次走生成流程（开销很小）
                    if (!generated || !output.isFile) return@execute

                    val source = ImageSource.fromFile(output)
                    resolvedSources[key] = source
                    trimCachesIfNeeded()
                    liveTiles[key]?.get()?.imageSource = source
                    worldWindow.requestRedraw()
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "瓦片生成失败 key=$key", t)
                } finally {
                    inProgress.remove(key)
                }
            }
        } catch (e: Exception) {
            inProgress.remove(key)
        }
    }

    private fun tileFile(key: String) = File(tileCacheDir, key.replace('.', '_') + ".png")

    private fun trimCachesIfNeeded() {
        if (liveTiles.size > MAX_LIVE_TILE_REFS) {
            var removed = 0
            for ((key, ref) in liveTiles) {
                if (ref.get() == null) {
                    if (liveTiles.remove(key, ref)) removed++
                }
                if (liveTiles.size <= MAX_LIVE_TILE_REFS || removed >= 256) break
            }
        }
        if (resolvedSources.size > MAX_RESOLVED_SOURCE_CACHE) {
            val target = resolvedSources.size - MAX_RESOLVED_SOURCE_CACHE
            var removed = 0
            for (key in resolvedSources.keys) {
                if (liveTiles[key]?.get() == null) {
                    if (resolvedSources.remove(key) != null) removed++
                }
                if (removed >= target) break
            }
        }
    }

    companion object {
        private const val TAG = "GdalRasterLayer"
        private const val TILE_SIZE = 256
        private const val NUM_LEVELS_MAX = 18

        /** 首级瓦片跨度下限/上限（度）：上限保证顶层瓦片分辨率不劣于约 8.7m/px */
        private const val FIRST_DELTA_MIN = 0.0001
        private const val FIRST_DELTA_MAX = 0.02

        /** 最优分辨率约 0.3m/px（≈2.7e-6 度/像素），超出后继续细分无收益 */
        private const val BEST_DEG_PER_PIXEL = 0.0000027
        private const val MAX_RESOLVED_SOURCE_CACHE = 3000
        private const val MAX_LIVE_TILE_REFS = 6000

        /** 全部栅格图层共用的瓦片生成线程池（重投影渲染耗时，限制并发） */
        private val tileExecutor = Executors.newFixedThreadPool(2)

        /** 瓦片缓存目录：/调查宝/tiles/raster/<路径哈希> */
        private fun prepareCacheDir(context: Context, rasterPath: String): File {
            val base = AppDirectories.getTileCacheDir(context) ?: context.cacheDir
            val dir = File(base, "raster/" + Integer.toHexString(rasterPath.hashCode()))
            if (!dir.exists() && !dir.mkdirs()) {
                android.util.Log.w(TAG, "瓦片缓存目录创建失败: ${dir.absolutePath}")
            }
            return dir
        }
    }
}
