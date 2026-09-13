/*
 * fatfs_device.c -- Glue between ChaN's FatFs and BitLocker decryption/encryption core.
 * Implements diskio.h block interface and higher-level CRUD operations for FAT32 / exFAT.
 */
#define _GNU_SOURCE 1
#include "dislocker/fatfs_device.h"
#include "dislocker/dislocker_priv.h"

#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <time.h>
#include <stdio.h>

#include "ff.h"
#include "diskio.h"

#ifdef __ANDROID__
#include <android/log.h>
#define DLOG(...) __android_log_print(ANDROID_LOG_INFO, "BitLockerFatFs", __VA_ARGS__)
#else
#define DLOG(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

typedef struct {
    dis_ctx_t *ctx;
    FATFS fs;
    char drive_str[8];
    int in_use;
    int pdrv;
    int read_only;
    uint16_t sector_size;
} fatfs_slot_t;

static fatfs_slot_t g_fatfs_slots[FF_VOLUMES];
static pthread_mutex_t g_slot_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_fatfs_mutex[FF_VOLUMES + 1];

/* ---------------- FatFs OS Functions (ffsystem.c replacement) ---------------- */

void* ff_memalloc(UINT msize) {
    return malloc((size_t)msize);
}

void ff_memfree(void* mblock) {
    free(mblock);
}

int ff_mutex_create(int vol) {
    if (vol < 0 || vol > FF_VOLUMES) return 0;
    return pthread_mutex_init(&g_fatfs_mutex[vol], NULL) == 0;
}

void ff_mutex_delete(int vol) {
    if (vol >= 0 && vol <= FF_VOLUMES) {
        pthread_mutex_destroy(&g_fatfs_mutex[vol]);
    }
}

int ff_mutex_take(int vol) {
    if (vol < 0 || vol > FF_VOLUMES) return 0;
    return pthread_mutex_lock(&g_fatfs_mutex[vol]) == 0;
}

void ff_mutex_give(int vol) {
    if (vol >= 0 && vol <= FF_VOLUMES) {
        pthread_mutex_unlock(&g_fatfs_mutex[vol]);
    }
}

DWORD get_fattime(void) {
    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    return ((DWORD)(tm.tm_year - 80) << 25)
         | ((DWORD)(tm.tm_mon + 1) << 21)
         | ((DWORD)tm.tm_mday << 16)
         | ((DWORD)tm.tm_hour << 11)
         | ((DWORD)tm.tm_min << 5)
         | ((DWORD)(tm.tm_sec >> 1));
}

/* ---------------- Low-Level Disk I/O (diskio.h) ---------------- */

static size_t get_backup_bytes(dis_ctx_t *ctx) {
    if (ctx && ctx->information && ctx->information->nb_backup_sectors > 0) {
        return (size_t)ctx->information->nb_backup_sectors * ctx->sector_size;
    }
    return 8192;
}

static int disk_read_internal(dis_ctx_t *ctx, uint8_t *buff, off_t offset, size_t size) {
    if (!ctx || !buff || size == 0) return 0;
    size_t backup_bytes = get_backup_bytes(ctx);
    off_t backup_base = ctx->information ? (off_t)ctx->information->boot_sectors_backup : 0;

    if (backup_base > 0 && offset < (off_t)backup_bytes) {
        size_t part1 = (size_t)(backup_bytes - offset);
        if (part1 > size) part1 = size;
        int r1 = dis_read_decrypted(ctx, buff, backup_base + offset, part1);
        if (r1 < 0) return -1;
        if (part1 < size) {
            int r2 = dis_read_decrypted(ctx, buff + part1, offset + (off_t)part1, size - part1);
            if (r2 < 0) return -1;
        }
        return (int)size;
    }
    return dis_read_decrypted(ctx, buff, offset, size);
}

