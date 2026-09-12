/*
 * fuse_daemon.c -- High-performance Userspace FUSE Mount Daemon for BitLockerDroid.
 *
 * Mounts a decrypted BitLocker volume (NTFS / exFAT / FAT32) directly to a target
 * mountpoint (e.g. /storage/BitLocker_<Label>) using Linux /dev/fuse.
 *
 * Runs with Root privileges in the PID 1 global mount namespace, allowing all
 * third-party Android applications (e.g. MT Manager, MX Player, Termux) to access
 * the decrypted drive as a real POSIX directory without any kernel filesystem drivers.
 */

#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <errno.h>
#include <stdint.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/mount.h>
#include <sys/uio.h>
#include <linux/fuse.h>
#include <time.h>

#ifndef FATTR_SIZE
#define FATTR_SIZE (1 << 3)
#endif

#include <android/log.h>

#include "dislocker/dislocker.h"
#include "dislocker/dislocker_priv.h"
#include "dislocker/ntfs3g_device.h"
#include "dislocker/fatfs_device.h"

// ntfs-3g headers
#include "ntfs-3g/types.h"
#include "ntfs-3g/volume.h"
#include "ntfs-3g/dir.h"
#include "ntfs-3g/attrib.h"
#include "ntfs-3g/inode.h"
#include "ntfs-3g/unistr.h"
#include "ntfs-3g/layout.h"

// fatfs headers
#include "ff.h"

#define TAG "BitLockerFUSE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define FS_TYPE_NTFS  1
#define FS_TYPE_FATFS 2

#ifndef DT_DIR
#define DT_DIR 4
#endif
#ifndef DT_REG
#define DT_REG 8
#endif

static volatile int g_running = 1;
static int g_fuse_fd = -1;
static char g_mountpoint[512] = {0};

static dis_ctx_t *g_dis_ctx = NULL;
static int g_fs_type = 0;
static ntfs_volume *g_ntfs_vol = NULL;
static dis_fatfs_handle_t g_fatfs_vol = NULL;
static int g_fatfs_mounted = 0;
static int g_read_only = 0;

/* ---------------- Inode / Path Registry ---------------- */

typedef struct inode_entry {
    uint64_t ino;
    char *path;
    struct inode_entry *next;
} inode_entry_t;

#define INODE_HASH_SIZE 4096
static inode_entry_t *g_inode_table[INODE_HASH_SIZE];
static uint64_t g_next_ino = 2; // Inode 1 is FUSE_ROOT_ID

static uint32_t hash_str(const char *s) {
    uint32_t h = 5381;
    int c;
    while ((c = *s++)) h = ((h << 5) + h) + c;
    return h % INODE_HASH_SIZE;
}

static uint64_t get_or_create_ino(const char *path) {
    if (!path || strcmp(path, "/") == 0 || strcmp(path, "") == 0) {
        return FUSE_ROOT_ID;
    }
    uint32_t bucket = hash_str(path);
    inode_entry_t *curr = g_inode_table[bucket];
    while (curr) {
        if (strcmp(curr->path, path) == 0) return curr->ino;
        curr = curr->next;
    }

    // Create new mapping
    uint64_t new_ino = g_next_ino++;
    inode_entry_t *entry = (inode_entry_t *)malloc(sizeof(inode_entry_t));
    if (!entry) return FUSE_ROOT_ID;
    entry->ino = new_ino;
    entry->path = strdup(path);
    entry->next = g_inode_table[bucket];
    g_inode_table[bucket] = entry;
    return new_ino;
}

static const char *get_path_by_ino(uint64_t ino) {
    if (ino == FUSE_ROOT_ID) return "/";
    for (int i = 0; i < INODE_HASH_SIZE; i++) {
        inode_entry_t *curr = g_inode_table[i];
        while (curr) {
            if (curr->ino == ino) return curr->path;
            curr = curr->next;
        }
    }
    return NULL;
}

static void remove_ino(const char *path) {
    if (!path) return;
    uint32_t bucket = hash_str(path);
    inode_entry_t *curr = g_inode_table[bucket];
    inode_entry_t *prev = NULL;
    while (curr) {
        if (strcmp(curr->path, path) == 0) {
            if (prev) prev->next = curr->next;
            else g_inode_table[bucket] = curr->next;
            free(curr->path);
            free(curr);
            return;
        }
        prev = curr;
        curr = curr->next;
    }
}

static void rename_ino(const char *old_path, const char *new_path) {
    if (!old_path || !new_path) return;
    remove_ino(new_path);
    uint32_t old_b = hash_str(old_path);
    inode_entry_t *curr = g_inode_table[old_b];
    inode_entry_t *prev = NULL;
    while (curr) {
        if (strcmp(curr->path, old_path) == 0) {
            if (prev) prev->next = curr->next;
            else g_inode_table[old_b] = curr->next;
            free(curr->path);
            curr->path = strdup(new_path);
            uint32_t new_b = hash_str(new_path);
            curr->next = g_inode_table[new_b];
            g_inode_table[new_b] = curr;
            return;
        }
        prev = curr;
        curr = curr->next;
    }
    get_or_create_ino(new_path);
}

