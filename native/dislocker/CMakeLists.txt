# mbedtls (third_party) built for the BitLockerDroid core.
# Only AES, SHA-256, and the platform/error support files are compiled;
# see third_party/mbedtls/include/mbedtls/config.h for the trimmed config.
set(MBEDTLS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/mbedtls)

add_library(mbedtls_core STATIC
    ${MBEDTLS_DIR}/library/aes.c
    ${MBEDTLS_DIR}/library/sha256.c
    ${MBEDTLS_DIR}/library/platform.c
    ${MBEDTLS_DIR}/library/platform_util.c
    ${MBEDTLS_DIR}/library/error.c
)

target_include_directories(mbedtls_core PUBLIC
    ${MBEDTLS_DIR}/include
)

set(NTFS3G_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libntfs-3g)

add_library(ntfs3g_core STATIC
    ${NTFS3G_DIR}/src/acls.c
    ${NTFS3G_DIR}/src/attrib.c
    ${NTFS3G_DIR}/src/attrlist.c
    ${NTFS3G_DIR}/src/bitmap.c
    ${NTFS3G_DIR}/src/bootsect.c
    ${NTFS3G_DIR}/src/cache.c
    ${NTFS3G_DIR}/src/collate.c
    ${NTFS3G_DIR}/src/compat.c
    ${NTFS3G_DIR}/src/compress.c
    ${NTFS3G_DIR}/src/debug.c
    ${NTFS3G_DIR}/src/device.c
    ${NTFS3G_DIR}/src/dir.c
    ${NTFS3G_DIR}/src/ea.c
    ${NTFS3G_DIR}/src/efs.c
    ${NTFS3G_DIR}/src/index.c
    ${NTFS3G_DIR}/src/inode.c
    ${NTFS3G_DIR}/src/ioctl.c
    ${NTFS3G_DIR}/src/lcnalloc.c
    ${NTFS3G_DIR}/src/logfile.c
    ${NTFS3G_DIR}/src/logging.c
    ${NTFS3G_DIR}/src/mft.c
    ${NTFS3G_DIR}/src/misc.c
    ${NTFS3G_DIR}/src/mst.c
    ${NTFS3G_DIR}/src/object_id.c
    ${NTFS3G_DIR}/src/realpath.c
    ${NTFS3G_DIR}/src/reparse.c
    ${NTFS3G_DIR}/src/runlist.c
    ${NTFS3G_DIR}/src/security.c
    ${NTFS3G_DIR}/src/unistr.c
    ${NTFS3G_DIR}/src/unix_io.c
    ${NTFS3G_DIR}/src/volume.c
    ${NTFS3G_DIR}/src/xattrs.c
)

target_include_directories(ntfs3g_core PUBLIC
    ${NTFS3G_DIR}/include
    ${NTFS3G_DIR}/include/ntfs-3g
)

target_compile_definitions(ntfs3g_core PUBLIC
    HAVE_CONFIG_H
    _GNU_SOURCE
    _FILE_OFFSET_BITS=64
)

target_compile_options(ntfs3g_core PRIVATE
    -Wno-pointer-sign
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-parentheses
    -Wno-format
    -Wno-sign-compare
)

set(FATFS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/fatfs)

add_library(fatfs_core STATIC
    ${FATFS_DIR}/ff.c
    ${FATFS_DIR}/ffunicode.c
)

target_include_directories(fatfs_core PUBLIC
    ${FATFS_DIR}
)

target_compile_definitions(fatfs_core PUBLIC
    _GNU_SOURCE
    _FILE_OFFSET_BITS=64
)

target_compile_options(fatfs_core PRIVATE
    -Wno-pointer-sign
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-parentheses
    -Wno-format
    -Wno-sign-compare
)

add_library(dislocker_core STATIC
    src/dislocker.c
    src/metadata/metadata.c
    src/encryption/aes_xts.c
    src/encryption/vmkey.c
    src/access/access.c
    src/io.c
    src/crypto/crypto_mbedtls.c
    src/ntfs3g_device.c
    src/fatfs_device.c
)

target_include_directories(dislocker_core PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${MBEDTLS_DIR}/include
    ${NTFS3G_DIR}/include
    ${NTFS3G_DIR}/include/ntfs-3g
    ${FATFS_DIR}
)

target_link_libraries(dislocker_core PUBLIC mbedtls_core ntfs3g_core fatfs_core)
