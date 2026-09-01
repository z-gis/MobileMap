#include <jni.h>
#include <android/log.h>

#include "proj/proj.h"

// ==================== PROJ 初始化 / 坐标转换 / 版本 ====================

static PJ_CONTEXT *g_proj_ctx = nullptr;

static PJ_CONTEXT *getProjContext() {
    if (!g_proj_ctx) {
        g_proj_ctx = proj_context_create();
    }
    return g_proj_ctx;
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
Java_com_zys_mobilemap_ui_activity_SplashActivity_initProjDataPath(JNIEnv *env,
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
Java_com_zys_mobilemap_ui_dialog_MoveToDialog_nativeConvertCoordinate(
        JNIEnv *env, jclass clazz,
        jdouble x, jdouble y,
        jstring src_crs, jstring tgt_crs) {

    const char *src = env->GetStringUTFChars(src_crs, nullptr);
    const char *tgt = env->GetStringUTFChars(tgt_crs, nullptr);

    PJ_CONTEXT *ctx = getProjContext();
    PJ *pj = proj_create_crs_to_crs(ctx, src, tgt, nullptr);

    if (!pj) {
        int err = proj_context_errno(ctx);
        __android_log_print(ANDROID_LOG_ERROR, "PROJ",
                            "proj_create_crs_to_crs failed: %s -> %s, error=%d: %s",
                            src, tgt, err, proj_context_errno_string(ctx, err));
    }

    jdoubleArray result = nullptr;
    if (pj) {
        PJ *norm = proj_normalize_for_visualization(ctx, pj);
        proj_destroy(pj);
        pj = norm;
    }

    if (pj) {
        PJ_COORD in_coord = proj_coord(x, y, 0, 0);
        PJ_COORD out_coord = proj_trans(pj, PJ_FWD, in_coord);

        if (!proj_errno(pj)) {
            result = env->NewDoubleArray(2);
            jdouble vals[2] = {out_coord.xy.x, out_coord.xy.y};
            env->SetDoubleArrayRegion(result, 0, 2, vals);
        }
        proj_destroy(pj);
    }

    env->ReleaseStringUTFChars(src_crs, src);
    env->ReleaseStringUTFChars(tgt_crs, tgt);
    return result;
}
