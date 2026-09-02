#include <jni.h>
#include <algorithm>
#include <string>
#include <cstring>
#include <cctype>
#include <android/log.h>

#include "gdal/ogrsf_frmts.h"
#include "gdal/gdal_priv.h"
#include "gdal/cpl_conv.h"

// ==================== 图层四至与字段名（LayerManageDialog / MainActivity） ====================

/**
 * 图层四至计算（矢量优先，栅格兜底），供 LayerManageDialog 与 MainActivity 共用。
 */
static bool computeLayerExtent(const char *p, double extent[4]) {
    bool ok = false;

    // 矢量数据：OGR 读取第一个图层的四至（支持 shp、kml，kmz 走 /vsizip/ 前缀）
    GDALDataset *ds = (GDALDataset *) GDALOpenEx(p, GDAL_OF_VECTOR | GDAL_OF_READONLY,
                                                 nullptr, nullptr, nullptr);
    if (ds != nullptr) {
        OGRLayer *layer = ds->GetLayer(0);
        if (layer != nullptr) {
            OGREnvelope ext;
            if (layer->GetExtent(&ext, TRUE) == OGRERR_NONE) {
                extent[0] = ext.MinX;
                extent[1] = ext.MinY;
                extent[2] = ext.MaxX;
                extent[3] = ext.MaxY;
                ok = true;

                // GetExtent 返回图层源坐标系下的包络；投影坐标系（如高斯-克吕格）下为米制，
                // 需重投影到 WGS84，否则上层把米当经纬度缩放/过滤导致视角与加载错位。
                // 四角全变换后取极值，兼容轴序翻转情形。
                OGRSpatialReference *srs = layer->GetSpatialRef();
                if (srs != nullptr && !srs->IsGeographic()) {
                    OGRSpatialReference *src = srs->Clone();
                    src->SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
                    OGRSpatialReference dst;
                    dst.SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
                    dst.SetWellKnownGeogCS("WGS84");
                    OGRCoordinateTransformation *ct = OGRCreateCoordinateTransformation(src, &dst);
                    src->Release();
                    if (ct != nullptr) {
                        double xs[4] = {ext.MinX, ext.MaxX, ext.MinX, ext.MaxX};
                        double ys[4] = {ext.MinY, ext.MinY, ext.MaxY, ext.MaxY};
                        if (ct->Transform(4, xs, ys)) {
                            extent[0] = std::min(std::min(xs[0], xs[1]), std::min(xs[2], xs[3]));
                            extent[2] = std::max(std::max(xs[0], xs[1]), std::max(xs[2], xs[3]));
                            extent[1] = std::min(std::min(ys[0], ys[1]), std::min(ys[2], ys[3]));
                            extent[3] = std::max(std::max(ys[0], ys[1]), std::max(ys[2], ys[3]));
                        }
                        OGRCoordinateTransformation::DestroyCT(ct);
                    } else {
                        __android_log_print(ANDROID_LOG_ERROR, "LayerInfo",
                                            "四至重投影失败：无法创建坐标变换（proj.db 未配置？），返回源坐标系四至");
                    }
                }
            }
        }
        GDALClose(ds);
    }

    // 栅格数据：GeoTransform 计算四至（tif/tiff）
    if (!ok) {
        ds = (GDALDataset *) GDALOpenEx(p, GDAL_OF_RASTER | GDAL_OF_READONLY,
                                        nullptr, nullptr, nullptr);
        if (ds != nullptr) {
            double gt[6];
            if (ds->GetGeoTransform(gt) == CE_None) {
                int w = ds->GetRasterXSize();
                int h = ds->GetRasterYSize();
                // 四角（含旋转项）
                double xs[4] = {gt[0],
                                gt[0] + w * gt[1],
                                gt[0] + h * gt[2],
                                gt[0] + w * gt[1] + h * gt[2]};
                double ys[4] = {gt[3],
                                gt[3] + w * gt[4],
                                gt[3] + h * gt[5],
                                gt[3] + w * gt[4] + h * gt[5]};

                // 投影坐标系（如高斯-克吕格）下 GeoTransform 为米制，
                // 需重投影到 WGS84 再取极值（同矢量分支策略），否则缩放定位错位。
                const OGRSpatialReference *srs = ds->GetSpatialRef();
                if (srs != nullptr && !srs->IsGeographic()) {
                    OGRSpatialReference *src = srs->Clone();
                    src->SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
                    OGRSpatialReference dst;
                    dst.SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
                    dst.SetWellKnownGeogCS("WGS84");
                    OGRCoordinateTransformation *ct = OGRCreateCoordinateTransformation(src, &dst);
                    src->Release();
                    if (ct != nullptr && ct->Transform(4, xs, ys)) {
                        OGRCoordinateTransformation::DestroyCT(ct);
                    } else {
                        if (ct != nullptr) OGRCoordinateTransformation::DestroyCT(ct);
                        __android_log_print(ANDROID_LOG_ERROR, "LayerInfo",
                                            "栅格四至重投影失败：无法创建坐标变换，返回源坐标系四至");
                    }
                }

                extent[0] = std::min(std::min(xs[0], xs[1]), std::min(xs[2], xs[3]));
                extent[2] = std::max(std::max(xs[0], xs[1]), std::max(xs[2], xs[3]));
                extent[1] = std::min(std::min(ys[0], ys[1]), std::min(ys[2], ys[3]));
                extent[3] = std::max(std::max(ys[0], ys[1]), std::max(ys[2], ys[3]));
                ok = true;
            }
            GDALClose(ds);
        }
    }

    return ok;
}