static int disk_write_internal(dis_ctx_t *ctx, const uint8_t *buff, off_t offset, size_t size) {
    if (!ctx || !buff || size == 0) return 0;
    size_t backup_bytes = get_backup_bytes(ctx);
    off_t backup_base = ctx->information ? (off_t)ctx->information->boot_sectors_backup : 0;

    if (backup_base > 0 && offset < (off_t)backup_bytes) {
        size_t part1 = (size_t)(backup_bytes - offset);
        if (part1 > size) part1 = size;
        int r1 = dis_write_encrypted(ctx, buff, backup_base + offset, part1);
        if (r1 < 0) return -1;
        if (part1 < size) {
            int r2 = dis_write_encrypted(ctx, buff + part1, offset + (off_t)part1, size - part1);
            if (r2 < 0) return -1;
        }
        return (int)size;
    }
    return dis_write_encrypted(ctx, buff, offset, size);
}

DSTATUS disk_initialize(BYTE pdrv) {
    if (pdrv >= FF_VOLUMES || !g_fatfs_slots[pdrv].in_use) return STA_NOINIT;
    return 0;
}

DSTATUS disk_status(BYTE pdrv) {
    if (pdrv >= FF_VOLUMES || !g_fatfs_slots[pdrv].in_use) return STA_NOINIT;
    return 0;
}

DRESULT disk_read(BYTE pdrv, BYTE* buff, LBA_t sector, UINT count) {
    if (pdrv >= FF_VOLUMES || !g_fatfs_slots[pdrv].in_use) return RES_NOTRDY;
    dis_ctx_t *ctx = g_fatfs_slots[pdrv].ctx;
    uint16_t ss = g_fatfs_slots[pdrv].sector_size ? g_fatfs_slots[pdrv].sector_size : (ctx->sector_size ? ctx->sector_size : 512);
    off_t offset = (off_t)sector * ss;
    size_t bytes = (size_t)count * ss;

    int ret = disk_read_internal(ctx, buff, offset, bytes);
    return (ret == (int)bytes) ? RES_OK : RES_ERROR;
}

DRESULT disk_write(BYTE pdrv, const BYTE* buff, LBA_t sector, UINT count) {
    if (pdrv >= FF_VOLUMES || !g_fatfs_slots[pdrv].in_use) return RES_NOTRDY;
    if (g_fatfs_slots[pdrv].read_only) return RES_WRPRT;
    dis_ctx_t *ctx = g_fatfs_slots[pdrv].ctx;
    uint16_t ss = g_fatfs_slots[pdrv].sector_size ? g_fatfs_slots[pdrv].sector_size : (ctx->sector_size ? ctx->sector_size : 512);
    off_t offset = (off_t)sector * ss;
    size_t bytes = (size_t)count * ss;

    int ret = disk_write_internal(ctx, buff, offset, bytes);
    return (ret == (int)bytes) ? RES_OK : RES_ERROR;
}

DRESULT disk_ioctl(BYTE pdrv, BYTE cmd, void* buff) {
    if (pdrv >= FF_VOLUMES || !g_fatfs_slots[pdrv].in_use) return RES_NOTRDY;
    dis_ctx_t *ctx = g_fatfs_slots[pdrv].ctx;
    uint16_t ss = g_fatfs_slots[pdrv].sector_size ? g_fatfs_slots[pdrv].sector_size : (ctx->sector_size ? ctx->sector_size : 512);

    switch (cmd) {
    case CTRL_SYNC:
        return dis_blk_sync(ctx) == 0 ? RES_OK : RES_ERROR;
    case GET_SECTOR_COUNT:
        *(LBA_t*)buff = (LBA_t)(ctx->volume_size / ss);
        return RES_OK;
    case GET_SECTOR_SIZE:
        *(WORD*)buff = (WORD)ss;
        return RES_OK;
    case GET_BLOCK_SIZE:
        *(DWORD*)buff = 1;
        return RES_OK;
    case CTRL_TRIM:
        return RES_OK;
    default:
        return RES_PARERR;
    }
}

/* ---------------- High-Level Bridge APIs ---------------- */

