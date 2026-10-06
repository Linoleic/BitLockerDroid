/*
 * dislocker_jni.c -- JNI bridge between the BitLockerDroid Kotlin layer and
 * the native dislocker core.
 *
 * Exposes a minimal, safe surface:
 *   hasBitLockerHeader(path)            -> boolean
 *   openVolume(path, offset, password)  -> long session handle (or 0)
 *   openVolumeRecovery(path, offset, rk) -> long session handle
 *   sessionInfo(handle)                 -> long[] {sectorSize, volumeSize, algorithm}
 *   read(handle, offset, size)          -> byte[]
 *   close(handle)
 *   getLastError()                      -> String
 *
 * This file is part of BitLockerDroid.
 */
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <pthread.h>
#include <android/log.h>
#if defined(__aarch64__)
#include <sys/auxv.h>
#include <asm/hwcap.h>
#endif

#include "dislocker/dislocker.h"
#include "dislocker/dislocker_priv.h"
#include "dislocker/crypto.h"
#include "dislocker/ntfs3g_device.h"
#include "dislocker/fatfs_device.h"
#include "daemon/smart_reader.h"

#define TAG "BitLockerNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define NATIVE_METHOD(env, cls, name, sig, fn) \
	{ name, sig, (void *)(fn) }

static void jni_throw(JNIEnv *env, const char *class_name, const char *msg)
{
	if ((*env)->ExceptionCheck(env)) {
		return;
	}
	jclass cls = (*env)->FindClass(env, class_name);
	if (cls) {
		(*env)->ThrowNew(env, cls, msg);
		(*env)->DeleteLocalRef(env, cls);
	}
}

static void jni_throw_illegal_state(JNIEnv *env, const char *msg)
{
	jni_throw(env, "java/lang/IllegalStateException", msg);
}

static void jni_throw_illegal_arg(JNIEnv *env, const char *msg)
{
	jni_throw(env, "java/lang/IllegalArgumentException", msg);
}

static jbyteArray read_bytes(JNIEnv *env, const void *data, size_t len)
{
	jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
	if (!arr)
		return NULL;
	(*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)data);
	return arr;
}

static jlong jptr(dis_ctx_t *ctx)
{
	return (jlong)(intptr_t)ctx;
}

static dis_ctx_t *ptrj(jlong h)
{
	return (dis_ctx_t *)(intptr_t)h;
}

/* ---------------- session handle registry ----------------
 * Raw ctx pointers handed to Java are validated against this table. After
 * nativeClose the slot is freed, so a stale or double-closed handle becomes a
 * clean IllegalStateException instead of a use-after-free. Each slot carries a
 * mutex so IO ops cannot run concurrently with close on the same ctx. */
#define JNI_MAX_SESSIONS 16

typedef struct {
	dis_ctx_t *ctx;
	pthread_mutex_t lock;
	int used;
	int closing;
} jni_slot_t;

static jni_slot_t g_sessions[JNI_MAX_SESSIONS];
static pthread_mutex_t g_sessions_lock = PTHREAD_MUTEX_INITIALIZER;

static jlong slot_register(dis_ctx_t *ctx)
{
	jlong h = 0;
	pthread_mutex_lock(&g_sessions_lock);
	for (int i = 0; i < JNI_MAX_SESSIONS; i++) {
		if (!g_sessions[i].used) {
			g_sessions[i].ctx = ctx;
			g_sessions[i].closing = 0;
			g_sessions[i].used = 1;
			h = jptr(ctx);
			break;
		}
	}
	pthread_mutex_unlock(&g_sessions_lock);
	return h;
}

/* Returns the slot owning `h` with its per-slot lock held, or NULL. */
static jni_slot_t *slot_acquire(jlong h)
{
	dis_ctx_t *ctx = ptrj(h);
	if (!ctx)
		return NULL;

	pthread_mutex_lock(&g_sessions_lock);
	for (int i = 0; i < JNI_MAX_SESSIONS; i++) {
		jni_slot_t *s = &g_sessions[i];
		if (s->used && !s->closing && s->ctx == ctx) {
			pthread_mutex_lock(&s->lock);
			if (s->closing || !s->used) {
				pthread_mutex_unlock(&s->lock);
				pthread_mutex_unlock(&g_sessions_lock);
				return NULL;
			}
			pthread_mutex_unlock(&g_sessions_lock);
			return s;
		}
	}
	pthread_mutex_unlock(&g_sessions_lock);
	return NULL;
}

static void slot_release(jni_slot_t *s)
{
	if (s)
		pthread_mutex_unlock(&s->lock);
}

/* Validates `h` and unregisters it; marks it closing and releases the global lock
 * before waiting on the slot's per-session lock to prevent starvation of other slots.
 * Returns the ctx for the caller to free, or NULL when the handle is stale. */
static dis_ctx_t *slot_take(jlong h)
{
	dis_ctx_t *ctx = ptrj(h);
	if (!ctx)
		return NULL;

	jni_slot_t *target = NULL;
	pthread_mutex_lock(&g_sessions_lock);
	for (int i = 0; i < JNI_MAX_SESSIONS; i++) {
		jni_slot_t *s = &g_sessions[i];
		if (s->used && !s->closing && s->ctx == ctx) {
			s->closing = 1;
			target = s;
			break;
		}
	}
	pthread_mutex_unlock(&g_sessions_lock);

	if (!target)
		return NULL;

	/* Drain any in-flight I/O without holding g_sessions_lock */
	pthread_mutex_lock(&target->lock);

	pthread_mutex_lock(&g_sessions_lock);
	target->used = 0;
	target->closing = 0;
	target->ctx = NULL;
	pthread_mutex_unlock(&g_sessions_lock);

	pthread_mutex_unlock(&target->lock);
	return ctx;
}

/* ---------------- native methods ---------------- */