extern "C"
JNIEXPORT jdoubleArray JNICALL
Java_com_zys_mobilemap_ui_dialog_LayerManageDialog_nativeGetLayerExtent(
        JNIEnv *env, jobject thiz, jstring path) {

    GDALAllRegister();

    const char *p = env->GetStringUTFChars(path, nullptr);
    double extent[4] = {0, 0, 0, 0};
    bool ok = computeLayerExtent(p, extent);
    env->ReleaseStringUTFChars(path, p);

    if (!ok) {
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(4);
    env->SetDoubleArrayRegion(result, 0, 4, extent);
    return result;
}

extern "C"
JNIEXPORT jdoubleArray JNICALL
Java_com_zys_mobilemap_ui_activity_MainActivity_nativeGetLayerExtent(
        JNIEnv *env, jobject thiz, jstring path) {

    GDALAllRegister();

    const char *p = env->GetStringUTFChars(path, nullptr);
    double extent[4] = {0, 0, 0, 0};
    bool ok = computeLayerExtent(p, extent);
    env->ReleaseStringUTFChars(path, p);

    if (!ok) {
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(4);
    env->SetDoubleArrayRegion(result, 0, 4, extent);
    return result;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_zys_mobilemap_ui_dialog_LayerManageDialog_nativeGetVectorFieldNames(
        JNIEnv *env, jobject thiz, jstring path) {

    GDALAllRegister();

    const char *p = env->GetStringUTFChars(path, nullptr);
    jobjectArray result = nullptr;

    // OGR 读取第一个图层的字段定义（shp 取 DBF 字段，供整层样式标注字段下拉）
    GDALDataset *ds = (GDALDataset *) GDALOpenEx(p, GDAL_OF_VECTOR | GDAL_OF_READONLY,
                                                 nullptr, nullptr, nullptr);
    if (ds != nullptr) {
        OGRLayer *layer = ds->GetLayer(0);
        if (layer != nullptr) {
            OGRFeatureDefn *defn = layer->GetLayerDefn();
            const int count = defn->GetFieldCount();
            jclass stringClass = env->FindClass("java/lang/String");
            result = env->NewObjectArray(count, stringClass, nullptr);
            for (int i = 0; i < count; i++) {
                jstring name = env->NewStringUTF(defn->GetFieldDefn(i)->GetNameRef());
                env->SetObjectArrayElement(result, i, name);
                env->DeleteLocalRef(name);
            }
        }
        GDALClose(ds);
    }

    env->ReleaseStringUTFChars(path, p);
    return result;
}

/**
 * 图层原始坐标系描述（矢量优先，栅格兜底）：
 * 返回“坐标系名称 + proj4 定义”（两者以换行分隔），无 SRS 或打开失败返回 nullptr。
 * 供图层信息对话框展示原始文件坐标系。
 */
static char *computeLayerSrs(const char *p) {
    std::string out;

    // KML/KMZ 按 OGC KML 标准固定为 WGS84 地理坐标系；部分 GDAL KML 驱动不显式返回 SRS，
    // 直接按扩展名判定（无需打开文件），避免图层信息对 kml/kmz 不显示坐标系。
    std::string lp(p);
    std::transform(lp.begin(), lp.end(), lp.begin(),
                   [](unsigned char c) { return (char) std::tolower(c); });
    if (lp.size() >= 4 &&
        (lp.compare(lp.size() - 4, 4, ".kml") == 0 || lp.compare(lp.size() - 4, 4, ".kmz") == 0)) {
        const char *wgs84 = "WGS 84\n+proj=longlat +datum=WGS84 +no_defs";
        char *kbuf = (char *) CPLMalloc(strlen(wgs84) + 1);
        memcpy(kbuf, wgs84, strlen(wgs84) + 1);
        return kbuf;
    }

    // 矢量数据：OGR 读取第一个图层的空间参考（shp 等）
    GDALDataset *ds = (GDALDataset *) GDALOpenEx(p, GDAL_OF_VECTOR | GDAL_OF_READONLY,
                                                 nullptr, nullptr, nullptr);
    OGRSpatialReference *srs = nullptr;
    if (ds != nullptr) {
        OGRLayer *layer = ds->GetLayer(0);
        if (layer != nullptr && layer->GetSpatialRef() != nullptr) {
            srs = layer->GetSpatialRef()->Clone();
        }
        GDALClose(ds);
    }

    // 栅格数据兜底：读取数据集空间参考
    if (srs == nullptr) {
        ds = (GDALDataset *) GDALOpenEx(p, GDAL_OF_RASTER | GDAL_OF_READONLY,
                                        nullptr, nullptr, nullptr);
        if (ds != nullptr) {
            const OGRSpatialReference *rsrs = ds->GetSpatialRef();
            if (rsrs != nullptr) {
                srs = rsrs->Clone();
            }
            GDALClose(ds);
        }
    }

    if (srs != nullptr) {
        srs->SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
        const char *name = srs->GetName();
        if (name != nullptr && name[0] != '\0') {
            out += name;
        }
        char *proj4 = nullptr;
        if (srs->exportToProj4(&proj4) == OGRERR_NONE && proj4 != nullptr) {
            if (!out.empty()) out += "\n";
            out += proj4;
            CPLFree(proj4);
        }
        srs->Release();
    }

    if (out.empty()) {
        return nullptr;
    }
    char *buf = (char *) CPLMalloc(out.size() + 1);
    memcpy(buf, out.c_str(), out.size() + 1);
    return buf;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_zys_mobilemap_ui_dialog_LayerManageDialog_nativeGetLayerSrs(
        JNIEnv *env, jobject thiz, jstring path) {

    GDALAllRegister();

    const char *p = env->GetStringUTFChars(path, nullptr);
    char *srs = computeLayerSrs(p);
    env->ReleaseStringUTFChars(path, p);

    if (srs == nullptr) {
        return nullptr;
    }
    jstring result = env->NewStringUTF(srs);
    CPLFree(srs);
    return result;
}
