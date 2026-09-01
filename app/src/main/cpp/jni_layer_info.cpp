#include <jni.h>
#include <algorithm>
#include <android/log.h>

#include "gdal/ogrsf_frmts.h"
#include "gdal/gdal_priv.h"

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
