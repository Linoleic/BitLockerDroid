#if defined(__aarch64__)

#include "aes_xts_armv8ce.h"

#include <arm_neon.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>
#include <string.h>
#include <stdint.h>
#include <android/log.h>

#define TAG "BitLocker-Crypto"

/* Multiply by x in GF(2^128) for Little-Endian XTS tweak.
 * Reduction polynomial is x^128 + x^7 + x^2 + x + 1 (0x87).
 */
static inline uint8x16_t armv8ce_gf128mul_x(uint8x16_t t)
{
    uint64x2_t u64 = vreinterpretq_u64_u8(t);
    uint64_t a = vgetq_lane_u64(u64, 0); // lower 64 bits
    uint64_t b = vgetq_lane_u64(u64, 1); // upper 64 bits

    uint64_t carry = b >> 63;
    uint64_t tt = (uint64_t)0x87ULL & (-(int64_t)carry);
    uint64_t ra = (a << 1) ^ tt;
    uint64_t rb = (b << 1) | (a >> 63);

    return vreinterpretq_u8_u64(vsetq_lane_u64(rb, vsetq_lane_u64(ra, vdupq_n_u64(0), 0), 1));
}

/* Single-block AES encryption (used for initial tweak generation) */
static inline uint8x16_t armv8ce_aes_encrypt_block(uint8x16_t state, const uint8x16_t *rk, int nr)
{
    state = vaesmcq_u8(vaeseq_u8(state, rk[0]));
    for (int i = 1; i < nr - 1; i++) {
        state = vaesmcq_u8(vaeseq_u8(state, rk[i]));
    }
    state = vaeseq_u8(state, rk[nr - 1]);
    state = veorq_u8(state, rk[nr]);
    return state;
}

/* Single-block AES decryption */
static inline uint8x16_t armv8ce_aes_decrypt_block(uint8x16_t state, const uint8x16_t *dec_rk, int nr)
{
    state = vaesimcq_u8(vaesdq_u8(state, dec_rk[0]));
    for (int i = 1; i < nr - 1; i++) {
        state = vaesimcq_u8(vaesdq_u8(state, dec_rk[i]));
    }
    state = vaesdq_u8(state, dec_rk[nr - 1]);
    state = veorq_u8(state, dec_rk[nr]);
    return state;
}

/* Self-test: encrypt and decrypt a known test pattern to ensure 100% roundtrip correctness */
static int armv8ce_self_test(void);

static volatile int s_armv8_ce_user_enabled = 1;

int dislocker_has_armv8_ce(void)
{
    static int s_has_ce = -1;
    if (__builtin_expect(s_has_ce != -1, 1)) {
        return s_has_ce;
    }

#if defined(HWCAP_AES)
    unsigned long hwcap = getauxval(AT_HWCAP);
    if ((hwcap & HWCAP_AES) != 0) {
        if (armv8ce_self_test()) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "ARMv8 Cryptography Extensions (Hardware AES) enabled and verified");
            s_has_ce = 1;
            return 1;
        } else {
            __android_log_print(ANDROID_LOG_WARN, TAG, "ARMv8 CE detected but self-test failed, falling back to software");
        }
    }
#endif

    s_has_ce = 0;
    return 0;
}

int dislocker_is_armv8_ce_supported(void)
{
    return dislocker_has_armv8_ce();
}

int dislocker_is_armv8_ce_enabled(void)
{
    return dislocker_has_armv8_ce() && s_armv8_ce_user_enabled;
}

void dislocker_set_armv8_ce_enabled(int enabled)
{
    s_armv8_ce_user_enabled = enabled ? 1 : 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "ARMv8 Hardware AES user switch set to: %s",
                        s_armv8_ce_user_enabled ? "ENABLED" : "DISABLED");
}

