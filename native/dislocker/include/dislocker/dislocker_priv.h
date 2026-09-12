#ifndef DISLOCKER_PRIV_H
#define DISLOCKER_PRIV_H

#include "dislocker/dislocker.h"
#include "dislocker/crypto.h"

#include <stdio.h>
#include <stdarg.h>
#include <sys/types.h>

#define TRUE  1
#define FALSE 0

/* 48-digit recovery key mapped to 16-byte binary + 8-bit checksum set */
#define RECOVERY_KEY_BINARY_LEN 16
#define RECOVERY_KEY_CHECKSUM_LEN 8

/* Full definition of the opaque context (dislocker.h forward-declares it) */
struct _dis_ctx {
	/* block device fd (may be -1 if unreadable; reads go via su dd) */
	int fd;
	/* partition offset on the device */
	off_t offset;
	/* block device path (used by the su-based reader) */
	char device_path[256];

	/* parsed volume header */
	volume_header_t volume_header;

	/* parsed metadata */
	bitlocker_information_t *information;
	uint8_t *metadata;          /* raw metadata buffer (owned) */
	size_t   metadata_size;
	bitlocker_dataset_t *dataset;
	cipher_t algorithm;         /* dataset->algorithm */

	/* regions (up to 3) where metadata blocks live */
	uint64_t regions[3];

	/* decrypted keys */
	uint8_t fvek[64];
	int     fvek_len;           /* 32 or 64 bytes */
	uint16_t sector_size;

	/* crypto contexts */
	aes_ctx_t xts_crypt;
	aes_ctx_t xts_tweak;

	/* CBC mode decryption context (legacy BitLocker AES-CBC). */
	aes_ctx_t cbc_dec;
	aes_ctx_t cbc_enc;

	/* volume state */
	dis_metadata_state_t volume_state;

	uint64_t volume_size;

	/* Persistent I/O daemon (root helper over pipes) */
	int io_in_fd;
	int io_out_fd;
	pid_t io_pid;
	pthread_mutex_t io_lock;
};

/* metadata.c */
int  dis_metadata_parse(dis_ctx_t *ctx);
void dis_metadata_free(dis_ctx_t *ctx);

/* vmkey.c */
int  dis_retrieve_keys(dis_ctx_t *ctx, const uint8_t *user_password,
	size_t password_len, const uint8_t *recovery_key,
	size_t recovery_key_len);
int  dis_init_xts(dis_ctx_t *ctx);

/* access.c */
int  dis_sector_read(dis_ctx_t *ctx, uint8_t *sector, off_t sector_address);
int  dis_decrypt_sector(dis_ctx_t *ctx, uint8_t *sector, off_t sector_address);
int  dis_encrypt_sector(dis_ctx_t *ctx, uint8_t *sector, off_t sector_address);
int  dis_sector_write(dis_ctx_t *ctx, const uint8_t *sector, off_t sector_address);

/* io.c -- root-based block device I/O via persistent daemon or direct fd */
int  dis_io_init(dis_ctx_t *ctx);
void dis_io_destroy(dis_ctx_t *ctx);
int  dis_blk_read(dis_ctx_t *ctx, uint8_t *buf, off_t offset, size_t len);
int  dis_blk_write(dis_ctx_t *ctx, const uint8_t *buf, off_t offset, size_t len);
int  dis_blk_sync(dis_ctx_t *ctx);

/* error.c */
void dis_set_error(const char *fmt, ...);

/* utf16 + password helpers */
int  dis_ascii_to_utf16(const uint8_t *ascii, size_t ascii_len,
	uint8_t **utf16_out, size_t *utf16_len_out);

#endif /* DISLOCKER_PRIV_H */
