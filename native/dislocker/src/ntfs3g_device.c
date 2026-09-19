/*
 * ntfs3g_device.c -- Custom ntfs_device_operations implementation for libntfs-3g
 * that transparently routes read/write through dislocker's AES-CBC/XTS encryption
 * and enforces the write barrier.
 */
#define _GNU_SOURCE 1
#include "dislocker/ntfs3g_device.h"
#include "dislocker/dislocker_priv.h"

#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <linux/fs.h>

#include "ntfs-3g/types.h"
#include "ntfs-3g/device.h"
#include "ntfs-3g/volume.h"
#include "ntfs-3g/dir.h"
#include "ntfs-3g/attrib.h"
#include "ntfs-3g/inode.h"
#include "ntfs-3g/unistr.h"
#include "ntfs-3g/layout.h"

#ifdef __ANDROID__
#include <android/log.h>
#define DLOG(...) __android_log_print(ANDROID_LOG_INFO, "BitLockerNtfs3g", __VA_ARGS__)
#else
#define DLOG(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

/* Forward declaration */
static struct ntfs_device_operations dislocker_ntfs_ops;

static int dislocker_dev_open(struct ntfs_device *dev, int flags)
{
	if (!dev || !dev->d_private) {
		errno = EINVAL;
		return -1;
	}
	set_ndev_flag(dev, Open);
	if (flags & O_RDONLY)
		set_ndev_flag(dev, ReadOnly);
	return 0;
}

static int dislocker_dev_close(struct ntfs_device *dev)
{
	if (!dev) {
		errno = EINVAL;
		return -1;
	}
	clear_ndev_flag(dev, Open);
	return 0;
}

static s64 dislocker_dev_seek(struct ntfs_device *dev, s64 offset, int whence)
{
	if (whence == SEEK_SET)
		return offset;
	return -1;
}

static s64 dislocker_dev_read(struct ntfs_device *dev, void *buf, s64 count)
{
	errno = ENOSYS;
	return -1;
}

static s64 dislocker_dev_write(struct ntfs_device *dev, const void *buf, s64 count)
{
	errno = ENOSYS;
	return -1;
}

static s64 dislocker_dev_pread(struct ntfs_device *dev, void *buf, s64 count, s64 offset)
{
	if (!dev || !dev->d_private || !buf || count < 0 || offset < 0) {
		errno = EINVAL;
		return -1;
	}
	dis_ctx_t *ctx = (dis_ctx_t *)dev->d_private;

	off_t target_offset = (off_t)offset;
	if (target_offset < 8192 && ctx->information) {
		target_offset += ctx->information->boot_sectors_backup;
	}

	int ret = dis_read_decrypted(ctx, (uint8_t *)buf, target_offset, (size_t)count);
	if (ret < 0) {
		errno = EIO;
		return -1;
	}
	return (s64)ret;
}

static s64 dislocker_dev_pwrite(struct ntfs_device *dev, const void *buf, s64 count, s64 offset)
{
	if (!dev || !dev->d_private || !buf || count < 0 || offset < 0) {
		errno = EINVAL;
		return -1;
	}
	dis_ctx_t *ctx = (dis_ctx_t *)dev->d_private;

	off_t target_offset = (off_t)offset;
	if (target_offset < 8192 && ctx->information) {
		target_offset += ctx->information->boot_sectors_backup;
	}

	int ret = dis_write_encrypted(ctx, (const uint8_t *)buf, target_offset, (size_t)count);
	if (ret < 0) {
		errno = EIO;
		return -1;
	}
	return (s64)ret;
}

static int dislocker_dev_sync(struct ntfs_device *dev)
{
	if (!dev || !dev->d_private)
		return 0;
	dis_ctx_t *ctx = (dis_ctx_t *)dev->d_private;
	return dis_blk_sync(ctx);
}

static int dislocker_dev_stat(struct ntfs_device *dev, struct stat *sbuf)
{
	if (!dev || !dev->d_private || !sbuf) {
		errno = EINVAL;
		return -1;
	}
	dis_ctx_t *ctx = (dis_ctx_t *)dev->d_private;

	memset(sbuf, 0, sizeof(*sbuf));
	sbuf->st_mode = S_IFBLK | 0600;
	sbuf->st_size = (off_t)ctx->volume_size;
	return 0;
}

