/*
 * io.c -- High-performance block device reading and writing.
 *
 * Provides a 3-tier I/O architecture:
 *  Tier 1: Direct pread/pwrite if SELinux allows opening the block device.
 *  Tier 2: Persistent root helper daemon (`bitlocker_io`) over binary pipes
 *          (~30 microsecond latency, zero fork per sector, zero CPU thrashing).
 *  Tier 3: Fallback to single-invocation `su -c dd` if daemon unavailable.
 *
 * This file is part of BitLockerDroid.
 */
#define _GNU_SOURCE 1
#include "dislocker/dislocker_priv.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/stat.h>

#ifdef __ANDROID__
#include <android/log.h>
#define DLOG(...) __android_log_print(ANDROID_LOG_INFO, "BitLockerIO", __VA_ARGS__)
#define ELOG(...) __android_log_print(ANDROID_LOG_ERROR, "BitLockerIO", __VA_ARGS__)
#else
#define DLOG(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define ELOG(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

#define DAEMON_MAGIC 0x4249544C // 'BITL'

#define CMD_EXIT  0
#define CMD_READ  1
#define CMD_WRITE 2
#define CMD_SYNC  3
#define CMD_SIZE  4

static const char *const SU_PATHS[] = {
	"/system/bin/su",
	"/product/bin/su",
	"/system/xbin/su",
	"/sbin/su",
	NULL
};

static const char *const DAEMON_PATHS[] = {
	"/data/local/tmp/bitlocker_io",
	"/data/data/com.bitlockerdroid/files/bitlocker_io",
	"/data/user/0/com.bitlockerdroid/files/bitlocker_io",
	NULL
};

/* Helper to read exactly `count` bytes from `fd` with a timeout in milliseconds */
static int pipe_read_exact(int fd, void *buf, size_t count, int timeout_ms)
{
	size_t got = 0;
	while (got < count) {
		struct pollfd pfd = { .fd = fd, .events = POLLIN };
		int pr = poll(&pfd, 1, timeout_ms);
		if (pr < 0) {
			if (errno == EINTR) continue;
			return -1;
		}
		if (pr == 0) {
			// Timeout
			return -ETIMEDOUT;
		}
		if (!(pfd.revents & POLLIN)) {
			return -EIO;
		}

		ssize_t r = read(fd, (char *)buf + got, count - got);
		if (r < 0) {
			if (errno == EINTR) continue;
			return -errno;
		}
		if (r == 0) {
			// EOF
			return got == 0 ? 0 : -EIO;
		}
		got += (size_t)r;
	}
	return (int)got;
}

/* Helper to write exactly `count` bytes to `fd` with a timeout in milliseconds */
static int pipe_write_exact(int fd, const void *buf, size_t count, int timeout_ms)
{
	size_t written = 0;
	while (written < count) {
		struct pollfd pfd = { .fd = fd, .events = POLLOUT };
		int pr = poll(&pfd, 1, timeout_ms);
		if (pr < 0) {
			if (errno == EINTR) continue;
			return -1;
		}
		if (pr == 0) return -ETIMEDOUT;
		if (!(pfd.revents & POLLOUT)) return -EIO;

		ssize_t w = write(fd, (const char *)buf + written, count - written);
		if (w < 0) {
			if (errno == EINTR) continue;
			return -errno;
		}
		if (w == 0) return -EIO;
		written += (size_t)w;
	}
	return (int)written;
}

static int is_safe_device_path(const char *path)
{
	if (!path || !*path) return 0;
	for (const char *p = path; *p; p++) {
		char c = *p;
		if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
		      (c >= '0' && c <= '9') || c == '/' || c == '_' ||
		      c == '-' || c == '.' || c == ':' || c == ',')) {
			return 0;
		}
	}
	return 1;
}

/* Find an accessible daemon binary */
static const char *find_daemon_binary(void)
{
	for (int i = 0; DAEMON_PATHS[i] != NULL; i++) {
		if (access(DAEMON_PATHS[i], X_OK) == 0) {
			return DAEMON_PATHS[i];
		}
	}
	return DAEMON_PATHS[0]; // fallback
}

