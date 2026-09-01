package com.zys.mobilemap.raster

/**
 * GeoTIFF 栅格 JNI 桥接（native-lib：jni_raster.cpp）。
 * 四至计算与瓦片渲染均在 C++ 层完成（GDAL 重投影 + 透明 PNG）。
 */
object RasterEx {

    /**
     * 读取栅格 WGS84 四至 [minLon, minLat, maxLon, maxLat]；
     * 投影坐标系经坐标变换重投影后取极值；解析失败返回 null。
     */
    external fun nativeGetRasterEnvelope(path: String): DoubleArray?

    /**
     * 将指定地理范围的栅格重采样渲染为带透明通道的 PNG 瓦片文件。
     * 瓦片范围与栅格范围无交集时返回 false（不落盘）。
     */
    external fun nativeRenderTile(
        rasterPath: String,
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        width: Int,
        height: Int,
        outPath: String
    ): Boolean
}