int aes_xts_crypt_armv8ce(aes_ctx_t *crypt_ctx, aes_ctx_t *tweak_ctx, int encrypt,
                          size_t length, const unsigned char *iv,
                          const unsigned char *input, unsigned char *output)
{
    if (length < 16) return -1;

    int nr = crypt_ctx->enc.nr;
    if (nr != 10 && nr != 14) return -1; // AES-128 (10 rounds) or AES-256 (14 rounds)

    uint8x16_t enc_rk[16];
    uint8x16_t dec_rk[16];

    // Load encryption round keys from mbedtls key schedule
    for (int i = 0; i <= nr; i++) {
        enc_rk[i] = vld1q_u8((const uint8_t *)(crypt_ctx->enc.rk + i * 4));
    }

    if (!encrypt) {
        // Derive Equivalent Inverse Cipher decryption round keys
        dec_rk[0] = enc_rk[nr];
        for (int i = 1; i < nr; i++) {
            dec_rk[i] = vaesimcq_u8(enc_rk[nr - i]);
        }
        dec_rk[nr] = enc_rk[0];
    }

    // Load tweak encryption round keys
    int tweak_nr = tweak_ctx->enc.nr;
    uint8x16_t tweak_rk[16];
    for (int i = 0; i <= tweak_nr; i++) {
        tweak_rk[i] = vld1q_u8((const uint8_t *)(tweak_ctx->enc.rk + i * 4));
    }

    // Step 1: Encrypt IV to obtain initial tweak T0
    uint8x16_t t = armv8ce_aes_encrypt_block(vld1q_u8(iv), tweak_rk, tweak_nr);

    size_t nb_blocks = length / 16;
    size_t remaining = length % 16;
    const uint8_t *in = input;
    uint8_t *out = output;

    // Step 2: 4-way interleaved loop for max superscalar pipeline throughput
    while (nb_blocks >= 4) {
        uint8x16_t t0 = t;
        uint8x16_t t1 = armv8ce_gf128mul_x(t0);
        uint8x16_t t2 = armv8ce_gf128mul_x(t1);
        uint8x16_t t3 = armv8ce_gf128mul_x(t2);
        t = armv8ce_gf128mul_x(t3); // Next tweak

        uint8x16_t b0 = veorq_u8(vld1q_u8(in + 0),  t0);
        uint8x16_t b1 = veorq_u8(vld1q_u8(in + 16), t1);
        uint8x16_t b2 = veorq_u8(vld1q_u8(in + 32), t2);
        uint8x16_t b3 = veorq_u8(vld1q_u8(in + 48), t3);

        if (encrypt) {
            b0 = vaesmcq_u8(vaeseq_u8(b0, enc_rk[0]));
            b1 = vaesmcq_u8(vaeseq_u8(b1, enc_rk[0]));
            b2 = vaesmcq_u8(vaeseq_u8(b2, enc_rk[0]));
            b3 = vaesmcq_u8(vaeseq_u8(b3, enc_rk[0]));

            for (int r = 1; r < nr - 1; r++) {
                b0 = vaesmcq_u8(vaeseq_u8(b0, enc_rk[r]));
                b1 = vaesmcq_u8(vaeseq_u8(b1, enc_rk[r]));
                b2 = vaesmcq_u8(vaeseq_u8(b2, enc_rk[r]));
                b3 = vaesmcq_u8(vaeseq_u8(b3, enc_rk[r]));
            }

            b0 = veorq_u8(veorq_u8(vaeseq_u8(b0, enc_rk[nr - 1]), enc_rk[nr]), t0);
            b1 = veorq_u8(veorq_u8(vaeseq_u8(b1, enc_rk[nr - 1]), enc_rk[nr]), t1);
            b2 = veorq_u8(veorq_u8(vaeseq_u8(b2, enc_rk[nr - 1]), enc_rk[nr]), t2);
            b3 = veorq_u8(veorq_u8(vaeseq_u8(b3, enc_rk[nr - 1]), enc_rk[nr]), t3);
        } else {
            b0 = vaesimcq_u8(vaesdq_u8(b0, dec_rk[0]));
            b1 = vaesimcq_u8(vaesdq_u8(b1, dec_rk[0]));
            b2 = vaesimcq_u8(vaesdq_u8(b2, dec_rk[0]));
            b3 = vaesimcq_u8(vaesdq_u8(b3, dec_rk[0]));

            for (int r = 1; r < nr - 1; r++) {
                b0 = vaesimcq_u8(vaesdq_u8(b0, dec_rk[r]));
                b1 = vaesimcq_u8(vaesdq_u8(b1, dec_rk[r]));
                b2 = vaesimcq_u8(vaesdq_u8(b2, dec_rk[r]));
                b3 = vaesimcq_u8(vaesdq_u8(b3, dec_rk[r]));
            }

            b0 = veorq_u8(veorq_u8(vaesdq_u8(b0, dec_rk[nr - 1]), dec_rk[nr]), t0);
            b1 = veorq_u8(veorq_u8(vaesdq_u8(b1, dec_rk[nr - 1]), dec_rk[nr]), t1);
            b2 = veorq_u8(veorq_u8(vaesdq_u8(b2, dec_rk[nr - 1]), dec_rk[nr]), t2);
            b3 = veorq_u8(veorq_u8(vaesdq_u8(b3, dec_rk[nr - 1]), dec_rk[nr]), t3);
        }

        vst1q_u8(out + 0,  b0);
        vst1q_u8(out + 16, b1);
        vst1q_u8(out + 32, b2);
        vst1q_u8(out + 48, b3);

        in += 64;
        out += 64;
        nb_blocks -= 4;
    }

    // Step 3: Single-block tail loop (1 to 3 blocks)
    while (nb_blocks > 0) {
        uint8x16_t b = veorq_u8(vld1q_u8(in), t);
        if (encrypt) {
            b = armv8ce_aes_encrypt_block(b, enc_rk, nr);
        } else {
            b = armv8ce_aes_decrypt_block(b, dec_rk, nr);
        }
        b = veorq_u8(b, t);
        vst1q_u8(out, b);

        t = armv8ce_gf128mul_x(t);
        in += 16;
        out += 16;
        nb_blocks--;
    }

    // Ciphertext stealing if unaligned (standard sector I/O in BitLocker is always 512B or 4096B aligned)
    if (remaining != 0) {
        // Return 1 to signal fallback for partial block tail if ever encountered
        return 1;
    }

    return 0;
}