int dis_io_init(dis_ctx_t *ctx)
{
	if (!ctx) return -1;

	/* Writing to the daemon pipe after it died must surface as EPIPE, not
	 * terminate the whole app with SIGPIPE. Process-wide and idempotent. */
	signal(SIGPIPE, SIG_IGN);

	pthread_mutex_init(&ctx->io_lock, NULL);
	ctx->io_in_fd = -1;
	ctx->io_out_fd = -1;
	ctx->io_pid = -1;

	if (!is_safe_device_path(ctx->device_path)) {
		dis_set_error("Security error: unsafe device path %s", ctx->device_path);
		return -1;
	}

	// 1. Try direct open
	ctx->fd = open(ctx->device_path, O_RDWR | O_LARGEFILE | O_CLOEXEC);
	if (ctx->fd < 0) {
		ctx->fd = open(ctx->device_path, O_RDONLY | O_LARGEFILE | O_CLOEXEC);
	}
	if (ctx->fd >= 0) {
		DLOG("Direct block access OK: %s (fd=%d)", ctx->device_path, ctx->fd);
		return 0;
	}

	// 2. Spawn persistent root I/O daemon
	int p_to_child[2];
	int p_from_child[2];
	if (pipe(p_to_child) != 0 || pipe(p_from_child) != 0) {
		dis_set_error("Failed to create daemon pipes: %s", strerror(errno));
		return -1;
	}

	pid_t pid = fork();
	if (pid < 0) {
		close(p_to_child[0]); close(p_to_child[1]);
		close(p_from_child[0]); close(p_from_child[1]);
		dis_set_error("Fork daemon failed: %s", strerror(errno));
		return -1;
	}

	if (pid == 0) {
		// Child
		close(p_to_child[1]);
		close(p_from_child[0]);

		dup2(p_to_child[0], STDIN_FILENO);
		dup2(p_from_child[1], STDOUT_FILENO);

		close(p_to_child[0]);
		close(p_from_child[1]);

		// Close any high file descriptors
		for (int fd = 3; fd < 64; fd++) {
			close(fd);
		}

		const char *daemon_bin = find_daemon_binary();
		char cmd[1024];
		snprintf(cmd, sizeof(cmd), "%s '%s'", daemon_bin, ctx->device_path);

		for (int i = 0; SU_PATHS[i] != NULL; i++) {
			execl(SU_PATHS[i], "su", "-c", cmd, (char *)NULL);
		}
		_exit(127);
	}

	// Parent
	close(p_to_child[0]);
	close(p_from_child[1]);

	// Read handshake from daemon (wait up to 3000 ms)
	int32_t magic = 0;
	int r = pipe_read_exact(p_from_child[0], &magic, sizeof(magic), 3000);
	if (r == sizeof(magic) && magic == DAEMON_MAGIC) {
		ctx->io_in_fd = p_to_child[1];
		ctx->io_out_fd = p_from_child[0];
		ctx->io_pid = pid;
		DLOG("Connected to persistent root I/O daemon for %s (pid=%d)", ctx->device_path, (int)pid);
		return 0;
	}

	ELOG("Failed to handshake with daemon (ret=%d, magic=0x%08x), killing helper", r, (unsigned int)magic);
	close(p_to_child[1]);
	close(p_from_child[0]);
	kill(pid, SIGKILL);
	waitpid(pid, NULL, 0);

	// Not fatal: will fall back to single su -c dd if needed
	return 0;
}

void dis_io_destroy(dis_ctx_t *ctx)
{
	if (!ctx) return;

	if (ctx->io_in_fd >= 0) {
		pthread_mutex_lock(&ctx->io_lock);
		uint8_t exit_cmd = CMD_EXIT;
		/* Daemon may already be gone; EPIPE here is expected and harmless
		 * now that SIGPIPE is ignored. */
		ssize_t wr = write(ctx->io_in_fd, &exit_cmd, 1);
		(void)wr;
		close(ctx->io_in_fd);
		close(ctx->io_out_fd);
		ctx->io_in_fd = -1;
		ctx->io_out_fd = -1;
		pthread_mutex_unlock(&ctx->io_lock);

		if (ctx->io_pid > 0) {
			/* Give the daemon a moment to exit on its own, then force-kill
			 * so no zombie or orphan is left holding the pipe. */
			int rc = 0;
			for (int i = 0; i < 50; i++) {
				rc = waitpid(ctx->io_pid, NULL, WNOHANG);
				if (rc == ctx->io_pid || rc < 0) break;
				usleep(10000);
			}
			if (rc <= 0) {
				kill(ctx->io_pid, SIGKILL);
				waitpid(ctx->io_pid, NULL, 0);
			}
			ctx->io_pid = -1;
		}
	}

	if (ctx->fd >= 0) {
		close(ctx->fd);
		ctx->fd = -1;
	}

	pthread_mutex_destroy(&ctx->io_lock);
}

