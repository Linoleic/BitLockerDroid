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
    SYNC_DRIVER_SENSE,
    SYNC_UNSUPPORTED_DESCRIPTOR,
    SYNC_INVALID_FIELD,
    SYNC_UNKNOWN_SENSE,
    SYNC_BAD_SENSE,
    SYNC_TRUNCATED_SENSE,
    SYNC_DEFERRED_SENSE,
    SYNC_INVALID_FIELD_DESCRIPTOR,
    SYNC_GOOD_BAD_DRIVER,
    SYNC_BAD_DRIVER_STATUS,
    SYNC_BAD_HOST_STATUS,
    SYNC_NONZERO_RESID,
    SYNC_TRANSPORT_ERROR,
    MODE_WCE_ON,
    MODE_ZERO_PAGE,
    MODE_TRUNCATED_HEADER,
    MODE_TRUNCATED_PAGE,
    MODE_MISSING_PAGE,
    MODE_BAD_PAGE_LENGTH,
    MODE_BLOCK_DESCRIPTOR,
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
static int g_mode_sense_count;
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
    g_mode_sense_count = 0;
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
        if (g_mode == SYNC_TRANSPORT_ERROR) {
            errno = EIO;
            return -1;
        }
        if (g_mode == SYNC_GOOD_BAD_DRIVER) {
            hdr->driver_status = 8;
            return 0;
        }
        if (g_mode == FAIL_SYNC_CACHE_ILLEGAL ||
            g_mode == SYNC_DRIVER_SENSE ||
            g_mode == SYNC_UNSUPPORTED_DESCRIPTOR ||
            g_mode == SYNC_INVALID_FIELD || g_mode == SYNC_UNKNOWN_SENSE ||
            g_mode == SYNC_BAD_SENSE || g_mode == SYNC_TRUNCATED_SENSE ||
            g_mode == SYNC_DEFERRED_SENSE ||
            g_mode == SYNC_INVALID_FIELD_DESCRIPTOR ||
            g_mode == SYNC_BAD_DRIVER_STATUS || g_mode == SYNC_BAD_HOST_STATUS ||
            g_mode == SYNC_NONZERO_RESID ||
            g_mode == MODE_WCE_ON || g_mode == MODE_TRUNCATED_HEADER ||
            g_mode == MODE_ZERO_PAGE || g_mode == MODE_TRUNCATED_PAGE ||
            g_mode == MODE_MISSING_PAGE ||
            g_mode == MODE_BAD_PAGE_LENGTH || g_mode == MODE_BLOCK_DESCRIPTOR) {
            hdr->status = 2;
            if (g_mode == SYNC_DRIVER_SENSE) hdr->driver_status = 8;
            if (g_mode == SYNC_BAD_DRIVER_STATUS) hdr->driver_status = 4;
            if (g_mode == SYNC_BAD_HOST_STATUS) hdr->host_status = 1;
            if (g_mode == SYNC_NONZERO_RESID) hdr->resid = 1;
            if (hdr->sbp && hdr->mx_sb_len >= 3) {
                unsigned char *sense = (unsigned char *)hdr->sbp;
                if (g_mode == SYNC_UNSUPPORTED_DESCRIPTOR ||
                    g_mode == SYNC_INVALID_FIELD_DESCRIPTOR) {
                    sense[0] = 0x72;
                    sense[1] = 0x05;
                    sense[2] = g_mode == SYNC_INVALID_FIELD_DESCRIPTOR ? 0x24 : 0x20;
                    sense[3] = 0;
                    sense[7] = 0;
                    hdr->sb_len_wr = 8;
                } else {
                    sense[0] = g_mode == SYNC_BAD_SENSE ? 0x7f :
                               g_mode == SYNC_DEFERRED_SENSE ? 0x71 : 0x70;
                    if (g_mode == SYNC_DRIVER_SENSE) sense[0] = 0xf0;
                    sense[2] = 0x05;
                    sense[7] = 10;
                    sense[12] = g_mode == SYNC_INVALID_FIELD ? 0x24 :
                                g_mode == SYNC_UNKNOWN_SENSE ? 0x21 : 0x20;
                    sense[13] = 0;
                    hdr->sb_len_wr = g_mode == SYNC_TRUNCATED_SENSE ? 14 : 18;
                }
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
                ((unsigned char *)hdr->sbp)[7] = 10;
                ((unsigned char *)hdr->sbp)[12] = 0x20;
                ((unsigned char *)hdr->sbp)[13] = 0;
                hdr->sb_len_wr = 18;
            }
        }
    } else if (hdr->cmdp[0] == 0x1a) {
        assert(hdr->cmd_len == 6);
        assert(hdr->cmdp[1] == 0x08 && hdr->cmdp[2] == 0x08);
        assert(hdr->dxferp != NULL);
        g_mode_sense_count++;
        event('M');
        unsigned char *data = (unsigned char *)hdr->dxferp;
        if (hdr->dxfer_len == 4) {
            data[0] = 23;
            data[1] = 0;
            data[2] = 0;
            data[3] = g_mode == MODE_BLOCK_DESCRIPTOR ? 1 : 0;
            if (g_mode == MODE_TRUNCATED_HEADER) hdr->resid = 1;
            if (g_mode == MODE_MISSING_PAGE) data[0] = 3;
        } else {
            assert(hdr->dxfer_len == 24 && hdr->cmdp[4] == 24);
            data[0] = 23;
            data[3] = g_mode == MODE_BLOCK_DESCRIPTOR ? 1 : 0;
            if (g_mode != MODE_ZERO_PAGE) {
                data[4] = g_mode == MODE_MISSING_PAGE ? 0 : 0x08;
                data[5] = g_mode == MODE_BAD_PAGE_LENGTH ? 0x13 : 0x12;
                data[6] = g_mode == MODE_WCE_ON ? 0x04 : 0;
            }
            if (g_mode == MODE_TRUNCATED_PAGE) hdr->resid = 1;
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

static void test_sync_illegal_request_allowed_with_write_cache_disabled(void) {
    reset_fake(FAIL_SYNC_CACHE_ILLEGAL);
    assert(scsi_spindown_device("/dev/block/sda") == 0);
    assert(strcmp(g_events, "sBCMMTD") == 0);
    assert(g_mode_sense_count == 2);
    assert(g_sg_stop_count == 1 && g_delete_write_count == 1);
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

static void test_sync_unsupported_allowed_only_with_verified_write_cache_disabled(void) {
    const enum fail_mode compatible_modes[] = {
        SYNC_DRIVER_SENSE,
        SYNC_UNSUPPORTED_DESCRIPTOR,
        SYNC_INVALID_FIELD,
        SYNC_INVALID_FIELD_DESCRIPTOR
    };
    for (size_t i = 0; i < sizeof(compatible_modes) / sizeof(compatible_modes[0]); i++) {
        reset_fake(compatible_modes[i]);
        assert(scsi_spindown_device("/dev/block/sda") == 0);
        assert(strcmp(g_events, "sBCMMTD") == 0);
        assert(g_mode_sense_count == 2);
        assert(g_sg_stop_count == 1 && g_delete_write_count == 1);
    }
}

static void test_sync_or_mode_sense_uncertainty_refuses_stop(void) {
    const enum fail_mode reject_modes[] = {
        SYNC_UNKNOWN_SENSE,
        SYNC_BAD_SENSE,
        SYNC_TRUNCATED_SENSE,
        SYNC_DEFERRED_SENSE,
        SYNC_GOOD_BAD_DRIVER,
        SYNC_BAD_DRIVER_STATUS,
        SYNC_BAD_HOST_STATUS,
        SYNC_NONZERO_RESID,
        SYNC_TRANSPORT_ERROR,
        MODE_ZERO_PAGE,
        MODE_WCE_ON,
        MODE_TRUNCATED_HEADER,
        MODE_TRUNCATED_PAGE,
        MODE_MISSING_PAGE,
        MODE_BAD_PAGE_LENGTH,
        MODE_BLOCK_DESCRIPTOR
    };
    for (size_t i = 0; i < sizeof(reject_modes) / sizeof(reject_modes[0]); i++) {
        reset_fake(reject_modes[i]);
        assert(scsi_spindown_device("/dev/block/sda") < 0);
        assert(g_sg_stop_count == 0 && g_delete_write_count == 0);
    }
}

int main(void) {
    test_success_order();
    test_flush_failure_stops_before_scsi();
    test_sync_illegal_request_allowed_with_write_cache_disabled();
    test_stop_illegal_request_does_not_delete();
    test_delete_write_failure_is_not_success();
    test_missing_delete_node_is_not_success();
    test_device_still_present_after_delete_is_not_success();
    test_internal_ufs_is_refused_without_writes();
    test_sync_unsupported_allowed_only_with_verified_write_cache_disabled();
    test_sync_or_mode_sense_uncertainty_refuses_stop();
    puts("spindown_test: all cases passed");
    return 0;
}
