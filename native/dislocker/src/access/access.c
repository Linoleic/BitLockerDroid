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
	} else if (ctx->algorithm == AES_128_DIFFUSER || ctx->algorithm == AES_256_DIFFUSER) {
		/* Elephant diffuser is not implemented: treating it as plain CBC would
		 * decrypt to garbage, and the write path would then corrupt the volume.
		 * Refuse these Vista-era volumes outright (see PROJECT_STATUS.md P1). */
		dis_set_error("AES-CBC+Diffuser volumes (0x%04x, Windows Vista era) are not supported",
			ctx->algorithm);
		return FALSE;
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
 * Re-encrypt `sector` and write it to the block device via `dis_blk_write`.
 */
int dis_sector_write(dis_ctx_t *ctx, const uint8_t *sector, off_t sector_address)
{
	if (!ctx || !sector)
		return FALSE;

	uint8_t enc[4096];
	if (ctx->sector_size > sizeof(enc))
		return FALSE;

	memcpy(enc, sector, ctx->sector_size);
	if (!dis_encrypt_sector(ctx, enc, sector_address))
		return FALSE;

	int nb = dis_blk_write(ctx, enc, sector_address, ctx->sector_size);
	return nb == (int)ctx->sector_size ? TRUE : FALSE;
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
		aes_encrypt_ecb(&ctx->cbc_enc, iv, iv);   /* FVEK encrypt the IV */
		aes_cbc_decrypt(&ctx->cbc_dec, iv, sector, sector, ctx->sector_size);
		break;
	}

	default:
		return FALSE;
	}

	return TRUE;
}

/*
 * Encrypt one plaintext sector in place.
 * sector_address is the byte offset (multiple of sector_size); the XTS/CBC
 * tweak/IV is derived from it. Returns TRUE on success.
 *
 * Enforces a strict write barrier: any attempt to write into the protected
 * BitLocker metadata area (sector_address < boot_sectors_backup) is blocked.
 */
int dis_encrypt_sector(dis_ctx_t *ctx, uint8_t *sector, off_t sector_address)
{
	if (!ctx || !sector)
		return FALSE;

	/* Write barrier check: protect BitLocker volume header (sectors 0..15) and metadata blocks */
	size_t header_bytes = 8192;
	if (ctx->information && ctx->information->nb_backup_sectors > 0) {
		header_bytes = (size_t)ctx->information->nb_backup_sectors * ctx->sector_size;
	}
	if (sector_address < (off_t)header_bytes) {
		DLOG("FATAL: Write barrier violation: sector_address=0x%llx < header_bytes=0x%zx",
			(unsigned long long)sector_address, header_bytes);
		return FALSE;
	}
	if (ctx->information) {
		for (int i = 0; i < 3; i++) {
			off_t info_off = (off_t)ctx->information->information_off[i];
			if (info_off != 0 && sector_address >= info_off && sector_address < info_off + 0x10000) {
				DLOG("FATAL: Write barrier violation: sector_address=0x%llx in metadata block %d (0x%llx)",
					(unsigned long long)sector_address, i, (unsigned long long)info_off);
				return FALSE;
			}
		}
	}

	switch (ctx->algorithm) {
	case AES_XTS_128:
	case AES_XTS_256: {
		/* XTS IV: little-endian sector number */
		uint8_t iv[16];
		memset(iv, 0, sizeof(iv));
		uint64_t sector_num = (uint64_t)(sector_address / ctx->sector_size);
		for (int i = 0; i < 8; i++)
			iv[i] = (uint8_t)(sector_num >> (i * 8));

		aes_xts_encrypt(&ctx->xts_crypt, &ctx->xts_tweak, sector, sector,
			ctx->sector_size, iv);
		break;
	}

	case AES_128_NO_DIFFUSER:
	case AES_256_NO_DIFFUSER:
	case AES_128_DIFFUSER:
	case AES_256_DIFFUSER: {
		uint8_t iv[16];
		memset(iv, 0, sizeof(iv));
		uint64_t addr = (uint64_t)(ctx->offset + sector_address); /* LE */
		for (int i = 0; i < 8; i++)
			iv[i] = (uint8_t)(addr >> (i * 8));
		aes_encrypt_ecb(&ctx->cbc_enc, iv, iv);   /* FVEK encrypt the IV */
		aes_cbc_encrypt(&ctx->cbc_enc, iv, sector, sector, ctx->sector_size);
		break;
	}

	default:
		return FALSE;
	}

	return TRUE;
}

