cmake_minimum_required(VERSION 3.22.1)
project(mapassist_android LANGUAGES C CXX)

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

# Pin the upstream SpeexDSP release used for optional assistant echo
# cancellation and high-quality PCM conversion. The source archive is fetched
# only by the native Android build and verified before extraction.
set(MAPASSIST_SPEEXDSP_VERSION SpeexDSP-1.2.1)
set(MAPASSIST_SPEEXDSP_SHA256
    d17ca363654556a4ff1d02cc13d9eb1fc5a8642c90b40bd54ce266c3807b91a7)
set(MAPASSIST_SPEEXDSP_CACHE "${CMAKE_BINARY_DIR}/speexdsp-${MAPASSIST_SPEEXDSP_VERSION}")
set(MAPASSIST_SPEEXDSP_ARCHIVE
    "${MAPASSIST_SPEEXDSP_CACHE}/speexdsp-${MAPASSIST_SPEEXDSP_VERSION}.tar.gz")
set(MAPASSIST_SPEEXDSP_ROOT
    "${MAPASSIST_SPEEXDSP_CACHE}/speexdsp-${MAPASSIST_SPEEXDSP_VERSION}")
file(MAKE_DIRECTORY "${MAPASSIST_SPEEXDSP_CACHE}")
if(EXISTS "${MAPASSIST_SPEEXDSP_ARCHIVE}")
    file(SHA256 "${MAPASSIST_SPEEXDSP_ARCHIVE}" MAPASSIST_SPEEXDSP_CACHED_SHA256)
    if(NOT MAPASSIST_SPEEXDSP_CACHED_SHA256 STREQUAL MAPASSIST_SPEEXDSP_SHA256)
        file(REMOVE "${MAPASSIST_SPEEXDSP_ARCHIVE}")
        file(REMOVE_RECURSE "${MAPASSIST_SPEEXDSP_ROOT}")
    endif()
endif()
if(NOT EXISTS "${MAPASSIST_SPEEXDSP_ARCHIVE}")
    file(DOWNLOAD
        "https://github.com/xiph/speexdsp/archive/refs/tags/${MAPASSIST_SPEEXDSP_VERSION}.tar.gz"
        "${MAPASSIST_SPEEXDSP_ARCHIVE}"
        EXPECTED_HASH "SHA256=${MAPASSIST_SPEEXDSP_SHA256}"
        STATUS MAPASSIST_SPEEXDSP_DOWNLOAD_STATUS
        TLS_VERIFY ON)
    list(GET MAPASSIST_SPEEXDSP_DOWNLOAD_STATUS 0 MAPASSIST_SPEEXDSP_DOWNLOAD_CODE)
    list(GET MAPASSIST_SPEEXDSP_DOWNLOAD_STATUS 1 MAPASSIST_SPEEXDSP_DOWNLOAD_MESSAGE)
    if(NOT MAPASSIST_SPEEXDSP_DOWNLOAD_CODE EQUAL 0)
        file(REMOVE "${MAPASSIST_SPEEXDSP_ARCHIVE}")
        message(FATAL_ERROR
            "Could not download pinned SpeexDSP source: ${MAPASSIST_SPEEXDSP_DOWNLOAD_MESSAGE}")
    endif()
endif()
if(NOT EXISTS "${MAPASSIST_SPEEXDSP_ROOT}/libspeexdsp/mdf.c")
    file(REMOVE_RECURSE "${MAPASSIST_SPEEXDSP_ROOT}")
    file(ARCHIVE_EXTRACT INPUT "${MAPASSIST_SPEEXDSP_ARCHIVE}"
         DESTINATION "${MAPASSIST_SPEEXDSP_CACHE}")
endif()

