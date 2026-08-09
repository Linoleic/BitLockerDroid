/*
 * access.c -- encrypted-sector read + AES-XTS / AES-CBC decrypt.
 *
 * Ported from dislocker v0.7.3 (src/encryption/decrypt.c decrypt_xts,
 * decrypt_cbc_without_diffuser, src/encryption/encommon.c
 * dis_crypt_set_fvekey), GPL-2.0.
 */
#define _GNU_SOURCE 1
#include "dislocker/dislocker_priv.h"

#ifdef __ANDROID__
#include <android/log.h>
#define DLOG(...) __android_log_print(ANDROID_LOG_ERROR, "BitLockerNative", __VA_ARGS__)
#else
#define DLOG(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int dis_init_xts(dis_ctx_t *ctx)
{
	DLOG("dis_init_xts algorithm=0x%04x fvek_len=%d", ctx->algorithm, ctx->fvek_len);
	/* FVEK layout for AES-XTS: data key | tweak key */
	if (ctx->algorithm == AES_XTS_128) {
		if (ctx->fvek_len < 32) {
			dis_set_error("XTS-128 needs 32-byte FVEK, got %d", ctx->fvek_len);
			return FALSE;
		}
		aes_ctx_init(&ctx->xts_crypt, ctx->fvek, AES_128);
		aes_ctx_init(&ctx->xts_tweak, ctx->fvek + 16, AES_128);
	} else if (ctx->algorithm == AES_XTS_256) {
		if (ctx->fvek_len < 64) {
			dis_set_error("XTS-256 needs 64-byte FVEK, got %d", ctx->fvek_len);
			return FALSE;
		}
		aes_ctx_init(&ctx->xts_crypt, ctx->fvek, AES_256);
		aes_ctx_init(&ctx->xts_tweak, ctx->fvek + 32, AES_256);
	} else if (ctx->algorithm == AES_128_NO_DIFFUSER) {
		if (ctx->fvek_len < 16) {
			dis_set_error("CBC-128 needs 16-byte FVEK, got %d", ctx->fvek_len);
			return FALSE;
		}
		aes_ctx_init(&ctx->cbc_dec, ctx->fvek, AES_128);
		aes_ctx_init(&ctx->cbc_enc, ctx->fvek, AES_128);
	} else if (ctx->algorithm == AES_256_NO_DIFFUSER) {
		if (ctx->fvek_len < 32) {
			dis_set_error("CBC-256 needs 32-byte FVEK, got %d", ctx->fvek_len);
			return FALSE;
		}
		aes_ctx_init(&ctx->cbc_dec, ctx->fvek, AES_256);
		aes_ctx_init(&ctx->cbc_enc, ctx->fvek, AES_256);
	} else if (ctx->algorithm == AES_128_DIFFUSER) {
		if (ctx->fvek_len < 32) {
			dis_set_error("CBC-diffuser-128 needs 32-byte FVEK, got %d", ctx->fvek_len);
			return FALSE;
		}
		aes_ctx_init(&ctx->cbc_dec, ctx->fvek, AES_128);
		aes_ctx_init(&ctx->cbc_enc, ctx->fvek, AES_128);
	} else if (ctx->algorithm == AES_256_DIFFUSER) {
		if (ctx->fvek_len < 64) {
			dis_set_error("CBC-diffuser-256 needs 64-byte FVEK, got %d", ctx->fvek_len);
			return FALSE;
		}
		aes_ctx_init(&ctx->cbc_dec, ctx->fvek, AES_256);
		aes_ctx_init(&ctx->cbc_enc, ctx->fvek, AES_256);
	} else {
		dis_set_error("Unsupported cipher 0x%04x", ctx->algorithm);
		return FALSE;
	}
	return TRUE;
}

/*
 * Read one encrypted sector at `sector_address` (a byte offset that is a
 * multiple of sector_size) and decrypt it in place into `sector`.
 */
int dis_sector_read(dis_ctx_t *ctx, uint8_t *sector, off_t sector_address)
{
	/* Read via su dd (SELinux blocks direct open of block devices). */
	ssize_t nb = dis_blk_read(ctx, sector, sector_address, ctx->sector_size);
	if (nb != (ssize_t)ctx->sector_size)
		return FALSE;

	return dis_decrypt_sector(ctx, sector, sector_address);
}

/*
 * Decrypt one already-read encrypted sector in place.
 * sector_address is the byte offset (multiple of sector_size); the XTS/CBC
 * tweak/IV is derived from it. Returns TRUE on success.
 */
int dis_decrypt_sector(dis_ctx_t *ctx, uint8_t *sector, off_t sector_address)
{
	if (!ctx || !sector)
		return FALSE;

	/* Per-mode decrypt. */
	switch (ctx->algorithm) {
	case AES_XTS_128:
	case AES_XTS_256: {
		/* XTS IV: little-endian sector number */
		uint8_t iv[16];
		memset(iv, 0, sizeof(iv));
		uint64_t sector_num = (uint64_t)(sector_address / ctx->sector_size);
		for (int i = 0; i < 8; i++)
			iv[i] = (uint8_t)(sector_num >> (i * 8));

		aes_xts_decrypt(&ctx->xts_crypt, &ctx->xts_tweak, sector, sector,
			ctx->sector_size, iv);
		break;
	}

	case AES_128_NO_DIFFUSER:
	case AES_256_NO_DIFFUSER:
	case AES_128_DIFFUSER:
	case AES_256_DIFFUSER: {
		/* CBC IV = AES-ECB(FVEK, physical sector_address), as in dislocker
		 * decrypt_cbc_without_diffuser. The IV uses the absolute byte offset
		 * (boot_backup + logical) — that is how BitLocker keys the sectors. */
		uint8_t iv[16];
		memset(iv, 0, sizeof(iv));
		uint64_t addr = (uint64_t)(ctx->offset + sector_address); /* LE */
		for (int i = 0; i < 8; i++)
			iv[i] = (uint8_t)(addr >> (i * 8));

		DLOG("cbc decrypt sector_addr=0x%llx fvek_len=%d in=%02x%02x%02x%02x fvek=%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x",
			(unsigned long long)sector_address, ctx->fvek_len,
			sector[0], sector[1], sector[2], sector[3],
			ctx->fvek[0], ctx->fvek[1], ctx->fvek[2], ctx->fvek[3],
			ctx->fvek[4], ctx->fvek[5], ctx->fvek[6], ctx->fvek[7],
			ctx->fvek[8], ctx->fvek[9], ctx->fvek[10], ctx->fvek[11],
			ctx->fvek[12], ctx->fvek[13], ctx->fvek[14], ctx->fvek[15]);

		aes_encrypt_ecb(&ctx->cbc_enc, iv, iv);   /* FVEK encrypt the IV */
		aes_cbc_decrypt(&ctx->cbc_dec, iv, sector, sector, ctx->sector_size);

		DLOG("cbc decrypted out=%02x%02x%02x%02x", sector[0], sector[1], sector[2], sector[3]);
		break;
	}

	default:
		return FALSE;
	}

	return TRUE;
}
