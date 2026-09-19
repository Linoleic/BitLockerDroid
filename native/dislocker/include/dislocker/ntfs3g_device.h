#ifndef DISLOCKER_NTFS3G_DEVICE_H
#define DISLOCKER_NTFS3G_DEVICE_H

#include "dislocker/dislocker.h"
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque handle to an opened ntfs-3g volume */
typedef void* dis_ntfs_handle_t;

/* Mount the NTFS volume over the decrypted dislocker session */
dis_ntfs_handle_t dis_ntfs_mount(dis_ctx_t *ctx, int read_only);

/* Unmount and flush the NTFS volume */
int dis_ntfs_umount(dis_ntfs_handle_t vol_handle);

/* Create a file or directory at parent_path with the given name.
 * Returns created MFT record number (>= 0) on success, or negative errno on failure. */
int64_t dis_ntfs_create(dis_ntfs_handle_t vol_handle, const char *parent_path, const char *name, int is_dir);

/* Delete a file or empty directory by path */
int dis_ntfs_delete(dis_ntfs_handle_t vol_handle, const char *path);

/* Rename or move a file/directory from old_path to new_path */
int dis_ntfs_rename(dis_ntfs_handle_t vol_handle, const char *old_path, const char *new_path);

/* Write `count` bytes from `buf` to file at `path` starting at `offset` */
int64_t dis_ntfs_write(dis_ntfs_handle_t vol_handle, const char *path, int64_t offset, const uint8_t *buf, int64_t count);

/* Truncate or extend a file at `path` to `new_size` */
int64_t dis_ntfs_truncate(dis_ntfs_handle_t vol_handle, const char *path, int64_t new_size);

/* Get total and free space in bytes. Returns 0 on success, -1 on error. */
int dis_ntfs_get_space(dis_ntfs_handle_t vol_handle, int64_t *total_bytes, int64_t *free_bytes);

/* Clear volume dirty flags (VOLUME_IS_DIRTY, VOLUME_CHKDSK_UNDERWAY) and flush metadata */
int dis_ntfs_repair_dirty(dis_ntfs_handle_t vol_handle);

#ifdef __cplusplus
}
#endif

#endif /* DISLOCKER_NTFS3G_DEVICE_H */