/* ---------------- Filesystem Helpers ---------------- */

typedef struct {
    uint64_t size;
    int is_dir;
    uint32_t mode;
    uint64_t mtime;
} file_meta_t;

static int query_file_meta(const char *path, file_meta_t *meta) {
    if (!meta) return -EINVAL;
    memset(meta, 0, sizeof(*meta));

    if (!path || strcmp(path, "/") == 0 || strcmp(path, "") == 0) {
        meta->is_dir = 1;
        meta->mode = S_IFDIR | 0777;
        meta->size = 4096;
        meta->mtime = 1700000000;
        return 0;
    }

    if (g_fs_type == FS_TYPE_NTFS) {
        ntfs_inode *ni = ntfs_pathname_to_inode(g_ntfs_vol, NULL, path);
        if (!ni) return -ENOENT;

        meta->is_dir = (ni->mrec && (ni->mrec->flags & MFT_RECORD_IS_DIRECTORY)) ? 1 : 0;
        meta->size = (uint64_t)ni->data_size;
        meta->mode = meta->is_dir ? (S_IFDIR | 0777) : (S_IFREG | 0666);
        meta->mtime = (uint64_t)(ni->last_data_change_time / 10000000 - 11644473600ULL);

        ntfs_inode_close(ni);
        return 0;
    } else if (g_fs_type == FS_TYPE_FATFS) {
        FILINFO fno;
        FRESULT fr = f_stat(path, &fno);
        if (fr != FR_OK) return -ENOENT;

        meta->is_dir = (fno.fattrib & AM_DIR) ? 1 : 0;
        meta->size = (uint64_t)fno.fsize;
        meta->mode = meta->is_dir ? (S_IFDIR | 0777) : (S_IFREG | 0666);
        meta->mtime = 1700000000;
        return 0;
    }

    return -EIO;
}

/* ---------------- FUSE Protocol Responses ---------------- */

static void send_fuse_reply(int fd, uint64_t unique, int error, const void *payload, size_t payload_len) {
    struct fuse_out_header out;
    out.len = (uint32_t)(sizeof(struct fuse_out_header) + payload_len);
    out.error = error;
    out.unique = unique;

    ssize_t res;
    if (payload && payload_len > 0) {
        struct iovec iov[2];
        iov[0].iov_base = &out;
        iov[0].iov_len = sizeof(out);
        iov[1].iov_base = (void *)payload;
        iov[1].iov_len = payload_len;
        res = writev(fd, iov, 2);
    } else {
        res = write(fd, &out, sizeof(out));
    }
    if (res < 0) {
        LOGE("send_fuse_reply unique=%llu error=%d failed: res=%zd errno=%d (%s)",
             (unsigned long long)unique, error, res, errno, strerror(errno));
    } else {
        LOGI("send_fuse_reply unique=%llu error=%d wrote %zd bytes",
             (unsigned long long)unique, error, res);
    }
}

#define FUSE_BUFFER_SIZE (256 * 1024)
#define FUSE_MAX_WRITE   (128 * 1024)

static void handle_fuse_init(int fd, struct fuse_in_header *in, void *data) {
    struct fuse_init_in *init_in = (struct fuse_init_in *)data;
    LOGI("handle_fuse_init: kernel major=%u minor=%u max_readahead=%u flags=0x%x",
         init_in->major, init_in->minor, init_in->max_readahead, init_in->flags);

    struct fuse_init_out init_out;
    memset(&init_out, 0, sizeof(init_out));

    init_out.major = FUSE_KERNEL_VERSION;
    init_out.minor = FUSE_KERNEL_MINOR_VERSION;
    init_out.max_readahead = init_in->max_readahead;
    init_out.flags = init_in->flags & (FUSE_ASYNC_READ | FUSE_BIG_WRITES | FUSE_AUTO_INVAL_DATA);
    init_out.max_background = 16;
    init_out.congestion_threshold = 12;
    init_out.max_write = FUSE_MAX_WRITE;
    init_out.time_gran = 1;

    send_fuse_reply(fd, in->unique, 0, &init_out, sizeof(init_out));
    LOGI("FUSE_INIT reply sent (proto %u.%u, max_write=%u)", init_out.major, init_out.minor, init_out.max_write);
}

static void fill_fuse_attr(uint64_t ino, const file_meta_t *meta, struct fuse_attr *attr) {
    memset(attr, 0, sizeof(*attr));
    attr->ino = ino;
    attr->size = meta->size;
    attr->blocks = (meta->size + 511) / 512;
    attr->atime = meta->mtime;
    attr->mtime = meta->mtime;
    attr->ctime = meta->mtime;
    attr->mode = meta->mode;
    attr->nlink = meta->is_dir ? 2 : 1;
    attr->uid = 1023; // media_rw
    attr->gid = 1023; // media_rw
    attr->blksize = 4096;
}

