#include <jni.h>
#include <string>
#include <vector>
#include <cstdio>
#include <cmath>
#include <android/log.h>

#include "gdal/ogrsf_frmts.h"
#include "gdal/gdal_priv.h"

// ==================== 矢量要素读取（GDAL/OGR -> JSON，GdalVectorReader） ====================

/** 要素数上限，防大文件 OOM */
static const int MAX_VECTOR_FEATURES = 50000;

static void jsonEscape(std::string &out, const char *s) {
    if (s == nullptr) return;
    for (const char *p = s; *p; ++p) {
        unsigned char c = (unsigned char) *p;
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += (char) c;
                }
        }
    }
}

static void appendXY(std::string &out, double x, double y) {
    char buf[64];
    // 摊平数组约定经度在前 [lon0,lat0,…]；Kotlin 侧 flattenToPositions 已转为 WorldWind 纬度在前的 Position，此处勿交换
    snprintf(buf, sizeof(buf), "[%.8f,%.8f]", x, y);
    out += buf;
}

static void appendLineCoords(std::string &out, OGRLineString *line) {
    out += "[";
    int n = line->getNumPoints();
    for (int i = 0; i < n; i++) {
        if (i) out += ",";
        appendXY(out, line->getX(i), line->getY(i));
    }
    out += "]";
}

static void appendFields(std::string &out, OGRFeature *feat) {
    out += ",\"fields\":{";
    OGRFeatureDefn *defn = feat->GetDefnRef();
    bool first = true;
    for (int i = 0; i < defn->GetFieldCount(); i++) {
        if (!feat->IsFieldSet(i) || feat->IsFieldNull(i)) continue;
        if (!first) out += ",";
        first = false;
        out += "\"";
        jsonEscape(out, defn->GetFieldDefn(i)->GetNameRef());
        out += "\":\"";
        jsonEscape(out, feat->GetFieldAsString(i));
        out += "\"";
    }
    out += "}";
}

static void emitPointFeature(std::string &out, bool &firstFeature, double x, double y, OGRFeature *feat,
                             const char *layerName) {
    if (!firstFeature) out += ",";
    firstFeature = false;
    char buf[96];
    snprintf(buf, sizeof(buf), "{\"geom\":\"POINT\",\"x\":%.8f,\"y\":%.8f,\"fid\":%lld", x, y,
             (long long) feat->GetFID());
    out += buf;
    out += ",\"layer\":\"";
    jsonEscape(out, layerName);
    out += "\"";
    appendFields(out, feat);
    out += "}";
}

static void emitLineFeature(std::string &out, bool &firstFeature,
                            const std::vector<OGRLineString *> &lines, OGRFeature *feat,
                            const char *layerName) {
    if (!firstFeature) out += ",";
    firstFeature = false;
    out += "{\"geom\":\"LINE\",\"parts\":[";
    for (size_t i = 0; i < lines.size(); i++) {
        if (i) out += ",";
        appendLineCoords(out, lines[i]);
    }
    out += "]";
    char buf[48];
    snprintf(buf, sizeof(buf), ",\"fid\":%lld", (long long) feat->GetFID());
    out += buf;
    out += ",\"layer\":\"";
    jsonEscape(out, layerName);
    out += "\"";
    appendFields(out, feat);
    out += "}";
}

static void emitPolygonFeature(std::string &out, bool &firstFeature, OGRPolygon *poly, OGRFeature *feat,
                               const char *layerName) {
    OGRLineString *outer = poly->getExteriorRing();
    if (outer == nullptr) return;
    if (!firstFeature) out += ",";
    firstFeature = false;
    out += "{\"geom\":\"POLYGON\",\"outer\":";
    appendLineCoords(out, outer);
    out += ",\"holes\":[";
    int n = poly->getNumInteriorRings();
    for (int i = 0; i < n; i++) {
        if (i) out += ",";
        appendLineCoords(out, poly->getInteriorRing(i));
    }
    out += "]";
    char buf[48];
    snprintf(buf, sizeof(buf), ",\"fid\":%lld", (long long) feat->GetFID());
    out += buf;
    out += ",\"layer\":\"";
    jsonEscape(out, layerName);
    out += "\"";
    appendFields(out, feat);
    out += "}";
}

/**
 * 按几何类型摊平输出要素：点/多点各一个 POINT 要素，多线合并为一个 LINE 要素（多 parts），
 * 多面拆分为多个 POLYGON 要素（外环+内环），几何集合递归处理。
 */
