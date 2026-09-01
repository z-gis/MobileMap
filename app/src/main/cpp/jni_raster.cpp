#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>
#include <android/log.h>
#include <gdal/gdalwarper.h>

#include "gdal/gdal_priv.h"
#include "gdal/gdal_alg.h"
#include "gdal/ogr_spatialref.h"

#define RASTER_LOG_TAG "GdalRaster"

// ==================== GeoTIFF 栅格：WGS84 四至 + 瓦片渲染（RasterEx） ====================

/**
 * 计算栅格数据集的 WGS84 四至：沿边界采样（覆盖旋转地理变换与投影弯曲），
 * 投影坐标系经坐标变换重投影到 WGS84 后取极值（与矢量四至同策略，
 * 避免米制包络被当经纬度使用）。
 */
static bool RasterWgs84Extent(GDALDataset *ds,
                              double &minLon, double &minLat,
                              double &maxLon, double &maxLat) {
    double gt[6];
    if (ds->GetGeoTransform(gt) != CE_None) {
        return false;
    }
    const int w = ds->GetRasterXSize();
    const int h = ds->GetRasterYSize();
    if (w <= 0 || h <= 0) {
        return false;
    }

    // 边界采样点（像素坐标 → 源坐标系坐标）
    const int STEPS = 16;
    std::vector<double> xs;
    std::vector<double> ys;
    xs.reserve(static_cast<size_t>(STEPS + 1) * 4);
    ys.reserve(static_cast<size_t>(STEPS + 1) * 4);
    auto addPoint = [&](double px, double py) {
        xs.push_back(gt[0] + px * gt[1] + py * gt[2]);
        ys.push_back(gt[3] + px * gt[4] + py * gt[5]);
    };
    for (int i = 0; i <= STEPS; i++) {
        const double t = static_cast<double>(i) / STEPS;
        addPoint(t * w, 0.0);
        addPoint(t * w, static_cast<double>(h));
        addPoint(0.0, t * h);
        addPoint(static_cast<double>(w), t * h);
    }

    // 投影坐标系（如高斯-克吕格）重投影到 WGS84
    const OGRSpatialReference *srcSrs = ds->GetSpatialRef();
    if (srcSrs != nullptr && !srcSrs->IsGeographic()) {
        OGRSpatialReference *src = srcSrs->Clone();
        src->SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
        OGRSpatialReference dst;
        dst.SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
        dst.SetWellKnownGeogCS("WGS84");
        OGRCoordinateTransformation *ct = OGRCreateCoordinateTransformation(src, &dst);
        src->Release();
        if (ct == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, RASTER_LOG_TAG,
                                "RasterWgs84Extent: 无法创建坐标变换（proj.db 未配置？）");
            return false;
        }
        const bool ok = ct->Transform(xs.size(), xs.data(), ys.data());
        OGRCoordinateTransformation::DestroyCT(ct);
        if (!ok) {
            return false;
        }
    }

    minLon = 180.0;
    maxLon = -180.0;
    minLat = 90.0;
    maxLat = -90.0;
    for (size_t i = 0; i < xs.size(); i++) {
        if (!std::isfinite(xs[i]) || !std::isfinite(ys[i])) continue;
        minLon = std::min(minLon, xs[i]);
        maxLon = std::max(maxLon, xs[i]);
        minLat = std::min(minLat, ys[i]);
        maxLat = std::max(maxLat, ys[i]);
    }

    minLon = std::max(-180.0, minLon);
    maxLon = std::min(180.0, maxLon);
    minLat = std::max(-90.0, minLat);
    maxLat = std::min(90.0, maxLat);
    return minLon < maxLon && minLat < maxLat;
}

/** 构造 WGS84 的 WKT（传统经纬度轴序） */
static bool BuildWgs84Wkt(std::string &out) {
    OGRSpatialReference wgs;
    wgs.SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
    wgs.SetWellKnownGeogCS("WGS84");
    char *wktRaw = nullptr;
    if (wgs.exportToWkt(&wktRaw) != OGRERR_NONE || wktRaw == nullptr) {
        return false;
    }
    out = wktRaw;
    CPLFree(wktRaw);
    return true;
}

