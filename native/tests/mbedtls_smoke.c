/* mbedtls_smoke.c -- verify the mbedtls build used by BitLockerDroid.
 * Run with:  gcc -std=c99 -I ../third_party/mbedtls/include -o mbedtls_smoke \
 *            mbedtls_smoke.c \
 *            ../third_party/mbedtls/library/aes.c \
 *            ../third_party/mbedtls/library/sha256.c \
 *            ../third_party/mbedtls/library/platform.c \
 *            ../third_party/mbedtls/library/platform_util.c \
 *            ../third_party/mbedtls/library/error.c
 */
#include <stdio.h>
#include <string.h>

#include "mbedtls/aes.h"
#include "mbedtls/sha256.h"

static int failures = 0;
#define CHECK(cond, name) do { \
	if (cond) { printf("  ok   %s\n", name); } \
	else { printf("  FAIL %s\n", name); failures++; } \
} while (0)

int main(void)
{
	printf("mbedtls smoke test\n=================\n");

	/* FIPS-197 C.1: AES-128 ECB */
	const unsigned char key128[16] = {
		0x00,0x01,0x02,0x03,0x04,0x05,0x06,0x07,
		0x08,0x09,0x0a,0x0b,0x0c,0x0d,0x0e,0x0f
	};
	const unsigned char pt[16] = {
		0x00,0x11,0x22,0x33,0x44,0x55,0x66,0x77,
		0x88,0x99,0xaa,0xbb,0xcc,0xdd,0xee,0xff
	};
	const unsigned char ct128[16] = {
		0x69,0xc4,0xe0,0xd8,0x6a,0x7b,0x04,0x30,
		0xd8,0xcd,0xb7,0x80,0x70,0xb4,0xc5,0x5a
	};

	mbedtls_aes_context a;
	mbedtls_aes_init(&a);
	mbedtls_aes_setkey_enc(&a, key128, 128);
	unsigned char out[16], dec[16];
	mbedtls_aes_crypt_ecb(&a, MBEDTLS_AES_ENCRYPT, pt, out);
	CHECK(memcmp(out, ct128, 16) == 0, "AES-128-ECB encrypt (FIPS-197 C.1)");

	mbedtls_aes_setkey_dec(&a, key128, 128);
	mbedtls_aes_crypt_ecb(&a, MBEDTLS_AES_DECRYPT, ct128, dec);
	CHECK(memcmp(dec, pt, 16) == 0, "AES-128-ECB decrypt roundtrip");
	mbedtls_aes_free(&a);

	/* SHA-256 of "abc" */
	unsigned char hash[32];
	mbedtls_sha256((const unsigned char *)"abc", 3, hash, 0);
	const unsigned char sha_abc[32] = {
		0xba,0x78,0x16,0xbf,0x8f,0x01,0xcf,0xea,0x41,0x41,0x40,0xde,0x5d,0xae,0x22,0x23,
		0xb0,0x03,0x61,0xa3,0x96,0x17,0x7a,0x9c,0xb4,0x10,0xff,0x61,0xf2,0x00,0x15,0xad
	};
	CHECK(memcmp(hash, sha_abc, 32) == 0, "SHA-256(abc)");

	/* AES-256 ECB */
	const unsigned char key256[32] = {
		0x00,0x01,0x02,0x03,0x04,0x05,0x06,0x07,
		0x08,0x09,0x0a,0x0b,0x0c,0x0d,0x0e,0x0f,
		0x10,0x11,0x12,0x13,0x14,0x15,0x16,0x17,
		0x18,0x19,0x1a,0x1b,0x1c,0x1d,0x1e,0x1f
	};
	const unsigned char ct256[16] = {
		0x8e,0xa2,0xb7,0xca,0x51,0x67,0x45,0xbf,
		0xea,0xfc,0x49,0x90,0x4b,0x49,0x60,0x89
	};
	mbedtls_aes_init(&a);
	mbedtls_aes_setkey_enc(&a, key256, 256);
	mbedtls_aes_crypt_ecb(&a, MBEDTLS_AES_ENCRYPT, pt, out);
	CHECK(memcmp(out, ct256, 16) == 0, "AES-256-ECB encrypt (FIPS-197 C.3)");
	mbedtls_aes_free(&a);

	printf("=================\n");
	if (failures == 0) printf("ALL MBEDTLS TESTS PASSED\n");
	else printf("%d TEST(S) FAILED\n", failures);
	return failures ? 1 : 0;
}
