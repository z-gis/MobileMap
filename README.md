# MobileMap（调查宝）

#### 介绍
林业外业调查软件，基础功能支持在线地图浏览（2D 平面 / 3D 球体双模），支持本地矢量数据加载（kmz kml shp gpkg dxf dwg），支持本地栅格数据加载（tif img）。
基础地图模块：地图浏览、定位（含海拔显示）、轨迹、照片、测量、SQL 可视化查询过滤等。
调查模块：林业样地调查(抚育每木调查)。
当前版本：v0.3.0

#### 软件架构
软件基础地图浏览基于自研 native 渲染模块 `worldwindjni`（C++ OpenGL ES 渲染，逻辑参照 WorldWind 的 Web Mercator 瓦片体系重写，已不再依赖 earth.worldwind 库），添加了天地图作为基本瓦片在线数据，实际坐标使用WGS84(EPSG:4326)。
矢量/栅格读取、坐标转换等 GDAL/proj 能力同样下沉在该 native 模块，Kotlin 侧仅保留 `NativeMapView`/`NativeRenderer` 等薄门面。

`worldwindjni` 已拆分为**独立仓库 [WorldWindJni](https://github.com/z-gis/WorldWindJni)**，本仓库不再包含任何 C++ 源码与预编译静态库：渲染内核在其仓库内经 NDK/CMake 编译为 AAR（`libworldwindjni.so` 随包携带，GDAL/proj 等静态库已在内核仓库内预编译），本仓库通过 Maven 仓库依赖 `com.zys:worldwindjni` 直接引用。


#### 构建说明

**开发环境**
- Android Studio（含 Android SDK）
- JDK：11 及以上
- minSdk 24 / targetSdk 36

**编译**
本仓库为纯 Kotlin 工程，无需 NDK/CMake 本地编译 native 代码，渲染内核 AAR 自动从 WorldWindJni 仓库发布的 Maven 仓库拉取（已在 `settings.gradle.kts` 中配置，需联网）。用 Android Studio 打开工程，或在项目根目录执行：

```
gradlew.bat :app:assembleDebug
```


如需修改渲染内核（C++ 层），请克隆 [WorldWindJni](https://github.com/z-gis/WorldWindJni) 仓库，其内含完整 native 源码与预编译静态库。
