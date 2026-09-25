cmake_minimum_required(VERSION 3.22.1)

project(dislocker LANGUAGES C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Hardening for every native target (JNI lib and both root daemons):
# - 64-bit off_t on 32-bit ABIs so block offsets > 2 GiB do not truncate
# - _FORTIFY_SOURCE=2 bounds-checked libc hardened variants (Release -O2+)
# - stack protector pinned explicitly rather than relying on NDK defaults
# - full RELRO + BIND_NOW so the GOT of privileged binaries is read-only
add_compile_definitions(_FILE_OFFSET_BITS=64 _FORTIFY_SOURCE=2)
add_compile_options(-fstack-protector-strong)
add_link_options("-Wl,-z,relro" "-Wl,-z,now")

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

# 16 KB page-aligned replacements for libaums dependencies
add_library(errno-lib SHARED
    libaums_compat/errno.c
)
target_link_options(errno-lib PRIVATE
    "-Wl,-z,max-page-size=16384"
)

add_library(usb-lib SHARED
    libaums_compat/usb.c
)
target_link_libraries(usb-lib PRIVATE
    log
)
target_link_options(usb-lib PRIVATE
    "-Wl,-z,max-page-size=16384"
)

