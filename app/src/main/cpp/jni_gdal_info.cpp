#include <jni.h>
#include <string>

#include "gdal/gdal_priv.h"

// ==================== GDAL 版本信息与驱动清单（AboutActivity） ====================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_zys_mobilemap_ui_activity_AboutActivity_getGDALVersion(JNIEnv *env,
                                                                jobject thiz) {

    GDALAllRegister();

    const char * chVersion = GDALVersionInfo("VERSION_NUM");
    return env->NewStringUTF(chVersion);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_zys_mobilemap_ui_activity_AboutActivity_getVectorDrivers(JNIEnv *env,
                                                                  jobject thiz) {

    GDALAllRegister();

    // 枚举全部矢量驱动，标注是否支持创建（写入）
    GDALDriverManager *mgr = GetGDALDriverManager();
    std::string result;
    const int count = mgr->GetDriverCount();
    for (int i = 0; i < count; i++) {
        GDALDriver *driver = mgr->GetDriver(i);
        if (driver->GetMetadataItem(GDAL_DCAP_VECTOR) == nullptr) continue;
        const bool canCreate = driver->GetMetadataItem(GDAL_DCAP_CREATE) != nullptr;
        if (!result.empty()) result += "\n";
        result += driver->GetDescription();
        result += canCreate ? "（读/写）" : "（只读）";
    }
    return env->NewStringUTF(result.c_str());
}
