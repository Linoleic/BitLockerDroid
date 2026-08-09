/*
 * aes_xts.c -- AES-XTS sector encryption/decryption.
 *
 * Directly ported from dislocker v0.7.3 (src/encryption/aes-xts.c,
 * src/encryption/decrypt.c), GPL-2.0. Uses the dependency-free AES from
 * crypto/mini_aes.c instead of mbedtls.
 *
 * BitLocker encrypts each 512/4096-byte sector in AES-XTS mode: data key
 * (FVEK) + tweak key, with the tweak = little-endian sector number.
 */
#include "dislocker/crypto.h"

#include <string.h>

/* ---------- little-endian u64 load/store (from dislocker aes-xts.c) ---------- */

#define GET_UINT64_LE(n, b, i)                          \
{                                                       \
	(n) = ( (uint64_t) (b)[(i) + 7] << 56 )             \
		| ( (uint64_t) (b)[(i) + 6] << 48 )             \
		| ( (uint64_t) (b)[(i) + 5] << 40 )             \
		| ( (uint64_t) (b)[(i) + 4] << 32 )             \
		| ( (uint64_t) (b)[(i) + 3] << 24 )             \
		| ( (uint64_t) (b)[(i) + 2] << 16 )             \
		| ( (uint64_t) (b)[(i) + 1] <<  8 )             \
		| ( (uint64_t) (b)[(i)    ]       );            \
}

#define PUT_UINT64_LE(n, b, i)                          \
{                                                       \
	(b)[(i) + 7] = (unsigned char) ( (n) >> 56 );       \
	(b)[(i) + 6] = (unsigned char) ( (n) >> 48 );       \
	(b)[(i) + 5] = (unsigned char) ( (n) >> 40 );       \
	(b)[(i) + 4] = (unsigned char) ( (n) >> 32 );       \
	(b)[(i) + 3] = (unsigned char) ( (n) >> 24 );       \
	(b)[(i) + 2] = (unsigned char) ( (n) >> 16 );       \
	(b)[(i) + 1] = (unsigned char) ( (n) >>  8 );       \
	(b)[(i)    ] = (unsigned char) ( (n)       );       \
}

/* ---------- GF(2^128) multiply by x (dislocker's table) ---------- */

#define xx(p, q) 0x##p##q

#define xda_bbe(i) ( \
	(i & 0x80 ? xx(43, 80) : 0) ^ (i & 0x40 ? xx(21, c0) : 0) ^ \
	(i & 0x20 ? xx(10, e0) : 0) ^ (i & 0x10 ? xx(08, 70) : 0) ^ \
	(i & 0x08 ? xx(04, 38) : 0) ^ (i & 0x04 ? xx(02, 1c) : 0) ^ \
	(i & 0x02 ? xx(01, 0e) : 0) ^ (i & 0x01 ? xx(00, 87) : 0) \
)