/* Fallback: single su -c dd read */
static int dis_blk_read_dd(dis_ctx_t *ctx, uint8_t *buf, off_t offset, size_t len)
{
	int pipefd[2];
	if (pipe(pipefd) != 0) return -1;

	pid_t pid = fork();
	if (pid < 0) {
		close(pipefd[0]); close(pipefd[1]);
		return -1;
	}

	if (pid == 0) {
		close(pipefd[0]);
		dup2(pipefd[1], STDOUT_FILENO);
		close(pipefd[1]);

		char offset_s[32], count_s[32];
		snprintf(offset_s, sizeof(offset_s), "%lld", (long long)(ctx->offset + offset));
		snprintf(count_s, sizeof(count_s), "%zu", len);

		char ddcmd[1024];
		snprintf(ddcmd, sizeof(ddcmd),
			"dd if='%s' bs=1 skip=%s count=%s 2>/dev/null",
			ctx->device_path, offset_s, count_s);

		for (int i = 0; SU_PATHS[i] != NULL; i++) {
			execl(SU_PATHS[i], "su", "-c", ddcmd, (char *)NULL);
		}
		_exit(127);
	}

	close(pipefd[1]);
	size_t got = 0;
	while (got < len) {
		ssize_t r = read(pipefd[0], buf + got, len - got);
		if (r < 0) {
			if (errno == EINTR) continue;
			break;
		}
		if (r == 0) break;
		got += (size_t)r;
	}
	close(pipefd[0]);

	int status = 0;
	waitpid(pid, &status, 0);

	return (got == len) ? (int)got : -1;
}

/* Fallback: single su -c dd write */
static int dis_blk_write_dd(dis_ctx_t *ctx, const uint8_t *buf, off_t offset, size_t len)
{
	int pipefd[2];
	if (pipe(pipefd) != 0) return -1;

	pid_t pid = fork();
	if (pid < 0) {
		close(pipefd[0]); close(pipefd[1]);
		return -1;
	}

	if (pid == 0) {
		close(pipefd[1]);
		dup2(pipefd[0], STDIN_FILENO);
		close(pipefd[0]);

		char seek_s[32], count_s[32];
		snprintf(seek_s, sizeof(seek_s), "%lld", (long long)(ctx->offset + offset));
		snprintf(count_s, sizeof(count_s), "%zu", len);

		char ddcmd[1024];
		snprintf(ddcmd, sizeof(ddcmd),
			"dd of='%s' bs=1 seek=%s count=%s conv=notrunc 2>/dev/null",
			ctx->device_path, seek_s, count_s);

		for (int i = 0; SU_PATHS[i] != NULL; i++) {
			execl(SU_PATHS[i], "su", "-c", ddcmd, (char *)NULL);
		}
		_exit(127);
	}

	close(pipefd[0]);
	size_t written = 0;
	while (written < len) {
		ssize_t w = write(pipefd[1], buf + written, len - written);
		if (w < 0) {
			if (errno == EINTR) continue;
			break;
		}
		if (w == 0) break;
		written += (size_t)w;
	}
	close(pipefd[1]);

	int status = 0;
	waitpid(pid, &status, 0);

	return (written == len && WIFEXITED(status) && WEXITSTATUS(status) == 0) ? (int)written : -1;
}

int dis_blk_read(dis_ctx_t *ctx, uint8_t *buf, off_t offset, size_t len)
{
	if (!ctx || !buf || len == 0 || !ctx->device_path[0])
		return -1;

	if (!is_safe_device_path(ctx->device_path)) {
		dis_set_error("Security error: unsafe device path %s", ctx->device_path);
		return -1;
	}

	// 1. Direct fd
	if (ctx->fd >= 0) {
		ssize_t n = pread(ctx->fd, buf, len, (off_t)(ctx->offset + offset));
		if (n == (ssize_t)len) return (int)len;
		dis_set_error("Direct pread failed: %s", strerror(errno));
		return -1;
	}

	// 2. Persistent daemon
	if (ctx->io_in_fd >= 0) {
		pthread_mutex_lock(&ctx->io_lock);

		uint8_t req[13];
		req[0] = CMD_READ;
		uint64_t disk_off = (uint64_t)(ctx->offset + offset);
		uint32_t req_len = (uint32_t)len;
		memcpy(req + 1, &disk_off, 8);
		memcpy(req + 9, &req_len, 4);

		if (pipe_write_exact(ctx->io_in_fd, req, sizeof(req), 5000) != sizeof(req)) {
			pthread_mutex_unlock(&ctx->io_lock);
			dis_set_error("Daemon read write failed");
			return -1;
		}

		int32_t status = 0;
		if (pipe_read_exact(ctx->io_out_fd, &status, sizeof(status), 5000) != sizeof(status) || status != (int32_t)len) {
			pthread_mutex_unlock(&ctx->io_lock);
			dis_set_error("Daemon read returned error: %d", (int)status);
			return -1;
		}

		if (pipe_read_exact(ctx->io_out_fd, buf, len, 10000) != (int)len) {
			pthread_mutex_unlock(&ctx->io_lock);
			dis_set_error("Daemon read payload truncated");
			return -1;
		}

		pthread_mutex_unlock(&ctx->io_lock);
		return (int)len;
	}

	// 3. Fallback to su -c dd
	return dis_blk_read_dd(ctx, buf, offset, len);
}