static jboolean native_hasBitLockerHeader(JNIEnv *env, jobject thiz, jstring path)
{
	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	if (!cpath)
		return JNI_FALSE;

	int ret = dis_has_bitlocker_header(cpath);

	if (ret < 0) {
		LOGE("hasBitLockerHeader(%s) failed: %s", cpath, dis_get_last_error());
	}

	(*env)->ReleaseStringUTFChars(env, path, cpath);

	return ret == 1 ? JNI_TRUE : JNI_FALSE;
}

static jlong native_openVolume(JNIEnv *env, jobject thiz,
	jstring path, jlong offset, jbyteArray password)
{
	if (!path || !password)
		return 0;

	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	if (!cpath)
		return 0;

	jsize plen = (*env)->GetArrayLength(env, password);
	uint8_t *pbuf = (uint8_t *)malloc((size_t)plen + 1);
	if (!pbuf) {
		(*env)->ReleaseStringUTFChars(env, path, cpath);
		return 0;
	}
	(*env)->GetByteArrayRegion(env, password, 0, plen, (jbyte *)pbuf);
	pbuf[plen] = 0;

	dis_session_info_t info;
	dis_ctx_t *ctx = dis_open_volume(cpath, (off_t)offset, pbuf, (size_t)plen, &info);

	dis_secure_zero(pbuf, (size_t)plen + 1);
	free(pbuf);
	(*env)->ReleaseStringUTFChars(env, path, cpath);

	if (!ctx) {
		LOGE("openVolume failed: %s", dis_get_last_error());
		return 0;
	}

	jlong h = slot_register(ctx);
	if (!h) {
		/* Registry full: close immediately rather than leak an untracked ctx. */
		dis_close_volume(ctx);
	}
	return h;
}

static jlong native_openVolumeRecovery(JNIEnv *env, jobject thiz,
	jstring path, jlong offset, jstring recoveryKey)
{
	if (!path || !recoveryKey)
		return 0;

	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	const char *ckey  = (*env)->GetStringUTFChars(env, recoveryKey, NULL);
	if (!cpath || !ckey) {
		/* Release whichever succeeded before bailing out. */
		if (cpath)
			(*env)->ReleaseStringUTFChars(env, path, cpath);
		if (ckey)
			(*env)->ReleaseStringUTFChars(env, recoveryKey, ckey);
		return 0;
	}

	size_t key_len = strlen(ckey);
	char *local_key = (char *)malloc(key_len + 1);
	if (!local_key) {
		(*env)->ReleaseStringUTFChars(env, recoveryKey, ckey);
		(*env)->ReleaseStringUTFChars(env, path, cpath);
		return 0;
	}
	memcpy(local_key, ckey, key_len + 1);
	/* Release the JNI-owned string buffer immediately without mutating JVM memory */
	(*env)->ReleaseStringUTFChars(env, recoveryKey, ckey);

	dis_session_info_t info;
	dis_ctx_t *ctx = dis_open_volume_recovery(cpath, (off_t)offset,
		(const uint8_t *)local_key, key_len, &info);

	/* Securely wipe local credential copy */
	dis_secure_zero(local_key, key_len + 1);
	free(local_key);

	(*env)->ReleaseStringUTFChars(env, path, cpath);

	if (!ctx) {
		LOGE("openVolumeRecovery failed: %s", dis_get_last_error());
		return 0;
	}

	jlong h = slot_register(ctx);
	if (!h)
		dis_close_volume(ctx);
	return h;
}

/* returns long[] {sectorSize, volumeSize, algorithm, fvekLen, dataOffset} */
static jlongArray native_sessionInfo(JNIEnv *env, jobject thiz, jlong handle)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot) {
		jni_throw_illegal_state(env, "invalid or closed session handle");
		return NULL;
	}
	dis_ctx_t *ctx = slot->ctx;

	jlong vals[5];
	vals[0] = dis_sector_size(ctx);
	vals[1] = (jlong)dis_volume_size(ctx);
	vals[2] = dis_algorithm(ctx);
	vals[3] = dis_fvek_len(ctx);
	/* data offset = boot_backup (physical start of encrypted data) */
	vals[4] = (jlong)(ctx->information ? (int64_t)ctx->information->boot_sectors_backup : 0);

	slot_release(slot);

	jlongArray out = (*env)->NewLongArray(env, 5);
	if (!out)
		return NULL;
	(*env)->SetLongArrayRegion(env, out, 0, 5, vals);
	return out;
}

static jbyteArray native_read(JNIEnv *env, jobject thiz, jlong handle,
	jlong offset, jint size)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot || size <= 0) {
		if (slot)
			slot_release(slot);
		jni_throw_illegal_arg(env, "invalid or closed session handle");
		return NULL;
	}
	dis_ctx_t *ctx = slot->ctx;

	uint8_t *buf = (uint8_t *)malloc((size_t)size);
	if (!buf) {
		slot_release(slot);
		return NULL;
	}

	int ret = dis_read_decrypted(ctx, buf, (off_t)offset, (size_t)size);
	slot_release(slot);

	if (ret < 0) {
		free(buf);
		LOGE("dis_read_decrypted failed: %s", dis_get_last_error());
		return NULL;
	}

	jbyteArray arr = read_bytes(env, buf, (size_t)ret);
	memset(buf, 0, (size_t)size);
	free(buf);
	return arr;
}

static jint native_write(JNIEnv *env, jobject thiz, jlong handle,
	jlong offset, jbyteArray data, jint size)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot || !data || size <= 0) {
		if (slot)
			slot_release(slot);
		jni_throw_illegal_arg(env, "invalid or closed session handle");
		return -1;
	}
	dis_ctx_t *ctx = slot->ctx;

	jsize dlen = (*env)->GetArrayLength(env, data);
	if (size > dlen)
		size = dlen;

	uint8_t *buf = (uint8_t *)malloc((size_t)size);
	if (!buf) {
		slot_release(slot);
		return -1;
	}

	(*env)->GetByteArrayRegion(env, data, 0, size, (jbyte *)buf);

	int ret = dis_write_encrypted(ctx, buf, (off_t)offset, (size_t)size);
	slot_release(slot);

	memset(buf, 0, (size_t)size);
	free(buf);

	if (ret < 0) {
		LOGE("dis_write_encrypted failed: %s", dis_get_last_error());
		return ret;
	}
	return (jint)ret;
}

