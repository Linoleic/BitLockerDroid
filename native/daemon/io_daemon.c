/*
 * io_daemon.c -- High-performance root I/O helper daemon for BitLockerDroid.
 *
 * Runs with root privileges via KernelSU/Magisk `su`. Opens the block device
 * once and services binary pread/pwrite requests over standard stdin/stdout
 * pipes.
 *
 * This eliminates the ~40ms fork/exec overhead per sector from `su -c dd`,
 * dropping I/O latency to ~30 microseconds and eliminating CPU thrashing/heating.
 * Automatically exits when the parent closes the pipe (EOF on stdin).
 */
#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <errno.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/uio.h>
#include <dirent.h>
#include <linux/fs.h>

#ifndef F_SETPIPE_SZ
#define F_SETPIPE_SZ 1031
#endif
#ifndef F_GETPIPE_SZ
#define F_GETPIPE_SZ 1032
#endif

#define DAEMON_MAGIC 0x4249544C // 'BITL'

#define CMD_EXIT  0
#define CMD_READ  1
#define CMD_WRITE 2
#define CMD_SYNC  3
#define CMD_SIZE  4
#define CMD_SMART 5

#include "smart_reader.h"

#define MAX_CHUNK_SIZE (4 * 1024 * 1024) // 4 MB max single request

static void maximize_pipe_size(int fd) {
    static const int sizes[] = { 4 * 1024 * 1024, 2 * 1024 * 1024, 1024 * 1024, 512 * 1024 };
    for (size_t i = 0; i < sizeof(sizes) / sizeof(sizes[0]); i++) {
        if (fcntl(fd, F_SETPIPE_SZ, sizes[i]) >= 0) {
            break;
        }
    }
}

static void tune_block_queue(int dev_fd) {
    struct stat st;
    if (fstat(dev_fd, &st) < 0 || !S_ISBLK(st.st_mode)) return;

    unsigned int maj = major(st.st_rdev);
    unsigned int min = minor(st.st_rdev);

    char path[256];
    snprintf(path, sizeof(path), "/sys/dev/block/%u:%u/queue/read_ahead_kb", maj, min);
    int fd = open(path, O_WRONLY);
    if (fd < 0) {
        snprintf(path, sizeof(path), "/sys/dev/block/%u:%u/../queue/read_ahead_kb", maj, min);
        fd = open(path, O_WRONLY);
    }
    if (fd >= 0) {
        write(fd, "2048\n", 5);
        close(fd);
    }

    snprintf(path, sizeof(path), "/sys/dev/block/%u:%u/queue/max_sectors_kb", maj, min);
    fd = open(path, O_WRONLY);
    if (fd < 0) {
        snprintf(path, sizeof(path), "/sys/dev/block/%u:%u/../queue/max_sectors_kb", maj, min);
        fd = open(path, O_WRONLY);
    }
    if (fd >= 0) {
        write(fd, "1024\n", 5);
        close(fd);
    }

    snprintf(path, sizeof(path), "/sys/dev/block/%u:%u/queue/nr_requests", maj, min);
    fd = open(path, O_WRONLY);
    if (fd < 0) {
        snprintf(path, sizeof(path), "/sys/dev/block/%u:%u/../queue/nr_requests", maj, min);
        fd = open(path, O_WRONLY);
    }
    if (fd >= 0) {
        write(fd, "256\n", 4);
        close(fd);
    }
}

static int read_all(int fd, void *buf, size_t count) {
    size_t got = 0;
    while (got < count) {
        ssize_t r = read(fd, (char *)buf + got, count - got);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (r == 0) return got == 0 ? 0 : -1;
        got += (size_t)r;
    }
    return 1;
}

static int write_all(int fd, const void *buf, size_t count) {
    size_t written = 0;
    while (written < count) {
        ssize_t w = write(fd, (const char *)buf + written, count - written);
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (w == 0) return -1;
        written += (size_t)w;
    }
    return 0;
}

static int writev_all(int fd, struct iovec *iov, int iovcnt) {
    size_t total = 0;
    for (int i = 0; i < iovcnt; i++) total += iov[i].iov_len;
    size_t written = 0;
    int cur_idx = 0;
    size_t cur_off = 0;

    while (written < total) {
        struct iovec liov[8];
        int lcnt = 0;
        for (int i = cur_idx; i < iovcnt && lcnt < 8; i++) {
            if (i == cur_idx) {
                liov[lcnt].iov_base = (char *)iov[i].iov_base + cur_off;
                liov[lcnt].iov_len = iov[i].iov_len - cur_off;
            } else {
                liov[lcnt].iov_base = iov[i].iov_base;
                liov[lcnt].iov_len = iov[i].iov_len;
            }
            lcnt++;
        }

        ssize_t w = writev(fd, liov, lcnt);
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (w == 0) return -1;
        written += (size_t)w;
        size_t rem = (size_t)w;
        while (cur_idx < iovcnt && rem > 0) {
            size_t avail = iov[cur_idx].iov_len - cur_off;
            if (rem >= avail) {
                rem -= avail;
                cur_idx++;
                cur_off = 0;
            } else {
                cur_off += rem;
                rem = 0;
            }
        }
    }
    return 0;
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);

    // Close any inherited file descriptors from parent process (> 2)
