/* -*- coding: utf-8 -*-
 * Minimal mbedtls configuration for BitLockerDroid's dislocker core.
 *
 * Only the primitives needed by BitLocker decryption are enabled:
 *   - AES (ECB/CBC/CTR blocks) for VMK/FVEK unwrap and AES-XTS sectors
 *   - SHA-256 for the password key-stretching chain
 *   - mbedtls platform layer (calloc/free wrappers)
 *
 * Based on mbedtls v2.28.8's default config.h, trimmed to the above.
 * SPDX-License-Identifier: Apache-2.0 OR GPL-2.0-or-later
 */
#ifndef MBEDTLS_CONFIG_H
#define MBEDTLS_CONFIG_H

/* Disable all modules by default; enable only what we need. */
#define MBEDTLS_AES_C
#define MBEDTLS_SHA256_C
#define MBEDTLS_PLATFORM_C
#define MBEDTLS_ERROR_C
#define MBEDTLS_PLATFORM_MEMORY

/* Cipher modes used by BitLocker: ECB (key unwrap), CBC and CTR (AES-CCM). */
#define MBEDTLS_CIPHER_MODE_CBC
#define MBEDTLS_CIPHER_MODE_CTR

/* Error string support is used by mbedtls error propagation. */
#define MBEDTLS_ERROR_STRERROR_DUMMY

/* We build on the host for self-tests and on Android NDK; no filesystem,
 * threading or entropy needed. */
#undef MBEDTLS_FS_IO
#undef MBEDTLS_THREADING_C
#undef MBEDTLS_THREADING_ALT
#undef MBEDTLS_HAVE_TIME
#undef MBEDTLS_HAVE_TIME_DATE

#include "mbedtls/check_config.h"

#endif /* MBEDTLS_CONFIG_H */