// ==================== 数据集/重投影 VRT 缓存（瓦片提速） ====================
// 旧实现每个瓦片都重新打开数据集并 GDALAutoCreateWarpedVRT（重投影开销最大），
// 改为按路径缓存打开的数据集 + WGS84 VRT，同一栅格的全部瓦片共用。
// GDAL 数据集非线程安全，全局互斥锁串行化访问（瓦片生成本身为 CPU 密集，竞争低）。

struct RasterEntry {
    GDALDataset *ds = nullptr;
    GDALDataset *warped = nullptr;
    double wgt[6] = {0, 0, 0, 0, 0, 0};
    int bandCount = 0;
    bool palette = false;
    int alphaBand = -1;
    bool envelopeOk = false;
    double minLon = 0, minLat = 0, maxLon = 0, maxLat = 0;
    bool ready = false;
};

static std::mutex g_rasterMutex;
static std::map<std::string, RasterEntry *> g_rasterCache;
static std::vector<std::string> g_rasterOrder; // LRU：头部最旧，尾部最新
static const size_t MAX_RASTER_CACHE = 4;

static void FreeRasterEntry(RasterEntry *e) {
    if (e->warped != nullptr) GDALClose(e->warped);
    if (e->ds != nullptr) GDALClose(e->ds);
    delete e;
}

/** 持锁调用：取或创建缓存条目（含打开数据集、四至、重投影 VRT、波段属性解析） */
static RasterEntry *GetRasterEntryLocked(const std::string &path) {
    GDALAllRegister();

    auto it = g_rasterCache.find(path);
    if (it != g_rasterCache.end()) {
        // 命中：移到 LRU 尾部
        auto &order = g_rasterOrder;
        order.erase(std::remove(order.begin(), order.end(), path), order.end());
        order.push_back(path);
        return it->second;
    }

    // 淘汰最旧条目
    while (g_rasterCache.size() >= MAX_RASTER_CACHE && !g_rasterOrder.empty()) {
        const std::string &oldest = g_rasterOrder.front();
        auto oldIt = g_rasterCache.find(oldest);
        if (oldIt != g_rasterCache.end()) {
            FreeRasterEntry(oldIt->second);
            g_rasterCache.erase(oldIt);
        }
        g_rasterOrder.erase(g_rasterOrder.begin());
    }

    RasterEntry *e = new RasterEntry();
    e->ds = (GDALDataset *) GDALOpenEx(path.c_str(), GDAL_OF_RASTER | GDAL_OF_READONLY,
                                       nullptr, nullptr, nullptr);
    if (e->ds == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, RASTER_LOG_TAG,
                            "GetRasterEntry: GDALOpenEx 失败 path=%s", path.c_str());
        delete e;
        return nullptr;
    }
    e->bandCount = e->ds->GetRasterCount();
    e->envelopeOk = RasterWgs84Extent(e->ds, e->minLon, e->minLat, e->maxLon, e->maxLat);

    if (e->envelopeOk) {
        std::string wkt;
        if (BuildWgs84Wkt(wkt)) {
            e->warped = (GDALDataset *) GDALAutoCreateWarpedVRT(
                    e->ds, nullptr, wkt.c_str(), GRA_Bilinear, 0.0, nullptr);
            if (e->warped != nullptr
                    && e->warped->GetGeoTransform(e->wgt) == CE_None
                    && e->bandCount >= 1 && e->wgt[1] != 0.0 && e->wgt[5] != 0.0) {
                e->ready = true;
            }
        }
    }

    GDALRasterBand *firstBand = e->ds->GetRasterBand(1);
    e->palette = firstBand != nullptr
            && firstBand->GetColorInterpretation() == GCI_PaletteIndex;
    if (e->bandCount >= 4
            && e->ds->GetRasterBand(4)->GetColorInterpretation() == GCI_AlphaBand) {
        e->alphaBand = 4;
    } else if (e->bandCount == 2
            && e->ds->GetRasterBand(2)->GetColorInterpretation() == GCI_AlphaBand) {
        e->alphaBand = 2;
    }

    g_rasterCache[path] = e;
    g_rasterOrder.push_back(path);
    return e;
}

