/*
 * Safe regression harness for scsi_spindown_device(). All operating-system
 * calls reachable from that function are replaced before including the source.
 * No block device, sysfs node, or real syscall is touched by this executable.
 */
#define _GNU_SOURCE 1
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <unistd.h>
#include <scsi/sg.h>

static int fake_stat(const char *path, struct stat *st);
static int fake_access(const char *path, int mode);
static char *fake_realpath(const char *path, char *resolved);
static int fake_open(const char *path, int flags, ...);
static int fake_ioctl(int fd, unsigned long request, ...);
static ssize_t fake_write(int fd, const void *buf, size_t count);
static int fake_close(int fd);
static void fake_sync(void);
static DIR *fake_opendir(const char *path);
static struct dirent *fake_readdir(DIR *dir);
static int fake_closedir(DIR *dir);

/* Function-like macros leave struct stat and other type names untouched. */
#define stat(...) fake_stat(__VA_ARGS__)
#define access(...) fake_access(__VA_ARGS__)
#define realpath(...) fake_realpath(__VA_ARGS__)
#define open(...) fake_open(__VA_ARGS__)
#define ioctl(...) fake_ioctl(__VA_ARGS__)
#define write(...) fake_write(__VA_ARGS__)
#define close(...) fake_close(__VA_ARGS__)
#define sync(...) fake_sync(__VA_ARGS__)
#define opendir(...) fake_opendir(__VA_ARGS__)
#define readdir(...) fake_readdir(__VA_ARGS__)
#define closedir(...) fake_closedir(__VA_ARGS__)
#include "../daemon/smart_reader.c"
#undef stat
#undef access
#undef realpath
#undef open
#undef ioctl
#undef write
#undef close
#undef sync
#undef opendir
#undef readdir
#undef closedir

enum fail_mode {
    FAIL_NONE,
    FAIL_BLOCK_FLUSH,
    FAIL_SYNC_CACHE_ILLEGAL,
    FAIL_STOP_ILLEGAL,
    FAIL_DELETE_WRITE,
    FAIL_DELETE_OPEN,
    KEEP_BLOCK_AFTER_DELETE,
    INTERNAL_UFS
};

static enum fail_mode g_mode;
static char g_events[64];
static size_t g_event_count;
static int g_sg_sync_count;
static int g_sg_stop_count;
static int g_delete_write_count;
static int g_open_device_count;
static int g_open_delete_count;
static int g_close_device_count;
static int g_close_delete_count;
static int g_partition_access_count;
static int g_sys_block_access_count;
static int g_ioctl_count;
static int g_write_count;
static int g_delete_written;

static void event(char c) {
    assert(g_event_count + 1 < sizeof(g_events));
    g_events[g_event_count++] = c;
    g_events[g_event_count] = '\0';
}

static void reset_fake(enum fail_mode mode) {
    g_mode = mode;
    g_events[0] = '\0';
    g_event_count = 0;
    g_sg_sync_count = 0;
    g_sg_stop_count = 0;
    g_delete_write_count = 0;
    g_open_device_count = 0;
    g_open_delete_count = 0;
    g_close_device_count = 0;
    g_close_delete_count = 0;
    g_partition_access_count = 0;
    g_sys_block_access_count = 0;
    g_ioctl_count = 0;
    g_write_count = 0;
    g_delete_written = 0;
    errno = 0;
}

static int fake_stat(const char *path, struct stat *st) {
    assert(strcmp(path, "/dev/block/sda") == 0);
    memset(st, 0, sizeof(*st));
    st->st_mode = S_IFBLK | 0600;
    st->st_rdev = makedev(8, 0);
    return 0;
}

static int fake_access(const char *path, int mode) {
    (void)mode;
    if (strstr(path, "/partition")) {
        g_partition_access_count++;
        errno = ENOENT;
        return -1;
    }
    if (strcmp(path, "/sys/block/sda") == 0) {
        g_sys_block_access_count++;
        if (g_delete_written && g_mode != KEEP_BLOCK_AFTER_DELETE) {
            errno = ENOENT;
            return -1;
        }
        return 0;
    }
    assert(0 && "unexpected access path");
    errno = ENOENT;
    return -1;
}

