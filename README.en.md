# MobileMap

#### Introduction
GIS field survey software. Supports online map browsing (2D plane / 3D globe modes), local vector data loading (kmz kml shp gpkg dxf dwg), and local raster data loading (tif img).

- **Project module** (since 0.4.0.0): survey photos, measurements, tracks and sample-plot records are all stored in a **project file** — a single-file GeoPackage (`.gpkg`) readable directly by desktop QGIS/ArcGIS. Multiple projects can be created/opened/renamed/shared/deleted; sharing a project sends all collected data at once. The legacy three databases (map.db / media.db / sample_survey.db) are no longer read or written; on first launch they are migrated once into the "default project".
- **Document module**: manages basemap sources, layer lists, styles and display options (app-level configuration still lives in documents).
- **Basic map module**: map browsing, positioning (with altitude display), tracking, photos, measurement, visual SQL query filtering, etc.
- **Survey module**: sample plot survey (per-tree inventory).

Current version: v0.4.0.5. Per-version changes (Chinese): [doc/版本迭代.md](doc/版本迭代.md).

#### Data directories (/sdcard/调查宝/)

| Directory | Contents |
|-----------|----------|
| `调查宝/projects/` | Project files (`<name>.gpkg`); track crash-recovery draft `.track_draft.json` |
| `调查宝/tiles/` | Online tile caches (one `<source>.mbtiles` per source; raster layers under `tiles/raster/<name>.mbtiles`) |
| `调查宝/layer/` | User-imported vector/raster data files |
| `调查宝/media/` | Captured photos and other media files |

Projects in the legacy app-private external directory and legacy directory-style tile caches (`<z>/<x>_<y>.tile`) are migrated automatically on first launch / layer add — no manual handling needed.

#### Software Architecture
Map browsing is built on the in-house native rendering kernel `globecore` (C++ OpenGL ES 2.0; GDAL/OGR vector I/O, PROJ coordinate transforms and libcurl tile fetching are statically linked in). The Kotlin side keeps only thin facades (`NativeMapView`) plus the business layer.

`globecore` lives in the **separate repository [GlobeCore](https://github.com/z-gis/GlobeCore)**; this repository contains no C++ sources or prebuilt static libraries. Two ways to consume the kernel AAR (choose one in `app/build.gradle.kts`):

- **Local AAR (current default)**: `implementation(files(".../aar-versioned/globecore-<version>-release.aar"))` — run `gradlew :globecore:assembleRelease` in the GlobeCore repo first to produce the versioned AAR;
- **Maven coordinate**: `implementation("com.zys:globecore:<version>")` — fetched from the GitHub Pages Maven repository.

> C++ changes in GlobeCore are **not picked up automatically** — after modifying the kernel you must rebuild the AAR and update the file name / version referenced here.

#### Build Instructions

**Development Environment**
- Android Studio (with Android SDK)
- JDK: 11 or above
- minSdk 24 / targetSdk 36

**Debug build**
This repository is a pure Kotlin project — no local NDK/CMake native build is required. Open the project with Android Studio, or run in the project root:

```
gradlew.bat :app:assembleDebug
```

The output APK is located at `app/build/outputs/apk/debug/`.

**Release build (signed)**
Signing credentials are read from `local.properties` (not committed):

```properties
storeFile=app.jks          # relative to the app/ module dir, or absolute
storePassword=...
keyAlias=...
keyPassword=...
```

```
gradlew.bat :app:assembleRelease
```

To modify the rendering kernel (C++ layer), clone the [GlobeCore](https://github.com/z-gis/GlobeCore) repository, which contains the full native sources and prebuilt static libraries.