static void native_close(JNIEnv *env, jobject thiz, jlong handle)
{
	dis_ctx_t *ctx = slot_take(handle);
	/* Stale handle: already closed or never registered — nothing to free. */
	if (!ctx)
		return;
	dis_close_volume(ctx);
}

/* Flushes the encrypted block device (fdatasync / daemon CMD_SYNC).
 * Safe-eject path: call after all write pipelines have drained. */
static jint native_sync(JNIEnv *env, jobject thiz, jlong handle)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot) {
		jni_throw_illegal_state(env, "invalid or closed session handle");
		return -1;
	}
	int ret = dis_blk_sync(slot->ctx);
	slot_release(slot);
	return ret == 0 ? 0 : -1;
}

/* Decrypts an already-read encrypted buffer at a sector-aligned offset.
 * input: the encrypted bytes; offset: volume-relative byte offset.
 * Returns the decrypted byte[] or null on failure. */
static jbyteArray native_decryptBuffer(JNIEnv *env, jobject thiz, jlong handle,
	jbyteArray input, jlong offset)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot || input == NULL) {
		if (slot)
			slot_release(slot);
		jni_throw_illegal_arg(env, "invalid or closed session handle");
		return NULL;
	}
	dis_ctx_t *ctx = slot->ctx;

	jsize len = (*env)->GetArrayLength(env, input);
	if (len <= 0 || (size_t)len % ctx->sector_size != 0) {
		slot_release(slot);
		return NULL;
	}

	uint8_t *in = (uint8_t *)malloc((size_t)len);
	uint8_t *out = (uint8_t *)malloc((size_t)len);
	if (!in || !out) {
		free(in); free(out);
		slot_release(slot);
		return NULL;
	}
	(*env)->GetByteArrayRegion(env, input, 0, len, (jbyte *)in);

	int ret = dis_decrypt_region(ctx, in, out, (off_t)offset, (size_t)len);
	slot_release(slot);

	free(in);
	if (ret < 0) {
		free(out);
		LOGE("decryptBuffer failed: %s", dis_get_last_error());
		return NULL;
	}

	jbyteArray arr = read_bytes(env, out, (size_t)ret);
	memset(out, 0, (size_t)len);
	free(out);
	return arr;
}

/* Encrypts a plaintext buffer at a sector-aligned offset.
 * input: the plaintext bytes; offset: volume-relative byte offset.
 * Returns the encrypted byte[] or null on failure. Enforces write barrier. */
static jbyteArray native_encryptBuffer(JNIEnv *env, jobject thiz, jlong handle,
	jbyteArray input, jlong offset)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot || input == NULL) {
		if (slot)
			slot_release(slot);
		jni_throw_illegal_arg(env, "invalid or closed session handle");
		return NULL;
	}
	dis_ctx_t *ctx = slot->ctx;

	jsize len = (*env)->GetArrayLength(env, input);
	if (len <= 0 || (size_t)len % ctx->sector_size != 0) {
		slot_release(slot);
		return NULL;
	}

	uint8_t *in = (uint8_t *)malloc((size_t)len);
	uint8_t *out = (uint8_t *)malloc((size_t)len);
	if (!in || !out) {
		free(in); free(out);
		slot_release(slot);
		return NULL;
	}
	(*env)->GetByteArrayRegion(env, input, 0, len, (jbyte *)in);

	int ret = dis_encrypt_region(ctx, in, out, (off_t)offset, (size_t)len);
	slot_release(slot);

	memset(in, 0, (size_t)len);
	free(in);
	if (ret < 0) {
		free(out);
		LOGE("encryptBuffer failed: %s", dis_get_last_error());
		return NULL;
	}

	jbyteArray arr = read_bytes(env, out, (size_t)ret);
	free(out);
	return arr;
}

static jlong native_ntfsMount(JNIEnv *env, jobject thiz, jlong handle, jboolean readOnly)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot)
		return 0;
	dis_ctx_t *ctx = slot->ctx;

	dis_ntfs_handle_t vol = dis_ntfs_mount(ctx, readOnly ? 1 : 0);
	slot_release(slot);
	if (!vol) {
		LOGE("nativeNtfsMount failed for %s", ctx->device_path);
		return 0;
	}
	return (jlong)(intptr_t)vol;
}

static jint native_ntfsUmount(JNIEnv *env, jobject thiz, jlong volHandle)
{
	if (!volHandle)
		return 0;
	return (jint)dis_ntfs_umount((dis_ntfs_handle_t)(intptr_t)volHandle);
}

static jlong native_ntfsCreate(JNIEnv *env, jobject thiz, jlong volHandle,
	jstring parentPath, jstring name, jboolean isDir)
{
	if (!volHandle || !name)
		return -1;

	const char *cparent = parentPath ? (*env)->GetStringUTFChars(env, parentPath, NULL) : NULL;
	const char *cname = (*env)->GetStringUTFChars(env, name, NULL);

	int64_t ret = dis_ntfs_create((dis_ntfs_handle_t)(intptr_t)volHandle, cparent, cname, isDir ? 1 : 0);

	if (cparent)
		(*env)->ReleaseStringUTFChars(env, parentPath, cparent);
	(*env)->ReleaseStringUTFChars(env, name, cname);

	return (jlong)ret;
}

static jint native_ntfsDelete(JNIEnv *env, jobject thiz, jlong volHandle, jstring path)
{
	if (!volHandle || !path)
		return -1;

	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	int ret = dis_ntfs_delete((dis_ntfs_handle_t)(intptr_t)volHandle, cpath);
	(*env)->ReleaseStringUTFChars(env, path, cpath);

	return (jint)ret;
}

