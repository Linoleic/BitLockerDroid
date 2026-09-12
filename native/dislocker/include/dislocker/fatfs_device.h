#ifndef DISLOCKER_FATFS_DEVICE_H
#define DISLOCKER_FATFS_DEVICE_H

#include "dislocker/dislocker.h"
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque handle to an opened FatFs volume */
typedef void* dis_fatfs_handle_t;

/* Mount the FAT/exFAT volume over the decrypted dislocker session */
dis_fatfs_handle_t dis_fatfs_mount(dis_ctx_t *ctx, int read_only);

/* Unmount and flush the FatFs volume */
int dis_fatfs_umount(dis_fatfs_handle_t vol_handle);

/* Create a file or directory at parent_path with the given name.
 * Returns created cluster number (or 0 for 0-byte file), or negative error. */
int64_t dis_fatfs_create(dis_fatfs_handle_t vol_handle, const char *parent_path, const char *name, int is_dir);

/* Delete a file or empty directory by path */
int dis_fatfs_delete(dis_fatfs_handle_t vol_handle, const char *path);

/* Rename or move a file/directory from old_path to new_path */
int dis_fatfs_rename(dis_fatfs_handle_t vol_handle, const char *old_path, const char *new_path);

/* Write `count` bytes from `buf` to file at `path` starting at `offset` */
int64_t dis_fatfs_write(dis_fatfs_handle_t vol_handle, const char *path, int64_t offset, const uint8_t *buf, int64_t count);

/* Truncate or extend a file at `path` to `new_size` */
int64_t dis_fatfs_truncate(dis_fatfs_handle_t vol_handle, const char *path, int64_t new_size);

/* Get total and free space in bytes. Returns 0 on success, -1 on error. */
int dis_fatfs_get_space(dis_fatfs_handle_t vol_handle, int64_t *total_bytes, int64_t *free_bytes);

#ifdef __cplusplus
}
#endif

#endif /* DISLOCKER_FATFS_DEVICE_H */
