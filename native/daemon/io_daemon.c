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
#include <dirent.h>
#include <linux/fs.h>

#define DAEMON_MAGIC 0x4249544C // 'BITL'

#define CMD_EXIT  0
#define CMD_READ  1
#define CMD_WRITE 2
#define CMD_SYNC  3
#define CMD_SIZE  4

#define MAX_CHUNK_SIZE (4 * 1024 * 1024) // 4 MB max single request

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
            if (write_all(STDOUT_FILENO, &status, 4) != 0) break;

            if (status > 0) {
                if (write_all(STDOUT_FILENO, buf, (size_t)status) != 0) break;
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
        }
    }

    close(dev_fd);
    free(buf);
    return 0;
}