int dis_blk_write(dis_ctx_t *ctx, const uint8_t *buf, off_t offset, size_t len)
{
	if (!ctx || !buf || len == 0 || !ctx->device_path[0])
		return -1;

	/* Write barrier: protect BitLocker volume header (sectors 0..15) and metadata blocks */
	size_t header_bytes = 8192;
	if (ctx->information && ctx->information->nb_backup_sectors > 0) {
		header_bytes = (size_t)ctx->information->nb_backup_sectors * ctx->sector_size;
	}
	off_t abs_off = ctx->offset + offset;
	if (abs_off < (off_t)header_bytes) {
		dis_set_error("Write barrier violation: offset %lld is in protected BitLocker header area",
			(long long)abs_off);
		return -1;
	}
	if (ctx->information) {
		for (int i = 0; i < 3; i++) {
			off_t info_off = (off_t)ctx->information->information_off[i];
			if (info_off != 0 && abs_off >= info_off && abs_off < info_off + 0x10000) {
				dis_set_error("Write barrier violation: offset %lld is in protected FVE metadata block %d",
					(long long)abs_off, i);
				return -1;
			}
		}
	}

	if (!is_safe_device_path(ctx->device_path)) {
		dis_set_error("Security error: unsafe device path %s", ctx->device_path);
		return -1;
	}

	// 1. Direct fd
	if (ctx->fd >= 0) {
		ssize_t n = pwrite(ctx->fd, buf, len, (off_t)(ctx->offset + offset));
		if (n == (ssize_t)len) return (int)len;
		dis_set_error("Direct pwrite failed: %s", strerror(errno));
		return -1;
	}

	// 2. Persistent daemon
	if (ctx->io_in_fd >= 0) {
		pthread_mutex_lock(&ctx->io_lock);

		uint8_t req[13];
		req[0] = CMD_WRITE;
		uint64_t disk_off = (uint64_t)(ctx->offset + offset);
		uint32_t req_len = (uint32_t)len;
		memcpy(req + 1, &disk_off, 8);
		memcpy(req + 9, &req_len, 4);

		if (pipe_write_exact(ctx->io_in_fd, req, sizeof(req), 5000) != sizeof(req)) {
			pthread_mutex_unlock(&ctx->io_lock);
			dis_set_error("Daemon write header failed");
			return -1;
		}

		if (pipe_write_exact(ctx->io_in_fd, buf, len, 15000) != (int)len) {
			pthread_mutex_unlock(&ctx->io_lock);
			dis_set_error("Daemon write payload failed");
			return -1;
		}

		int32_t status = 0;
		if (pipe_read_exact(ctx->io_out_fd, &status, sizeof(status), 5000) != sizeof(status) || status != (int32_t)len) {
			pthread_mutex_unlock(&ctx->io_lock);
			dis_set_error("Daemon write returned error: %d", (int)status);
			return -1;
		}

		pthread_mutex_unlock(&ctx->io_lock);
		return (int)len;
	}

	// 3. Fallback to su -c dd
	return dis_blk_write_dd(ctx, buf, offset, len);
}

int dis_blk_sync(dis_ctx_t *ctx)
{
	if (!ctx) return -1;

	if (ctx->fd >= 0) {
		return fdatasync(ctx->fd) == 0 ? 0 : -1;
	}

	if (ctx->io_in_fd >= 0) {
		pthread_mutex_lock(&ctx->io_lock);
		uint8_t cmd = CMD_SYNC;
		if (pipe_write_exact(ctx->io_in_fd, &cmd, 1, 5000) != 1) {
			pthread_mutex_unlock(&ctx->io_lock);
			return -1;
		}
		int32_t status = 0;
		if (pipe_read_exact(ctx->io_out_fd, &status, sizeof(status), 5000) != sizeof(status)) {
			pthread_mutex_unlock(&ctx->io_lock);
			return -1;
		}
		pthread_mutex_unlock(&ctx->io_lock);
		return status == 0 ? 0 : -1;
	}

	return 0;
}
