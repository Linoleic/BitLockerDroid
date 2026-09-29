/*
 * sha256_armv8ce.c -- ARMv8 Cryptographic Extensions SHA-256 hardware acceleration.
 *
 * Utilises ARMv8-A SHA-256 Cryptography Extension instructions:
 *   - SHA256H  (vsha256hq_u32)
 *   - SHA256H2 (vsha256h2q_u32)
 *   - SHA256SU0 (vsha256su0q_u32)
 *   - SHA256SU1 (vsha256su1q_u32)
 */
#include "sha256_armv8ce.h"

#include <string.h>
#include <stdint.h>
#include <stddef.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#if defined(__linux__) || defined(__ANDROID__)
#include <sys/auxv.h>
#include <asm/hwcap.h>
#endif
#endif

/* Global atomic runtime user switch (1 = enabled, 0 = disabled) */
static volatile int s_armv8_sha2_user_enabled = 1;
static volatile int s_armv8_sha2_hw_supported = -1;

#if defined(__aarch64__)

/* Standard SHA-256 round constants */
static const uint32_t K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
    0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
    0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
    0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
};

#define SHA256_ROUND(msg, k_ptr) do { \
    uint32x4_t wk = vaddq_u32((msg), vld1q_u32(k_ptr)); \
    uint32x4_t abcd_orig = abcd; \
    abcd = vsha256hq_u32(abcd, efgh, wk); \
    efgh = vsha256h2q_u32(efgh, abcd_orig, wk); \
} while (0)

#define SHA256_UPDATE_SCHEDULE(w0, w1, w2, w3) do { \
    (w0) = vsha256su0q_u32((w0), (w1)); \
    (w0) = vsha256su1q_u32((w0), (w2), (w3)); \
} while (0)

void sha256_armv8ce_process(uint32_t state[8], const uint8_t data[64])
{
    uint32x4_t abcd = vld1q_u32(&state[0]);
    uint32x4_t efgh = vld1q_u32(&state[4]);
    uint32x4_t abcd_saved = abcd;
    uint32x4_t efgh_saved = efgh;

    /* Load message block and convert big-endian network bytes to 32-bit words */
    uint32x4_t msg0 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 0)));
    uint32x4_t msg1 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 16)));
    uint32x4_t msg2 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 32)));
    uint32x4_t msg3 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 48)));

    /* Rounds 0..15 */
    SHA256_ROUND(msg0, &K[0]);
    SHA256_ROUND(msg1, &K[4]);
    SHA256_ROUND(msg2, &K[8]);
    SHA256_ROUND(msg3, &K[12]);

    /* Rounds 16..31 */
    SHA256_UPDATE_SCHEDULE(msg0, msg1, msg2, msg3);
    SHA256_ROUND(msg0, &K[16]);
    SHA256_UPDATE_SCHEDULE(msg1, msg2, msg3, msg0);
    SHA256_ROUND(msg1, &K[20]);
    SHA256_UPDATE_SCHEDULE(msg2, msg3, msg0, msg1);
    SHA256_ROUND(msg2, &K[24]);
    SHA256_UPDATE_SCHEDULE(msg3, msg0, msg1, msg2);
    SHA256_ROUND(msg3, &K[28]);

    /* Rounds 32..47 */
    SHA256_UPDATE_SCHEDULE(msg0, msg1, msg2, msg3);
    SHA256_ROUND(msg0, &K[32]);
    SHA256_UPDATE_SCHEDULE(msg1, msg2, msg3, msg0);
    SHA256_ROUND(msg1, &K[36]);
    SHA256_UPDATE_SCHEDULE(msg2, msg3, msg0, msg1);
    SHA256_ROUND(msg2, &K[40]);
    SHA256_UPDATE_SCHEDULE(msg3, msg0, msg1, msg2);
    SHA256_ROUND(msg3, &K[44]);

    /* Rounds 48..63 */
    SHA256_UPDATE_SCHEDULE(msg0, msg1, msg2, msg3);
    SHA256_ROUND(msg0, &K[48]);
    SHA256_UPDATE_SCHEDULE(msg1, msg2, msg3, msg0);
    SHA256_ROUND(msg1, &K[52]);
    SHA256_UPDATE_SCHEDULE(msg2, msg3, msg0, msg1);
    SHA256_ROUND(msg2, &K[56]);
    SHA256_UPDATE_SCHEDULE(msg3, msg0, msg1, msg2);
    SHA256_ROUND(msg3, &K[60]);

    /* Accumulate state */
    abcd = vaddq_u32(abcd, abcd_saved);
    efgh = vaddq_u32(efgh, efgh_saved);

    vst1q_u32(&state[0], abcd);
    vst1q_u32(&state[4], efgh);
}