static char *fake_realpath(const char *path, char *resolved) {
    assert(strcmp(path, "/sys/block/sda") == 0 || strcmp(path, "/sys/dev/block/8:0") == 0);
    const char *sys_path = g_mode == INTERNAL_UFS
        ? "/sys/devices/platform/ufshc/host0/block/sda"
        : "/sys/devices/platform/usbhost/1-1/host0/target0:0:0/0:0:0:0/block/sda";
    strcpy(resolved, sys_path);
    return resolved;
}

static int fake_open(const char *path, int flags, ...) {
    (void)flags;
    if (strcmp(path, "/dev/block/sda") == 0) {
        g_open_device_count++;
        return 10;
    }
    if (strcmp(path, "/sys/block/sda/device/delete") == 0) {
        if (g_mode == FAIL_DELETE_OPEN) {
            errno = ENOENT;
            return -1;
        }
        g_open_delete_count++;
        return 20;
    }
    assert(0 && "unexpected open path");
    errno = ENOENT;
    return -1;
}

static int fake_ioctl(int fd, unsigned long request, ...) {
    assert(fd == 10);
    g_ioctl_count++;
    if (request == BLKFLSBUF) {
        event('B');
        if (g_mode == FAIL_BLOCK_FLUSH) {
            errno = EIO;
            return -1;
        }
        return 0;
    }
    assert(request == SG_IO);
    va_list args;
    va_start(args, request);
    struct sg_io_hdr *hdr = va_arg(args, struct sg_io_hdr *);
    va_end(args);
    assert(hdr != NULL && hdr->cmdp != NULL);
    if (hdr->cmdp[0] == 0x35) {
        assert(hdr->cmd_len == 10);
        g_sg_sync_count++;
        event('C');
        if (g_mode == FAIL_SYNC_CACHE_ILLEGAL) {
            hdr->status = 2;
            if (hdr->sbp && hdr->mx_sb_len >= 3) {
                ((unsigned char *)hdr->sbp)[0] = 0x70;
                ((unsigned char *)hdr->sbp)[2] = 0x05;
            }
        }
    } else if (hdr->cmdp[0] == 0x1b) {
        assert(hdr->cmd_len == 6);
        g_sg_stop_count++;
        event('T');
        if (g_mode == FAIL_STOP_ILLEGAL) {
            hdr->status = 2;
            if (hdr->sbp && hdr->mx_sb_len >= 3) {
                ((unsigned char *)hdr->sbp)[0] = 0x70;
                ((unsigned char *)hdr->sbp)[2] = 0x05;
            }
        }
    } else {
        assert(0 && "unexpected SCSI command");
    }
    return 0;
}

static ssize_t fake_write(int fd, const void *buf, size_t count) {
    assert(fd == 20);
    assert(count == 2 && memcmp(buf, "1\n", 2) == 0);
    g_write_count++;
    g_delete_write_count++;
    event('D');
    if (g_mode == FAIL_DELETE_WRITE) {
        errno = EIO;
        return -1;
    }
    g_delete_written = 1;
    return 2;
}

static int fake_close(int fd) {
    if (fd == 10) g_close_device_count++;
    else if (fd == 20) g_close_delete_count++;
    else assert(0 && "unexpected descriptor close");
    return 0;
}

static void fake_sync(void) { event('s'); }

static DIR *fake_opendir(const char *path) {
    assert(strcmp(path, "/sys/block/sda/device/scsi_disk") == 0);
    errno = ENOENT; /* Optional manage_start_stop directory is absent. */
    return NULL;
}

static struct dirent *fake_readdir(DIR *dir) {
    (void)dir;
    assert(0 && "readdir should not be reached when opendir fails");
    return NULL;
}

static int fake_closedir(DIR *dir) {
    (void)dir;
    assert(0 && "closedir should not be reached when opendir fails");
    return -1;
}

