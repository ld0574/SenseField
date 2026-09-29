cmake_minimum_required(VERSION 3.16)
project(mapassist LANGUAGES CXX)

add_library(mapassist SHARED src/mapassist.cpp src/layout_locator.cpp src/player_state.cpp)
target_include_directories(mapassist PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_features(mapassist PRIVATE cxx_std_17)
target_compile_options(mapassist PRIVATE -Wall -Wextra -Wpedantic)

# The Android sub-build reuses this directory as a cross-compiled library;
# keep the host-only memory regression executable out of that APK graph.
include(CTest)
if(BUILD_TESTING AND NOT ANDROID)
    add_executable(mapassist_perception_memory_test tests/perception_memory_test.cpp)
    target_compile_features(mapassist_perception_memory_test PRIVATE cxx_std_17)
    target_include_directories(mapassist_perception_memory_test
                               PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    target_link_libraries(mapassist_perception_memory_test PRIVATE mapassist)
    add_test(NAME mapassist_perception_memory
             COMMAND mapassist_perception_memory_test)
endif()
