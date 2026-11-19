# MobileMap

#### Introduction
GIS field survey software. Supports online map browsing (2D plane / 3D globe modes), local vector data loading (kmz kml shp gpkg dxf dwg), and local raster data loading (tif img).

- **Project module** (since 0.4.0.0): survey photos, measurements, tracks and sample-plot records are all stored in a **project file** — a single-file GeoPackage (`.gpkg`) readable directly by desktop QGIS/ArcGIS. Multiple projects can be created/opened/renamed/shared/duplicated/backed up/merged/deleted; sharing a project sends all collected data at once. The legacy three databases (map.db / media.db / sample_survey.db) are no longer read or written; on first launch they are migrated once into the "default project". Daily collection writes (since 0.4.0.4) use app-side SQLite incremental direct-write (millisecond-level persistence, no longer via C++ GDAL full-file rebuild); **photos are stored directly as BLOBs in the database** (primary photos in `photos.photo_data`, additional attachments 2..N in `photo_att_data`), no longer kept as disk files — disk originals are automatically collected into the database and deleted on startup cleanup.
- **Document module**: manages basemap sources, layer lists, styles and display options (app-level configuration still lives in documents). The layer list supports **groups** (collapsible group headers, per-group visibility toggles, move-to/new group; groups organize only and do not change draw order).
- **Points of interest**: long-press on the map opens a pie menu; "add POI" creates a photo placemark at the pressed coordinates, with photos from camera or multi-select album — independent of GPS fix status.
- **Remarks**: photo placemarks and measurement records support multi-line remarks (`remark` column in `photos` / `measures_*`, added idempotently to legacy databases on first open).
- **Geometry editing** (since 0.4.3.0): long-press a gdb/shp feature to enter edit mode — polygons can be **split** (draw a polyline to cut; the largest piece keeps the original FID), **deleted** (with confirmation) or **merged** (pick another polygon in the same layer; the current FID and attributes are kept); line/point layers support delete only. The engine takes a full backup (whole gdb directory / shp file family) before the first geometry write and refuses to write if the backup fails.
- **Layer removal with optional file deletion** (since 0.4.3.0): removing a layer can optionally delete its source files (off by default) — shp sidecar family and import directory, exclusively-used gdb, or the original archive; data sources shared by several layers are never deleted.
- **KML display** (since 0.4.3.0): KML labels are shown by default (hidden only when the source LabelStyle scale is 0); embedded PNG icons are decoded and deduplicated by source href; attributes exported inside description HTML tables are restored into real fields.
- **Style preview** (since 0.4.3.0): unified layer style preview — point layers render the real icon plus label overlay; KML styles are sectioned by point/line/polygon.
- **Basic map module**: map browsing, positioning (with altitude display), tracking, photos, measurement, visual SQL query filtering, etc.
- **Survey module**: sample plot survey (per-tree inventory).

Current version: v0.4.3.0 (rendering kernel GlobeCore 0.1.6.0). Per-version changes (Chinese): [doc/版本迭代.md](doc/版本迭代.md).

#### Data directories (/sdcard/调查宝/)

| Directory | Contents |
|-----------|----------|
| `调查宝/projects/` | Project files (`<name>.gpkg`); track crash-recovery draft `.track_draft.json` |
| `调查宝/tiles/` | Online tile caches (one `<source>.mbtiles` per source; raster layers under `tiles/raster/<name>.mbtiles`) |
| `调查宝/layer/` | User-imported vector/raster data files |
| `调查宝/media/` | Legacy photo directory: new photos are stored as BLOBs in the project GeoPackage, no longer written here; startup cleanup automatically collects disk originals into the database and deletes them |

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
- minSdk 24 / targetSdk 37

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
