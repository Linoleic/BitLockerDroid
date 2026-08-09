/*
 * dislocker.c -- BitLockerDroid native core: session orchestration.
 *
 * Structure and API modelled after dislocker v0.7.3 (src/dislocker.c),
 * GPL-2.0. This builds a self-contained library exposing:
 *   - dis_has_bitlocker_header()
 *   - dis_open_volume() / dis_open_volume_recovery()
 *   - dis_read_decrypted()
 *   - dis_close_volume()
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
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>

/* ---------------- error reporting ---------------- */

static __thread char last_error[256];

void dis_set_error(const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	vsnprintf(last_error, sizeof(last_error), fmt, ap);
	va_end(ap);
}

const char *dis_get_last_error(void)
{
	return last_error;
}

/* ---------------- public API ---------------- */

uint16_t dis_sector_size(dis_ctx_t *ctx) { return ctx ? ctx->sector_size : 0; }
uint64_t dis_volume_size(dis_ctx_t *ctx) { return ctx ? ctx->volume_size : 0; }
uint16_t dis_algorithm(dis_ctx_t *ctx)   { return ctx ? ctx->algorithm : 0; }
int      dis_fvek_len(dis_ctx_t *ctx)    { return ctx ? ctx->fvek_len : 0; }

int dis_has_bitlocker_header(const char *path)
{
	int fd = open(path, O_RDONLY | O_CLOEXEC);
	if (fd < 0) {
		dis_set_error("Cannot open %s: %s", path, strerror(errno));
		return -errno;
	}

	volume_header_t vh;
	ssize_t nb = pread(fd, &vh, sizeof(vh), 0);
	close(fd);

	if (nb != (ssize_t)sizeof(vh))
		return 0; /* too small or unreadable -> not BitLocker */

	if (memcmp(BITLOCKER_SIGNATURE, vh.signature, strlen(BITLOCKER_SIGNATURE)) == 0)
		return 1;
	if (memcmp(BITLOCKER_TO_GO_SIGNATURE, vh.signature, strlen(BITLOCKER_TO_GO_SIGNATURE)) == 0)
		return 1;

	return 0;
}

static dis_ctx_t *dis_open_volume_common(const char *path, off_t offset,
	const uint8_t *user_password, size_t password_len,
	const uint8_t *recovery_key, size_t recovery_key_len,
	dis_session_info_t *info)
{
	dis_ctx_t *ctx = calloc(1, sizeof(dis_ctx_t));
	if (!ctx) {
		dis_set_error("Out of memory");
		return NULL;
	}

	ctx->fd = open(path, O_RDONLY | O_CLOEXEC);
	/* If we cannot open the block device directly (SELinux), all reads will
	 * go through su dd in dis_blk_read(); keep the fd but don't fail. */
	if (ctx->fd < 0)
		ctx->fd = -1;

	snprintf(ctx->device_path, sizeof(ctx->device_path), "%s", path);

	/* 1. parse volume header + metadata */
	int ret = dis_metadata_parse(ctx);
	if (ret != DIS_RET_SUCCESS) {
		DLOG(
			"metadata_parse failed ret=%d err=%s", ret, dis_get_last_error());
		dis_close_volume(ctx);
		return NULL;
	}

	/* Logical offsets equal physical offsets on this volume; the CBC/XTS tweak
	 * uses the absolute byte offset. Keep ctx->offset = 0 so reads at logical
	 * offset X read physical X with IV=X. */
	ctx->offset = 0;

	DLOG("volume: alg=0x%04x sector=%u size=%llu boot_backup=0x%llx info_off0=0x%llx",
		ctx->algorithm, ctx->sector_size,
		(unsigned long long)ctx->volume_size,
		(unsigned long long)ctx->information->boot_sectors_backup,
		(unsigned long long)ctx->information->information_off[0]);

	/* 2. retrieve VMK/FVEK */
	if (!dis_retrieve_keys(ctx, user_password, password_len, recovery_key, recovery_key_len)) {
		DLOG(
			"retrieve_keys failed err=%s", dis_get_last_error());
		dis_close_volume(ctx);
		return NULL;
	}

	/* 3. init XTS contexts from FVEK */
	if (!dis_init_xts(ctx)) {
		DLOG(
			"init_xts failed err=%s", dis_get_last_error());
		dis_close_volume(ctx);
		return NULL;
	}

	/* 4. fill the info struct */
	if (info) {
		memset(info, 0, sizeof(*info));
		info->status = DIS_RET_SUCCESS;
		info->sector_size = ctx->sector_size;
		info->algorithm = ctx->algorithm;
		info->volume_size = ctx->volume_size;
		/* data_offset = physical offset where the NTFS data starts
		 * (boot_backup); logical reads are absolute (physical = logical). */
		info->data_offset = ctx->information->boot_sectors_backup;
		memcpy(info->volume_guid, ctx->volume_header.guid, 16);
		memcpy(info->fvek, ctx->fvek, ctx->fvek_len);
		info->fvek_len = (uint8_t)ctx->fvek_len;
	}

	return ctx;
}

