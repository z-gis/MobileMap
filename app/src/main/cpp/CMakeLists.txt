# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)


include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

add_library(sqlite STATIC IMPORTED)
set_target_properties(sqlite PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libsqlite3.a)

add_library(proj STATIC IMPORTED)
set_target_properties(proj PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libproj.a)

add_library(gdal STATIC IMPORTED)
set_target_properties(gdal PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libgdal.a)


add_library(kmlbase STATIC IMPORTED)
set_target_properties(kmlbase PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libkmlbase.a)

add_library(kmldom STATIC IMPORTED)
set_target_properties(kmldom PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libkmldom.a)

add_library(kmlengine STATIC IMPORTED)
set_target_properties(kmlengine PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libkmlengine.a)

add_library(kmlxsd STATIC IMPORTED)
set_target_properties(kmlxsd PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libkmlxsd.a)

add_library(kmlconvenience STATIC IMPORTED)
set_target_properties(kmlconvenience PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libkmlconvenience.a)

add_library(kmlregionator STATIC IMPORTED)
set_target_properties(kmlregionator PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libkmlregionator.a)

add_library(uriparser STATIC IMPORTED)
set_target_properties(uriparser PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/liburiparser.a)

add_library(minizip STATIC IMPORTED)
set_target_properties(minizip PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libminizip.a)

add_library(expatstatic STATIC IMPORTED)
set_target_properties(expatstatic PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libexpat.a)

add_library(zstatic STATIC IMPORTED)
set_target_properties(zstatic PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libz.a)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("mobilemap")

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        # 图层四至/字段名/坐标系 JNI（jni_layer_info.cpp）
        jni_proj.cpp
        jni_gdal_info.cpp
        jni_layer_info.cpp
        jni_vector_reader.cpp
        jni_raster.cpp)

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        sqlite
        proj
        gdal
        kmlengine
        kmldom
        kmlbase
        kmlxsd
        kmlconvenience
        kmlregionator
        uriparser
        minizip
        expatstatic
        zstatic
        android
        log)