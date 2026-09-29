#ifndef DISLOCKER_AES_XTS_ARMV8CE_H
#define DISLOCKER_AES_XTS_ARMV8CE_H

#include <stdint.h>
#include <stddef.h>
#include "dislocker/crypto.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Returns 1 if the current CPU hardware supports ARMv8 Cryptography Extensions (AESE, AESD, AESMC, AESIMC),
 * and has passed internal self-test; returns 0 otherwise (e.g. x86_64 emulator or legacy CPU).
 */
int dislocker_has_armv8_ce(void);

/**
 * Returns 1 if ARMv8 CE is physically supported by CPU and passes self-test.
 */
int dislocker_is_armv8_ce_supported(void);

/**
 * Returns 1 if ARMv8 CE is physically supported AND enabled by user settings.
 */
int dislocker_is_armv8_ce_enabled(void);

/**
 * Dynamically enables (enabled != 0) or disables (enabled == 0) ARMv8 CE hardware acceleration.
 */
void dislocker_set_armv8_ce_enabled(int enabled);

/**
 * Hardware-accelerated AES-XTS decrypt/encrypt for a sector block.
 * Requires length >= 16.
 */
int aes_xts_crypt_armv8ce(aes_ctx_t *crypt_ctx, aes_ctx_t *tweak_ctx, int encrypt,
                          size_t length, const unsigned char *iv,
                          const unsigned char *input, unsigned char *output);

/**
 * Hardware-accelerated AES-CBC decrypt for a sector block.
 * Requires length >= 16 and length % 16 == 0.
 */
int aes_cbc_decrypt_armv8ce(aes_ctx_t *ctx, const uint8_t *iv,
                            const uint8_t *in, uint8_t *out, size_t length);

/**
 * Hardware-accelerated AES-CBC encrypt for a sector block.
 * Requires length >= 16 and length % 16 == 0.
 */
int aes_cbc_encrypt_armv8ce(aes_ctx_t *ctx, const uint8_t *iv,
                            const uint8_t *in, uint8_t *out, size_t length);

/**
 * Hardware-accelerated single-block AES-ECB encrypt.
 */
int aes_ecb_encrypt_armv8ce(aes_ctx_t *ctx, const uint8_t in[16], uint8_t out[16]);

#ifdef __cplusplus
}
#endif

#endif /* DISLOCKER_AES_XTS_ARMV8CE_H */