static int dislocker_dev_ioctl(struct ntfs_device *dev, unsigned long request, void *argp)
{
	if (!dev || !dev->d_private) {
		errno = EINVAL;
		return -1;
	}
	dis_ctx_t *ctx = (dis_ctx_t *)dev->d_private;

	if (request == BLKSSZGET && argp) {
		*(int *)argp = (int)ctx->sector_size;
		return 0;
	}
	if (request == BLKGETSIZE64 && argp) {
		*(uint64_t *)argp = ctx->volume_size;
		return 0;
	}
	errno = ENOTTY;
	return -1;
}

static struct ntfs_device_operations dislocker_ntfs_ops = {
	.open   = dislocker_dev_open,
	.close  = dislocker_dev_close,
	.seek   = dislocker_dev_seek,
	.read   = dislocker_dev_read,
	.write  = dislocker_dev_write,
	.pread  = dislocker_dev_pread,
	.pwrite = dislocker_dev_pwrite,
	.sync   = dislocker_dev_sync,
	.stat   = dislocker_dev_stat,
	.ioctl  = dislocker_dev_ioctl,
};

/* ---------------- High-Level Bridge APIs ---------------- */

dis_ntfs_handle_t dis_ntfs_mount(dis_ctx_t *ctx, int read_only)
{
	if (!ctx)
		return NULL;

	struct ntfs_device *dev = ntfs_device_alloc(ctx->device_path, 0, &dislocker_ntfs_ops, ctx);
	if (!dev) {
		DLOG("ntfs_device_alloc failed");
		return NULL;
	}

	ntfs_mount_flags flags = NTFS_MNT_RECOVER | NTFS_MNT_IGNORE_HIBERFILE;
	if (read_only)
		flags |= NTFS_MNT_RDONLY;

	ntfs_volume *vol = ntfs_device_mount(dev, flags);
	if (!vol) {
		DLOG("ntfs_device_mount failed");
		return NULL;
	}
	return (dis_ntfs_handle_t)vol;
}

int dis_ntfs_umount(dis_ntfs_handle_t vol_handle)
{
	if (!vol_handle)
		return 0;
	return ntfs_umount((ntfs_volume *)vol_handle, FALSE);
}

static void sync_volume_metadata(ntfs_volume *vol)
{
	if (!vol) return;
	if (vol->lcnbmp_ni && NInoDirty(vol->lcnbmp_ni))
		ntfs_inode_sync(vol->lcnbmp_ni);
	if (vol->mft_ni && NInoDirty(vol->mft_ni))
		ntfs_inode_sync(vol->mft_ni);
	if (vol->mftmirr_ni && NInoDirty(vol->mftmirr_ni))
		ntfs_inode_sync(vol->mftmirr_ni);
	if (vol->dev)
		ntfs_device_sync(vol->dev);
}

int64_t dis_ntfs_create(dis_ntfs_handle_t vol_handle, const char *parent_path, const char *name, int is_dir)
{
	if (!vol_handle || !name || strlen(name) == 0)
		return -EINVAL;
	ntfs_volume *vol = (ntfs_volume *)vol_handle;

	ntfs_inode *dir_ni = NULL;
	if (!parent_path || strcmp(parent_path, "/") == 0 || strcmp(parent_path, "") == 0) {
		dir_ni = ntfs_inode_open(vol, FILE_root);
	} else {
		dir_ni = ntfs_pathname_to_inode(vol, NULL, parent_path);
	}
	if (!dir_ni) {
		DLOG("ntfs_create: failed to open parent directory: %s", parent_path ? parent_path : "/");
		return -ENOENT;
	}

	ntfschar *uname = NULL;
	int uname_len = ntfs_mbstoucs(name, &uname);
	if (uname_len < 0) {
		ntfs_inode_close(dir_ni);
		return -EINVAL;
	}

	mode_t type = is_dir ? S_IFDIR : S_IFREG;
	ntfs_inode *new_ni = ntfs_create(dir_ni, 0, uname, (u8)uname_len, type);
	free(uname);

	if (!new_ni) {
		int err = errno;
		DLOG("ntfs_create: failed to create %s in %s (errno=%d)", name, parent_path, err);
		ntfs_inode_close(dir_ni);
		return -err;
	}

	uint64_t mft_no = new_ni->mft_no;
	ntfs_inode_sync(new_ni);
	ntfs_inode_close(new_ni);

	/* Sync directory index to disk */
	ntfs_inode_sync(dir_ni);
	ntfs_inode_close(dir_ni);

	/* Flush volume metadata */
	sync_volume_metadata(vol);

	DLOG("ntfs_create succeeded for '%s' in '%s' -> mft=%llu", name, parent_path ? parent_path : "/", (unsigned long long)mft_no);
	return (int64_t)mft_no;
}

