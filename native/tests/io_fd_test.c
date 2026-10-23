/*
 * Socketpair regression harness for io.c's framed fd: transport.
 * Build with NDK aarch64-linux-android33-clang from the repository root:
 *   -std=c11 -Wall -Wextra -I native/dislocker/include
 *   -I native/third_party/mbedtls/include native/tests/io_fd_test.c
 *   native/dislocker/src/io.c -pthread -llog -o io-fd-test
 * The binary is intended to run on an Android/Linux host; it uses no block
 * devices and transfers only synthetic bytes.
 */
#define _GNU_SOURCE 1
#include "dislocker/dislocker_priv.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define MAGIC 0x4249544c
#define CMD_EXIT 0
#define CMD_READ 1
#define CMD_WRITE 2
#define CMD_SYNC 3
#define CMD_SIZE 4
#define TEST_DATA_LEN 1024

enum worker_mode { WORK_SUCCESS, WORK_NEGATIVE, WORK_TRUNCATED, WORK_LATE_PARTIAL };

typedef struct {
	int fd;
	enum worker_mode mode;
	int failed;
} worker_args_t;

void dis_set_error(const char *fmt, ...)
{
	(void)fmt;
}

static int read_exact(int fd, void *buf, size_t len)
{
	uint8_t *p = (uint8_t *)buf;
	while (len) {
		ssize_t n = read(fd, p, len);
		if (n < 0 && errno == EINTR) continue;
		if (n <= 0) return -1;
		p += n;
		len -= (size_t)n;
	}
	return 0;
}

static int write_exact(int fd, const void *buf, size_t len)
{
	const uint8_t *p = (const uint8_t *)buf;
	while (len) {
		ssize_t n = write(fd, p, len);
		if (n < 0 && errno == EINTR) continue;
		if (n <= 0) return -1;
		p += n;
		len -= (size_t)n;
	}
	return 0;
}

static void sleep_seconds(unsigned int sec)
{
	struct timespec ts = { (time_t)sec, 0 };
	while (nanosleep(&ts, &ts) < 0 && errno == EINTR) {}
}

static int expect_cmd(int fd, uint8_t expected, uint8_t *request, size_t tail_len)
{
	if (read_exact(fd, request, 1) != 0 || request[0] != expected) return -1;
	return tail_len ? read_exact(fd, request + 1, tail_len) : 0;
}

static void *fake_worker(void *opaque)
{
	worker_args_t *args = (worker_args_t *)opaque;
	int32_t magic = MAGIC;
	if (write_exact(args->fd, &magic, sizeof(magic)) != 0) {
		args->failed = 1;
		return NULL;
	}

	uint8_t req[13] = {0};
	if (args->mode == WORK_SUCCESS) {
		uint32_t length = 0;
		int32_t status = TEST_DATA_LEN;
		uint8_t payload[TEST_DATA_LEN];
		memset(payload, 0x5a, sizeof(payload));
		if (expect_cmd(args->fd, CMD_READ, req, 12) != 0) goto fail;
		memcpy(&length, req + 9, sizeof(length));
		if (length != TEST_DATA_LEN || write_exact(args->fd, &status, sizeof(status)) ||
			write_exact(args->fd, payload, sizeof(payload))) goto fail;

		if (expect_cmd(args->fd, CMD_WRITE, req, 12) != 0) goto fail;
		memcpy(&length, req + 9, sizeof(length));
		if (length != TEST_DATA_LEN) goto fail;
		uint8_t written[TEST_DATA_LEN];
		if (read_exact(args->fd, written, sizeof(written)) != 0) goto fail;
		for (size_t i = 0; i < sizeof(written); i++) if (written[i] != 0xa5) goto fail;
		if (write_exact(args->fd, &status, sizeof(status))) goto fail;

		uint8_t cmd = 0;
		status = 0;
		if (expect_cmd(args->fd, CMD_SYNC, &cmd, 0) != 0 || write_exact(args->fd, &status, sizeof(status))) goto fail;
		uint64_t size = 0x12345000ULL;
		if (expect_cmd(args->fd, CMD_SIZE, &cmd, 0) != 0 || write_exact(args->fd, &size, sizeof(size))) goto fail;
		return NULL;
	}

	if (expect_cmd(args->fd, CMD_READ, req, 12) != 0) goto fail;
	if (args->mode == WORK_NEGATIVE) {
		int32_t status = -EIO;
		if (write_exact(args->fd, &status, sizeof(status)) != 0) goto fail;
		return NULL;
	}
	if (args->mode == WORK_TRUNCATED) {
		int32_t status = TEST_DATA_LEN;
		uint8_t fragment[17];
		memset(fragment, 0x31, sizeof(fragment));
		if (write_exact(args->fd, &status, sizeof(status)) || write_exact(args->fd, fragment, sizeof(fragment))) goto fail;
		close(args->fd); /* EOF must fail the framed read rather than salvage zeros. */
		args->fd = -1;
		return NULL;
	}
	if (args->mode == WORK_LATE_PARTIAL) {
		int32_t status = TEST_DATA_LEN;
		if (write_exact(args->fd, &status, 2) != 0) goto fail;
		/* Native status read has a 5 second deadline; this response is stale. */
		sleep_seconds(6);
		(void)write_exact(args->fd, ((uint8_t *)&status) + 2, 2);
		uint8_t stale[TEST_DATA_LEN];
		memset(stale, 0x77, sizeof(stale));
		(void)write_exact(args->fd, stale, sizeof(stale));
		return NULL;
	}

fail:
	args->failed = 1;
	return NULL;
}

