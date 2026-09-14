#include <jni.h>
#include <android/log.h>

#include <map>
#include <mutex>
#include <string>

#include "proj/proj.h"

// ==================== PROJ 初始化 / 坐标转换 / 版本 ====================

static PJ_CONTEXT *g_proj_ctx = nullptr;

static PJ_CONTEXT *getProjContext() {
    if (!g_proj_ctx) {
        g_proj_ctx = proj_context_create();
    }
    return g_proj_ctx;
}

// ==================== 转换管道缓存 ====================
// 主界面中心/定位坐标文本随导航事件逐帧刷新，每次都 proj_create_crs_to_crs
// （需查 proj.db 构建管道）开销远大于转换本身，故按 "源|目标" 缓存 PJ。
// PJ 常驻不销毁（组合数量有限）；PROJ 上下文与 PJ 非线程安全，转换全程持锁串行。

static std::mutex g_pjMutex;
static std::map<std::string, PJ *> g_pjCache;

/** 持锁调用：取或创建缓存的坐标转换管道（已归一化为 x=经度/东、y=纬度/北 的可视化轴序） */
static PJ *getCachedPipelineLocked(const std::string &src, const std::string &tgt) {
    const std::string key = src + "|" + tgt;
    auto it = g_pjCache.find(key);
    if (it != g_pjCache.end()) {
        return it->second;
    }
    PJ_CONTEXT *ctx = getProjContext();
    PJ *pj = proj_create_crs_to_crs(ctx, src.c_str(), tgt.c_str(), nullptr);
    if (pj != nullptr) {
        PJ *norm = proj_normalize_for_visualization(ctx, pj);
        proj_destroy(pj);
        pj = norm;
    }
    if (pj == nullptr) {
        int err = proj_context_errno(ctx);
        __android_log_print(ANDROID_LOG_ERROR, "PROJ",
                            "proj_create_crs_to_crs failed: %s -> %s, error=%d: %s",
                            src.c_str(), tgt.c_str(), err, proj_context_errno_string(ctx, err));
        return nullptr;
    }
    g_pjCache[key] = pj;
    return pj;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_zys_mobilemap_ui_activity_AboutActivity_getProjVersion(JNIEnv *env,
                                                                jobject thiz) {
    const PJ_INFO info = proj_info();
    return static_cast<jint>(info.major * 10000 + info.minor * 100 + info.patch);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_zys_mobilemap_ui_activity_MainActivity_initProjDataPath(JNIEnv *env,
                                                                 jobject thiz,
                                                                 jstring proj_data_path) {
    const char *path = env->GetStringUTFChars(proj_data_path, nullptr);
    PJ_CONTEXT *ctx = getProjContext();
    proj_context_set_search_paths(ctx, 1, &path);
    env->ReleaseStringUTFChars(proj_data_path, path);

    PJ *test = proj_create(ctx, "EPSG:4326");
    if (test) {
        __android_log_print(ANDROID_LOG_INFO, "PROJ", "proj.db loaded OK from: %s", path);
        proj_destroy(test);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, "PROJ",
                            "Failed to load proj.db from: %s, error: %s",
                            path, proj_context_errno_string(ctx, proj_context_errno(ctx)));
    }
}

extern "C"
JNIEXPORT jdoubleArray JNICALL
Java_com_zys_mobilemap_util_CoordTransform_nativeConvert(
        JNIEnv *env, jobject thiz,
        jdouble x, jdouble y,
        jstring src_crs, jstring tgt_crs) {

    const char *src = env->GetStringUTFChars(src_crs, nullptr);
    const char *tgt = env->GetStringUTFChars(tgt_crs, nullptr);
    std::string srcCrs(src);
    std::string tgtCrs(tgt);
    env->ReleaseStringUTFChars(src_crs, src);
    env->ReleaseStringUTFChars(tgt_crs, tgt);

    jdoubleArray result = nullptr;
    std::lock_guard<std::mutex> lock(g_pjMutex);
    PJ *pj = getCachedPipelineLocked(srcCrs, tgtCrs);
    if (pj != nullptr) {
        PJ_COORD out_coord = proj_trans(pj, PJ_FWD, proj_coord(x, y, 0, 0));
        if (!proj_errno(pj)) {
            jdouble vals[2] = {out_coord.xy.x, out_coord.xy.y};
            result = env->NewDoubleArray(2);
            env->SetDoubleArrayRegion(result, 0, 2, vals);
        } else {
            proj_errno_reset(pj);
        }
    }
    return result;
}
