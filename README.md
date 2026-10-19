# MobileMap（调查宝）

#### 介绍
GIS 外业调查软件。基础功能支持在线地图浏览（2D 平面 / 3D 球体双模），支持本地矢量数据加载（kmz kml shp gpkg dxf dwg），支持本地栅格数据加载（tif img）。

- **工程模块**（0.4.0.0 起）：采集照片、测量、轨迹、样地调查统一存入**工程文件**——单文件 GeoPackage（`.gpkg`），可被电脑端 QGIS/ArcGIS 直接读取；支持多工程新建/打开/重命名/分享/删除，分享工程即将全部采集数据一次性发出。旧版三库（map.db / media.db / sample_survey.db）已停止读写，首次启动自动一次性迁入「默认工程」。日常采集写入（0.5.0 起）由 app 端 SQLite 增量直写完成（毫秒级落盘，不再经 C++ GDAL 整文件重建）；**照片本体直接以 BLOB 入库**（主照片存 `photos.photo_data`、第 2..N 张附件存附加表 `photo_att_data`），不再保留磁盘照片文件，磁盘原件由启动清扫自动收库后删除。
- **文档模块**：管理底图图源、图层清单、样式与显示配置项（应用控制配置仍走文档）。
- **基础地图**：地图浏览、定位（含海拔）、轨迹、照片、测量、SQL 可视化查询过滤等。
- **调查模块**：样地调查（每木调查）。

当前版本：v0.4.0.3

#### 数据目录（/sdcard/调查宝/）

| 目录 | 内容 |
|------|------|
| `调查宝/projects/` | 工程文件（`<工程名>.gpkg`）；轨迹崩溃恢复草稿 `.track_draft.json` |
| `调查宝/tiles/` | 在线图源瓦片缓存（每图源一个 `<图源名>.mbtiles` 单文件，栅格层在 `tiles/raster/<名>.mbtiles`） |
| `调查宝/layer/` | 用户导入的矢量/栅格数据文件 |
| `调查宝/media/` | 旧版照片文件目录：新采集照片本体直接入库为工程 gpkg BLOB，不再在此落盘；启动清扫把 BLOB 已入库的磁盘原件自动收库删除，其他工程的文件不受影响 |

旧应用专属外部存储目录的工程文件与旧目录式瓦片缓存（`<z>/<x>_<y>.tile`）均在首次启动/加层时自动迁移，无需手工处理。

#### 软件架构
地图浏览基于自研 native 渲染内核 `globecore`（C++ OpenGL ES 2.0 渲染，GDAL/OGR 矢量读写、PROJ 坐标转换、libcurl 取瓦片全部静态链入）；Kotlin 侧仅保留 `NativeMapView` 等薄门面与业务层。

`globecore` 为**独立仓库 [GlobeCore](https://github.com/z-gis/GlobeCore)**，本仓库不含任何 C++ 源码与预编译静态库。内核 AAR 两种引用方式（`app/build.gradle.kts` 二选一）：

- **本地 AAR（当前默认）**：`implementation(files(".../aar-versioned/globecore-<版本>-release.aar"))`，需先在 GlobeCore 仓执行 `gradlew :globecore:assembleRelease` 产出版本化 AAR；
- **Maven 坐标**：`implementation("com.zys:globecore:<版本>")`，从 GitHub Pages Maven 仓在线拉取。

> GlobeCore 的 C++ 改动**不会自动进入本应用包**——改内核后必须重建 AAR 并同步此处文件名/版本号。

#### 构建说明

**开发环境**
- Android Studio（含 Android SDK）
- JDK：11 及以上
- minSdk 24 / targetSdk 36

如需修改渲染内核（C++ 层），请克隆 [GlobeCore](https://github.com/z-gis/GlobeCore) 仓库，其内含完整 native 源码与预编译静态库。
