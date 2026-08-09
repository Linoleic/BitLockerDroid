cmake_minimum_required(VERSION 3.22.1)

project(dislocker LANGUAGES C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_subdirectory(dislocker)

add_library(dislocker_jni SHARED
    jni/dislocker_jni.c
)

target_include_directories(dislocker_jni PRIVATE
    dislocker/include
    third_party/mbedtls/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(dislocker_jni
    dislocker_core
    log
)

# Export as libdislocker.so so Kotlin System.loadLibrary("dislocker") works
set_target_properties(dislocker_jni PROPERTIES
    OUTPUT_NAME dislocker
    PREFIX lib
)