extern "C"
JNIEXPORT jdoubleArray JNICALL
Java_com_zys_mobilemap_raster_RasterEx_nativeGetRasterEnvelope(JNIEnv *env,
                                                               jobject thiz,
                                                               jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    std::string rasterPath(p);
    env->ReleaseStringUTFChars(path, p);
    if (rasterPath.empty()) {
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_rasterMutex);
    RasterEntry *e = GetRasterEntryLocked(rasterPath);
    if (e == nullptr || !e->envelopeOk) {
        __android_log_print(ANDROID_LOG_WARN, RASTER_LOG_TAG,
                            "nativeGetRasterEnvelope: 四至计算失败 path=%s", rasterPath.c_str());
        return nullptr;
    }

    jdouble extent[4] = {e->minLon, e->minLat, e->maxLon, e->maxLat};
    jdoubleArray result = env->NewDoubleArray(4);
    env->SetDoubleArrayRegion(result, 0, 4, extent);
    return result;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_zys_mobilemap_raster_RasterEx_nativeRenderTile(JNIEnv *env,
                                                        jobject thiz,
                                                        jstring rasterPath,
                                                        jdouble jMinLon,
                                                        jdouble jMinLat,
                                                        jdouble jMaxLon,
                                                        jdouble jMaxLat,
                                                        jint width,
                                                        jint height,
                                                        jstring outPath) {
    const char *sp = env->GetStringUTFChars(rasterPath, nullptr);
    std::string srcPath(sp);
    env->ReleaseStringUTFChars(rasterPath, sp);
    const char *op = env->GetStringUTFChars(outPath, nullptr);
    std::string dstPath(op);
    env->ReleaseStringUTFChars(outPath, op);
    if (srcPath.empty() || dstPath.empty() || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    if (jMinLon >= jMaxLon || jMinLat >= jMaxLat) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_rasterMutex);
    RasterEntry *entry = GetRasterEntryLocked(srcPath);
    if (entry == nullptr || !entry->ready) {
        return JNI_FALSE;
    }
    GDALDataset *ds = entry->ds;
    GDALDataset *warped = entry->warped;
    const double *wgt = entry->wgt;
    const int bandCount = entry->bandCount;

    // 瓦片范围与栅格范围求交集；无交集跳过该瓦片。
    // 透明度修复关键①：仅渲染交集区域，交集之外的输出像素保持 alpha=0（全透明），
    // 旧版整瓦片重投影后范围外像素为不透明黑色，导致地图出现黑色矩形。
    const double ixMinLon = std::max(jMinLon, entry->minLon);
    const double ixMaxLon = std::min(jMaxLon, entry->maxLon);
    const double ixMinLat = std::max(jMinLat, entry->minLat);
    const double ixMaxLat = std::min(jMaxLat, entry->maxLat);
    if (ixMinLon >= ixMaxLon || ixMinLat >= ixMaxLat) {
        return JNI_FALSE;
    }

    // 输出缓冲：alpha 初始 0（范围外全透明）
    const size_t pxCount = static_cast<size_t>(width) * height;
    std::vector<uint8_t> red(pxCount, 0);
    std::vector<uint8_t> green(pxCount, 0);
    std::vector<uint8_t> blue(pxCount, 0);
    std::vector<uint8_t> alpha(pxCount, 0);

    // 交集 → 瓦片像素窗口（行 0 对应 maxLat）
    const double tileDegW = jMaxLon - jMinLon;
    const double tileDegH = jMaxLat - jMinLat;
    int dstX0 = static_cast<int>(std::floor((ixMinLon - jMinLon) / tileDegW * width));
    int dstX1 = static_cast<int>(std::ceil((ixMaxLon - jMinLon) / tileDegW * width));
    int dstY0 = static_cast<int>(std::floor((jMaxLat - ixMaxLat) / tileDegH * height));
    int dstY1 = static_cast<int>(std::ceil((jMaxLat - ixMinLat) / tileDegH * height));
    dstX0 = std::max(0, std::min(dstX0, width));
    dstX1 = std::max(0, std::min(dstX1, width));
    dstY0 = std::max(0, std::min(dstY0, height));
    dstY1 = std::max(0, std::min(dstY1, height));
    const int dstW = dstX1 - dstX0;
    const int dstH = dstY1 - dstY0;
    if (dstW <= 0 || dstH <= 0) {
        return JNI_FALSE;
    }

    // 交集 → 重投影 VRT 像素窗口（VRT 地理变换为 WGS84 度）
    int srcX0 = static_cast<int>(std::floor((ixMinLon - wgt[0]) / wgt[1]));
    int srcX1 = static_cast<int>(std::ceil((ixMaxLon - wgt[0]) / wgt[1]));
    int srcY0 = static_cast<int>(std::floor((wgt[3] - ixMaxLat) / (-wgt[5])));
    int srcY1 = static_cast<int>(std::ceil((wgt[3] - ixMinLat) / (-wgt[5])));
    srcX0 = std::max(0, std::min(srcX0, warped->GetRasterXSize()));
    srcX1 = std::max(0, std::min(srcX1, warped->GetRasterXSize()));
    srcY0 = std::max(0, std::min(srcY0, warped->GetRasterYSize()));
    srcY1 = std::max(0, std::min(srcY1, warped->GetRasterYSize()));
    const int srcW = srcX1 - srcX0;
    const int srcH = srcY1 - srcY0;
    if (srcW <= 0 || srcH <= 0) {
        return JNI_FALSE;
    }

    // 交集子窗口临时缓冲（读出后按行拷贝到瓦片输出缓冲的偏移位置）
    const size_t subCount = static_cast<size_t>(dstW) * dstH;
    std::vector<uint8_t> tmpR(subCount, 0);
    std::vector<uint8_t> tmpG(subCount, 0);
    std::vector<uint8_t> tmpB(subCount, 0);
    std::vector<uint8_t> tmpA(subCount, 255);

    auto readBand = [&](int bandIndex, uint8_t *dstBuf) -> bool {
        GDALRasterBand *band = warped->GetRasterBand(bandIndex);
        if (band == nullptr) {
            return false;
        }
        // 非 Byte 类型由 GDAL 内部转换为 Byte（高位数据按值域截断）
        return band->RasterIO(GF_Read, srcX0, srcY0, srcW, srcH,
                              dstBuf, dstW, dstH, GDT_Byte, 0, 0, nullptr) == CE_None;
    };

    // 波段属性（调色板/alpha 波段）在缓存条目创建时一次性解析；
    // alpha 波段直接读取重投影后的真实 alpha 波段（透明度修复关键②：
    // 旧版把整个 4 波段数据集重投影到单波段数据集，实际取到的是红波段当 alpha）
    const bool palette = entry->palette;
    const int alphaBand = entry->alphaBand;

    bool readOk;
    if (palette) {
        // 调色板栅格：读索引后按色表展开为 RGB（alpha 取色表条目）
        readOk = readBand(1, tmpR.data());
        if (readOk) {
            GDALColorTable *table = ds->GetRasterBand(1)->GetColorTable();
            for (size_t i = 0; i < subCount; i++) {
                // GetColorEntry 返回含 alpha（c4）的原始色表条目
                const GDALColorEntry *ce =
                        table != nullptr ? table->GetColorEntry(tmpR[i]) : nullptr;
                if (ce != nullptr) {
                    tmpR[i] = static_cast<uint8_t>(ce->c1);
                    tmpG[i] = static_cast<uint8_t>(ce->c2);
                    tmpB[i] = static_cast<uint8_t>(ce->c3);
                    tmpA[i] = static_cast<uint8_t>(ce->c4);
                } else {
                    tmpA[i] = 0;
                }
            }
        }
    } else if (bandCount == 1 || alphaBand == 2) {
        readOk = readBand(1, tmpR.data());
        if (readOk) {
            tmpG = tmpR;
            tmpB = tmpR;
            if (alphaBand == 2) {
                readOk = readBand(2, tmpA.data());
            }
        }
    } else {
        readOk = readBand(1, tmpR.data())
                && readBand(2, tmpG.data())
                && readBand(3, tmpB.data());
        if (readOk && alphaBand == 4) {
            readOk = readBand(4, tmpA.data());
        }
    }
    if (!readOk) {
        return JNI_FALSE;
    }

    // nodata 掩膜 → alpha=0（RGB 三波段均定义 nodata 且像素全部命中时置透明；
    // 单波段灰度按单波段判定）
    if (!palette) {
        const int checkBands = (bandCount == 1 || alphaBand == 2) ? 1 : std::min(3, bandCount);
        bool hasNoData[3] = {false, false, false};
        double noData[3] = {0, 0, 0};
        int defined = 0;
        for (int b = 0; b < checkBands; b++) {
            int has = FALSE;
            noData[b] = warped->GetRasterBand(b + 1)->GetNoDataValue(&has);
            hasNoData[b] = (has == TRUE) && noData[b] >= 0.0 && noData[b] <= 255.0;
            if (hasNoData[b]) defined++;
        }
        if (defined == checkBands) {
            const auto nd0 = static_cast<uint8_t>(std::lround(noData[0]));
            const auto nd1 = checkBands > 1 ? static_cast<uint8_t>(std::lround(noData[1])) : nd0;
            const auto nd2 = checkBands > 2 ? static_cast<uint8_t>(std::lround(noData[2])) : nd0;
            for (size_t i = 0; i < subCount; i++) {
                if (tmpR[i] == nd0 && tmpG[i] == nd1 && tmpB[i] == nd2) {
                    tmpA[i] = 0;
                }
            }
        }
    }

    // 子窗口拷贝到瓦片输出缓冲（窗口外保持透明）
    for (int y = 0; y < dstH; y++) {
        const size_t srcOff = static_cast<size_t>(y) * dstW;
        const size_t dstOff = static_cast<size_t>(dstY0 + y) * width + dstX0;
        std::memcpy(red.data() + dstOff, tmpR.data() + srcOff, dstW);
        std::memcpy(green.data() + dstOff, tmpG.data() + srcOff, dstW);
        std::memcpy(blue.data() + dstOff, tmpB.data() + srcOff, dstW);
        std::memcpy(alpha.data() + dstOff, tmpA.data() + srcOff, dstW);
    }

    // RGBA 内存数据集 → PNG（带透明通道）
    GDALDriver *memDriver = GetGDALDriverManager()->GetDriverByName("MEM");
    GDALDriver *pngDriver = GetGDALDriverManager()->GetDriverByName("PNG");
    if (memDriver == nullptr || pngDriver == nullptr) {
        return JNI_FALSE;
    }

    GDALDataset *memDs = memDriver->Create("", width, height, 4, GDT_Byte, nullptr);
    if (memDs == nullptr) {
        return JNI_FALSE;
    }

    bool writeOk = true;
    writeOk = writeOk && memDs->GetRasterBand(1)->RasterIO(
            GF_Write, 0, 0, width, height, red.data(), width, height, GDT_Byte, 0, 0, nullptr) == CE_None;
    writeOk = writeOk && memDs->GetRasterBand(2)->RasterIO(
            GF_Write, 0, 0, width, height, green.data(), width, height, GDT_Byte, 0, 0, nullptr) == CE_None;
    writeOk = writeOk && memDs->GetRasterBand(3)->RasterIO(
            GF_Write, 0, 0, width, height, blue.data(), width, height, GDT_Byte, 0, 0, nullptr) == CE_None;
    writeOk = writeOk && memDs->GetRasterBand(4)->RasterIO(
            GF_Write, 0, 0, width, height, alpha.data(), width, height, GDT_Byte, 0, 0, nullptr) == CE_None;
    if (writeOk) {
        memDs->GetRasterBand(1)->SetColorInterpretation(GCI_RedBand);
        memDs->GetRasterBand(2)->SetColorInterpretation(GCI_GreenBand);
        memDs->GetRasterBand(3)->SetColorInterpretation(GCI_BlueBand);
        memDs->GetRasterBand(4)->SetColorInterpretation(GCI_AlphaBand);
    }

    bool copyOk = false;
    if (writeOk) {
        GDALDataset *outDs = pngDriver->CreateCopy(dstPath.c_str(), memDs, FALSE,
                                                   nullptr, nullptr, nullptr);
        copyOk = (outDs != nullptr);
        if (outDs != nullptr) {
            GDALClose(outDs);
        }
    }

    GDALClose(memDs);
    return copyOk ? JNI_TRUE : JNI_FALSE;
}
