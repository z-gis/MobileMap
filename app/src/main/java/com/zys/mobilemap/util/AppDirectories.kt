package com.zys.mobilemap.util

import android.content.Context
import android.os.Environment
import java.io.File

class AppDirectories {

    companion object {

        const val ROOT_DIR_NAME: String = "调查宝"

        const val LAYER_DIR_NAME: String = "layer"

        const val MAP_DIR_NAME: String = "map"

        const val MEDIA_DIR_NAME: String = "Media"

        const val SAMPLE_DIR_NAME: String = "sample"

        const val CONFIG_DIR_NAME: String = "config"

        const val TILES_DIR_NAME: String = "tiles"

        fun getRootDir(context: Context?): File {
            val base = getBaseDir(context)
            return File(base, ROOT_DIR_NAME)
        }

        fun getLayerDir(context: Context?): File? {
            return ensureDir(File(getRootDir(context), LAYER_DIR_NAME))
        }

        fun getMapDir(context: Context?): File? {
            return ensureDir(File(getRootDir(context), MAP_DIR_NAME))
        }

        fun getMediaDir(context: Context?): File? {
            return ensureDir(File(getRootDir(context), MEDIA_DIR_NAME))
        }

        fun getSampleDir(context: Context?): File? {
            return ensureDir(File(getRootDir(context), SAMPLE_DIR_NAME))
        }

        fun getConfigDir(context: Context?): File? {
            return ensureDir(File(getRootDir(context), CONFIG_DIR_NAME))
        }

        fun getTileCacheDir(context: Context?): File? {
            return ensureDir(File(getRootDir(context), TILES_DIR_NAME))
        }

        fun getSampleDatabaseFile(context: Context?, sourcePath: String?): File {
            val sampleDir = getSampleDir(context)
            val baseName = deriveBaseName(sourcePath)
            return File(sampleDir, baseName + ".db")
        }

        fun ensureAll(context: Context?) {
            if (context == null) {
                return
            }
            ensureDir(getRootDir(context))
            ensureDir(getLayerDir(context))
            ensureDir(getMapDir(context))
            ensureDir(getMediaDir(context))
            ensureDir(getSampleDir(context))
            ensureDir(getConfigDir(context))
            ensureDir(getTileCacheDir(context))
        }

        fun deriveBaseName(path: String?): String? {
            var name = if (path == null) "" else File(path).getName()
            val dot = name.lastIndexOf('.')
            if (dot > 0) {
                name = name.substring(0, dot)
            }
            name = name.trim { it <= ' ' }
            if (name.isEmpty()) {
                name = "sample"
            }
            return sanitizeFileName(name)
        }

        private fun getBaseDir(context: Context?): File {
            if (context == null) {
                return File(".")
            }
            val externalRoot = Environment.getExternalStorageDirectory()
            if (externalRoot != null) {
                return externalRoot
            }
            val internal = context.getFilesDir()
            return if (internal != null) internal else File(".")
        }

        private fun ensureDir(dir: File?): File? {
            if (dir != null && !dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        private fun sanitizeFileName(value: String?): String? {
            if (value == null || value.isEmpty()) {
                return "sample"
            }
            return value.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
        }
    }
}
