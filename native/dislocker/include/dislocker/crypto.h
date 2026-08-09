#ifndef DISLOCKER_CRYPTO_H
#define DISLOCKER_CRYPTO_H

#include <stdint.h>
#include <stddef.h>

#include "mbedtls/aes.h"
#include "mbedtls/sha256.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ---------------- AES ---------------- */

typedef enum {
	AES_128 = 0,
	AES_256 = 1
} aes_key_len_t;

#define AES_BLOCK_SIZE 16

/*
 * An AES key with both an encrypt and a decrypt key schedule. mbedtls's
 * mbedtls_aes_context can only hold one schedule at a time, so we keep two:
 * aes_encrypt_ecb uses enc, aes_decrypt_ecb uses dec. The XTS tweak key only
 * ever encrypts; the extra schedule costs nothing.
 */
typedef struct {
	mbedtls_aes_context enc;
	mbedtls_aes_context dec;
} aes_ctx_t;

void aes_ctx_init(aes_ctx_t *ctx, const uint8_t *key, aes_key_len_t key_len);
void aes_encrypt_ecb(aes_ctx_t *ctx, const uint8_t in[16], uint8_t out[16]);
void aes_decrypt_ecb(aes_ctx_t *ctx, const uint8_t in[16], uint8_t out[16]);

/* Set up XTS contexts: crypt key = fvek[0..klen), tweak key = fvek[klen..2*klen) */
void aes_xts_setkey(aes_ctx_t *crypt, aes_ctx_t *tweak,
	const uint8_t *fvek, int key_len_bytes, aes_key_len_t len);

/* AES-XTS decrypt/encrypt of a full sector (length multiple of 16, incl. CTS) */
void aes_xts_decrypt(aes_ctx_t *crypt_ctx, aes_ctx_t *tweak_ctx,
	const uint8_t *in, uint8_t *out, size_t length, const uint8_t iv[16]);
void aes_xts_encrypt(aes_ctx_t *crypt_ctx, aes_ctx_t *tweak_ctx,
	const uint8_t *in, uint8_t *out, size_t length, const uint8_t iv[16]);

/* AES-CBC decrypt of a full sector (used by legacy BitLocker AES-CBC modes).
 * iv: 16-byte initialisation vector; length must be a multiple of 16. */
void aes_cbc_decrypt(aes_ctx_t *ctx, const uint8_t *iv,
	const uint8_t *in, uint8_t *out, size_t length);

/* ---------------- SHA-256 ---------------- */

typedef struct {
	mbedtls_sha256_context mbedtls;
} sha256_ctx_t;

void sha256_init(sha256_ctx_t *ctx);
void sha256_update(sha256_ctx_t *ctx, const uint8_t *data, size_t len);
void sha256_final(sha256_ctx_t *ctx, uint8_t out[32]);
void sha256(const uint8_t *data, size_t len, uint8_t out[32]);

/* ---------------- CRC-32 (BitLocker metadata validation) ---------------- */

uint32_t crc32_buf(const uint8_t *data, uint32_t len);

#ifdef __cplusplus
}
#endif

#endif /* DISLOCKER_CRYPTO_H */