int dis_ntfs_delete(dis_ntfs_handle_t vol_handle, const char *path)
{
	if (!vol_handle || !path)
		return -EINVAL;
	ntfs_volume *vol = (ntfs_volume *)vol_handle;

	/* Find parent directory and name */
	char parent[1024];
	const char *name = NULL;
	const char *last_slash = strrchr(path, '/');
	if (!last_slash) {
		strcpy(parent, "/");
		name = path;
	} else if (last_slash == path) {
		strcpy(parent, "/");
		name = last_slash + 1;
	} else {
		size_t plen = last_slash - path;
		if (plen >= sizeof(parent)) plen = sizeof(parent) - 1;
		memcpy(parent, path, plen);
		parent[plen] = '\0';
		name = last_slash + 1;
	}

	if (!name || strlen(name) == 0) {
		DLOG("dis_ntfs_delete: invalid path '%s'", path);
		return -EINVAL;
	}

	ntfs_inode *dir_ni = NULL;
	if (strcmp(parent, "/") == 0 || strcmp(parent, "") == 0) {
		dir_ni = ntfs_inode_open(vol, FILE_root);
	} else {
		dir_ni = ntfs_pathname_to_inode(vol, NULL, parent);
	}
	if (!dir_ni) {
		DLOG("dis_ntfs_delete: failed to open parent directory '%s' for '%s'", parent, path);
		return -ENOENT;
	}

	ntfschar *uname = NULL;
	int uname_len = ntfs_mbstoucs(name, &uname);
	if (uname_len < 0) {
		ntfs_inode_close(dir_ni);
		return -EINVAL;
	}

	ntfs_inode *ni = ntfs_pathname_to_inode(vol, NULL, path);
	if (!ni) {
		DLOG("dis_ntfs_delete: failed to open target inode for '%s' (errno=%d)", path, errno);
		free(uname);
		ntfs_inode_close(dir_ni);
		return -ENOENT;
	}

	/*
	 * ntfs_delete unlinks and frees the inode if link count reaches 0.
	 * IMPORTANT: ntfs_delete automatically closes BOTH ni and dir_ni on success AND on failure.
	 */
	int ret = ntfs_delete(vol, path, ni, dir_ni, uname, (u8)uname_len);
	free(uname);

	if (ret < 0) {
		int err = errno;
		DLOG("ntfs_delete failed for %s (ret=%d, errno=%d)", path, ret, err);
		return -err;
	}

	sync_volume_metadata(vol);
	DLOG("dis_ntfs_delete succeeded for '%s'", path);
	return 0;
}

int dis_ntfs_rename(dis_ntfs_handle_t vol_handle, const char *old_path, const char *new_path)
{
	if (!vol_handle || !old_path || !new_path)
		return -EINVAL;
	ntfs_volume *vol = (ntfs_volume *)vol_handle;

	ntfs_inode *ni = ntfs_pathname_to_inode(vol, NULL, old_path);
	if (!ni)
		return -ENOENT;

	/* Find parent path and target name for new_path */
	const char *last_slash = strrchr(new_path, '/');
	char parent[1024];
	const char *target_name = NULL;
	if (!last_slash || last_slash == new_path) {
		strcpy(parent, "/");
		target_name = last_slash ? (last_slash + 1) : new_path;
	} else {
		size_t plen = last_slash - new_path;
		if (plen >= sizeof(parent)) plen = sizeof(parent) - 1;
		strncpy(parent, new_path, plen);
		parent[plen] = '\0';
		target_name = last_slash + 1;
	}

	ntfs_inode *new_dir_ni = NULL;
	if (strcmp(parent, "/") == 0) {
		new_dir_ni = ntfs_inode_open(vol, FILE_root);
	} else {
		new_dir_ni = ntfs_pathname_to_inode(vol, NULL, parent);
	}

	if (!new_dir_ni) {
		ntfs_inode_close(ni);
		return -ENOENT;
	}

	ntfschar *uname = NULL;
	int uname_len = ntfs_mbstoucs(target_name, &uname);
	if (uname_len < 0) {
		ntfs_inode_close(new_dir_ni);
		ntfs_inode_close(ni);
		return -EINVAL;
	}

	int ret = ntfs_link(ni, new_dir_ni, uname, (u8)uname_len);
	free(uname);
	ntfs_inode_sync(ni);
	ntfs_inode_close(ni);
	ntfs_inode_sync(new_dir_ni);
	ntfs_inode_close(new_dir_ni);

	if (ret < 0) {
		DLOG("ntfs_rename: failed to link %s -> %s (errno=%d)", old_path, new_path, errno);
		return -errno;
	}

	/* Unlink old name */
	ret = dis_ntfs_delete(vol_handle, old_path);
	sync_volume_metadata(vol);
	return ret;
}