set(MAPASSIST_SPEEXDSP_SOURCE "${MAPASSIST_SPEEXDSP_ROOT}")
set(MAPASSIST_SPEEXDSP_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/speexdsp-generated")
file(MAKE_DIRECTORY "${MAPASSIST_SPEEXDSP_GENERATED}")
file(WRITE "${MAPASSIST_SPEEXDSP_GENERATED}/speexdsp_config_types.h"
    "#ifndef __SPEEX_TYPES_H__\n#define __SPEEX_TYPES_H__\n#include <stdint.h>\ntypedef int16_t spx_int16_t;\ntypedef uint16_t spx_uint16_t;\ntypedef int32_t spx_int32_t;\ntypedef uint32_t spx_uint32_t;\n#endif\n")

add_library(speexdsp_aec STATIC
    "${MAPASSIST_SPEEXDSP_SOURCE}/libspeexdsp/mdf.c"
    "${MAPASSIST_SPEEXDSP_SOURCE}/libspeexdsp/fftwrap.c"
    "${MAPASSIST_SPEEXDSP_SOURCE}/libspeexdsp/kiss_fft.c"
    "${MAPASSIST_SPEEXDSP_SOURCE}/libspeexdsp/kiss_fftr.c"
    "${MAPASSIST_SPEEXDSP_SOURCE}/libspeexdsp/resample.c")
target_include_directories(speexdsp_aec PUBLIC
    "${MAPASSIST_SPEEXDSP_SOURCE}/include"
    "${MAPASSIST_SPEEXDSP_SOURCE}/libspeexdsp"
    "${MAPASSIST_SPEEXDSP_GENERATED}")
target_compile_definitions(speexdsp_aec PRIVATE FIXED_POINT USE_KISS_FFT EXPORT=)
target_compile_options(speexdsp_aec PRIVATE -O2)

# Pin the WebRTC Common Audio VAD C sources carried by py-webrtcvad 2.0.10.
# Only cbits/webrtc is compiled; the Python/MIT wrapper is not included.
set(MAPASSIST_WEBRTCVAD_VERSION 2.0.10)
set(MAPASSIST_WEBRTCVAD_SHA256
    f1bed2fb25b63fb7b1a55d64090c993c9c9167b28485ae0bcdd81cf6ede96aea)
set(MAPASSIST_WEBRTCVAD_CACHE "${CMAKE_BINARY_DIR}/webrtcvad-${MAPASSIST_WEBRTCVAD_VERSION}")
set(MAPASSIST_WEBRTCVAD_ARCHIVE
    "${MAPASSIST_WEBRTCVAD_CACHE}/webrtcvad-${MAPASSIST_WEBRTCVAD_VERSION}.tar.gz")
set(MAPASSIST_WEBRTCVAD_ROOT
    "${MAPASSIST_WEBRTCVAD_CACHE}/webrtcvad-${MAPASSIST_WEBRTCVAD_VERSION}")
file(MAKE_DIRECTORY "${MAPASSIST_WEBRTCVAD_CACHE}")
if(EXISTS "${MAPASSIST_WEBRTCVAD_ARCHIVE}")
    file(SHA256 "${MAPASSIST_WEBRTCVAD_ARCHIVE}" MAPASSIST_WEBRTCVAD_CACHED_SHA256)
    if(NOT MAPASSIST_WEBRTCVAD_CACHED_SHA256 STREQUAL MAPASSIST_WEBRTCVAD_SHA256)
        file(REMOVE "${MAPASSIST_WEBRTCVAD_ARCHIVE}")
        file(REMOVE_RECURSE "${MAPASSIST_WEBRTCVAD_ROOT}")
    endif()
endif()
if(NOT EXISTS "${MAPASSIST_WEBRTCVAD_ARCHIVE}")
    file(DOWNLOAD
        "https://files.pythonhosted.org/packages/89/34/e2de2d97f3288512b9ea56f92e7452f8207eb5a0096500badf9dfd48f5e6/webrtcvad-${MAPASSIST_WEBRTCVAD_VERSION}.tar.gz"
        "${MAPASSIST_WEBRTCVAD_ARCHIVE}"
        EXPECTED_HASH "SHA256=${MAPASSIST_WEBRTCVAD_SHA256}"
        STATUS MAPASSIST_WEBRTCVAD_DOWNLOAD_STATUS
        TLS_VERIFY ON)
    list(GET MAPASSIST_WEBRTCVAD_DOWNLOAD_STATUS 0 MAPASSIST_WEBRTCVAD_DOWNLOAD_CODE)
    list(GET MAPASSIST_WEBRTCVAD_DOWNLOAD_STATUS 1 MAPASSIST_WEBRTCVAD_DOWNLOAD_MESSAGE)
    if(NOT MAPASSIST_WEBRTCVAD_DOWNLOAD_CODE EQUAL 0)
        file(REMOVE "${MAPASSIST_WEBRTCVAD_ARCHIVE}")
        message(FATAL_ERROR
            "Could not download pinned WebRTC VAD source: ${MAPASSIST_WEBRTCVAD_DOWNLOAD_MESSAGE}")
    endif()
endif()
if(NOT EXISTS "${MAPASSIST_WEBRTCVAD_ROOT}/cbits/webrtc/common_audio/vad/webrtc_vad.c")
    file(REMOVE_RECURSE "${MAPASSIST_WEBRTCVAD_ROOT}")
    file(ARCHIVE_EXTRACT INPUT "${MAPASSIST_WEBRTCVAD_ARCHIVE}"
         DESTINATION "${MAPASSIST_WEBRTCVAD_CACHE}")
endif()

set(MAPASSIST_WEBRTCVAD_SOURCE "${MAPASSIST_WEBRTCVAD_ROOT}/cbits/webrtc/common_audio")
file(GLOB MAPASSIST_WEBRTCVAD_SIGNAL_SOURCES CONFIGURE_DEPENDS
    "${MAPASSIST_WEBRTCVAD_SOURCE}/signal_processing/*.c")
file(GLOB MAPASSIST_WEBRTCVAD_SPEECH_SOURCES CONFIGURE_DEPENDS
    "${MAPASSIST_WEBRTCVAD_SOURCE}/vad/*.c")
add_library(webrtc_vad STATIC
    ${MAPASSIST_WEBRTCVAD_SIGNAL_SOURCES}
    ${MAPASSIST_WEBRTCVAD_SPEECH_SOURCES})
target_include_directories(webrtc_vad PUBLIC
    "${MAPASSIST_WEBRTCVAD_ROOT}/cbits")
target_compile_definitions(webrtc_vad PRIVATE WEBRTC_POSIX)
target_compile_options(webrtc_vad PRIVATE -O2)

add_library(presentation_audio_jni SHARED presentation_audio_jni.cpp)
target_compile_features(presentation_audio_jni PRIVATE cxx_std_17)
target_include_directories(presentation_audio_jni PRIVATE
    "${MAPASSIST_SPEEXDSP_SOURCE}/include"
    "${MAPASSIST_WEBRTCVAD_ROOT}/cbits")
target_link_libraries(presentation_audio_jni PRIVATE speexdsp_aec webrtc_vad log m)