int aes_cbc_decrypt_armv8ce(aes_ctx_t *ctx, const uint8_t *iv,
                            const uint8_t *in, uint8_t *out, size_t length)
{
    if (length < 16 || (length % 16) != 0) return -1;

    int nr = ctx->enc.nr;
    if (nr != 10 && nr != 14) return -1;

    uint8x16_t enc_rk[16];
    uint8x16_t dec_rk[16];

    for (int i = 0; i <= nr; i++) {
        enc_rk[i] = vld1q_u8((const uint8_t *)(ctx->enc.rk + i * 4));
    }

    // Equivalent Inverse Cipher decryption keys
    dec_rk[0] = enc_rk[nr];
    for (int i = 1; i < nr; i++) {
        dec_rk[i] = vaesimcq_u8(enc_rk[nr - i]);
    }
    dec_rk[nr] = enc_rk[0];

    uint8x16_t prev = vld1q_u8(iv);
    size_t nb_blocks = length / 16;
    const uint8_t *src = in;
    uint8_t *dst = out;

    // 4-way interleaved CBC decrypt (in-place safe: all 4 ciphertext blocks are loaded first)
    while (nb_blocks >= 4) {
        uint8x16_t c0 = vld1q_u8(src + 0);
        uint8x16_t c1 = vld1q_u8(src + 16);
        uint8x16_t c2 = vld1q_u8(src + 32);
        uint8x16_t c3 = vld1q_u8(src + 48);

        uint8x16_t b0 = c0;
        uint8x16_t b1 = c1;
        uint8x16_t b2 = c2;
        uint8x16_t b3 = c3;

        // Round 0
        b0 = vaesimcq_u8(vaesdq_u8(b0, dec_rk[0]));
        b1 = vaesimcq_u8(vaesdq_u8(b1, dec_rk[0]));
        b2 = vaesimcq_u8(vaesdq_u8(b2, dec_rk[0]));
        b3 = vaesimcq_u8(vaesdq_u8(b3, dec_rk[0]));

        // Intermediate rounds
        for (int r = 1; r < nr - 1; r++) {
            b0 = vaesimcq_u8(vaesdq_u8(b0, dec_rk[r]));
            b1 = vaesimcq_u8(vaesdq_u8(b1, dec_rk[r]));
            b2 = vaesimcq_u8(vaesdq_u8(b2, dec_rk[r]));
            b3 = vaesimcq_u8(vaesdq_u8(b3, dec_rk[r]));
        }

        // Final round
        b0 = veorq_u8(vaesdq_u8(b0, dec_rk[nr - 1]), dec_rk[nr]);
        b1 = veorq_u8(vaesdq_u8(b1, dec_rk[nr - 1]), dec_rk[nr]);
        b2 = veorq_u8(vaesdq_u8(b2, dec_rk[nr - 1]), dec_rk[nr]);
        b3 = veorq_u8(vaesdq_u8(b3, dec_rk[nr - 1]), dec_rk[nr]);

        // CBC XOR step: Pi = D(Ci) ^ Ci-1
        b0 = veorq_u8(b0, prev);
        b1 = veorq_u8(b1, c0);
        b2 = veorq_u8(b2, c1);
        b3 = veorq_u8(b3, c2);

        // Update prev for next batch (last ciphertext block)
        prev = c3;

        vst1q_u8(dst + 0,  b0);
        vst1q_u8(dst + 16, b1);
        vst1q_u8(dst + 32, b2);
        vst1q_u8(dst + 48, b3);

        src += 64;
        dst += 64;
        nb_blocks -= 4;
    }

    // Single-block tail loop (1 to 3 blocks)
    while (nb_blocks > 0) {
        uint8x16_t c = vld1q_u8(src);
        uint8x16_t b = armv8ce_aes_decrypt_block(c, dec_rk, nr);
        b = veorq_u8(b, prev);
        prev = c;
        vst1q_u8(dst, b);

        src += 16;
        dst += 16;
        nb_blocks--;
    }

    return 0;
}

