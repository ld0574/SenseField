cmake_minimum_required(VERSION 3.22.1)
project(mapassist_android LANGUAGES CXX)

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
target_link_libraries(mapassist_jni PRIVATE mapassist log)
