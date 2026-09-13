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
#include <pthread.h>
#include <android/log.h>

#include "dislocker/dislocker.h"
#include "dislocker/dislocker_priv.h"
#include "dislocker/ntfs3g_device.h"
#include "dislocker/fatfs_device.h"

#define TAG "BitLockerNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define NATIVE_METHOD(env, cls, name, sig, fn) \
	{ name, sig, (void *)(fn) }

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
		if (s->used && s->ctx == ctx) {
			pthread_mutex_lock(&s->lock);
			pthread_mutex_unlock(&g_sessions_lock);
			return s;
		}
	}
	pthread_mutex_unlock(&g_sessions_lock);
	return NULL;
}

static void slot_release(jni_slot_t *s)
{
	pthread_mutex_unlock(&s->lock);
}

/* Validates `h` and unregisters it; returns the ctx for the caller to free,
 * or NULL when the handle is stale (already closed / never opened). */
static dis_ctx_t *slot_take(jlong h)
{
	dis_ctx_t *ctx = ptrj(h);
	if (!ctx)
		return NULL;

	dis_ctx_t *out = NULL;
	pthread_mutex_lock(&g_sessions_lock);
	for (int i = 0; i < JNI_MAX_SESSIONS; i++) {
		jni_slot_t *s = &g_sessions[i];
		if (s->used && s->ctx == ctx) {
			pthread_mutex_lock(&s->lock);
			s->used = 0;
			s->ctx = NULL;
			pthread_mutex_unlock(&s->lock);
			out = ctx;
			break;
		}
	}
	pthread_mutex_unlock(&g_sessions_lock);
	return out;
}

/* ---------------- native methods ---------------- */

static jboolean native_hasBitLockerHeader(JNIEnv *env, jobject thiz, jstring path)
{
	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	if (!cpath)
		return JNI_FALSE;

	int ret = dis_has_bitlocker_header(cpath);

	(*env)->ReleaseStringUTFChars(env, path, cpath);

	if (ret < 0) {
		LOGE("hasBitLockerHeader(%s) failed: %s", cpath, dis_get_last_error());
		return JNI_FALSE;
	}
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

	memset(pbuf, 0, (size_t)plen + 1);
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

	dis_session_info_t info;
	dis_ctx_t *ctx = dis_open_volume_recovery(cpath, (off_t)offset,
		(const uint8_t *)ckey, strlen(ckey), &info);

	(*env)->ReleaseStringUTFChars(env, recoveryKey, ckey);
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
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
			"invalid or closed session handle");
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
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
			"invalid or closed session handle");
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
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
			"invalid or closed session handle");
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
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
			"invalid or closed session handle");
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
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
			"invalid or closed session handle");
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

	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	jsize dlen = (*env)->GetArrayLength(env, data);
	if (count > dlen) count = dlen;

	uint8_t *buf = (uint8_t *)malloc((size_t)count);
	if (!buf) {
		(*env)->ReleaseStringUTFChars(env, path, cpath);
		return -1;
	}
	(*env)->GetByteArrayRegion(env, data, 0, count, (jbyte *)buf);

	int64_t ret = dis_ntfs_write((dis_ntfs_handle_t)(intptr_t)volHandle, cpath, (int64_t)offset, buf, (int64_t)count);

	free(buf);
	(*env)->ReleaseStringUTFChars(env, path, cpath);
	return (jlong)ret;
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

	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	jsize dlen = (*env)->GetArrayLength(env, data);
	if (count > dlen) count = dlen;

	uint8_t *buf = (uint8_t *)malloc((size_t)count);
	if (!buf) {
		(*env)->ReleaseStringUTFChars(env, path, cpath);
		return -1;
	}
	(*env)->GetByteArrayRegion(env, data, 0, count, (jbyte *)buf);

	int64_t ret = dis_fatfs_write((dis_fatfs_handle_t)(intptr_t)volHandle, cpath, (int64_t)offset, buf, (int64_t)count);

	free(buf);
	(*env)->ReleaseStringUTFChars(env, path, cpath);
	return (jlong)ret;
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

/* ---------------- registration ---------------- */