static jint native_ntfsRename(JNIEnv *env, jobject thiz, jlong volHandle,
	jstring oldPath, jstring newPath)
{
	if (!volHandle || !oldPath || !newPath)
		return -1;

	const char *cold = (*env)->GetStringUTFChars(env, oldPath, NULL);
	const char *cnew = (*env)->GetStringUTFChars(env, newPath, NULL);

	int ret = dis_ntfs_rename((dis_ntfs_handle_t)(intptr_t)volHandle, cold, cnew);

	(*env)->ReleaseStringUTFChars(env, oldPath, cold);
	(*env)->ReleaseStringUTFChars(env, newPath, cnew);

	return (jint)ret;
}

static jlong native_ntfsWrite(JNIEnv *env, jobject thiz, jlong volHandle,
	jstring path, jlong offset, jbyteArray data, jint count)
{
	if (!volHandle || !path || !data || count <= 0 || offset < 0)
		return -1;

	jsize dlen = (*env)->GetArrayLength(env, data);
	if (count > dlen) count = dlen;

	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	if (!cpath) return -1;

	void *buf = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
	if (!buf) {
		(*env)->ReleaseStringUTFChars(env, path, cpath);
		return -1;
	}

	int64_t ret = dis_ntfs_write((dis_ntfs_handle_t)(intptr_t)volHandle, cpath, (int64_t)offset, (const uint8_t *)buf, (int64_t)count);

	(*env)->ReleasePrimitiveArrayCritical(env, data, buf, JNI_ABORT);
	(*env)->ReleaseStringUTFChars(env, path, cpath);
	return (jlong)ret;
}

static jint native_ntfsSync(JNIEnv *env, jobject thiz, jlong volHandle)
{
	if (!volHandle) return -1;
	return (jint)dis_ntfs_sync((dis_ntfs_handle_t)(intptr_t)volHandle);
}

static jlong native_ntfsTruncate(JNIEnv *env, jobject thiz, jlong volHandle,
	jstring path, jlong newSize)
{
	if (!volHandle || !path || newSize < 0)
		return -1;

	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	int64_t ret = dis_ntfs_truncate((dis_ntfs_handle_t)(intptr_t)volHandle, cpath, (int64_t)newSize);
	(*env)->ReleaseStringUTFChars(env, path, cpath);

	return (jlong)ret;
}

static jlong native_fatfsMount(JNIEnv *env, jobject thiz, jlong handle, jboolean readOnly)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot)
		return 0;
	dis_ctx_t *ctx = slot->ctx;

	dis_fatfs_handle_t vol = dis_fatfs_mount(ctx, readOnly ? 1 : 0);
	slot_release(slot);
	if (!vol) {
		LOGE("nativeFatfsMount failed for %s", ctx->device_path);
		return 0;
	}
	return (jlong)(intptr_t)vol;
}

static jint native_fatfsUmount(JNIEnv *env, jobject thiz, jlong volHandle)
{
	if (!volHandle)
		return 0;
	return (jint)dis_fatfs_umount((dis_fatfs_handle_t)(intptr_t)volHandle);
}

static jlong native_fatfsCreate(JNIEnv *env, jobject thiz, jlong volHandle,
	jstring parentPath, jstring name, jboolean isDir)
{
	if (!volHandle || !name)
		return -1;

	const char *cparent = parentPath ? (*env)->GetStringUTFChars(env, parentPath, NULL) : NULL;
	const char *cname = (*env)->GetStringUTFChars(env, name, NULL);

	int64_t ret = dis_fatfs_create((dis_fatfs_handle_t)(intptr_t)volHandle, cparent, cname, isDir ? 1 : 0);

	if (cparent)
		(*env)->ReleaseStringUTFChars(env, parentPath, cparent);
	(*env)->ReleaseStringUTFChars(env, name, cname);

	return (jlong)ret;
}

static jint native_fatfsDelete(JNIEnv *env, jobject thiz, jlong volHandle, jstring path)
{
	if (!volHandle || !path)
		return -1;

	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	int ret = dis_fatfs_delete((dis_fatfs_handle_t)(intptr_t)volHandle, cpath);
	(*env)->ReleaseStringUTFChars(env, path, cpath);

	return (jint)ret;
}

static jint native_fatfsRename(JNIEnv *env, jobject thiz, jlong volHandle,
	jstring oldPath, jstring newPath)
{
	if (!volHandle || !oldPath || !newPath)
		return -1;

	const char *cold = (*env)->GetStringUTFChars(env, oldPath, NULL);
	const char *cnew = (*env)->GetStringUTFChars(env, newPath, NULL);

	int ret = dis_fatfs_rename((dis_fatfs_handle_t)(intptr_t)volHandle, cold, cnew);

	(*env)->ReleaseStringUTFChars(env, oldPath, cold);
	(*env)->ReleaseStringUTFChars(env, newPath, cnew);

	return (jint)ret;
}

static jlong native_fatfsWrite(JNIEnv *env, jobject thiz, jlong volHandle,
	jstring path, jlong offset, jbyteArray data, jint count)
{
	if (!volHandle || !path || !data || count <= 0 || offset < 0)
		return -1;

	jsize dlen = (*env)->GetArrayLength(env, data);
	if (count > dlen) count = dlen;

	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	if (!cpath) return -1;

	void *buf = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
	if (!buf) {
		(*env)->ReleaseStringUTFChars(env, path, cpath);
		return -1;
	}

	int64_t ret = dis_fatfs_write((dis_fatfs_handle_t)(intptr_t)volHandle, cpath, (int64_t)offset, (const uint8_t *)buf, (int64_t)count);

	(*env)->ReleasePrimitiveArrayCritical(env, data, buf, JNI_ABORT);
	(*env)->ReleaseStringUTFChars(env, path, cpath);
	return (jlong)ret;
}