int aes_cbc_encrypt_armv8ce(aes_ctx_t *ctx, const uint8_t *iv,
                            const uint8_t *in, uint8_t *out, size_t length)
{
    if (length < 16 || (length % 16) != 0) return -1;

    int nr = ctx->enc.nr;
    if (nr != 10 && nr != 14) return -1;

    uint8x16_t enc_rk[16];
    for (int i = 0; i <= nr; i++) {
        enc_rk[i] = vld1q_u8((const uint8_t *)(ctx->enc.rk + i * 4));
    }

    uint8x16_t prev = vld1q_u8(iv);
    size_t nb_blocks = length / 16;
    const uint8_t *src = in;
    uint8_t *dst = out;

    for (size_t i = 0; i < nb_blocks; i++) {
        uint8x16_t p = vld1q_u8(src);
        uint8x16_t state = veorq_u8(p, prev);
        state = armv8ce_aes_encrypt_block(state, enc_rk, nr);
        vst1q_u8(dst, state);
        prev = state;

        src += 16;
        dst += 16;
    }

    return 0;
}

int aes_ecb_encrypt_armv8ce(aes_ctx_t *ctx, const uint8_t in[16], uint8_t out[16])
{
    int nr = ctx->enc.nr;
    if (nr != 10 && nr != 14) return -1;

    uint8x16_t enc_rk[16];
    for (int i = 0; i <= nr; i++) {
        enc_rk[i] = vld1q_u8((const uint8_t *)(ctx->enc.rk + i * 4));
    }

    uint8x16_t block = vld1q_u8(in);
    block = armv8ce_aes_encrypt_block(block, enc_rk, nr);
    vst1q_u8(out, block);
    return 0;
}