static const uint16_t gf128mul_table_bbe[256] = {
	xda_bbe(0x00), xda_bbe(0x01), xda_bbe(0x02), xda_bbe(0x03), xda_bbe(0x04), xda_bbe(0x05), xda_bbe(0x06), xda_bbe(0x07),
	xda_bbe(0x08), xda_bbe(0x09), xda_bbe(0x0a), xda_bbe(0x0b), xda_bbe(0x0c), xda_bbe(0x0d), xda_bbe(0x0e), xda_bbe(0x0f),
	xda_bbe(0x10), xda_bbe(0x11), xda_bbe(0x12), xda_bbe(0x13), xda_bbe(0x14), xda_bbe(0x15), xda_bbe(0x16), xda_bbe(0x17),
	xda_bbe(0x18), xda_bbe(0x19), xda_bbe(0x1a), xda_bbe(0x1b), xda_bbe(0x1c), xda_bbe(0x1d), xda_bbe(0x1e), xda_bbe(0x1f),
	xda_bbe(0x20), xda_bbe(0x21), xda_bbe(0x22), xda_bbe(0x23), xda_bbe(0x24), xda_bbe(0x25), xda_bbe(0x26), xda_bbe(0x27),
	xda_bbe(0x28), xda_bbe(0x29), xda_bbe(0x2a), xda_bbe(0x2b), xda_bbe(0x2c), xda_bbe(0x2d), xda_bbe(0x2e), xda_bbe(0x2f),
	xda_bbe(0x30), xda_bbe(0x31), xda_bbe(0x32), xda_bbe(0x33), xda_bbe(0x34), xda_bbe(0x35), xda_bbe(0x36), xda_bbe(0x37),
	xda_bbe(0x38), xda_bbe(0x39), xda_bbe(0x3a), xda_bbe(0x3b), xda_bbe(0x3c), xda_bbe(0x3d), xda_bbe(0x3e), xda_bbe(0x3f),
	xda_bbe(0x40), xda_bbe(0x41), xda_bbe(0x42), xda_bbe(0x43), xda_bbe(0x44), xda_bbe(0x45), xda_bbe(0x46), xda_bbe(0x47),
	xda_bbe(0x48), xda_bbe(0x49), xda_bbe(0x4a), xda_bbe(0x4b), xda_bbe(0x4c), xda_bbe(0x4d), xda_bbe(0x4e), xda_bbe(0x4f),
	xda_bbe(0x50), xda_bbe(0x51), xda_bbe(0x52), xda_bbe(0x53), xda_bbe(0x54), xda_bbe(0x55), xda_bbe(0x56), xda_bbe(0x57),
	xda_bbe(0x58), xda_bbe(0x59), xda_bbe(0x5a), xda_bbe(0x5b), xda_bbe(0x5c), xda_bbe(0x5d), xda_bbe(0x5e), xda_bbe(0x5f),
	xda_bbe(0x60), xda_bbe(0x61), xda_bbe(0x62), xda_bbe(0x63), xda_bbe(0x64), xda_bbe(0x65), xda_bbe(0x66), xda_bbe(0x67),
	xda_bbe(0x68), xda_bbe(0x69), xda_bbe(0x6a), xda_bbe(0x6b), xda_bbe(0x6c), xda_bbe(0x6d), xda_bbe(0x6e), xda_bbe(0x6f),
	xda_bbe(0x70), xda_bbe(0x71), xda_bbe(0x72), xda_bbe(0x73), xda_bbe(0x74), xda_bbe(0x75), xda_bbe(0x76), xda_bbe(0x77),
	xda_bbe(0x78), xda_bbe(0x79), xda_bbe(0x7a), xda_bbe(0x7b), xda_bbe(0x7c), xda_bbe(0x7d), xda_bbe(0x7e), xda_bbe(0x7f),
	xda_bbe(0x80), xda_bbe(0x81), xda_bbe(0x82), xda_bbe(0x83), xda_bbe(0x84), xda_bbe(0x85), xda_bbe(0x86), xda_bbe(0x87),
	xda_bbe(0x88), xda_bbe(0x89), xda_bbe(0x8a), xda_bbe(0x8b), xda_bbe(0x8c), xda_bbe(0x8d), xda_bbe(0x8e), xda_bbe(0x8f),
	xda_bbe(0x90), xda_bbe(0x91), xda_bbe(0x92), xda_bbe(0x93), xda_bbe(0x94), xda_bbe(0x95), xda_bbe(0x96), xda_bbe(0x97),
	xda_bbe(0x98), xda_bbe(0x99), xda_bbe(0x9a), xda_bbe(0x9b), xda_bbe(0x9c), xda_bbe(0x9d), xda_bbe(0x9e), xda_bbe(0x9f),
	xda_bbe(0xa0), xda_bbe(0xa1), xda_bbe(0xa2), xda_bbe(0xa3), xda_bbe(0xa4), xda_bbe(0xa5), xda_bbe(0xa6), xda_bbe(0xa7),
	xda_bbe(0xa8), xda_bbe(0xa9), xda_bbe(0xaa), xda_bbe(0xab), xda_bbe(0xac), xda_bbe(0xad), xda_bbe(0xae), xda_bbe(0xaf),
	xda_bbe(0xb0), xda_bbe(0xb1), xda_bbe(0xb2), xda_bbe(0xb3), xda_bbe(0xb4), xda_bbe(0xb5), xda_bbe(0xb6), xda_bbe(0xb7),
	xda_bbe(0xb8), xda_bbe(0xb9), xda_bbe(0xba), xda_bbe(0xbb), xda_bbe(0xbc), xda_bbe(0xbd), xda_bbe(0xbe), xda_bbe(0xbf),
	xda_bbe(0xc0), xda_bbe(0xc1), xda_bbe(0xc2), xda_bbe(0xc3), xda_bbe(0xc4), xda_bbe(0xc5), xda_bbe(0xc6), xda_bbe(0xc7),
	xda_bbe(0xc8), xda_bbe(0xc9), xda_bbe(0xca), xda_bbe(0xcb), xda_bbe(0xcc), xda_bbe(0xcd), xda_bbe(0xce), xda_bbe(0xcf),
	xda_bbe(0xd0), xda_bbe(0xd1), xda_bbe(0xd2), xda_bbe(0xd3), xda_bbe(0xd4), xda_bbe(0xd5), xda_bbe(0xd6), xda_bbe(0xd7),
	xda_bbe(0xd8), xda_bbe(0xd9), xda_bbe(0xda), xda_bbe(0xdb), xda_bbe(0xdc), xda_bbe(0xdd), xda_bbe(0xde), xda_bbe(0xdf),
	xda_bbe(0xe0), xda_bbe(0xe1), xda_bbe(0xe2), xda_bbe(0xe3), xda_bbe(0xe4), xda_bbe(0xe5), xda_bbe(0xe6), xda_bbe(0xe7),
	xda_bbe(0xe8), xda_bbe(0xe9), xda_bbe(0xea), xda_bbe(0xeb), xda_bbe(0xec), xda_bbe(0xed), xda_bbe(0xee), xda_bbe(0xef),
	xda_bbe(0xf0), xda_bbe(0xf1), xda_bbe(0xf2), xda_bbe(0xf3), xda_bbe(0xf4), xda_bbe(0xf5), xda_bbe(0xf6), xda_bbe(0xf7),
	xda_bbe(0xf8), xda_bbe(0xf9), xda_bbe(0xfa), xda_bbe(0xfb), xda_bbe(0xfc), xda_bbe(0xfd), xda_bbe(0xfe), xda_bbe(0xff)
};