static jint native_fatfsSync(JNIEnv *env, jobject thiz, jlong volHandle)
{
	if (!volHandle) return -1;
	return (jint)dis_fatfs_sync((dis_fatfs_handle_t)(intptr_t)volHandle);
}

static jlong native_fatfsTruncate(JNIEnv *env, jobject thiz, jlong volHandle,
	jstring path, jlong newSize)
{
	if (!volHandle || !path || newSize < 0)
		return -1;

	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	int64_t ret = dis_fatfs_truncate((dis_fatfs_handle_t)(intptr_t)volHandle, cpath, (int64_t)newSize);
	(*env)->ReleaseStringUTFChars(env, path, cpath);

	return (jlong)ret;
}

static jstring native_getLastError(JNIEnv *env, jobject thiz)
{
	return (*env)->NewStringUTF(env, dis_get_last_error());
}

static jstring native_getVolumeGuid(JNIEnv *env, jobject thiz, jlong handle)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot)
		return NULL;
	dis_ctx_t *ctx = slot->ctx;

	const uint8_t *guid_bytes = NULL;
	if (ctx->dataset) {
		guid_bytes = (const uint8_t *)ctx->dataset->guid;
	} else if (memcmp(BITLOCKER_TO_GO_SIGNATURE, ctx->volume_header.signature, 8) == 0) {
		guid_bytes = (const uint8_t *)ctx->volume_header.bltg_guid;
	} else {
		guid_bytes = (const uint8_t *)ctx->volume_header.guid;
	}

	slot_release(slot);

	if (!guid_bytes)
		return NULL;

	int all_zero = 1;
	for (int i = 0; i < 16; i++) {
		if (guid_bytes[i] != 0) {
			all_zero = 0;
			break;
		}
	}
	if (all_zero)
		return NULL;

	char buf[40];
	uint32_t d1 = (uint32_t)guid_bytes[0] | ((uint32_t)guid_bytes[1] << 8) | ((uint32_t)guid_bytes[2] << 16) | ((uint32_t)guid_bytes[3] << 24);
	uint16_t d2 = (uint16_t)guid_bytes[4] | ((uint16_t)guid_bytes[5] << 8);
	uint16_t d3 = (uint16_t)guid_bytes[6] | ((uint16_t)guid_bytes[7] << 8);
	snprintf(buf, sizeof(buf), "%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
		d1, d2, d3,
		guid_bytes[8], guid_bytes[9],
		guid_bytes[10], guid_bytes[11], guid_bytes[12], guid_bytes[13], guid_bytes[14], guid_bytes[15]);

	return (*env)->NewStringUTF(env, buf);
}

static jstring native_getRecoveryKeyId(JNIEnv *env, jobject thiz, jlong handle)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot)
		return NULL;
	dis_ctx_t *ctx = slot->ctx;

	uint8_t guid_bytes[16] = {0};
	int found = dis_get_recovery_key_id(ctx, guid_bytes);
	slot_release(slot);

	if (!found)
		return NULL;

	char buf[40];
	uint32_t d1 = (uint32_t)guid_bytes[0] | ((uint32_t)guid_bytes[1] << 8) | ((uint32_t)guid_bytes[2] << 16) | ((uint32_t)guid_bytes[3] << 24);
	uint16_t d2 = (uint16_t)guid_bytes[4] | ((uint16_t)guid_bytes[5] << 8);
	uint16_t d3 = (uint16_t)guid_bytes[6] | ((uint16_t)guid_bytes[7] << 8);
	snprintf(buf, sizeof(buf), "%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X",
		d1, d2, d3,
		guid_bytes[8], guid_bytes[9],
		guid_bytes[10], guid_bytes[11], guid_bytes[12], guid_bytes[13], guid_bytes[14], guid_bytes[15]);

	return (*env)->NewStringUTF(env, buf);
}

static jlongArray native_ntfsGetSpace(JNIEnv *env, jobject thiz, jlong volHandle)
{
	if (!volHandle) return NULL;
	int64_t total = 0, free_b = 0;
	if (dis_ntfs_get_space((dis_ntfs_handle_t)(intptr_t)volHandle, &total, &free_b) != 0)
		return NULL;
	jlong vals[2] = { (jlong)total, (jlong)free_b };
	jlongArray out = (*env)->NewLongArray(env, 2);
	if (!out) return NULL;
	(*env)->SetLongArrayRegion(env, out, 0, 2, vals);
	return out;
}

static jint native_ntfsRepairDirty(JNIEnv *env, jobject thiz, jlong volHandle)
{
	if (!volHandle) return -1;
	return (jint)dis_ntfs_repair_dirty((dis_ntfs_handle_t)(intptr_t)volHandle);
}

static jlongArray native_fatfsGetSpace(JNIEnv *env, jobject thiz, jlong volHandle)
{
	if (!volHandle) return NULL;
	int64_t total = 0, free_b = 0;
	if (dis_fatfs_get_space((dis_fatfs_handle_t)(intptr_t)volHandle, &total, &free_b) != 0)
		return NULL;
	jlong vals[2] = { (jlong)total, (jlong)free_b };
	jlongArray out = (*env)->NewLongArray(env, 2);
	if (!out) return NULL;
	(*env)->SetLongArrayRegion(env, out, 0, 2, vals);
	return out;
}

/* ---------------- Disaster Recovery & Raw Block Access ---------------- */