static void make_ff_path(fatfs_slot_t *slot, const char *path, char *out, size_t out_len) {
    if (!path || path[0] == '\0' || strcmp(path, "/") == 0) {
        snprintf(out, out_len, "%d:/", slot->pdrv);
        return;
    }
    if (path[0] == '/') {
        snprintf(out, out_len, "%d:%s", slot->pdrv, path);
    } else {
        snprintf(out, out_len, "%d:/%s", slot->pdrv, path);
    }
}

dis_fatfs_handle_t dis_fatfs_mount(dis_ctx_t *ctx, int read_only) {
    if (!ctx) return NULL;

    pthread_mutex_lock(&g_slot_lock);
    int slot_idx = -1;
    for (int i = 0; i < FF_VOLUMES; i++) {
        if (!g_fatfs_slots[i].in_use) {
            slot_idx = i;
            break;
        }
    }
    if (slot_idx < 0) {
        pthread_mutex_unlock(&g_slot_lock);
        DLOG("dis_fatfs_mount: no free slots available (max %d)", FF_VOLUMES);
        return NULL;
    }

    fatfs_slot_t *slot = &g_fatfs_slots[slot_idx];
    memset(slot, 0, sizeof(*slot));
    slot->ctx = ctx;
    slot->pdrv = slot_idx;
    slot->in_use = 1;
    slot->read_only = read_only;
    slot->sector_size = ctx->sector_size ? ctx->sector_size : 512;

    /* Detect sector size from decrypted filesystem boot sector (exFAT or FAT32) */
    uint8_t boot[512];
    if (disk_read_internal(ctx, boot, 0, 512) == 512) {
        if (memcmp(boot + 3, "EXFAT   ", 8) == 0) {
            uint8_t shift = boot[108];
            if (shift >= 9 && shift <= 12) {
                slot->sector_size = (uint16_t)(1 << shift);
            }
        } else if (boot[0] == 0xEB || boot[0] == 0xE9) {
            uint16_t bps = (uint16_t)boot[11] | ((uint16_t)boot[12] << 8);
            if (bps == 512 || bps == 1024 || bps == 2048 || bps == 4096) {
                slot->sector_size = bps;
            }
        }
    }
    DLOG("dis_fatfs_mount: slot %d sector_size=%u read_only=%d", slot_idx, (unsigned int)slot->sector_size, read_only);

    snprintf(slot->drive_str, sizeof(slot->drive_str), "%d:", slot_idx);
    pthread_mutex_unlock(&g_slot_lock);

    /* Mount the drive */
    FRESULT res = f_mount(&slot->fs, slot->drive_str, 1);
    if (res != FR_OK) {
        DLOG("f_mount failed for drive '%s' (res=%d)", slot->drive_str, (int)res);
        pthread_mutex_lock(&g_slot_lock);
        slot->in_use = 0;
        slot->ctx = NULL;
        pthread_mutex_unlock(&g_slot_lock);
        return NULL;
    }

    DLOG("Successfully mounted FatFs volume on '%s'", slot->drive_str);
    return (dis_fatfs_handle_t)slot;
}

int dis_fatfs_umount(dis_fatfs_handle_t vol_handle) {
    if (!vol_handle) return 0;
    fatfs_slot_t *slot = (fatfs_slot_t *)vol_handle;

    pthread_mutex_lock(&g_slot_lock);
    if (!slot->in_use) {
        pthread_mutex_unlock(&g_slot_lock);
        return 0;
    }
    f_unmount(slot->drive_str);
    slot->in_use = 0;
    slot->ctx = NULL;
    pthread_mutex_unlock(&g_slot_lock);

    DLOG("Unmounted FatFs volume");
    return 0;
}

