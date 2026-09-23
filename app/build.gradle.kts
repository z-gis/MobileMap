plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.zys.mobilemap"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.zys.mobilemap"
        minSdk = 24
        targetSdk = 36
        versionCode = 26092301
        versionName = "0.2.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "UPDATE_METADATA_URL", "\"https://gitee.com/z-gis/MobileMap/raw/master/update.json\"")

        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // app 自身不再有 native 源码：原 cpp（GDAL/PROJ JNI）已整体迁入 worldwindjni 模块统一编译
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    ndkVersion = "28.2.13676358"
}

val apkVersionName = android.defaultConfig.versionName ?: "0.0"
androidComponents {
    onVariants(selector().all()) { variant ->
        val suffix = if (variant.buildType == "release") "" else "-debug"
        variant.outputs.forEach { output ->
            output.outputFileName.set("MobileMap-v${apkVersionName}${suffix}.apk".lowercase())
        }
    }
}

dependencies {
    implementation(project(":worldwindjni"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}