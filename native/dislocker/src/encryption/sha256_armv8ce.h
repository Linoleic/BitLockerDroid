/*
 * sha256_armv8ce.h -- ARMv8 Cryptographic Extensions SHA-256 hardware acceleration.
 *
 * Utilises ARMv8-A SHA-256 Cryptography Extension instructions:
 *   - SHA256H  (vsha256hq_u32)
 *   - SHA256H2 (vsha256h2q_u32)
 *   - SHA256SU0 (vsha256su0q_u32)
 *   - SHA256SU1 (vsha256su1q_u32)
 */
#ifndef DISLOCKER_SHA256_ARMV8CE_H
#define DISLOCKER_SHA256_ARMV8CE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Checks whether the current CPU supports ARMv8 SHA-256 hardware acceleration
 * and verifies that self-tests pass.
 * Returns 1 if supported, 0 otherwise.
 */
int dislocker_is_armv8_sha2_supported(void);

/**
 * Checks whether ARMv8 SHA-256 acceleration is currently enabled.
 * Returns 1 if supported and enabled, 0 otherwise.
 */
int dislocker_is_armv8_sha2_enabled(void);

/**
 * Enables or disables ARMv8 SHA-256 hardware acceleration at runtime.
 */
void dislocker_set_armv8_sha2_enabled(int enabled);

/**
 * Processes a single 64-byte block of data using ARMv8 SHA-256 instructions,
 * updating the 8-word SHA-256 state in-place.
 */
void sha256_armv8ce_process(uint32_t state[8], const uint8_t data[64]);

/**
 * Computes full SHA-256 hash of arbitrary-length data using ARMv8 hardware instructions.
 * Returns 0 on success, negative error code on failure.
 */
int sha256_armv8ce(const uint8_t *data, size_t len, uint8_t out[32]);

/**
 * Specialized hardware accelerated BitLocker key-stretching chain hash with custom round count.
 */
int bitlocker_stretch_key_rounds_armv8ce(void *ch, uint8_t *result, uint32_t rounds);

/**
 * Specialized hardware accelerated BitLocker key-stretching chain hash.
 * Executes 0x100000 (1,048,576) iterations of BitLocker chain hash in ARM NEON registers.
 * Returns 0 on success, negative error code on failure.
 */
int bitlocker_stretch_key_armv8ce(void *ch, uint8_t *result);

#ifdef __cplusplus
}
#endif

#endif /* DISLOCKER_SHA256_ARMV8CE_H */