static int start_context(dis_ctx_t *ctx, int *peer_fd, enum worker_mode mode,
	pthread_t *thread, worker_args_t *args)
{
	int sv[2];
	if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) return -1;
	memset(ctx, 0, sizeof(*ctx));
	ctx->fd = -1;
	ctx->offset = 0;
	snprintf(ctx->device_path, sizeof(ctx->device_path), "fd:%d", sv[0]);
	args->fd = sv[1];
	args->mode = mode;
	args->failed = 0;
	if (pthread_create(thread, NULL, fake_worker, args) != 0) {
		close(sv[0]); close(sv[1]);
		return -1;
	}
	if (dis_io_init(ctx) != 0) {
		pthread_join(*thread, NULL);
		return -1;
	}
	int flags = fcntl(sv[0], F_GETFL, 0);
	if (flags < 0 || !(flags & O_NONBLOCK)) return -1;
	*peer_fd = sv[1];
	return 0;
}

static int check_success(void)
{
	dis_ctx_t ctx;
	int peer;
	pthread_t thread;
	worker_args_t args;
	if (start_context(&ctx, &peer, WORK_SUCCESS, &thread, &args) != 0) return -1;
	uint8_t data[TEST_DATA_LEN];
	memset(data, 0, sizeof(data));
	if (dis_blk_read(&ctx, data, 0, sizeof(data)) != (int)sizeof(data)) return -1;
	for (size_t i = 0; i < sizeof(data); i++) if (data[i] != 0x5a) return -1;
	memset(data, 0xa5, sizeof(data));
	if (dis_blk_write_raw(&ctx, data, 4096, sizeof(data)) != (int)sizeof(data)) return -1;
	if (dis_blk_sync(&ctx) != 0 || dis_blk_get_size(&ctx) != 0x12345000ULL) return -1;
	dis_io_destroy(&ctx);
	pthread_join(thread, NULL);
	close(peer);
	return args.failed ? -1 : 0;
}

static int check_failure(enum worker_mode mode)
{
	dis_ctx_t ctx;
	int peer;
	pthread_t thread;
	worker_args_t args;
	if (start_context(&ctx, &peer, mode, &thread, &args) != 0) return -1;
	int owned_fd = ctx.io_in_fd;
	uint8_t data[TEST_DATA_LEN];
	memset(data, 0xcc, sizeof(data));
	if (dis_blk_read(&ctx, data, 0, sizeof(data)) >= 0) return -1;
	if (dis_blk_read(&ctx, data, 0, sizeof(data)) >= 0) return -1; /* poisoned: no reuse */
	if (dis_blk_write_raw(&ctx, data, 4096, sizeof(data)) >= 0 ||
		dis_blk_sync(&ctx) >= 0 || dis_blk_get_size(&ctx) != 0) return -1;
	if (!__atomic_load_n(&ctx.io_poisoned, __ATOMIC_ACQUIRE) || ctx.io_in_fd != -1 || ctx.io_out_fd != -1) return -1;

	/* Poisoning already closed the owned endpoint. Reuse its fd number and
	 * ensure destroy does not close the unrelated descriptor. */
	int sentinel = open("/dev/null", O_RDONLY);
	if (sentinel < 0 || sentinel != owned_fd) return -1;
	dis_io_destroy(&ctx);
	if (fcntl(sentinel, F_GETFD) < 0) return -1;
	close(sentinel);
	pthread_join(thread, NULL);
	if (args.fd >= 0 && mode != WORK_TRUNCATED) close(args.fd);
	return args.failed ? -1 : 0;
}

int main(void)
{
	signal(SIGPIPE, SIG_IGN);
	if (check_success() != 0) { fprintf(stderr, "fd success-path test failed\n"); return 1; }
	if (check_failure(WORK_NEGATIVE) != 0) { fprintf(stderr, "negative-status test failed\n"); return 1; }
	if (check_failure(WORK_TRUNCATED) != 0) { fprintf(stderr, "truncated-payload test failed\n"); return 1; }
	if (check_failure(WORK_LATE_PARTIAL) != 0) { fprintf(stderr, "partial/late-status test failed\n"); return 1; }
	puts("io_fd_test: all tests passed");
	return 0;
}
