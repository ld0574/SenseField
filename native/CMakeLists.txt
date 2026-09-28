cmake_minimum_required(VERSION 3.16)
project(mapassist LANGUAGES CXX)

add_library(mapassist SHARED src/mapassist.cpp src/layout_locator.cpp src/player_state.cpp)
target_include_directories(mapassist PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_features(mapassist PRIVATE cxx_std_17)
target_compile_options(mapassist PRIVATE -Wall -Wextra -Wpedantic)