static void test_success_order(void) {
    reset_fake(FAIL_NONE);
    assert(scsi_spindown_device("/dev/block/sda") == 0);
    assert(strcmp(g_events, "sBCTD") == 0);
    assert(g_sg_sync_count == 1 && g_sg_stop_count == 1);
    assert(g_delete_write_count == 1 && g_write_count == 1);
    assert(g_open_device_count == 1 && g_close_device_count == 1);
    assert(g_open_delete_count == 1 && g_close_delete_count == 1);
    assert(g_partition_access_count == 1 && g_sys_block_access_count == 1);
}

static void test_flush_failure_stops_before_scsi(void) {
    reset_fake(FAIL_BLOCK_FLUSH);
    assert(scsi_spindown_device("/dev/block/sda") < 0);
    assert(strcmp(g_events, "sB") == 0);
    assert(g_sg_sync_count == 0 && g_sg_stop_count == 0);
    assert(g_delete_write_count == 0);
    assert(g_close_device_count == 1 && g_open_delete_count == 0);
}

static void test_sync_illegal_request_does_not_stop_or_delete(void) {
    reset_fake(FAIL_SYNC_CACHE_ILLEGAL);
    assert(scsi_spindown_device("/dev/block/sda") < 0);
    assert(strcmp(g_events, "sBC") == 0);
    assert(g_sg_stop_count == 0 && g_delete_write_count == 0);
    assert(g_close_device_count == 1 && g_open_delete_count == 0);
}

static void test_stop_illegal_request_does_not_delete(void) {
    reset_fake(FAIL_STOP_ILLEGAL);
    assert(scsi_spindown_device("/dev/block/sda") < 0);
    assert(strcmp(g_events, "sBCT") == 0);
    assert(g_sg_sync_count == 1 && g_sg_stop_count == 1);
    assert(g_delete_write_count == 0);
    assert(g_close_device_count == 1 && g_open_delete_count == 0);
}

static void test_delete_write_failure_is_not_success(void) {
    reset_fake(FAIL_DELETE_WRITE);
    assert(scsi_spindown_device("/dev/block/sda") < 0);
    assert(strcmp(g_events, "sBCTD") == 0);
    assert(g_delete_write_count == 1);
    assert(g_close_device_count == 1 && g_close_delete_count == 1);
}

static void test_missing_delete_node_is_not_success(void) {
    reset_fake(FAIL_DELETE_OPEN);
    assert(scsi_spindown_device("/dev/block/sda") < 0);
    assert(strcmp(g_events, "sBCT") == 0);
    assert(g_delete_write_count == 0);
    assert(g_close_device_count == 1 && g_open_delete_count == 0);
}

static void test_device_still_present_after_delete_is_not_success(void) {
    reset_fake(KEEP_BLOCK_AFTER_DELETE);
    assert(scsi_spindown_device("/dev/block/sda") < 0);
    assert(strcmp(g_events, "sBCTD") == 0);
    assert(g_delete_write_count == 1);
    assert(g_close_device_count == 1 && g_close_delete_count == 1);
    assert(g_sys_block_access_count == 1);
}

static void test_internal_ufs_is_refused_without_writes(void) {
    reset_fake(INTERNAL_UFS);
    assert(scsi_spindown_device("/dev/block/sda") == -EPERM);
    assert(g_events[0] == '\0');
    assert(g_open_device_count == 0 && g_ioctl_count == 0 && g_write_count == 0);
    assert(g_open_delete_count == 0 && g_partition_access_count == 1);
}

int main(void) {
    test_success_order();
    test_flush_failure_stops_before_scsi();
    test_sync_illegal_request_does_not_stop_or_delete();
    test_stop_illegal_request_does_not_delete();
    test_delete_write_failure_is_not_success();
    test_missing_delete_node_is_not_success();
    test_device_still_present_after_delete_is_not_success();
    test_internal_ufs_is_refused_without_writes();
    puts("spindown_test: all cases passed");
    return 0;
}