int sha256_armv8ce(const uint8_t *data, size_t len, uint8_t out[32])
{
    if (!data || !out) return -1;

    uint32_t state[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };

    size_t total_len = len;
    while (len >= 64) {
        sha256_armv8ce_process(state, data);
        data += 64;
        len -= 64;
    }

    /* Standard SHA-256 padding */
    uint8_t final_buf[128];
    memset(final_buf, 0, sizeof(final_buf));
    if (len > 0) {
        memcpy(final_buf, data, len);
    }
    final_buf[len] = 0x80;

    size_t final_blocks = (len < 56) ? 64 : 128;
    uint64_t total_bits = (uint64_t)total_len * 8ULL;
    for (int i = 0; i < 8; i++) {
        final_buf[final_blocks - 1 - i] = (uint8_t)(total_bits >> (i * 8));
    }

    sha256_armv8ce_process(state, final_buf);
    if (final_blocks == 128) {
        sha256_armv8ce_process(state, final_buf + 64);
    }

    /* Output big-endian digest */
    for (int i = 0; i < 8; i++) {
        out[i * 4 + 0] = (uint8_t)(state[i] >> 24);
        out[i * 4 + 1] = (uint8_t)(state[i] >> 16);
        out[i * 4 + 2] = (uint8_t)(state[i] >> 8);
        out[i * 4 + 3] = (uint8_t)(state[i] >> 0);
    }

    return 0;
}

typedef struct {
    uint8_t updated_hash[32];
    uint8_t password_hash[32];
    uint8_t salt[16];
    uint64_t hash_count;
} chain_hash_layout_t;

int bitlocker_stretch_key_rounds_armv8ce(void *ch_ptr, uint8_t *result, uint32_t rounds)
{
    if (!ch_ptr) return -1;
    chain_hash_layout_t *ch = (chain_hash_layout_t *)ch_ptr;

    /* Pre-construct the 2 message blocks (88 bytes total input per round) */
    uint8_t b0[64];
    uint8_t b1[64];

    memcpy(b0, ch->updated_hash, 32);
    memcpy(b0 + 32, ch->password_hash, 32);

    memset(b1, 0, 64);
    memcpy(b1, ch->salt, 16);
    b1[24] = 0x80;
    b1[62] = 0x02; /* 704 bits = 88 * 8 = 0x02c0 */
    b1[63] = 0xc0;

    uint64_t count = ch->hash_count;
    uint32_t state[8];

    for (uint32_t loop = 0; loop < rounds; ++loop) {
        state[0] = 0x6a09e667; state[1] = 0xbb67ae85;
        state[2] = 0x3c6ef372; state[3] = 0xa54ff53a;
        state[4] = 0x510e527f; state[5] = 0x9b05688c;
        state[6] = 0x1f83d9ab; state[7] = 0x5be0cd19;

        /* Block 0: updated_hash[32] + password_hash[32] */
        sha256_armv8ce_process(state, b0);

        /* Block 1: salt[16] + hash_count[8] + padding */
        memcpy(b1 + 16, &count, 8);
        sha256_armv8ce_process(state, b1);

        count++;

        /* Update b0[0..31] with newly computed hash for the next round */
        for (int i = 0; i < 8; i++) {
            b0[i * 4 + 0] = (uint8_t)(state[i] >> 24);
            b0[i * 4 + 1] = (uint8_t)(state[i] >> 16);
            b0[i * 4 + 2] = (uint8_t)(state[i] >> 8);
            b0[i * 4 + 3] = (uint8_t)(state[i] >> 0);
        }
    }

    ch->hash_count = count;
    memcpy(ch->updated_hash, b0, 32);
    if (result) {
        memcpy(result, b0, 32);
    }
    return 0;
}