int64_t dis_fatfs_create(dis_fatfs_handle_t vol_handle, const char *parent_path, const char *name, int is_dir) {
    if (!vol_handle || !name || strlen(name) == 0) return -EINVAL;
    fatfs_slot_t *slot = (fatfs_slot_t *)vol_handle;
    if (slot->read_only) return -EROFS;

    char full_path[1024];
    size_t plen = parent_path ? strlen(parent_path) : 0;
    while (plen > 1 && parent_path[plen - 1] == '/') {
        plen--;
    }
    if (!parent_path || plen == 0 || (plen == 1 && parent_path[0] == '/')) {
        snprintf(full_path, sizeof(full_path), "%d:/%s", slot->pdrv, name);
    } else {
        if (parent_path[0] == '/') {
            snprintf(full_path, sizeof(full_path), "%d:%.*s/%s", slot->pdrv, (int)plen, parent_path, name);
        } else {
            snprintf(full_path, sizeof(full_path), "%d:/%.*s/%s", slot->pdrv, (int)plen, parent_path, name);
        }
    }

    if (is_dir) {
        FRESULT res = f_mkdir(full_path);
        if (res != FR_OK && res != FR_EXIST) {
            DLOG("f_mkdir failed for '%s' (res=%d)", full_path, (int)res);
            return -((int64_t)res);
        }
        DIR dir;
        res = f_opendir(&dir, full_path);
        DWORD clst = (res == FR_OK) ? dir.obj.sclust : 0;
        if (res == FR_OK) f_closedir(&dir);
        DLOG("f_mkdir succeeded for '%s' -> clst=%lu", full_path, (unsigned long)clst);
        return (int64_t)clst;
    } else {
        FIL fil;
        FRESULT res = f_open(&fil, full_path, FA_CREATE_NEW | FA_WRITE | FA_READ);
        if (res != FR_OK) {
            DLOG("f_open create failed for '%s' (res=%d)", full_path, (int)res);
            return -((int64_t)res);
        }
        DWORD clst = fil.obj.sclust;
        f_close(&fil);
        DLOG("f_open create succeeded for '%s' -> clst=%lu", full_path, (unsigned long)clst);
        return (int64_t)clst;
    }
}

static FRESULT delete_recursive(const char *path) {
    DIR dir;
    FILINFO fno;
    FRESULT res = f_opendir(&dir, path);
    if (res != FR_OK) {
        return f_unlink(path);
    }

    char sub_path[1024];
    while (1) {
        res = f_readdir(&dir, &fno);
        if (res != FR_OK || fno.fname[0] == 0) break;
        if (strcmp(fno.fname, ".") == 0 || strcmp(fno.fname, "..") == 0) continue;

        snprintf(sub_path, sizeof(sub_path), "%s/%s", path, fno.fname);
        if (fno.fattrib & AM_DIR) {
            delete_recursive(sub_path);
        } else {
            f_unlink(sub_path);
        }
    }
    f_closedir(&dir);
    return f_unlink(path);
}

int dis_fatfs_delete(dis_fatfs_handle_t vol_handle, const char *path) {
    if (!vol_handle || !path) return -EINVAL;
    fatfs_slot_t *slot = (fatfs_slot_t *)vol_handle;
    if (slot->read_only) return -EROFS;

    char full_path[1024];
    make_ff_path(slot, path, full_path, sizeof(full_path));

    FRESULT res = f_unlink(full_path);
    if (res == FR_DENIED) {
        /* Directory not empty: delete recursively */
        res = delete_recursive(full_path);
    }
    if (res != FR_OK) {
        DLOG("f_unlink failed for '%s' (res=%d)", full_path, (int)res);
        return -((int)res);
    }
    DLOG("f_unlink succeeded for '%s'", full_path);
    return 0;
}

int dis_fatfs_rename(dis_fatfs_handle_t vol_handle, const char *old_path, const char *new_path) {
    if (!vol_handle || !old_path || !new_path) return -EINVAL;
    fatfs_slot_t *slot = (fatfs_slot_t *)vol_handle;
    if (slot->read_only) return -EROFS;

    char full_old[1024];
    char full_new[1024];
    make_ff_path(slot, old_path, full_old, sizeof(full_old));
    make_ff_path(slot, new_path, full_new, sizeof(full_new));

    FRESULT res = f_rename(full_old, full_new);
    if (res != FR_OK) {
        DLOG("f_rename failed for '%s' -> '%s' (res=%d)", full_old, full_new, (int)res);
        return -((int)res);
    }
    DLOG("f_rename succeeded for '%s' -> '%s'", full_old, full_new);
    return 0;
}