typedef unsigned char be128[16];

static void gf128mul_x_ble(be128 r, const be128 x)
{
	uint64_t a, b, ra, rb, tt;

	GET_UINT64_LE(a, x, 0);
	GET_UINT64_LE(b, x, 8);

	tt = gf128mul_table_bbe[b >> 63];
	ra = (a << 1) ^ tt;
	rb = (b << 1) | (a >> 63);

	PUT_UINT64_LE(ra, r, 0);
	PUT_UINT64_LE(rb, r, 8);
}

/* ---------- XTS core (ported from dislocker dis_aes_crypt_xts) ---------- */

static int aes_xts_crypt(aes_ctx_t *crypt_ctx, aes_ctx_t *tweak_ctx, int encrypt,
	size_t length, const unsigned char *iv, const unsigned char *input,
	unsigned char *output)
{
	union xts_buf128 {
		uint8_t  u8[16];
		uint64_t u64[2];
	};

	union xts_buf128 scratch;
	union xts_buf128 cts_scratch;
	union xts_buf128 t_buf;
	union xts_buf128 cts_t_buf;
	union xts_buf128 *inbuf;
	union xts_buf128 *outbuf;
	size_t nb_blocks = length / 16;
	size_t remaining = length % 16;

	if (length < 16)
		return -1;

	inbuf = (union xts_buf128 *)input;
	outbuf = (union xts_buf128 *)output;

	aes_encrypt_ecb(tweak_ctx, iv, t_buf.u8);

	goto first;

	do {
		gf128mul_x_ble(t_buf.u8, t_buf.u8);
first:
		/* PP <- T xor P */
		scratch.u64[0] = inbuf->u64[0] ^ t_buf.u64[0];
		scratch.u64[1] = inbuf->u64[1] ^ t_buf.u64[1];

		if (encrypt)
			aes_encrypt_ecb(crypt_ctx, scratch.u8, outbuf->u8);
		else
			aes_decrypt_ecb(crypt_ctx, scratch.u8, outbuf->u8);

		/* C <- T xor CC */
		outbuf->u64[0] = outbuf->u64[0] ^ t_buf.u64[0];
		outbuf->u64[1] = outbuf->u64[1] ^ t_buf.u64[1];

		inbuf += 1;
		outbuf += 1;
		nb_blocks -= 1;
	} while (nb_blocks > 0);

	/* Ciphertext stealing, if necessary */
	if (remaining != 0) {
		outbuf = (union xts_buf128 *)output;
		nb_blocks = length / 16;

		if (encrypt) {
			memcpy(cts_scratch.u8, (uint8_t *)&outbuf[nb_blocks], remaining);
			memcpy(cts_scratch.u8 + remaining, ((uint8_t *)&outbuf[nb_blocks - 1]) + remaining, 16 - remaining);
			memcpy((uint8_t *)&outbuf[nb_blocks], (uint8_t *)&outbuf[nb_blocks - 1], remaining);

			gf128mul_x_ble(t_buf.u8, t_buf.u8);

			scratch.u64[0] = cts_scratch.u64[0] ^ t_buf.u64[0];
			scratch.u64[1] = cts_scratch.u64[1] ^ t_buf.u64[1];

			aes_encrypt_ecb(crypt_ctx, scratch.u8, scratch.u8);

			(&outbuf[nb_blocks - 1])->u64[0] = scratch.u64[0] ^ t_buf.u64[0];
			(&outbuf[nb_blocks - 1])->u64[1] = scratch.u64[1] ^ t_buf.u64[1];
		} else {
			cts_t_buf.u64[0] = t_buf.u64[0];
			cts_t_buf.u64[1] = t_buf.u64[1];

			gf128mul_x_ble(t_buf.u8, t_buf.u8);

			scratch.u64[0] = outbuf[nb_blocks - 1].u64[0] ^ t_buf.u64[0];
			scratch.u64[1] = outbuf[nb_blocks - 1].u64[1] ^ t_buf.u64[1];

			aes_decrypt_ecb(crypt_ctx, scratch.u8, scratch.u8);

			cts_scratch.u64[0] = scratch.u64[0] ^ t_buf.u64[0];
			cts_scratch.u64[1] = scratch.u64[1] ^ t_buf.u64[1];

			memcpy((uint8_t *)&outbuf[nb_blocks - 1], (uint8_t *)&outbuf[nb_blocks], remaining);
			memcpy((uint8_t *)&outbuf[nb_blocks - 1] + remaining, cts_scratch.u8, 16 - remaining);
			memcpy((uint8_t *)&outbuf[nb_blocks], cts_scratch.u8, remaining);

			scratch.u64[0] = (&outbuf[nb_blocks - 1])->u64[0] ^ cts_t_buf.u64[0];
			scratch.u64[1] = (&outbuf[nb_blocks - 1])->u64[1] ^ cts_t_buf.u64[1];

			aes_decrypt_ecb(crypt_ctx, scratch.u8, scratch.u8);

			(&outbuf[nb_blocks - 1])->u64[0] = scratch.u64[0] ^ cts_t_buf.u64[0];
			(&outbuf[nb_blocks - 1])->u64[1] = scratch.u64[1] ^ cts_t_buf.u64[1];
		}
	}

	return 0;
}

void aes_xts_decrypt(aes_ctx_t *crypt_ctx, aes_ctx_t *tweak_ctx,
	const uint8_t *in, uint8_t *out, size_t length, const uint8_t iv[16])
{
	aes_xts_crypt(crypt_ctx, tweak_ctx, 0, length, iv, in, out);
}

void aes_xts_encrypt(aes_ctx_t *crypt_ctx, aes_ctx_t *tweak_ctx,
	const uint8_t *in, uint8_t *out, size_t length, const uint8_t iv[16])
{
	aes_xts_crypt(crypt_ctx, tweak_ctx, 1, length, iv, in, out);
}