static int armv8ce_self_test(void)
{
    aes_ctx_t crypt_ctx;
    aes_ctx_t tweak_ctx;

    uint8_t test_key[16] = {
        0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae, 0xd2, 0xa6,
        0xab, 0xf7, 0x15, 0x88, 0x09, 0xcf, 0x4f, 0x3c
    };
    uint8_t tweak_key[16] = {
        0x8e, 0x73, 0xb0, 0xf7, 0xda, 0x0e, 0x64, 0x52,
        0xc8, 0x10, 0xf3, 0x2b, 0x80, 0x90, 0x79, 0xe5
    };
    uint8_t iv[16] = { 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 0, 0 };

    aes_ctx_init(&crypt_ctx, test_key, AES_128);
    aes_ctx_init(&tweak_ctx, tweak_key, AES_128);

    uint8_t plain[64];
    for (int i = 0; i < 64; i++) plain[i] = (uint8_t)(i * 3 + 7);

    uint8_t cipher[64];
    uint8_t decrypted[64];

    // 1. XTS Encrypt & Decrypt Roundtrip
    if (aes_xts_crypt_armv8ce(&crypt_ctx, &tweak_ctx, 1, 64, iv, plain, cipher) != 0) {
        return 0;
    }
    if (aes_xts_crypt_armv8ce(&crypt_ctx, &tweak_ctx, 0, 64, iv, cipher, decrypted) != 0) {
        return 0;
    }
    if (memcmp(plain, decrypted, 64) != 0) {
        return 0;
    }

    // 2. CBC Encrypt & Decrypt Roundtrip (in-place verify)
    uint8_t cbc_buf[64];
    memcpy(cbc_buf, plain, 64);
    if (aes_cbc_encrypt_armv8ce(&crypt_ctx, iv, cbc_buf, cbc_buf, 64) != 0) {
        return 0;
    }
    if (aes_cbc_decrypt_armv8ce(&crypt_ctx, iv, cbc_buf, cbc_buf, 64) != 0) {
        return 0;
    }
    if (memcmp(plain, cbc_buf, 64) != 0) {
        return 0;
    }

    return 1;
}

#else /* !__aarch64__ */

#include "aes_xts_armv8ce.h"

int dislocker_has_armv8_ce(void)
{
    return 0;
}

int dislocker_is_armv8_ce_supported(void)
{
    return 0;
}

int dislocker_is_armv8_ce_enabled(void)
{
    return 0;
}

void dislocker_set_armv8_ce_enabled(int enabled)
{
    (void)enabled;
}

int aes_xts_crypt_armv8ce(aes_ctx_t *crypt_ctx, aes_ctx_t *tweak_ctx, int encrypt,
                          size_t length, const unsigned char *iv,
                          const unsigned char *input, unsigned char *output)
{
    (void)crypt_ctx; (void)tweak_ctx; (void)encrypt;
    (void)length; (void)iv; (void)input; (void)output;
    return -1;
}

int aes_cbc_decrypt_armv8ce(aes_ctx_t *ctx, const uint8_t *iv,
                            const uint8_t *in, uint8_t *out, size_t length)
{
    (void)ctx; (void)iv; (void)in; (void)out; (void)length;
    return -1;
}

int aes_cbc_encrypt_armv8ce(aes_ctx_t *ctx, const uint8_t *iv,
                            const uint8_t *in, uint8_t *out, size_t length)
{
    (void)ctx; (void)iv; (void)in; (void)out; (void)length;
    return -1;
}

int aes_ecb_encrypt_armv8ce(aes_ctx_t *ctx, const uint8_t in[16], uint8_t out[16])
{
    (void)ctx; (void)in; (void)out;
    return -1;
}

#endif /* __aarch64__ */