static void emitGeometry(std::string &out, bool &firstFeature, int &count, bool &truncated,
                         OGRGeometry *g, OGRFeature *feat, const char *layerName) {
    if (g == nullptr || truncated) return;
    switch (wkbFlatten(g->getGeometryType())) {
        case wkbPoint: {
            if (count >= MAX_VECTOR_FEATURES) { truncated = true; return; }
            count++;
            OGRPoint *pt = (OGRPoint *) g;
            emitPointFeature(out, firstFeature, pt->getX(), pt->getY(), feat, layerName);
            break;
        }
        case wkbMultiPoint: {
            OGRMultiPoint *mp = (OGRMultiPoint *) g;
            for (int i = 0; i < mp->getNumGeometries(); i++) {
                if (count >= MAX_VECTOR_FEATURES) { truncated = true; return; }
                count++;
                OGRPoint *pt = (OGRPoint *) mp->getGeometryRef(i);
                emitPointFeature(out, firstFeature, pt->getX(), pt->getY(), feat, layerName);
            }
            break;
        }
        case wkbLineString: {
            if (count >= MAX_VECTOR_FEATURES) { truncated = true; return; }
            count++;
            std::vector<OGRLineString *> lines;
            lines.push_back((OGRLineString *) g);
            emitLineFeature(out, firstFeature, lines, feat, layerName);
            break;
        }
        case wkbMultiLineString: {
            if (count >= MAX_VECTOR_FEATURES) { truncated = true; return; }
            count++;
            OGRMultiLineString *ml = (OGRMultiLineString *) g;
            std::vector<OGRLineString *> lines;
            for (int i = 0; i < ml->getNumGeometries(); i++) {
                lines.push_back((OGRLineString *) ml->getGeometryRef(i));
            }
            emitLineFeature(out, firstFeature, lines, feat, layerName);
            break;
        }
        case wkbPolygon: {
            if (count >= MAX_VECTOR_FEATURES) { truncated = true; return; }
            count++;
            emitPolygonFeature(out, firstFeature, (OGRPolygon *) g, feat, layerName);
            break;
        }
        case wkbMultiPolygon: {
            OGRMultiPolygon *mpoly = (OGRMultiPolygon *) g;
            for (int i = 0; i < mpoly->getNumGeometries(); i++) {
                if (count >= MAX_VECTOR_FEATURES) { truncated = true; return; }
                count++;
                emitPolygonFeature(out, firstFeature, (OGRPolygon *) mpoly->getGeometryRef(i), feat, layerName);
            }
            break;
        }
        case wkbGeometryCollection: {
            OGRGeometryCollection *gc = (OGRGeometryCollection *) g;
            for (int i = 0; i < gc->getNumGeometries(); i++) {
                emitGeometry(out, firstFeature, count, truncated, gc->getGeometryRef(i), feat, layerName);
            }
            break;
        }
        default:
            break;
    }
}

/**
 * 构造 WGS84 四至对应的空间过滤几何（矩形多边形）。
 * 过滤范围需位于图层源坐标系：有 SRS 时经反向变换（WGS84 -> 源 SRS），无 SRS 时按 WGS84 直接使用。
 * 返回的几何由 SetSpatialFilter 接管所有权；不适用（无效四至/变换失败）返回 nullptr。
 */