int64_t dis_fatfs_write(dis_fatfs_handle_t vol_handle, const char *path, int64_t offset, const uint8_t *buf, int64_t count) {
    if (!vol_handle || !path || !buf || count < 0 || offset < 0) return -EINVAL;
    fatfs_slot_t *slot = (fatfs_slot_t *)vol_handle;
    if (slot->read_only) return -EROFS;

    char full_path[1024];
    make_ff_path(slot, path, full_path, sizeof(full_path));

    FIL fil;
    FRESULT res = f_open(&fil, full_path, FA_WRITE | FA_OPEN_ALWAYS);
    if (res != FR_OK) {
        DLOG("f_open write failed for '%s' (res=%d)", full_path, (int)res);
        return -((int64_t)res);
    }

    res = f_lseek(&fil, (FSIZE_t)offset);
    if (res != FR_OK) {
        DLOG("f_lseek failed for '%s' at %lld (res=%d)", full_path, (long long)offset, (int)res);
        f_close(&fil);
        return -((int64_t)res);
    }

    UINT bw = 0;
    res = f_write(&fil, buf, (UINT)count, &bw);
    f_close(&fil);

    if (res != FR_OK) {
        DLOG("f_write failed for '%s' (res=%d)", full_path, (int)res);
        return -((int64_t)res);
    }
    return (int64_t)bw;
}

int64_t dis_fatfs_truncate(dis_fatfs_handle_t vol_handle, const char *path, int64_t new_size) {
    if (!vol_handle || !path || new_size < 0) return -EINVAL;
    fatfs_slot_t *slot = (fatfs_slot_t *)vol_handle;
    if (slot->read_only) return -EROFS;

    char full_path[1024];
    make_ff_path(slot, path, full_path, sizeof(full_path));

    if (new_size == 0) {
        FIL fil;
        FRESULT res = f_open(&fil, full_path, FA_CREATE_ALWAYS | FA_WRITE);
        if (res != FR_OK) {
            DLOG("f_open create_always failed for '%s' (res=%d)", full_path, (int)res);
            return -((int64_t)res);
        }
        f_close(&fil);
        DLOG("f_open create_always (truncate to 0) succeeded for '%s'", full_path);
        return 0;
    }

    FIL fil;
    FRESULT res = f_open(&fil, full_path, FA_WRITE | FA_OPEN_ALWAYS);
    if (res != FR_OK) {
        DLOG("f_open truncate failed for '%s' (res=%d)", full_path, (int)res);
        return -((int64_t)res);
    }

    res = f_lseek(&fil, (FSIZE_t)new_size);
    if (res != FR_OK) {
        f_close(&fil);
        return -((int64_t)res);
    }

    res = f_truncate(&fil);
    f_close(&fil);

    if (res != FR_OK) {
        DLOG("f_truncate failed for '%s' (res=%d)", full_path, (int)res);
        return -((int64_t)res);
    }
    return 0;
}

int dis_fatfs_get_space(dis_fatfs_handle_t vol_handle, int64_t *total_bytes, int64_t *free_bytes) {
    if (!vol_handle || !total_bytes || !free_bytes) return -1;
    fatfs_slot_t *slot = (fatfs_slot_t *)vol_handle;
    DWORD free_clst = 0;
    FATFS *fs = &slot->fs;
    FRESULT res = f_getfree(slot->drive_str, &free_clst, &fs);
    if (res != FR_OK || !fs) return -1;

#if FF_MAX_SS != FF_MIN_SS
    uint64_t cluster_size = (uint64_t)fs->csize * fs->ssize;
#else
    uint64_t cluster_size = (uint64_t)fs->csize * FF_MAX_SS;
#endif
    if (cluster_size == 0) cluster_size = 512;
    uint64_t total_clst = fs->n_fatent > 2 ? (fs->n_fatent - 2) : 0;
    *total_bytes = (int64_t)(total_clst * cluster_size);
    *free_bytes = (int64_t)((uint64_t)free_clst * cluster_size);
    return 0;
}
