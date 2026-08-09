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

add_library(dislocker_core STATIC
    src/dislocker.c
    src/metadata/metadata.c
    src/encryption/aes_xts.c
    src/encryption/vmkey.c
    src/access/access.c
    src/io.c
    src/crypto/crypto_mbedtls.c
)

target_include_directories(dislocker_core PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${MBEDTLS_DIR}/include
)

target_link_libraries(dislocker_core PUBLIC mbedtls_core)
