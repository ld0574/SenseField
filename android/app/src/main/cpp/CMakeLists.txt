cmake_minimum_required(VERSION 3.22.1)
project(mapassist_android LANGUAGES CXX)

# Pin the Android inference runtime so Android Studio and command-line builds
# use the same ABI and operators as the model parity check. The archive is
# cached inside the ignored native build directory after the first build.
set(MAPASSIST_NCNN_VERSION 20260526)
set(MAPASSIST_NCNN_SHA256
    85b18b875488585c2d21360430e0e54abb6c04aa88094b471c20208ab55ff796)
set(MAPASSIST_NCNN_CACHE "${CMAKE_BINARY_DIR}/ncnn-${MAPASSIST_NCNN_VERSION}")
set(MAPASSIST_NCNN_ARCHIVE
    "${MAPASSIST_NCNN_CACHE}/ncnn-${MAPASSIST_NCNN_VERSION}-android.zip")
set(MAPASSIST_NCNN_ROOT
    "${MAPASSIST_NCNN_CACHE}/ncnn-${MAPASSIST_NCNN_VERSION}-android")
set(MAPASSIST_NCNN_CONFIG
    "${MAPASSIST_NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake")

file(MAKE_DIRECTORY "${MAPASSIST_NCNN_CACHE}")
set(MAPASSIST_NCNN_EXTRACT FALSE)
if(EXISTS "${MAPASSIST_NCNN_ARCHIVE}")
    file(SHA256 "${MAPASSIST_NCNN_ARCHIVE}" MAPASSIST_NCNN_CACHED_SHA256)
    if(NOT MAPASSIST_NCNN_CACHED_SHA256 STREQUAL MAPASSIST_NCNN_SHA256)
        message(STATUS "Discarding ncnn archive with an unexpected SHA-256")
        file(REMOVE "${MAPASSIST_NCNN_ARCHIVE}")
        file(REMOVE_RECURSE "${MAPASSIST_NCNN_ROOT}")
    endif()
endif()

if(NOT EXISTS "${MAPASSIST_NCNN_ARCHIVE}")
    file(DOWNLOAD
        "https://github.com/Tencent/ncnn/releases/download/${MAPASSIST_NCNN_VERSION}/ncnn-${MAPASSIST_NCNN_VERSION}-android.zip"
        "${MAPASSIST_NCNN_ARCHIVE}"
        EXPECTED_HASH "SHA256=${MAPASSIST_NCNN_SHA256}"
        STATUS MAPASSIST_NCNN_DOWNLOAD_STATUS
        TLS_VERIFY ON
        SHOW_PROGRESS)
    list(GET MAPASSIST_NCNN_DOWNLOAD_STATUS 0 MAPASSIST_NCNN_DOWNLOAD_CODE)
    list(GET MAPASSIST_NCNN_DOWNLOAD_STATUS 1 MAPASSIST_NCNN_DOWNLOAD_MESSAGE)
    if(NOT MAPASSIST_NCNN_DOWNLOAD_CODE EQUAL 0)
        file(REMOVE "${MAPASSIST_NCNN_ARCHIVE}")
        message(FATAL_ERROR
            "Could not download pinned ncnn Android package: ${MAPASSIST_NCNN_DOWNLOAD_MESSAGE}")
    endif()
    set(MAPASSIST_NCNN_EXTRACT TRUE)
endif()

if(NOT EXISTS "${MAPASSIST_NCNN_CONFIG}")
    set(MAPASSIST_NCNN_EXTRACT TRUE)
endif()
if(MAPASSIST_NCNN_EXTRACT)
    file(REMOVE_RECURSE "${MAPASSIST_NCNN_ROOT}")
    file(ARCHIVE_EXTRACT
        INPUT "${MAPASSIST_NCNN_ARCHIVE}"
        DESTINATION "${MAPASSIST_NCNN_CACHE}")
endif()

set(ncnn_DIR "${MAPASSIST_NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn"
    CACHE PATH "Pinned ncnn Android CMake package" FORCE)
find_package(ncnn CONFIG REQUIRED NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native
                 ${CMAKE_CURRENT_BINARY_DIR}/mapassist_core)

# The installable debug APK is used for on-device latency checks. Keep native
# pixel processing optimized there while retaining debug symbols/logging.
target_compile_options(mapassist PRIVATE $<$<CONFIG:Debug>:-O2>)

add_library(mapassist_jni SHARED native_bridge.cpp)
target_compile_features(mapassist_jni PRIVATE cxx_std_17)
target_compile_options(mapassist_jni PRIVATE $<$<CONFIG:Debug>:-O2>)
target_include_directories(mapassist_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native/include)
target_link_libraries(mapassist_jni PRIVATE mapassist ncnn log android)
