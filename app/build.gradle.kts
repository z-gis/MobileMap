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
        versionCode = 26100102
        versionName = "0.3.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "UPDATE_METADATA_URL",
            "\"https://gitee.com/z-gis/MobileMap/raw/master/update.json\"")

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
    // app 自身不再有 native 源码：渲染内核在独立仓库 GlobeCore 编译，经其发布的 Maven 仓库以 AAR 引用
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
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
    // globecore 渲染内核：本地 AAR（GlobeCore 仓库 :globecore:publish 产物），或改用下方 Maven 坐标在线拉取
    //implementation(files("../../globecore/globecore/build/outputs/aar-versioned/globecore-0.1.2.0-release.aar"))
    implementation("com.zys:globecore:0.1.2.0")
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