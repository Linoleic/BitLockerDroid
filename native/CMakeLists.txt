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
    third_party/libntfs-3g/include
    third_party/libntfs-3g/include/ntfs-3g
    third_party/fatfs
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(dislocker_jni
    dislocker_core
    log
)

# 16 KB page size alignment for Android 15+ compatibility
target_link_options(dislocker_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
)

# Export as libdislocker.so so Kotlin System.loadLibrary("dislocker") works
set_target_properties(dislocker_jni PROPERTIES
    OUTPUT_NAME dislocker
    PREFIX lib
)

add_executable(bitlocker_io_daemon
    daemon/io_daemon.c
)

target_link_options(bitlocker_io_daemon PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-pie"
)

set_target_properties(bitlocker_io_daemon PROPERTIES
    OUTPUT_NAME bitlocker_io
    PREFIX "lib"
    SUFFIX ".so"
)

add_executable(bitlocker_fuse_daemon
    daemon/fuse_daemon.c
)

target_include_directories(bitlocker_fuse_daemon PRIVATE
    dislocker/include
    third_party/mbedtls/include
    third_party/libntfs-3g/include
    third_party/libntfs-3g/include/ntfs-3g
    third_party/fatfs
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(bitlocker_fuse_daemon PRIVATE
    dislocker_core
    ntfs3g_core
    fatfs_core
    mbedtls_core
    log
)

target_link_options(bitlocker_fuse_daemon PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-pie"
)

set_target_properties(bitlocker_fuse_daemon PROPERTIES
    OUTPUT_NAME bitlocker_fuse
    PREFIX "lib"
    SUFFIX ".so"
)