int64_t dis_ntfs_write(dis_ntfs_handle_t vol_handle, const char *path, int64_t offset, const uint8_t *buf, int64_t count)
{
	if (!vol_handle || !path || !buf || count < 0 || offset < 0)
		return -EINVAL;
	ntfs_volume *vol = (ntfs_volume *)vol_handle;

	ntfs_inode *ni = ntfs_pathname_to_inode(vol, NULL, path);
	if (!ni)
		return -ENOENT;

	ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
	if (!na) {
		ntfs_inode_close(ni);
		return -EIO;
	}

	s64 written = ntfs_attr_pwrite(na, (s64)offset, (s64)count, buf);
	ntfs_attr_close(na);
	ntfs_inode_sync(ni);
	ntfs_inode_close(ni);
	sync_volume_metadata(vol);

	return (int64_t)written;
}

int64_t dis_ntfs_truncate(dis_ntfs_handle_t vol_handle, const char *path, int64_t new_size)
{
	if (!vol_handle || !path || new_size < 0)
		return -EINVAL;
	ntfs_volume *vol = (ntfs_volume *)vol_handle;

	ntfs_inode *ni = ntfs_pathname_to_inode(vol, NULL, path);
	if (!ni)
		return -ENOENT;

	ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
	if (!na) {
		ntfs_inode_close(ni);
		return -EIO;
	}

	int ret = ntfs_attr_truncate(na, (s64)new_size);
	ntfs_attr_close(na);
	ntfs_inode_sync(ni);
	ntfs_inode_close(ni);
	sync_volume_metadata(vol);

	return ret < 0 ? -errno : 0;
}

int dis_ntfs_get_space(dis_ntfs_handle_t vol_handle, int64_t *total_bytes, int64_t *free_bytes)
{
	if (!vol_handle || !total_bytes || !free_bytes)
		return -1;
	ntfs_volume *vol = (ntfs_volume *)vol_handle;

	if (!NVolFreeSpaceKnown(vol) || vol->free_clusters < 0) {
		if (ntfs_volume_get_free_space(vol) != 0) {
			DLOG("ntfs_volume_get_free_space failed");
		}
	}

	int64_t total = (int64_t)vol->nr_clusters * (int64_t)vol->cluster_size;
	int64_t free_b = (int64_t)vol->free_clusters * (int64_t)vol->cluster_size;
	if (free_b < 0) free_b = 0;
	if (total <= 0 && vol->dev && vol->dev->d_private) {
		total = (int64_t)((dis_ctx_t *)vol->dev->d_private)->volume_size;
	}
	if (free_b > total) free_b = total;
	*total_bytes = total;
	*free_bytes = free_b;
	DLOG("dis_ntfs_get_space: total=%lld, free=%lld (nr_clusters=%lld, free_clusters=%lld, cluster_size=%u)",
		(long long)*total_bytes, (long long)*free_bytes,
		(long long)vol->nr_clusters, (long long)vol->free_clusters, (unsigned int)vol->cluster_size);
	return 0;
}

int dis_ntfs_repair_dirty(dis_ntfs_handle_t vol_handle)
{
	if (!vol_handle)
		return -EINVAL;
	ntfs_volume *vol = (ntfs_volume *)vol_handle;

	BOOL was_ro = NVolReadOnly(vol);
	if (was_ro) {
		vol->state &= ~NV_ReadOnly;
		if (vol->dev)
			NDevClearReadOnly(vol->dev);
	}

	le16 clean_flags = cpu_to_le16(le16_to_cpu(vol->flags) & ~(VOLUME_IS_DIRTY | VOLUME_CHKDSK_UNDERWAY));
	int ret = ntfs_volume_write_flags(vol, clean_flags);
	if (ret < 0) {
		DLOG("ntfs_volume_write_flags failed: errno=%d", errno);
	}
	sync_volume_metadata(vol);

	if (was_ro) {
		vol->state |= NV_ReadOnly;
		if (vol->dev)
			NDevSetReadOnly(vol->dev);
	}
	return ret < 0 ? -errno : 0;
}
