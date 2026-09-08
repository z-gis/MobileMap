@file:OptIn(LowLevelCacheApi::class)

package com.zys.mobilemap.cache

import earth.worldwind.layer.cache.CachePolicy
import earth.worldwind.layer.cache.LowLevelCacheApi
import earth.worldwind.layer.cache.TileStore
import earth.worldwind.layer.source.TileBlob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件式瓦片缓存：每个瓦片一个独立文件，保存于 /调查宝/tiles/<图源>/<级别>/ 下，
 * 级别为目录，文件名为 x_y.tile（扩展名不使用图片格式，避免 Android 系统读取图片显示）。
 *
 * 不启用容量淘汰与过期重验证（[CachePolicy.UNBOUNDED]），瓦片永久保留直至手动清理。
 */
class FileTileStore(private val dir: File) : TileStore {

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    override val cachePolicy: CachePolicy = CachePolicy.UNBOUNDED

    override suspend fun readTile(z: Int, x: Int, y: Int): TileBlob? = withContext(Dispatchers.IO) {
        val file = tileFile(z, x, y)
        if (file.exists()) TileBlob(file.readBytes()) else null
    }

    override suspend fun writeTile(z: Int, x: Int, y: Int, blob: TileBlob) = withContext(Dispatchers.IO) {
        // 负结果（服务器确认无此瓦片）不落盘，保持缓存目录干净
        if (blob.isEmpty) return@withContext
        val target = tileFile(z, x, y)
        // 级别目录可能尚未创建；parentFile 理论上非空，防御性判空避免空指针
        val parent = target.parentFile ?: return@withContext
        if (!parent.exists()) parent.mkdirs()
        val tmp = File(parent, target.name + ".tmp")
        tmp.writeBytes(blob.bytes)
        if (target.exists()) target.delete()
        // 原子替换，避免渲染中断产生半截文件
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    override suspend fun deleteTile(z: Int, x: Int, y: Int) {
        withContext(Dispatchers.IO) { tileFile(z, x, y).delete() }
    }

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        dir.walkTopDown().filter { it.isFile && it.extension == TILE_EXT }.sumOf { it.length() }
    }

    /** 级别目录：/调查宝/tiles/<图源>/<级别>/ */
    private fun levelDir(z: Int): File = File(dir, z.toString())

    private fun tileFile(z: Int, x: Int, y: Int): File = File(levelDir(z), "${x}_${y}.$TILE_EXT")

    companion object {
        /** 瓦片文件扩展名：非图片格式，避免被系统相册/图库读取显示 */
        const val TILE_EXT = "tile"
    }
}