static void handle_fuse_getattr(int fd, struct fuse_in_header *in) {
    const char *path = get_path_by_ino(in->nodeid);
    if (!path) {
        send_fuse_reply(fd, in->unique, -ENOENT, NULL, 0);
        return;
    }

    file_meta_t meta;
    int ret = query_file_meta(path, &meta);
    if (ret != 0) {
        send_fuse_reply(fd, in->unique, ret, NULL, 0);
        return;
    }

    struct fuse_attr_out out;
    memset(&out, 0, sizeof(out));
    out.attr_valid = 5;
    fill_fuse_attr(in->nodeid, &meta, &out.attr);

    send_fuse_reply(fd, in->unique, 0, &out, sizeof(out));
}

static void handle_fuse_lookup(int fd, struct fuse_in_header *in, void *data) {
    const char *parent_path = get_path_by_ino(in->nodeid);
    const char *child_name = (const char *)data;

    if (!parent_path || !child_name) {
        send_fuse_reply(fd, in->unique, -ENOENT, NULL, 0);
        return;
    }

    char full_path[1024];
    if (strcmp(parent_path, "/") == 0) {
        snprintf(full_path, sizeof(full_path), "/%s", child_name);
    } else {
        snprintf(full_path, sizeof(full_path), "%s/%s", parent_path, child_name);
    }

    file_meta_t meta;
    int ret = query_file_meta(full_path, &meta);
    if (ret != 0) {
        send_fuse_reply(fd, in->unique, ret, NULL, 0);
        return;
    }

    uint64_t child_ino = get_or_create_ino(full_path);

    struct fuse_entry_out entry;
    memset(&entry, 0, sizeof(entry));
    entry.nodeid = child_ino;
    entry.generation = 1;
    entry.entry_valid = 5;
    entry.attr_valid = 5;
    fill_fuse_attr(child_ino, &meta, &entry.attr);

    send_fuse_reply(fd, in->unique, 0, &entry, sizeof(entry));
}

/* ---------------- Directory Reading ---------------- */

struct readdir_ctx {
    char *buf;
    size_t size;
    size_t filled;
    uint64_t offset;
    uint64_t current_idx;
    const char *parent_path;
};

static int readdir_add_entry(struct readdir_ctx *ctx, uint64_t ino, const char *name, int is_dir) {
    ctx->current_idx++;
    if (ctx->current_idx <= ctx->offset) {
        return 0; // Skip already emitted entries
    }

    size_t namelen = strlen(name);
    size_t entlen = FUSE_DIRENT_ALIGN(FUSE_NAME_OFFSET + namelen);

    if (ctx->filled + entlen > ctx->size) {
        return 1; // Buffer full
    }

    struct fuse_dirent *d = (struct fuse_dirent *)(ctx->buf + ctx->filled);
    d->ino = ino;
    d->off = ctx->current_idx;
    d->namelen = (uint32_t)namelen;
    d->type = is_dir ? DT_DIR : DT_REG;
    memcpy(d->name, name, namelen);

    ctx->filled += entlen;
    return 0;
}

static int ntfs_filldir_cb(void *dirent_ctx, const ntfschar *name, const int name_len,
                           const int name_type, const s64 pos, const MFT_REF mref,
                           const unsigned dt_type) {
    struct readdir_ctx *ctx = (struct readdir_ctx *)dirent_ctx;
    if (name_type == 2) return 0; // Skip DOS 8.3 names

    char *mbs = NULL;
    int len = ntfs_ucstombs(name, name_len, &mbs, 0);
    if (len <= 0 || !mbs) {
        return 0;
    }

    // Skip special NTFS metadata hidden files ($MFT, $LogFile, etc.)
    if (mbs[0] == '$' && strcmp(mbs, "$RECYCLE.BIN") != 0) {
        free(mbs);
        return 0;
    }

    char child_path[1024];
    if (strcmp(ctx->parent_path, "/") == 0) {
        snprintf(child_path, sizeof(child_path), "/%s", mbs);
    } else {
        snprintf(child_path, sizeof(child_path), "%s/%s", ctx->parent_path, mbs);
    }
    uint64_t child_ino = get_or_create_ino(child_path);

    int ret = readdir_add_entry(ctx, child_ino, mbs, dt_type == NTFS_DT_DIR);
    free(mbs);
    return ret;
}

