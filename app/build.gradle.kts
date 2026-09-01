plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.zys.mobilemap"
    compileSdk {
        version = release(37) // 与earth.worldwind:worldwind:2.0.9保持一致
    }

    defaultConfig {
        applicationId = "com.zys.mobilemap"
        minSdk = 24
        targetSdk = 36
        versionCode = 26081401
        versionName = "0.2.7.0"

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
        isCoreLibraryDesugaringEnabled = true //添加implementation("earth.worldwind:worldwind:2.0.9")后添加
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
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
    implementation(libs.androidx.activity.ktx)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5") //添加implementation("earth.worldwind:worldwind:2.0.9")后添加
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation("earth.worldwind:worldwind:2.0.9")
}