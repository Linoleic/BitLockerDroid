// Host substitute for io.c: dis_blk_read reads from a raw image file.
#define _GNU_SOURCE 1
#include "dislocker/dislocker_priv.h"
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>

static int g_fd = -1;
static long g_base = 0;   // offset of the volume data within the image (0)

void host_set_image(const char* path) {
    g_fd = open(path, O_RDONLY);
    if (g_fd < 0) { perror("open image"); }
}

int dis_blk_read(dis_ctx_t *ctx, uint8_t *buf, off_t offset, size_t len) {
    if (!ctx || !buf || len == 0) return -1;
    if (g_fd < 0) { dis_set_error("no image"); return -1; }
    ssize_t n = pread(g_fd, buf, len, (off_t)(g_base + offset));
    return (n == (ssize_t)len) ? (int)n : -1;
}