static const JNINativeMethod methods[] = {
	NATIVE_METHOD(env, cls, "nativeHasBitLockerHeader", "(Ljava/lang/String;)Z", native_hasBitLockerHeader),
	NATIVE_METHOD(env, cls, "nativeOpenVolume", "(Ljava/lang/String;J[B)J", native_openVolume),
	NATIVE_METHOD(env, cls, "nativeOpenVolumeRecovery", "(Ljava/lang/String;JLjava/lang/String;)J", native_openVolumeRecovery),
	NATIVE_METHOD(env, cls, "nativeSessionInfo", "(J)[J", native_sessionInfo),
	NATIVE_METHOD(env, cls, "nativeGetVolumeGuid", "(J)Ljava/lang/String;", native_getVolumeGuid),
	NATIVE_METHOD(env, cls, "nativeRead", "(JJI)[B", native_read),
	NATIVE_METHOD(env, cls, "nativeWrite", "(JJ[BI)I", native_write),
	NATIVE_METHOD(env, cls, "nativeDecryptBuffer", "(J[BJ)[B", native_decryptBuffer),
	NATIVE_METHOD(env, cls, "nativeEncryptBuffer", "(J[BJ)[B", native_encryptBuffer),
	NATIVE_METHOD(env, cls, "nativeClose", "(J)V", native_close),
	NATIVE_METHOD(env, cls, "nativeGetLastError", "()Ljava/lang/String;", native_getLastError),

	/* NTFS-3G bridge */
	NATIVE_METHOD(env, cls, "nativeNtfsMount", "(JZ)J", native_ntfsMount),
	NATIVE_METHOD(env, cls, "nativeNtfsUmount", "(J)I", native_ntfsUmount),
	NATIVE_METHOD(env, cls, "nativeNtfsCreate", "(JLjava/lang/String;Ljava/lang/String;Z)J", native_ntfsCreate),
	NATIVE_METHOD(env, cls, "nativeNtfsDelete", "(JLjava/lang/String;)I", native_ntfsDelete),
	NATIVE_METHOD(env, cls, "nativeNtfsRename", "(JLjava/lang/String;Ljava/lang/String;)I", native_ntfsRename),
	NATIVE_METHOD(env, cls, "nativeNtfsWrite", "(JLjava/lang/String;J[BI)J", native_ntfsWrite),
	NATIVE_METHOD(env, cls, "nativeNtfsTruncate", "(JLjava/lang/String;J)J", native_ntfsTruncate),
	NATIVE_METHOD(env, cls, "nativeNtfsGetSpace", "(J)[J", native_ntfsGetSpace),

	/* FatFs bridge (FAT32 & exFAT) */
	NATIVE_METHOD(env, cls, "nativeFatfsMount", "(JZ)J", native_fatfsMount),
	NATIVE_METHOD(env, cls, "nativeFatfsUmount", "(J)I", native_fatfsUmount),
	NATIVE_METHOD(env, cls, "nativeFatfsCreate", "(JLjava/lang/String;Ljava/lang/String;Z)J", native_fatfsCreate),
	NATIVE_METHOD(env, cls, "nativeFatfsDelete", "(JLjava/lang/String;)I", native_fatfsDelete),
	NATIVE_METHOD(env, cls, "nativeFatfsRename", "(JLjava/lang/String;Ljava/lang/String;)I", native_fatfsRename),
	NATIVE_METHOD(env, cls, "nativeFatfsWrite", "(JLjava/lang/String;J[BI)J", native_fatfsWrite),
	NATIVE_METHOD(env, cls, "nativeFatfsTruncate", "(JLjava/lang/String;J)J", native_fatfsTruncate),
	NATIVE_METHOD(env, cls, "nativeFatfsGetSpace", "(J)[J", native_fatfsGetSpace),
};

jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
	JNIEnv *env = NULL;
	if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
		return JNI_ERR;

	jclass cls = (*env)->FindClass(env, "com/bitlockerdroid/util/NativeBridge");
	if (!cls)
		return JNI_ERR;

	if ((*env)->RegisterNatives(env, cls, methods,
		sizeof(methods) / sizeof(methods[0])) < 0)
		return JNI_ERR;

	return JNI_VERSION_1_6;
}
