/*
 * io.c -- root-based block device reading via `su -c dd`.
 *
 * SELinux blocks unprivileged apps and system_server from opening
 * block_device nodes directly. On this device the module app (with a
 * KernelSU grant) can read the volume via `su -c dd`. We route every
 * sector read through `su -c dd if=<path> bs=1 skip=<offset> count=<len>`
 * so the dislocker core can decrypt BitLocker volumes without a direct fd.
 *
 * Uses fork/exec (not popen) because the Android NDK's libc does not
 * reliably expose popen.
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
#include <sys/wait.h>

/*
 * Read `len` bytes at byte `offset` from the block device. Returns the
 * number of bytes read, or -1 on failure.
 */
int dis_blk_read(dis_ctx_t *ctx, uint8_t *buf, off_t offset, size_t len)
{
	if (!ctx || !buf || len == 0)
		return -1;

	int pipefd[2];
	if (pipe(pipefd) != 0) {
		dis_set_error("pipe failed: %s", strerror(errno));
		return -1;
	}

	pid_t pid = fork();
	if (pid < 0) {
		close(pipefd[0]);
		close(pipefd[1]);
		dis_set_error("fork failed: %s", strerror(errno));
		return -1;
	}

	if (pid == 0) {
		/* Child: redirect stdout to pipe, run su -c dd. */
		close(pipefd[0]);
		dup2(pipefd[1], STDOUT_FILENO);
		close(pipefd[1]);

		char offset_s[32], count_s[32];
		snprintf(offset_s, sizeof(offset_s), "%lld", (long long)(ctx->offset + offset));
		snprintf(count_s, sizeof(count_s), "%zu", len);

		/* Build the dd command and run it via su directly (same as the Java
		 * RootAccess path that works). Avoid a shell wrapper to dodge quoting. */
		char ddcmd[1024];
		snprintf(ddcmd, sizeof(ddcmd),
			"dd if='%s' bs=1 skip=%s count=%s 2>/dev/null",
			ctx->device_path, offset_s, count_s);

		execl("/system/bin/su", "su", "-c", ddcmd, (char *)NULL);
		/* Try /system/xbin/su as fallback (older Android). */
		execl("/system/xbin/su", "su", "-c", ddcmd, (char *)NULL);
		/* Only reached if exec fails. */
		_exit(127);
	}

	/* Parent: read from pipe. */
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

	if (got != len) {
		dis_set_error("su dd read only %zu/%zu bytes from %s",
			got, len, ctx->device_path);
		return -1;
	}
	return (int)got;
}
