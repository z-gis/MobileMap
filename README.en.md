# MobileMap

#### Introduction
Forestry field survey software. Supports online map browsing (2D plane / 3D globe modes), local vector data loading (kmz kml shp gpkg dxf dwg), and local raster data loading (tif img).
Basic map module: map browsing, positioning (with altitude display), tracking, photos, measurement, visual SQL query filtering, etc.
Survey module: sample plot survey.
Current version: v0.3.0

#### Software Architecture
The software is built around map browsing on top of the in-house native rendering module `worldwindjni` (C++ OpenGL ES rendering, re-implemented with reference to WorldWind's Web Mercator tile scheme; the earth.worldwind library is no longer used), with Tianditu added as the base online tile data. Actual coordinates use WGS84 (EPSG:4326).
Vector/raster reading and coordinate transformation (GDAL/proj) are also provided by the same native module; the Kotlin side keeps only thin facades (`NativeMapView`/`NativeRenderer`).

`worldwindjni` has been split into a **separate repository [WorldWindJni](https://github.com/z-gis/WorldWindJni)**. This repository no longer contains any C++ sources or prebuilt static libraries: the rendering kernel is compiled into an AAR in that repository via NDK/CMake (`libworldwindjni.so` bundled in, with GDAL/proj etc. statically prebuilt inside the kernel repo), and is consumed here as the Maven dependency `com.zys:worldwindjni`.

#### Build Instructions

**Development Environment**
- Android Studio (with Android SDK)
- JDK: 11 or above
- minSdk 24 / targetSdk 36

**Compile**
This repository is a pure Kotlin project — no local NDK/CMake native build is required. The rendering kernel AAR is fetched automatically from the Maven repository published by WorldWindJni (already configured in `settings.gradle.kts`; network access required). Open the project with Android Studio, or run in the project root:

```
gradlew.bat :app:assembleDebug
```

The output APK is located at `app/build/outputs/apk/debug/` (named `mobilemap-v{version}-debug.apk`).

To modify the rendering kernel (C++ layer), clone the [WorldWindJni](https://github.com/z-gis/WorldWindJni) repository, which contains the full native sources and prebuilt static libraries.
