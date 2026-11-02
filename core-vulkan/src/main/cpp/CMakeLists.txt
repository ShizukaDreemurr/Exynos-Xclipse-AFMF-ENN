cmake_minimum_required(VERSION 3.22.1)
project(fluidframes_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(fluidframes_native SHARED
    jni_bridge.cpp
    pipeline.cpp)

target_compile_definitions(fluidframes_native PRIVATE VK_USE_PLATFORM_ANDROID_KHR=1)
target_compile_options(fluidframes_native PRIVATE -Wall -Wextra -Wno-unused-parameter)

target_link_libraries(fluidframes_native
    vulkan
    android
    dl
    log)
