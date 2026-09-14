#ifndef DISLOCKER_DISLOCKER_H
#define DISLOCKER_DISLOCKER_H

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>

#include "dislocker/metadata.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Return codes (subset of dislocker return_values.h) */
#define DIS_RET_SUCCESS                         0
#define DIS_RET_ERROR_ALLOC                     -1
#define DIS_RET_ERROR_FILE_OPEN                 -2
#define DIS_RET_ERROR_VOLUME_HEADER_READ        -11
#define DIS_RET_ERROR_VOLUME_HEADER_CHECK       -12
#define DIS_RET_ERROR_METADATA_OFFSET           -20
#define DIS_RET_ERROR_METADATA_CHECK            -21
#define DIS_RET_ERROR_METADATA_VERSION_UNSUPPORTED -22
#define DIS_RET_ERROR_DATASET_CHECK             -25
#define DIS_RET_ERROR_VMK_RETRIEVAL             -26
#define DIS_RET_ERROR_FVEK_RETRIEVAL            -27
#define DIS_RET_ERROR_CRYPTO_INIT               -40
#define DIS_RET_ERROR_CRYPTO_ALGORITHM_UNSUPPORTED -41
#define DIS_RET_ERROR_VOLUME_STATE_NOT_SAFE     -14
#define DIS_RET_ERROR_DISLOCKER_INVAL           -103

/* Opaque context */
typedef struct _dis_ctx dis_ctx_t;

/* Result of a decryption session initialization */
typedef struct {
	int   status;                  /* DIS_RET_* */
	uint16_t sector_size;          /* 512 or 4096 */
	uint16_t algorithm;            /* cipher_t, e.g. AES_XTS_256 */
	uint64_t volume_size;          /* decrypted volume size in bytes */
	uint64_t metadata_offset;      /* offset of the metadata on the volume */
	uint64_t data_offset;          /* physical offset where encrypted data starts */
	uint8_t  volume_guid[16];
	uint8_t  fvek[64];             /* decrypted FVEK (valid when status == 0) */
	uint8_t  fvek_len;             /* 32 (128-bit) or 64 (256-bit) key bytes */
} dis_session_info_t;

/* ---------------- public API ---------------- */

/* Probe whether the block device at `path` has a BitLocker volume header.
 * Returns 1 if a BitLocker header is present, 0 if not, -errno on error. */
int dis_has_bitlocker_header(const char *path);

/* Open a BitLocker volume, parse metadata, decrypt the VMK with the given
 * user password (UTF-8 bytes, no NUL), retrieve the FVEK, and set up AES-XTS
 * contexts. Returns a session handle or NULL (check get_dis_last_error()).
 * `offset` is the partition offset on the block device (0 for a full disk). */
dis_ctx_t *dis_open_volume(const char *path, off_t offset,
	const uint8_t *user_password, size_t password_len, dis_session_info_t *info);

/* Open a BitLocker volume using a 48-digit recovery key. */
dis_ctx_t *dis_open_volume_recovery(const char *path, off_t offset,
	const uint8_t *recovery_key, size_t recovery_key_len, dis_session_info_t *info);

/* Decrypt `size` bytes at `offset` (relative to the decrypted volume) into
 * `buffer`. Returns the number of bytes decrypted, or a negative error. */
int dis_read_decrypted(dis_ctx_t *ctx, uint8_t *buffer, off_t offset, size_t size);

/* Write `size` bytes from `buffer` at `offset` (relative to the decrypted volume).
 * Handles partial sectors via Read-Modify-Write and enforces the write barrier.
 * Returns the number of bytes written, or a negative error. */
int dis_write_encrypted(dis_ctx_t *ctx, const uint8_t *buffer, off_t offset, size_t size);

/* Decrypt a pre-read encrypted region (offset/size must be sector-aligned).
 * Used when the caller reads the raw sectors itself (e.g. via su). */
int dis_decrypt_region(dis_ctx_t *ctx, const uint8_t *in, uint8_t *out,
	off_t offset, size_t size);

/* Encrypt a plaintext region (offset/size must be sector-aligned).
 * Writes ciphertext to `out`. Enforces write-barrier against metadata area. */
int dis_encrypt_region(dis_ctx_t *ctx, const uint8_t *in, uint8_t *out,
	off_t offset, size_t size);

/* Close and free the session, wiping key material. */
void dis_close_volume(dis_ctx_t *ctx);

/* Accessors (the struct is opaque to callers). */
uint16_t dis_sector_size(dis_ctx_t *ctx);
uint64_t dis_volume_size(dis_ctx_t *ctx);
uint16_t dis_algorithm(dis_ctx_t *ctx);
int      dis_fvek_len(dis_ctx_t *ctx);
int      dis_get_recovery_key_id(dis_ctx_t *ctx, uint8_t guid_out[16]);

/* Last error string (thread-local). */
const char *dis_get_last_error(void);

#ifdef __cplusplus
}
#endif

#endif /* DISLOCKER_DISLOCKER_H */
