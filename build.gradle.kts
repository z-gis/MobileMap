// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // android.library 已随 worldwindjni 拆分为独立仓库（WorldWindJni）移出本工程
}

// 加载implementation("earth.worldwind:worldwind:2.0.9")后提示错误，添加。
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}