static void handle_fuse_readdir(int fd, struct fuse_in_header *in, void *data) {
    struct fuse_read_in *rin = (struct fuse_read_in *)data;
    const char *path = get_path_by_ino(in->nodeid);
    if (!path) {
        send_fuse_reply(fd, in->unique, -ENOENT, NULL, 0);
        return;
    }

    char *buf = (char *)malloc(rin->size);
    if (!buf) {
        send_fuse_reply(fd, in->unique, -ENOMEM, NULL, 0);
        return;
    }

    struct readdir_ctx ctx;
    ctx.buf = buf;
    ctx.size = rin->size;
    ctx.filled = 0;
    ctx.offset = rin->offset;
    ctx.current_idx = 0;
    ctx.parent_path = path;

    // Always include "." and ".."
    readdir_add_entry(&ctx, in->nodeid, ".", 1);
    readdir_add_entry(&ctx, in->nodeid == FUSE_ROOT_ID ? FUSE_ROOT_ID : 1, "..", 1);

    if (g_fs_type == FS_TYPE_NTFS) {
        ntfs_inode *dir_ni = (strcmp(path, "/") == 0) ? ntfs_inode_open(g_ntfs_vol, FILE_root)
                                                      : ntfs_pathname_to_inode(g_ntfs_vol, NULL, path);
        if (dir_ni) {
            s64 pos = 0;
            ntfs_readdir(dir_ni, &pos, &ctx, ntfs_filldir_cb);
            ntfs_inode_close(dir_ni);
        }
    } else if (g_fs_type == FS_TYPE_FATFS) {
        DIR dir;
        FRESULT fr = f_opendir(&dir, path);
        if (fr == FR_OK) {
            FILINFO fno;
            while (f_readdir(&dir, &fno) == FR_OK && fno.fname[0] != 0) {
                int is_dir = (fno.fattrib & AM_DIR) ? 1 : 0;
                char child_path[1024];
                if (strcmp(path, "/") == 0) snprintf(child_path, sizeof(child_path), "/%s", fno.fname);
                else snprintf(child_path, sizeof(child_path), "%s/%s", path, fno.fname);
                uint64_t child_ino = get_or_create_ino(child_path);

                if (readdir_add_entry(&ctx, child_ino, fno.fname, is_dir) != 0) break;
            }
            f_closedir(&dir);
        }
    }

    send_fuse_reply(fd, in->unique, 0, ctx.buf, ctx.filled);
    free(buf);
}

/* ---------------- File Reading ---------------- */

static void handle_fuse_read(int fd, struct fuse_in_header *in, void *data) {
    struct fuse_read_in *rin = (struct fuse_read_in *)data;
    const char *path = get_path_by_ino(in->nodeid);
    if (!path) {
        send_fuse_reply(fd, in->unique, -ENOENT, NULL, 0);
        return;
    }

    char *buf = (char *)malloc(rin->size);
    if (!buf) {
        send_fuse_reply(fd, in->unique, -ENOMEM, NULL, 0);
        return;
    }

    ssize_t bytes_read = 0;

    if (g_fs_type == FS_TYPE_NTFS) {
        ntfs_inode *ni = ntfs_pathname_to_inode(g_ntfs_vol, NULL, path);
        if (ni) {
            ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, NULL, 0);
            if (na) {
                bytes_read = (ssize_t)ntfs_attr_pread(na, rin->offset, rin->size, buf);
                ntfs_attr_close(na);
            }
            ntfs_inode_close(ni);
        }
    } else if (g_fs_type == FS_TYPE_FATFS) {
        FIL fil;
        FRESULT fr = f_open(&fil, path, FA_READ);
        if (fr == FR_OK) {
            f_lseek(&fil, rin->offset);
            UINT br = 0;
            f_read(&fil, buf, rin->size, &br);
            bytes_read = (ssize_t)br;
            f_close(&fil);
        }
    }

    if (bytes_read < 0) bytes_read = 0;
    send_fuse_reply(fd, in->unique, 0, buf, (size_t)bytes_read);
    free(buf);
}

/* ---------------- File & Directory Modification ---------------- */

static void handle_fuse_create(int fd, struct fuse_in_header *in, void *data) {
    if (g_read_only) {
        send_fuse_reply(fd, in->unique, -EROFS, NULL, 0);
        return;
    }

    struct fuse_create_in *cin = (struct fuse_create_in *)data;
    const char *name = (const char *)data + sizeof(struct fuse_create_in);
    const char *parent_path = get_path_by_ino(in->nodeid);

    if (!parent_path || !name || strlen(name) == 0) {
        send_fuse_reply(fd, in->unique, -EINVAL, NULL, 0);
        return;
    }

    char full_path[1024];
    if (strcmp(parent_path, "/") == 0) {
        snprintf(full_path, sizeof(full_path), "/%s", name);
    } else {
        snprintf(full_path, sizeof(full_path), "%s/%s", parent_path, name);
    }

    int64_t ret = 0;
    if (g_fs_type == FS_TYPE_NTFS) {
        ret = dis_ntfs_create((dis_ntfs_handle_t)g_ntfs_vol, parent_path, name, 0);
    } else if (g_fs_type == FS_TYPE_FATFS) {
        ret = dis_fatfs_create(g_fatfs_vol, parent_path, name, 0);
    } else {
        ret = -ENOSYS;
    }

    if (ret < 0) {
        if (ret == -EEXIST && !(cin->flags & O_EXCL)) {
            if (cin->flags & O_TRUNC) {
                if (g_fs_type == FS_TYPE_NTFS) dis_ntfs_truncate((dis_ntfs_handle_t)g_ntfs_vol, full_path, 0);
                else if (g_fs_type == FS_TYPE_FATFS) dis_fatfs_truncate(g_fatfs_vol, full_path, 0);
            }
        } else {
            LOGE("handle_fuse_create failed for %s: %lld", full_path, (long long)ret);
            send_fuse_reply(fd, in->unique, (int)ret, NULL, 0);
            return;
        }
    }

    uint64_t child_ino = get_or_create_ino(full_path);
    file_meta_t meta;
    if (query_file_meta(full_path, &meta) != 0) {
        meta.is_dir = 0;
        meta.size = 0;
        meta.mode = S_IFREG | 0666;
        meta.mtime = (uint64_t)time(NULL);
    }

    struct {
        struct fuse_entry_out entry;
        struct fuse_open_out open;
    } reply;
    memset(&reply, 0, sizeof(reply));

    reply.entry.nodeid = child_ino;
    reply.entry.generation = 1;
    reply.entry.entry_valid = 1;
    reply.entry.attr_valid = 1;
    fill_fuse_attr(child_ino, &meta, &reply.entry.attr);

    reply.open.fh = child_ino;
    reply.open.open_flags = 0;

    send_fuse_reply(fd, in->unique, 0, &reply, sizeof(reply));
}