dis_ctx_t *dis_open_volume(const char *path, off_t offset,
	const uint8_t *user_password, size_t password_len, dis_session_info_t *info)
{
	if (!path || !user_password || password_len == 0) {
		dis_set_error("Invalid arguments to dis_open_volume");
		return NULL;
	}
	return dis_open_volume_common(path, offset, user_password, password_len, NULL, 0, info);
}

dis_ctx_t *dis_open_volume_recovery(const char *path, off_t offset,
	const uint8_t *recovery_key, size_t recovery_key_len, dis_session_info_t *info)
{
	if (!path || !recovery_key || recovery_key_len == 0) {
		dis_set_error("Invalid arguments to dis_open_volume_recovery");
		return NULL;
	}
	return dis_open_volume_common(path, offset, NULL, 0, recovery_key, recovery_key_len, info);
}

int dis_read_decrypted(dis_ctx_t *ctx, uint8_t *buffer, off_t offset, size_t size)
{
	if (!ctx || !buffer) {
		dis_set_error("Invalid arguments to dis_read_decrypted");
		return -EINVAL;
	}

	if (size == 0)
		return 0;

	if (offset < 0) {
		dis_set_error("Negative offset %lld", (long long)offset);
		return -EINVAL;
	}

	if ((uint64_t)offset >= ctx->volume_size) {
		dis_set_error("Offset %lld exceeds volume size %llu",
			(long long)offset, (unsigned long long)ctx->volume_size);
		return -EINVAL;
	}

	uint16_t sector_size = ctx->sector_size;
	size_t sector_to_add = 0;

	if ((offset % sector_size) != 0)
		sector_to_add += 1;
	if (((offset + (off_t)size) % sector_size) != 0)
		sector_to_add += 1;

	size_t sector_count = (size / sector_size) + sector_to_add;
	off_t sector_start = offset / sector_size;
	size_t total = sector_count * sector_size;

	uint8_t *buf = malloc(total);
	if (!buf)
		return -ENOMEM;

	int ok = 1;
	for (size_t i = 0; i < sector_count; i++) {
		uint8_t sector[4096];
		uint8_t *dst = buf + i * sector_size;

		if (!dis_sector_read(ctx, sector, (sector_start + i) * sector_size)) {
			ok = 0;
			break;
		}
		memcpy(dst, sector, sector_size);
	}

	if (!ok) {
		memset(buf, 0, total);
		free(buf);
		return -EIO;
	}

	memcpy(buffer, buf + (offset % sector_size), size);

	memset(buf, 0, total);
	free(buf);

	return (int)size;
}

/*
 * Decrypt a region of encrypted data that has already been read into `in`.
 * `offset` is the volume-relative byte offset of `in`. Writes decrypted data
 * to `out`. This lets the Java layer feed sectors read via su while the native
 * core does the AES decrypt. Returns number of bytes written or -1.
 */
int dis_decrypt_region(dis_ctx_t *ctx, const uint8_t *in, uint8_t *out,
	off_t offset, size_t size)
{
	if (!ctx || !in || !out || size == 0)
		return -1;

	uint16_t sector_size = ctx->sector_size;
	if (offset % sector_size != 0 || size % sector_size != 0)
		return -1;

	size_t n = size / sector_size;
	for (size_t i = 0; i < n; i++) {
		uint8_t *sector = (uint8_t *)in + i * sector_size;
		uint8_t *dst = out + i * sector_size;
		memcpy(dst, sector, sector_size);
		if (!dis_decrypt_sector(ctx, dst, offset + i * sector_size))
			return -1;
	}
	return (int)size;
}

void dis_close_volume(dis_ctx_t *ctx)
{
	if (!ctx)
		return;

	if (ctx->fd >= 0)
		close(ctx->fd);

	dis_metadata_free(ctx);

	memset(ctx->fvek, 0, sizeof(ctx->fvek));
	memset(ctx, 0, sizeof(dis_ctx_t));
	free(ctx);
}