static jbyteArray native_extractFveMetadata(JNIEnv *env, jobject thiz, jstring path, jlong offset)
{
	if (!path) return NULL;
	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	if (!cpath) return NULL;

	dis_ctx_t *ctx = calloc(1, sizeof(dis_ctx_t));
	if (!ctx) {
		(*env)->ReleaseStringUTFChars(env, path, cpath);
		return NULL;
	}

	snprintf(ctx->device_path, sizeof(ctx->device_path), "%s", cpath);
	ctx->offset = (off_t)offset;
	(*env)->ReleaseStringUTFChars(env, path, cpath);

	if (dis_io_init(ctx) != 0) {
		LOGE("dis_io_init failed for extract: %s", dis_get_last_error());
		free(ctx);
		return NULL;
	}

	uint8_t *pkg = NULL;
	size_t pkg_len = 0;
	int ret = dis_metadata_extract_package(ctx, &pkg, &pkg_len);

	dis_close_volume(ctx);

	if (ret != DIS_RET_SUCCESS || !pkg || pkg_len == 0) {
		LOGE("extractFveMetadata failed: %s", dis_get_last_error());
		if (pkg) free(pkg);
		return NULL;
	}

	jbyteArray arr = read_bytes(env, pkg, pkg_len);
	free(pkg);
	return arr;
}

static jbyteArray native_extractFveMetadataFromHandle(JNIEnv *env, jobject thiz, jlong handle)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot) {
		jni_throw_illegal_state(env, "invalid or closed session handle");
		return NULL;
	}
	dis_ctx_t *ctx = slot->ctx;

	uint8_t *pkg = NULL;
	size_t pkg_len = 0;
	int ret = dis_metadata_extract_package(ctx, &pkg, &pkg_len);
	slot_release(slot);

	if (ret != DIS_RET_SUCCESS || !pkg || pkg_len == 0) {
		LOGE("extractFveMetadataFromHandle failed: %s", dis_get_last_error());
		if (pkg) free(pkg);
		return NULL;
	}

	jbyteArray arr = read_bytes(env, pkg, pkg_len);
	free(pkg);
	return arr;
}

static jint native_restoreFveMetadata(JNIEnv *env, jobject thiz, jstring path, jlong offset, jbyteArray data, jlong targetPartitionSize)
{
	if (!path || !data) return -1;
	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	if (!cpath) return -1;

	jsize dlen = (*env)->GetArrayLength(env, data);
	if (dlen <= 0) {
		(*env)->ReleaseStringUTFChars(env, path, cpath);
		return -1;
	}

	uint8_t *pkg = malloc((size_t)dlen);
	if (!pkg) {
		(*env)->ReleaseStringUTFChars(env, path, cpath);
		return -1;
	}
	(*env)->GetByteArrayRegion(env, data, 0, dlen, (jbyte *)pkg);

	dis_ctx_t *ctx = calloc(1, sizeof(dis_ctx_t));
	if (!ctx) {
		free(pkg);
		(*env)->ReleaseStringUTFChars(env, path, cpath);
		return -1;
	}

	snprintf(ctx->device_path, sizeof(ctx->device_path), "%s", cpath);
	ctx->offset = (off_t)offset;
	(*env)->ReleaseStringUTFChars(env, path, cpath);

	if (dis_io_init(ctx) != 0) {
		LOGE("dis_io_init failed for restore: %s", dis_get_last_error());
		free(pkg);
		free(ctx);
		return -102;
	}

	int ret = dis_metadata_restore_package(ctx, pkg, (size_t)dlen, (uint64_t)targetPartitionSize);

	dis_close_volume(ctx);
	free(pkg);
	return (jint)ret;
}

static jlong native_openRawDevice(JNIEnv *env, jobject thiz, jstring path, jlong offset)
{
	if (!path) return 0;
	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	if (!cpath) return 0;

	dis_ctx_t *ctx = calloc(1, sizeof(dis_ctx_t));
	if (!ctx) {
		(*env)->ReleaseStringUTFChars(env, path, cpath);
		return 0;
	}

	snprintf(ctx->device_path, sizeof(ctx->device_path), "%s", cpath);
	ctx->offset = (off_t)offset;
	ctx->sector_size = 512;
	(*env)->ReleaseStringUTFChars(env, path, cpath);

	if (dis_io_init(ctx) != 0) {
		LOGE("dis_io_init failed for raw device: %s", dis_get_last_error());
		free(ctx);
		return 0;
	}

	ctx->volume_size = dis_blk_get_size(ctx);
	if (ctx->volume_size > (uint64_t)ctx->offset)
		ctx->volume_size -= ctx->offset;

	jlong h = slot_register(ctx);
	if (!h)
		dis_close_volume(ctx);
	return h;
}

static jbyteArray native_readRaw(JNIEnv *env, jobject thiz, jlong handle, jlong offset, jint size)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot || size <= 0) {
		if (slot)
			slot_release(slot);
		jni_throw_illegal_arg(env, "invalid or closed session handle");
		return NULL;
	}
	dis_ctx_t *ctx = slot->ctx;

	uint8_t *buf = (uint8_t *)malloc((size_t)size);
	if (!buf) {
		slot_release(slot);
		return NULL;
	}

	int ret = dis_blk_read(ctx, buf, (off_t)offset, (size_t)size);
	slot_release(slot);

	if (ret < 0) {
		free(buf);
		LOGE("dis_blk_read failed: %s", dis_get_last_error());
		return NULL;
	}

	jbyteArray arr = read_bytes(env, buf, (size_t)ret);
	free(buf);
	return arr;
}

static jlong native_getDeviceSize(JNIEnv *env, jobject thiz, jlong handle)
{
	jni_slot_t *slot = slot_acquire(handle);
	if (!slot) return 0;
	dis_ctx_t *ctx = slot->ctx;
	uint64_t sz = dis_blk_get_size(ctx);
	if (sz > (uint64_t)ctx->offset)
		sz -= ctx->offset;
	slot_release(slot);
	return (jlong)sz;
}