int bitlocker_stretch_key_armv8ce(void *ch_ptr, uint8_t *result)
{
    return bitlocker_stretch_key_rounds_armv8ce(ch_ptr, result, 0x100000);
}

#endif /* __aarch64__ */

int dislocker_is_armv8_sha2_supported(void)
{
    if (s_armv8_sha2_hw_supported != -1) {
        return s_armv8_sha2_hw_supported;
    }

#if defined(__aarch64__)
#if defined(__linux__) || defined(__ANDROID__)
    unsigned long hwcaps = getauxval(AT_HWCAP);
#ifndef HWCAP_SHA2
#define HWCAP_SHA2 (1 << 6)
#endif
    if (!(hwcaps & HWCAP_SHA2)) {
        s_armv8_sha2_hw_supported = 0;
        return 0;
    }
#endif

    /* 1. NIST standard SHA-256 test vector: "abc" -> ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad */
    static const uint8_t nist_abc[3] = {'a', 'b', 'c'};
    static const uint8_t expected_hash[32] = {
        0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea,
        0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
        0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c,
        0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad
    };
    uint8_t out[32];
    if (sha256_armv8ce(nist_abc, 3, out) != 0 || memcmp(out, expected_hash, 32) != 0) {
        s_armv8_sha2_hw_supported = 0;
        return 0;
    }

    /* 2. BitLocker 88-byte chain hash self-test: compare specialized rounds against general sha256_armv8ce */
    chain_hash_layout_t test_ch1, test_ch2;
    memset(&test_ch1, 0x5a, sizeof(test_ch1));
    test_ch1.hash_count = 0;
    memcpy(&test_ch2, &test_ch1, sizeof(test_ch1));

    for (int r = 0; r < 2; r++) {
        sha256_armv8ce((uint8_t *)&test_ch1, sizeof(test_ch1), test_ch1.updated_hash);
        test_ch1.hash_count++;
    }

    uint8_t hw_result[32];
    if (bitlocker_stretch_key_rounds_armv8ce(&test_ch2, hw_result, 2) != 0) {
        s_armv8_sha2_hw_supported = 0;
        return 0;
    }

    if (memcmp(test_ch1.updated_hash, hw_result, 32) != 0 ||
        memcmp(test_ch1.updated_hash, test_ch2.updated_hash, 32) != 0 ||
        test_ch1.hash_count != test_ch2.hash_count) {
        s_armv8_sha2_hw_supported = 0;
        return 0;
    }

    s_armv8_sha2_hw_supported = 1;
    return 1;
#else
    s_armv8_sha2_hw_supported = 0;
    return 0;
#endif
}

int dislocker_is_armv8_sha2_enabled(void)
{
    return s_armv8_sha2_user_enabled && dislocker_is_armv8_sha2_supported();
}

void dislocker_set_armv8_sha2_enabled(int enabled)
{
    s_armv8_sha2_user_enabled = enabled ? 1 : 0;
}

#if !defined(__aarch64__)
void sha256_armv8ce_process(uint32_t state[8], const uint8_t data[64])
{
    (void)state; (void)data;
}

int sha256_armv8ce(const uint8_t *data, size_t len, uint8_t out[32])
{
    (void)data; (void)len; (void)out;
    return -1;
}

int bitlocker_stretch_key_rounds_armv8ce(void *ch, uint8_t *result, uint32_t rounds)
{
    (void)ch; (void)result; (void)rounds;
    return -1;
}

int bitlocker_stretch_key_armv8ce(void *ch, uint8_t *result)
{
    (void)ch; (void)result;
    return -1;
}
#endif
