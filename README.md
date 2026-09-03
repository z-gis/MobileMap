# MobileMap（调查宝）

#### 介绍
林业外业调查软件，基础功能支持在线地图浏览，支持本地矢量数据加载（kmz kml shp）,支持本地栅格数据加载（tif）。
基础地图模块：支持地图基本浏览功能，定位、轨迹、照片、测量等。
调查模块：样地调查（0.2.7.4未将上一版本模块迁移）


#### 软件架构
软件基础地图浏览，底层库使用WorldWind，添加了天地图作为基本瓦片在线数据，实际坐标使用WGS84(EPSG:4326)。


#### 构建说明

**开发环境**
- Android Studio（含 Android SDK）
- NDK：28.2.13676358
- CMake：3.22.1
- JDK：11 及以上（项目启用核心库脱糖以兼容 worldwind）
- minSdk 24 / targetSdk 36

**native 预编译静态库（重要）**
C++ 层（`app/src/main/cpp/`）依赖 GDAL、proj、kml、sqlite、expat、minizip 等第三方**预编译静态库**（`.a`），按 ABI 分别位于：
- `app/src/main/cpp/jniLibs/arm64-v8a/`
- `app/src/main/cpp/jniLibs/x86_64/`

这些库单个体积很大（如 `libgdal.a` 约 315MB、`libproj.a` 约 91MB），**超过 GitHub 单文件 100MB 限制，因此未包含在本 GitHub 仓库中**。缺少它们时 native 无法编译。

完整可编译的工程（含全部静态库）托管在 **Gitee**，请从 Gitee 下载获取这些库：

> https://gitee.com/z-gis/MobileMap

从 Gitee 克隆后，将 `app/src/main/cpp/jniLibs/` 目录（`arm64-v8a` 与 `x86_64` 两个子目录下的全部 `.a` 文件）复制到本地 GitHub 克隆工程的相同路径即可。

**编译**
放好静态库后，用 Android Studio 打开工程，或在项目根目录执行：

```
gradlew.bat :app:assembleDebug
```

首次编译会通过 CMake 构建 native 库（`libmobilemap.so`），耗时较长；产物 APK 位于 `app/build/outputs/apk/debug/`。