static jboolean native_isHardwareAesSupported(JNIEnv *env, jobject thiz)
{
	(void)env; (void)thiz;
	return dislocker_is_armv8_ce_supported() ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_isHardwareAesEnabled(JNIEnv *env, jobject thiz)
{
	(void)env; (void)thiz;
	return dislocker_is_armv8_ce_enabled() ? JNI_TRUE : JNI_FALSE;
}

static void native_setHardwareAesEnabled(JNIEnv *env, jobject thiz, jboolean enabled)
{
	(void)env; (void)thiz;
	dislocker_set_armv8_ce_enabled(enabled ? 1 : 0);
}

static jboolean native_isHardwareSha2Supported(JNIEnv *env, jobject thiz)
{
	(void)env; (void)thiz;
	return dislocker_is_armv8_sha2_supported() ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_isHardwareSha2Enabled(JNIEnv *env, jobject thiz)
{
	(void)env; (void)thiz;
	return dislocker_is_armv8_sha2_enabled() ? JNI_TRUE : JNI_FALSE;
}

static void native_setHardwareSha2Enabled(JNIEnv *env, jobject thiz, jboolean enabled)
{
	(void)env; (void)thiz;
	dislocker_set_armv8_sha2_enabled(enabled ? 1 : 0);
}

static jlong native_benchmarkKeyStretching(JNIEnv *env, jobject thiz, jint rounds)
{
	(void)env; (void)thiz;
	if (rounds <= 0) return 0;

	uint8_t ch_buf[88];
	memset(ch_buf, 0x5a, sizeof(ch_buf));
	uint8_t result[32];

	struct timespec t0, t1;
	clock_gettime(CLOCK_MONOTONIC, &t0);

	if (dislocker_is_armv8_sha2_enabled()) {
		bitlocker_stretch_key_rounds_armv8ce(ch_buf, result, (uint32_t)rounds);
	} else {
		for (int i = 0; i < rounds; i++) {
			sha256(ch_buf, sizeof(ch_buf), ch_buf);
			uint64_t *cnt = (uint64_t *)(ch_buf + 80);
			(*cnt)++;
		}
	}

	clock_gettime(CLOCK_MONOTONIC, &t1);
	int64_t us = (int64_t)(t1.tv_sec - t0.tv_sec) * 1000000LL + (t1.tv_nsec - t0.tv_nsec) / 1000LL;
	return (jlong)us;
}

static jstring native_getHardwareAesDetails(JNIEnv *env, jobject thiz)
{
	(void)thiz;
#if defined(__aarch64__) && defined(HWCAP_AES)
	unsigned long hwcap = getauxval(AT_HWCAP);
	int has_hwcap = (hwcap & HWCAP_AES) != 0;
	int supported = dislocker_is_armv8_ce_supported();
	if (supported) {
		return (*env)->NewStringUTF(env, "HWCAP_AES: OK | Self-Test: AES-XTS & CBC Passed (ARMv8 CE)");
	} else if (has_hwcap) {
		return (*env)->NewStringUTF(env, "HWCAP_AES: OK | Self-Test: Failed (Fallback Software)");
	} else {
		return (*env)->NewStringUTF(env, "HWCAP_AES: Missing | CPU lacks ARMv8 CE instructions");
	}
#else
	return (*env)->NewStringUTF(env, "Non-ARM64 architecture | Software table mode");
#endif
}

static jstring native_getHardwareSha2Details(JNIEnv *env, jobject thiz)
{
	(void)thiz;
#if defined(__aarch64__) && defined(HWCAP_SHA2)
	unsigned long hwcap = getauxval(AT_HWCAP);
	int has_hwcap = (hwcap & HWCAP_SHA2) != 0;
	int supported = dislocker_is_armv8_sha2_supported();
	if (supported) {
		return (*env)->NewStringUTF(env, "HWCAP_SHA2: OK | Self-Test: NIST & Stretch Passed (ARMv8 CE)");
	} else if (has_hwcap) {
		return (*env)->NewStringUTF(env, "HWCAP_SHA2: OK | Self-Test: Failed (Fallback mbedtls)");
	} else {
		return (*env)->NewStringUTF(env, "HWCAP_SHA2: Missing | CPU lacks ARMv8 CE instructions");
	}
#else
	return (*env)->NewStringUTF(env, "Non-ARM64 architecture | Software mbedtls mode");
#endif
}

static jstring native_readDeviceSmart(JNIEnv *env, jobject thiz, jstring path_str)
{
	if (!path_str) return NULL;
	const char *path = (*env)->GetStringUTFChars(env, path_str, NULL);
	if (!path) return NULL;

	char json[2048];
	read_smart_json(path, json, sizeof(json));
	(*env)->ReleaseStringUTFChars(env, path_str, path);

	return (*env)->NewStringUTF(env, json);
}

/* ---------------- registration ---------------- */

static const JNINativeMethod methods[] = {
	NATIVE_METHOD(env, cls, "nativeReadDeviceSmart", "(Ljava/lang/String;)Ljava/lang/String;", native_readDeviceSmart),
	NATIVE_METHOD(env, cls, "nativeIsHardwareAesSupported", "()Z", native_isHardwareAesSupported),
	NATIVE_METHOD(env, cls, "nativeIsHardwareAesEnabled", "()Z", native_isHardwareAesEnabled),
	NATIVE_METHOD(env, cls, "nativeSetHardwareAesEnabled", "(Z)V", native_setHardwareAesEnabled),
	NATIVE_METHOD(env, cls, "nativeGetHardwareAesDetails", "()Ljava/lang/String;", native_getHardwareAesDetails),
	NATIVE_METHOD(env, cls, "nativeIsHardwareSha2Supported", "()Z", native_isHardwareSha2Supported),
	NATIVE_METHOD(env, cls, "nativeIsHardwareSha2Enabled", "()Z", native_isHardwareSha2Enabled),
	NATIVE_METHOD(env, cls, "nativeSetHardwareSha2Enabled", "(Z)V", native_setHardwareSha2Enabled),
	NATIVE_METHOD(env, cls, "nativeGetHardwareSha2Details", "()Ljava/lang/String;", native_getHardwareSha2Details),
	NATIVE_METHOD(env, cls, "nativeBenchmarkKeyStretching", "(I)J", native_benchmarkKeyStretching),
	NATIVE_METHOD(env, cls, "nativeHasBitLockerHeader", "(Ljava/lang/String;)Z", native_hasBitLockerHeader),
	NATIVE_METHOD(env, cls, "nativeOpenVolume", "(Ljava/lang/String;J[B)J", native_openVolume),
	NATIVE_METHOD(env, cls, "nativeOpenVolumeRecovery", "(Ljava/lang/String;JLjava/lang/String;)J", native_openVolumeRecovery),
	NATIVE_METHOD(env, cls, "nativeSessionInfo", "(J)[J", native_sessionInfo),
	NATIVE_METHOD(env, cls, "nativeGetVolumeGuid", "(J)Ljava/lang/String;", native_getVolumeGuid),
	NATIVE_METHOD(env, cls, "nativeGetRecoveryKeyId", "(J)Ljava/lang/String;", native_getRecoveryKeyId),
	NATIVE_METHOD(env, cls, "nativeRead", "(JJI)[B", native_read),
	NATIVE_METHOD(env, cls, "nativeWrite", "(JJ[BI)I", native_write),
	NATIVE_METHOD(env, cls, "nativeDecryptBuffer", "(J[BJ)[B", native_decryptBuffer),
	NATIVE_METHOD(env, cls, "nativeEncryptBuffer", "(J[BJ)[B", native_encryptBuffer),
	NATIVE_METHOD(env, cls, "nativeClose", "(J)V", native_close),
	NATIVE_METHOD(env, cls, "nativeSync", "(J)I", native_sync),
	NATIVE_METHOD(env, cls, "nativeGetLastError", "()Ljava/lang/String;", native_getLastError),

	/* Disaster Recovery & Low-level Backup */
	NATIVE_METHOD(env, cls, "nativeExtractFveMetadata", "(Ljava/lang/String;J)[B", native_extractFveMetadata),
	NATIVE_METHOD(env, cls, "nativeExtractFveMetadataFromHandle", "(J)[B", native_extractFveMetadataFromHandle),
	NATIVE_METHOD(env, cls, "nativeRestoreFveMetadata", "(Ljava/lang/String;J[BJ)I", native_restoreFveMetadata),
	NATIVE_METHOD(env, cls, "nativeOpenRawDevice", "(Ljava/lang/String;J)J", native_openRawDevice),
	NATIVE_METHOD(env, cls, "nativeReadRaw", "(JJI)[B", native_readRaw),
	NATIVE_METHOD(env, cls, "nativeGetDeviceSize", "(J)J", native_getDeviceSize),

	/* NTFS-3G bridge */
	NATIVE_METHOD(env, cls, "nativeNtfsMount", "(JZ)J", native_ntfsMount),
	NATIVE_METHOD(env, cls, "nativeNtfsUmount", "(J)I", native_ntfsUmount),
	NATIVE_METHOD(env, cls, "nativeNtfsCreate", "(JLjava/lang/String;Ljava/lang/String;Z)J", native_ntfsCreate),
	NATIVE_METHOD(env, cls, "nativeNtfsDelete", "(JLjava/lang/String;)I", native_ntfsDelete),
	NATIVE_METHOD(env, cls, "nativeNtfsRename", "(JLjava/lang/String;Ljava/lang/String;)I", native_ntfsRename),
	NATIVE_METHOD(env, cls, "nativeNtfsWrite", "(JLjava/lang/String;J[BI)J", native_ntfsWrite),
	NATIVE_METHOD(env, cls, "nativeNtfsTruncate", "(JLjava/lang/String;J)J", native_ntfsTruncate),
	NATIVE_METHOD(env, cls, "nativeNtfsGetSpace", "(J)[J", native_ntfsGetSpace),
	NATIVE_METHOD(env, cls, "nativeNtfsRepairDirty", "(J)I", native_ntfsRepairDirty),
	NATIVE_METHOD(env, cls, "nativeNtfsSync", "(J)I", native_ntfsSync),

	/* FatFs bridge (FAT32 & exFAT) */
	NATIVE_METHOD(env, cls, "nativeFatfsMount", "(JZ)J", native_fatfsMount),
	NATIVE_METHOD(env, cls, "nativeFatfsUmount", "(J)I", native_fatfsUmount),
	NATIVE_METHOD(env, cls, "nativeFatfsCreate", "(JLjava/lang/String;Ljava/lang/String;Z)J", native_fatfsCreate),
	NATIVE_METHOD(env, cls, "nativeFatfsDelete", "(JLjava/lang/String;)I", native_fatfsDelete),
	NATIVE_METHOD(env, cls, "nativeFatfsRename", "(JLjava/lang/String;Ljava/lang/String;)I", native_fatfsRename),
	NATIVE_METHOD(env, cls, "nativeFatfsWrite", "(JLjava/lang/String;J[BI)J", native_fatfsWrite),
	NATIVE_METHOD(env, cls, "nativeFatfsTruncate", "(JLjava/lang/String;J)J", native_fatfsTruncate),
	NATIVE_METHOD(env, cls, "nativeFatfsGetSpace", "(J)[J", native_fatfsGetSpace),
	NATIVE_METHOD(env, cls, "nativeFatfsSync", "(J)I", native_fatfsSync),
};

jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
	JNIEnv *env = NULL;
	if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
		return JNI_ERR;

	for (int i = 0; i < JNI_MAX_SESSIONS; i++) {
		pthread_mutex_init(&g_sessions[i].lock, NULL);
		g_sessions[i].ctx = NULL;
		g_sessions[i].used = 0;
		g_sessions[i].closing = 0;
	}

	jclass cls = (*env)->FindClass(env, "com/bitlockerdroid/util/NativeBridge");
	if (!cls)
		return JNI_ERR;

	if ((*env)->RegisterNatives(env, cls, methods,
		sizeof(methods) / sizeof(methods[0])) < 0)
		return JNI_ERR;

	return JNI_VERSION_1_6;
}