static void handle_fuse_write(int fd, struct fuse_in_header *in, void *data) {
    if (g_read_only) {
        send_fuse_reply(fd, in->unique, -EROFS, NULL, 0);
        return;
    }

    struct fuse_write_in *win = (struct fuse_write_in *)data;
    const char *path = get_path_by_ino(in->nodeid);
    if (!path && win->fh) path = get_path_by_ino(win->fh);
    if (!path) {
        send_fuse_reply(fd, in->unique, -ENOENT, NULL, 0);
        return;
    }

    const uint8_t *buf = (const uint8_t *)data + sizeof(struct fuse_write_in);
    int64_t written = 0;

    if (g_fs_type == FS_TYPE_NTFS) {
        written = dis_ntfs_write((dis_ntfs_handle_t)g_ntfs_vol, path, (int64_t)win->offset, buf, (int64_t)win->size);
    } else if (g_fs_type == FS_TYPE_FATFS) {
        written = dis_fatfs_write(g_fatfs_vol, path, (int64_t)win->offset, buf, (int64_t)win->size);
    } else {
        written = -ENOSYS;
    }

    if (written < 0) {
        LOGE("handle_fuse_write failed for %s (off=%lld, size=%u): %lld",
             path, (long long)win->offset, win->size, (long long)written);
        send_fuse_reply(fd, in->unique, (int)written, NULL, 0);
        return;
    }

    struct fuse_write_out out;
    memset(&out, 0, sizeof(out));
    out.size = (uint32_t)written;
    send_fuse_reply(fd, in->unique, 0, &out, sizeof(out));
}

static void handle_fuse_setattr(int fd, struct fuse_in_header *in, void *data) {
    struct fuse_setattr_in *sin = (struct fuse_setattr_in *)data;
    const char *path = get_path_by_ino(in->nodeid);
    if (!path && sin->fh) path = get_path_by_ino(sin->fh);
    if (!path) {
        send_fuse_reply(fd, in->unique, -ENOENT, NULL, 0);
        return;
    }

    if (sin->valid & FATTR_SIZE) {
        if (g_read_only) {
            send_fuse_reply(fd, in->unique, -EROFS, NULL, 0);
            return;
        }
        int64_t ret = 0;
        if (g_fs_type == FS_TYPE_NTFS) {
            ret = dis_ntfs_truncate((dis_ntfs_handle_t)g_ntfs_vol, path, (int64_t)sin->size);
        } else if (g_fs_type == FS_TYPE_FATFS) {
            ret = dis_fatfs_truncate(g_fatfs_vol, path, (int64_t)sin->size);
        }
        if (ret < 0) {
            LOGE("truncate failed for %s to %llu: %lld", path, (unsigned long long)sin->size, (long long)ret);
            send_fuse_reply(fd, in->unique, (int)ret, NULL, 0);
            return;
        }
    }

    file_meta_t meta;
    int ret = query_file_meta(path, &meta);
    if (ret != 0) {
        send_fuse_reply(fd, in->unique, ret, NULL, 0);
        return;
    }

    struct fuse_attr_out out;
    memset(&out, 0, sizeof(out));
    out.attr_valid = 1;
    fill_fuse_attr(in->nodeid, &meta, &out.attr);
    send_fuse_reply(fd, in->unique, 0, &out, sizeof(out));
}

static void handle_fuse_mkdir(int fd, struct fuse_in_header *in, void *data) {
    if (g_read_only) {
        send_fuse_reply(fd, in->unique, -EROFS, NULL, 0);
        return;
    }

    const char *name = (const char *)data + sizeof(struct fuse_mkdir_in);
    const char *parent_path = get_path_by_ino(in->nodeid);

    if (!parent_path || !name || strlen(name) == 0) {
        send_fuse_reply(fd, in->unique, -EINVAL, NULL, 0);
        return;
    }

    char full_path[1024];
    if (strcmp(parent_path, "/") == 0) {
        snprintf(full_path, sizeof(full_path), "/%s", name);
    } else {
        snprintf(full_path, sizeof(full_path), "%s/%s", parent_path, name);
    }

    int64_t ret = 0;
    if (g_fs_type == FS_TYPE_NTFS) {
        ret = dis_ntfs_create((dis_ntfs_handle_t)g_ntfs_vol, parent_path, name, 1);
    } else if (g_fs_type == FS_TYPE_FATFS) {
        ret = dis_fatfs_create(g_fatfs_vol, parent_path, name, 1);
    } else {
        ret = -ENOSYS;
    }

    if (ret < 0) {
        LOGE("handle_fuse_mkdir failed for %s: %lld", full_path, (long long)ret);
        send_fuse_reply(fd, in->unique, (int)ret, NULL, 0);
        return;
    }

    uint64_t child_ino = get_or_create_ino(full_path);
    file_meta_t meta;
    if (query_file_meta(full_path, &meta) != 0) {
        meta.is_dir = 1;
        meta.size = 4096;
        meta.mode = S_IFDIR | 0777;
        meta.mtime = (uint64_t)time(NULL);
    }

    struct fuse_entry_out entry;
    memset(&entry, 0, sizeof(entry));
    entry.nodeid = child_ino;
    entry.generation = 1;
    entry.entry_valid = 1;
    entry.attr_valid = 1;
    fill_fuse_attr(child_ino, &meta, &entry.attr);

    send_fuse_reply(fd, in->unique, 0, &entry, sizeof(entry));
}