static OGRGeometry *createSpatialFilterGeometry(double minLon, double minLat, double maxLon, double maxLat,
                                                OGRSpatialReference *srcRef) {
    if (!(minLon < maxLon && minLat <= maxLat)) return nullptr;
    OGRPolygon *bbox = new OGRPolygon();
    OGRLinearRing *ring = new OGRLinearRing();
    ring->addPoint(minLon, minLat);
    ring->addPoint(maxLon, minLat);
    ring->addPoint(maxLon, maxLat);
    ring->addPoint(minLon, maxLat);
    ring->closeRings();
    bbox->addRingDirectly(ring);
    if (srcRef != nullptr) {
        OGRSpatialReference wgs84;
        wgs84.SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
        wgs84.SetWellKnownGeogCS("WGS84");
        OGRSpatialReference *dstClone = srcRef->Clone();
        dstClone->SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
        OGRCoordinateTransformation *inv = OGRCreateCoordinateTransformation(&wgs84, dstClone);
        dstClone->Release();
        if (inv == nullptr || !bbox->transform(inv)) {
            if (inv != nullptr) OGRCoordinateTransformation::DestroyCT(inv);
            delete bbox;
            return nullptr;
        }
        OGRCoordinateTransformation::DestroyCT(inv);
    }
    return bbox;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_zys_mobilemap_vector_GdalVectorReader_nativeReadVectorFeatures(
        JNIEnv *env, jobject thiz, jstring path,
        jdouble minLon, jdouble minLat, jdouble maxLon, jdouble maxLat) {

    GDALAllRegister();

    const char *p = env->GetStringUTFChars(path, nullptr);
    GDALDataset *ds = (GDALDataset *) GDALOpenEx(p, GDAL_OF_VECTOR | GDAL_OF_READONLY,
                                                 nullptr, nullptr, nullptr);
    env->ReleaseStringUTFChars(path, p);
    if (ds == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "GdalVectorReader", "GDALOpenEx 打开矢量数据失败");
        return nullptr;
    }

    std::string out = "{\"features\":[";
    bool firstFeature = true;
    int count = 0;
    bool truncated = false;
    // 空间过滤：仅读取屏幕中心点附近四至范围内的要素（含相邻要素缓冲），
    // 从源头限制读取量，要素数与折点数同时受控；任一为 NaN 表示不过滤
    bool hasFilter = !isnan(minLon) && !isnan(minLat) && !isnan(maxLon) && !isnan(maxLat);

    // 遍历全部图层（dwg 等格式的实体分布在多个子图层）
    int layerCount = ds->GetLayerCount();
    for (int li = 0; li < layerCount && !truncated; li++) {
        OGRLayer *layer = ds->GetLayer(li);
        if (layer == nullptr) continue;

        // 图层 SRS 非空时重投影到 WGS84（传统 GIS 轴序 经度,纬度），空则按 WGS84 处理
        OGRCoordinateTransformation *ct = nullptr;
        OGRSpatialReference *srcClone = nullptr;
        OGRSpatialReference *srcRef = layer->GetSpatialRef();
        if (srcRef != nullptr) {
            srcClone = srcRef->Clone();
            srcClone->SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
            OGRSpatialReference dstRef;
            dstRef.SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
            dstRef.SetWellKnownGeogCS("WGS84");
            ct = OGRCreateCoordinateTransformation(srcClone, &dstRef);
            if (ct == nullptr) {
                // 创建失败（常见：proj.db 搜索路径未配置）时坐标将按源坐标系原样输出，
                // 投影米制被当经纬度渲染导致位置错误，必须提示而非静默
                __android_log_print(ANDROID_LOG_ERROR, "GdalVectorReader",
                                    "图层[%s]创建坐标变换失败（proj.db 未配置？），坐标将按源坐标系原样输出",
                                    layer->GetName());
            }
        }

        // 空间过滤：将 WGS84 四至反变换到图层源坐标系后设置，仅遍历范围内要素；
        // 过滤几何由 SetSpatialFilter 接管，后续无需释放；范围无效/变换失败则不过滤兜底读全部。
        if (hasFilter) {
            OGRGeometry *filterGeom = createSpatialFilterGeometry(minLon, minLat, maxLon, maxLat, srcRef);
            if (filterGeom != nullptr) {
                layer->SetSpatialFilter(filterGeom);
            } else if (srcRef == nullptr) {
                layer->SetSpatialFilterRect(minLon, minLat, maxLon, maxLat);
            }
        }

        layer->ResetReading();
        OGRFeature *feat;
        while (!truncated && (feat = layer->GetNextFeature()) != nullptr) {
            OGRGeometry *geom = feat->GetGeometryRef();
            if (geom != nullptr) {
                if (ct != nullptr) {
                    geom->transform(ct);
                }
                emitGeometry(out, firstFeature, count, truncated, geom, feat, layer->GetName());
            }
            OGRFeature::DestroyFeature(feat);
        }

        if (ct != nullptr) {
            OGRCoordinateTransformation::DestroyCT(ct);
        }
        if (srcClone != nullptr) {
            srcClone->Release();
        }
    }

    if (truncated) {
        __android_log_print(ANDROID_LOG_WARN, "GdalVectorReader",
                            "要素数超过上限 %d，已截断", MAX_VECTOR_FEATURES);
    }

    out += "]}";
    GDALClose(ds);
    return env->NewStringUTF(out.c_str());
}