#if defined(__NR_close_range)
    if (syscall(__NR_close_range, 3, ~0U, 0) != 0)
#endif
    {
        DIR *d = opendir("/proc/self/fd");
        if (d) {
            int dfd = dirfd(d);
            struct dirent *de;
            while ((de = readdir(d)) != NULL) {
                int fd = atoi(de->d_name);
                if (fd > 2 && fd != dfd) {
                    close(fd);
                }
            }
            closedir(d);
        } else {
            int max_fd = (int)sysconf(_SC_OPEN_MAX);
            if (max_fd < 0 || max_fd > 4096) max_fd = 4096;
            for (int fd = 3; fd < max_fd; fd++) {
                close(fd);
            }
        }
    }

    if (argc >= 3 && strcmp(argv[1], "--smart") == 0) {
        char json[2048];
        read_smart_json(argv[2], json, sizeof(json));
        puts(json);
        return 0;
    }

    if (argc < 2) {
        int32_t err = -EINVAL;
        write_all(STDOUT_FILENO, &err, 4);
        return 1;
    }

    const char *dev_path = argv[1];
    int dev_fd = open(dev_path, O_RDWR | O_LARGEFILE | O_CLOEXEC);
    if (dev_fd < 0) {
        dev_fd = open(dev_path, O_RDONLY | O_LARGEFILE | O_CLOEXEC);
    }
    if (dev_fd < 0) {
        int32_t err = -errno;
        write_all(STDOUT_FILENO, &err, 4);
        return 2;
    }

    // Maximize pipe buffer sizes and tune kernel block queue
    maximize_pipe_size(STDIN_FILENO);
    maximize_pipe_size(STDOUT_FILENO);
    tune_block_queue(dev_fd);

    // Success handshake
    int32_t magic = DAEMON_MAGIC;
    if (write_all(STDOUT_FILENO, &magic, 4) != 0) {
        close(dev_fd);
        return 3;
    }

    uint8_t *buf = (uint8_t *)malloc(MAX_CHUNK_SIZE);
    if (!buf) {
        close(dev_fd);
        return 4;
    }

    while (1) {
        uint8_t cmd = 0;
        int r = read_all(STDIN_FILENO, &cmd, 1);
        if (r <= 0 || cmd == CMD_EXIT) {
            break;
        }

        if (cmd == CMD_READ) {
            uint64_t offset = 0;
            uint32_t len = 0;
            if (read_all(STDIN_FILENO, &offset, 8) <= 0 ||
                read_all(STDIN_FILENO, &len, 4) <= 0) {
                break;
            }

            if (len > MAX_CHUNK_SIZE) {
                len = MAX_CHUNK_SIZE;
            }

            ssize_t n = pread(dev_fd, buf, (size_t)len, (off_t)offset);
            int32_t status = (n < 0) ? -errno : (int32_t)n;
            if (status <= 0) {
                if (write_all(STDOUT_FILENO, &status, 4) != 0) break;
            } else {
                struct iovec iov[2];
                iov[0].iov_base = &status;
                iov[0].iov_len = sizeof(status);
                iov[1].iov_base = buf;
                iov[1].iov_len = (size_t)status;
                if (writev_all(STDOUT_FILENO, iov, 2) != 0) break;
            }
        } else if (cmd == CMD_WRITE) {
            uint64_t offset = 0;
            uint32_t len = 0;
            if (read_all(STDIN_FILENO, &offset, 8) <= 0 ||
                read_all(STDIN_FILENO, &len, 4) <= 0) {
                break;
            }

            if (len > MAX_CHUNK_SIZE) {
                int32_t status = -EINVAL;
                write_all(STDOUT_FILENO, &status, 4);
                break;
            }

            if (read_all(STDIN_FILENO, buf, len) <= 0) {
                break;
            }

            ssize_t n = pwrite(dev_fd, buf, (size_t)len, (off_t)offset);
            int32_t status = (n < 0) ? -errno : (int32_t)n;
            if (write_all(STDOUT_FILENO, &status, 4) != 0) break;
        } else if (cmd == CMD_SYNC) {
            int s = fdatasync(dev_fd);
            int32_t status = (s < 0) ? -errno : 0;
            if (write_all(STDOUT_FILENO, &status, 4) != 0) break;
        } else if (cmd == CMD_SIZE) {
            uint64_t size = 0;
            if (ioctl(dev_fd, BLKGETSIZE64, &size) < 0) {
                off_t end = lseek(dev_fd, 0, SEEK_END);
                size = (end < 0) ? 0 : (uint64_t)end;
            }
            if (write_all(STDOUT_FILENO, &size, 8) != 0) break;
        } else if (cmd == CMD_SMART) {
            char smart_json[2048];
            read_smart_json(dev_path, smart_json, sizeof(smart_json));
            uint32_t len = (uint32_t)strlen(smart_json);
            if (write_all(STDOUT_FILENO, &len, 4) != 0) break;
            if (len > 0) {
                if (write_all(STDOUT_FILENO, smart_json, len) != 0) break;
            }
        }
    }

    close(dev_fd);
    free(buf);
    return 0;
}