static void handle_fuse_unlink(int fd, struct fuse_in_header *in, void *data, int is_dir) {
    if (g_read_only) {
        send_fuse_reply(fd, in->unique, -EROFS, NULL, 0);
        return;
    }

    const char *name = (const char *)data;
    const char *parent_path = get_path_by_ino(in->nodeid);

    if (!parent_path || !name || strlen(name) == 0) {
        send_fuse_reply(fd, in->unique, -EINVAL, NULL, 0);
        return;
    }

    char full_path[1024];
    if (strcmp(parent_path, "/") == 0) {
        snprintf(full_path, sizeof(full_path), "/%s", name);
    } else {
        snprintf(full_path, sizeof(full_path), "%s/%s", parent_path, name);
    }

    int ret = 0;
    if (g_fs_type == FS_TYPE_NTFS) {
        ret = dis_ntfs_delete((dis_ntfs_handle_t)g_ntfs_vol, full_path);
    } else if (g_fs_type == FS_TYPE_FATFS) {
        ret = dis_fatfs_delete(g_fatfs_vol, full_path);
    } else {
        ret = -ENOSYS;
    }

    if (ret != 0) {
        LOGE("handle_fuse_unlink failed for %s: %d", full_path, ret);
        send_fuse_reply(fd, in->unique, ret, NULL, 0);
        return;
    }

    remove_ino(full_path);
    send_fuse_reply(fd, in->unique, 0, NULL, 0);
}

static void handle_fuse_rename(int fd, struct fuse_in_header *in, void *data, int is_rename2) {
    if (g_read_only) {
        send_fuse_reply(fd, in->unique, -EROFS, NULL, 0);
        return;
    }

    uint64_t newdir = 0;
    const char *oldname = NULL;
    if (is_rename2) {
        struct fuse_rename2_in *r2 = (struct fuse_rename2_in *)data;
        newdir = r2->newdir;
        oldname = (const char *)data + sizeof(struct fuse_rename2_in);
    } else {
        struct fuse_rename_in *r = (struct fuse_rename_in *)data;
        newdir = r->newdir;
        oldname = (const char *)data + sizeof(struct fuse_rename_in);
    }
    const char *newname = oldname + strlen(oldname) + 1;

    const char *old_parent = get_path_by_ino(in->nodeid);
    const char *new_parent = get_path_by_ino(newdir);

    if (!old_parent || !new_parent || !oldname || !newname) {
        send_fuse_reply(fd, in->unique, -EINVAL, NULL, 0);
        return;
    }

    char old_path[1024], new_path[1024];
    if (strcmp(old_parent, "/") == 0) snprintf(old_path, sizeof(old_path), "/%s", oldname);
    else snprintf(old_path, sizeof(old_path), "%s/%s", old_parent, oldname);

    if (strcmp(new_parent, "/") == 0) snprintf(new_path, sizeof(new_path), "/%s", newname);
    else snprintf(new_path, sizeof(new_path), "%s/%s", new_parent, newname);

    int ret = 0;
    if (g_fs_type == FS_TYPE_NTFS) {
        ret = dis_ntfs_rename((dis_ntfs_handle_t)g_ntfs_vol, old_path, new_path);
    } else if (g_fs_type == FS_TYPE_FATFS) {
        ret = dis_fatfs_rename(g_fatfs_vol, old_path, new_path);
    } else {
        ret = -ENOSYS;
    }

    if (ret != 0) {
        LOGE("handle_fuse_rename failed %s -> %s: %d", old_path, new_path, ret);
        send_fuse_reply(fd, in->unique, ret, NULL, 0);
        return;
    }

    rename_ino(old_path, new_path);
    send_fuse_reply(fd, in->unique, 0, NULL, 0);
}

static void handle_fuse_statfs(int fd, struct fuse_in_header *in) {
    struct fuse_statfs_out out;
    memset(&out, 0, sizeof(out));

    out.st.bsize = 4096;
    out.st.frsize = 4096;
    if (g_dis_ctx) {
        uint64_t total = dis_volume_size(g_dis_ctx);
        out.st.blocks = total / 4096;
        out.st.bfree = out.st.blocks / 2; // approximation
        out.st.bavail = out.st.bfree;
    }
    out.st.files = 100000;
    out.st.ffree = 50000;
    out.st.namelen = 255;

    send_fuse_reply(fd, in->unique, 0, &out, sizeof(out));
}

