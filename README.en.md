# MobileMap

#### Introduction
Forestry field survey software. Basic features support online map browsing, local vector data loading (kmz kml shp dwg dxf), and local raster data loading (tif img).
Basic map module: supports fundamental map browsing, positioning, tracking, photos, measurement, etc.
Survey module: sample plot survey.

#### Software Architecture
The software is built around map browsing, using WorldWind as the underlying library, with Tianditu added as the base online tile data. Actual coordinates use WGS84 (EPSG:4326).

#### Build Instructions

**Development Environment**
- Android Studio (with Android SDK)
- NDK: 28.2.13676358
- CMake: 3.22.1
- JDK: 11 or above (the project enables core library desugaring for worldwind compatibility)
- minSdk 24 / targetSdk 36

**Native Prebuilt Static Libraries (Important)**
The C++ layer (`app/src/main/cpp/`) depends on third-party **prebuilt static libraries** (`.a`) such as GDAL, proj, kml, sqlite, expat and minizip, located per ABI at:
- `app/src/main/cpp/jniLibs/arm64-v8a/`
- `app/src/main/cpp/jniLibs/x86_64/`

These libraries are individually very large (e.g. `libgdal.a` ~315MB, `libproj.a` ~91MB), **exceeding GitHub's 100MB per-file limit, and are therefore NOT included in this GitHub repository**. Without them the native build will fail.

The complete buildable project (with all static libraries) is hosted on **Gitee**. Please download these libraries from Gitee:

> https://gitee.com/z-gis/MobileMap

After cloning from Gitee, copy the `app/src/main/cpp/jniLibs/` directory (all `.a` files under both the `arm64-v8a` and `x86_64` subdirectories) into the same path of your local GitHub clone.

**Compile**
Once the static libraries are in place, open the project with Android Studio, or run in the project root:

```
gradlew.bat :app:assembleDebug
```

The first build compiles the native library (`libmobilemap.so`) via CMake and takes a while; the output APK is located at `app/build/outputs/apk/debug/`.