/* ---------------- Signal & Cleanup ---------------- */

static void sig_handler(int sig) {
    (void)sig;
    g_running = 0;
}

static void cleanup_mount(void) {
    LOGI("Cleaning up FUSE mount at %s...", g_mountpoint);
    if (strlen(g_mountpoint) > 0) {
        umount2(g_mountpoint, MNT_DETACH);
        rmdir(g_mountpoint);
    }
    if (g_fuse_fd >= 0) {
        close(g_fuse_fd);
        g_fuse_fd = -1;
    }
    if (g_ntfs_vol) {
        dis_ntfs_umount((dis_ntfs_handle_t)g_ntfs_vol);
        g_ntfs_vol = NULL;
    }
    if (g_fatfs_vol) {
        dis_fatfs_umount(g_fatfs_vol);
        g_fatfs_vol = NULL;
    }
    g_fatfs_mounted = 0;
    if (g_dis_ctx) {
        dis_close_volume(g_dis_ctx);
        g_dis_ctx = NULL;
    }
    LOGI("Cleanup complete.");
}

/* ---------------- Main Entry Point ---------------- */

int main(int argc, char **argv) {
    signal(SIGTERM, sig_handler);
    signal(SIGINT, sig_handler);
    signal(SIGPIPE, SIG_IGN);

    if (argc < 6) {
        fprintf(stderr, "Usage: %s <dev_path> <offset> <key_type: 1=pass,2=rec> <key> <mountpoint> [rw|ro] [-f]\n", argv[0]);
        return 1;
    }

    const char *dev_path = argv[1];
    off_t offset = (off_t)atoll(argv[2]);
    int key_type = atoi(argv[3]);
    const char *key = argv[4];
    char key_buf[256];
    if (strcmp(key, "-") == 0) {
        if (fgets(key_buf, sizeof(key_buf), stdin)) {
            size_t l = strlen(key_buf);
            while (l > 0 && (key_buf[l - 1] == '\r' || key_buf[l - 1] == '\n')) {
                key_buf[--l] = '\0';
            }
            key = key_buf;
        } else {
            LOGE("Failed to read key from stdin");
            return 1;
        }
    }
    strncpy(g_mountpoint, argv[5], sizeof(g_mountpoint) - 1);

    int foreground = 0;
    for (int i = 6; i < argc; i++) {
        if (strcmp(argv[i], "-f") == 0) foreground = 1;
        else if (strcmp(argv[i], "ro") == 0 || strcmp(argv[i], "-ro") == 0) g_read_only = 1;
        else if (strcmp(argv[i], "rw") == 0 || strcmp(argv[i], "-rw") == 0) g_read_only = 0;
    }

    LOGI("Starting BitLocker FUSE mount: dev=%s offset=%lld key_type=%d mnt=%s ro=%d",
         dev_path, (long long)offset, key_type, g_mountpoint, g_read_only);

    // 1. Open BitLocker Volume
    dis_session_info_t info;
    if (key_type == 2) {
        g_dis_ctx = dis_open_volume_recovery(dev_path, offset, (const uint8_t *)key, strlen(key), &info);
    } else {
        g_dis_ctx = dis_open_volume(dev_path, offset, (const uint8_t *)key, strlen(key), &info);
    }

    if (!g_dis_ctx) {
        LOGE("Failed to unlock BitLocker volume: %s", dis_get_last_error());
        return 2;
    }
    LOGI("Dislocker volume unlocked: size=%llu bytes", (unsigned long long)info.volume_size);

    // 2. Identify filesystem by reading decrypted boot sector
    uint8_t boot[512];
    if (dis_read_decrypted(g_dis_ctx, boot, info.data_offset, 512) < 512) {
        LOGE("Failed to read decrypted boot sector");
        cleanup_mount();
        return 3;
    }

    if (memcmp(boot + 3, "NTFS    ", 8) == 0) {
        LOGI("Detected filesystem: NTFS (read_only=%d)", g_read_only);
        g_fs_type = FS_TYPE_NTFS;
        g_ntfs_vol = (ntfs_volume *)dis_ntfs_mount(g_dis_ctx, g_read_only);
        if (!g_ntfs_vol) {
            LOGE("dis_ntfs_mount failed");
            cleanup_mount();
            return 4;
        }
    } else {
        LOGI("Detected filesystem: FAT32/exFAT (read_only=%d)", g_read_only);
        g_fs_type = FS_TYPE_FATFS;
        g_fatfs_vol = dis_fatfs_mount(g_dis_ctx, g_read_only);
        if (!g_fatfs_vol) {
            LOGE("dis_fatfs_mount failed");
            cleanup_mount();
            return 4;
        }
        g_fatfs_mounted = 1;
    }

    // 3. Open /dev/fuse and Mount
    g_fuse_fd = open("/dev/fuse", O_RDWR | O_CLOEXEC);
    if (g_fuse_fd < 0) {
        LOGE("open /dev/fuse failed: %s", strerror(errno));
        cleanup_mount();
        return 5;
    }

    chmod("/storage", 0755);
    mkdir(g_mountpoint, 0755);
    char opts[256];
    snprintf(opts, sizeof(opts), "fd=%d,rootmode=0040755,user_id=1023,group_id=1023,allow_other", g_fuse_fd);

    unsigned long mntflags = MS_NOSUID | MS_NODEV | (g_read_only ? MS_RDONLY : 0);
    if (mount("bitlocker", g_mountpoint, "fuse", mntflags, opts) < 0) {
        LOGE("mount FUSE to %s failed: %s", g_mountpoint, strerror(errno));
        cleanup_mount();
        return 6;
    }
    LOGI("FUSE successfully mounted to %s with fd=%d!", g_mountpoint, g_fuse_fd);

    if (!foreground) {
        pid_t pid = fork();
        if (pid < 0) {
            LOGE("fork failed: %s", strerror(errno));
            cleanup_mount();
            return 8;
        }
        if (pid > 0) {
            printf("MOUNTED_PID=%d\n", (int)pid);
            fflush(stdout);
            _exit(0);
        }
        setsid();
        close(0);
        close(1);
        close(2);
        open("/dev/null", O_RDONLY);
        open("/dev/null", O_WRONLY);
        open("/dev/null", O_WRONLY);
    } else {
        printf("MOUNTED_PID=%d\n", (int)getpid());
        fflush(stdout);
    }

    // 4. Main FUSE Event Loop
    uint8_t *in_buf = (uint8_t *)malloc(FUSE_BUFFER_SIZE);
    if (!in_buf) {
        cleanup_mount();
        return 7;
    }

    while (g_running) {
        ssize_t n = read(g_fuse_fd, in_buf, FUSE_BUFFER_SIZE);
        if (n < (ssize_t)sizeof(struct fuse_in_header)) {
            LOGW("read from fuse_fd returned %zd, errno=%d (%s), g_running=%d",
                 n, errno, strerror(errno), g_running);
            if (n < 0 && (errno == EINTR || errno == EAGAIN)) {
                if (!g_running) break;
                continue;
            }
            break;
        }

        struct fuse_in_header *in = (struct fuse_in_header *)in_buf;
        void *data = in_buf + sizeof(struct fuse_in_header);

        switch (in->opcode) {
            case FUSE_INIT:
                handle_fuse_init(g_fuse_fd, in, data);
                break;
            case FUSE_GETATTR:
                handle_fuse_getattr(g_fuse_fd, in);
                break;
            case FUSE_SETATTR:
                handle_fuse_setattr(g_fuse_fd, in, data);
                break;
            case FUSE_LOOKUP:
                handle_fuse_lookup(g_fuse_fd, in, data);
                break;
            case FUSE_CREATE:
                handle_fuse_create(g_fuse_fd, in, data);
                break;
            case FUSE_OPEN:
            case FUSE_OPENDIR: {
                struct fuse_open_out o;
                memset(&o, 0, sizeof(o));
                o.fh = in->nodeid;
                send_fuse_reply(g_fuse_fd, in->unique, 0, &o, sizeof(o));
                break;
            }
            case FUSE_READ:
                handle_fuse_read(g_fuse_fd, in, data);
                break;
            case FUSE_WRITE:
                handle_fuse_write(g_fuse_fd, in, data);
                break;
            case FUSE_MKDIR:
                handle_fuse_mkdir(g_fuse_fd, in, data);
                break;
            case FUSE_UNLINK:
                handle_fuse_unlink(g_fuse_fd, in, data, 0);
                break;
            case FUSE_RMDIR:
                handle_fuse_unlink(g_fuse_fd, in, data, 1);
                break;
            case FUSE_RENAME:
                handle_fuse_rename(g_fuse_fd, in, data, 0);
                break;
            case FUSE_RENAME2:
                handle_fuse_rename(g_fuse_fd, in, data, 1);
                break;
            case FUSE_READDIR:
                handle_fuse_readdir(g_fuse_fd, in, data);
                break;
            case FUSE_RELEASE:
            case FUSE_RELEASEDIR:
            case FUSE_FLUSH:
            case FUSE_FSYNC:
            case FUSE_FSYNCDIR:
                send_fuse_reply(g_fuse_fd, in->unique, 0, NULL, 0);
                break;
            case FUSE_ACCESS:
                send_fuse_reply(g_fuse_fd, in->unique, 0, NULL, 0);
                break;
            case FUSE_STATFS:
                handle_fuse_statfs(g_fuse_fd, in);
                break;
            case FUSE_FALLOCATE:
                send_fuse_reply(g_fuse_fd, in->unique, 0, NULL, 0);
                break;
            case FUSE_FORGET:
            case FUSE_BATCH_FORGET:
                // No reply required for forget
                break;
            default:
                LOGW("unhandled fuse opcode %u, unique=%llu", in->opcode, (unsigned long long)in->unique);
                send_fuse_reply(g_fuse_fd, in->unique, -ENOSYS, NULL, 0);
                break;
        }
    }

    free(in_buf);
    cleanup_mount();
    return 0;
}
