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
#include <android/log.h>

#include "dislocker/dislocker.h"
#include "dislocker/dislocker_priv.h"

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
	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	if (!cpath)
		return 0;

	jsize plen = (*env)->GetArrayLength(env, password);
	uint8_t *pbuf = (uint8_t *)malloc((size_t)plen + 1);
	(*env)->GetByteArrayRegion(env, password, 0, plen, (jbyte *)pbuf);
	pbuf[plen] = 0;

	dis_session_info_t info;
	dis_ctx_t *ctx = dis_open_volume(cpath, (off_t)offset, pbuf, (size_t)plen, &info);

	free(pbuf);
	(*env)->ReleaseStringUTFChars(env, path, cpath);

	if (!ctx)
		LOGE("openVolume failed: %s", dis_get_last_error());

	return jptr(ctx);
}

static jlong native_openVolumeRecovery(JNIEnv *env, jobject thiz,
	jstring path, jlong offset, jstring recoveryKey)
{
	const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
	const char *ckey  = (*env)->GetStringUTFChars(env, recoveryKey, NULL);
	if (!cpath || !ckey)
		return 0;

	dis_session_info_t info;
	dis_ctx_t *ctx = dis_open_volume_recovery(cpath, (off_t)offset,
		(const uint8_t *)ckey, strlen(ckey), &info);

	(*env)->ReleaseStringUTFChars(env, recoveryKey, ckey);
	(*env)->ReleaseStringUTFChars(env, path, cpath);

	if (!ctx)
		LOGE("openVolumeRecovery failed: %s", dis_get_last_error());

	return jptr(ctx);
}

/* returns long[] {sectorSize, volumeSize, algorithm, fvekLen, dataOffset} */
static jlongArray native_sessionInfo(JNIEnv *env, jobject thiz, jlong handle)
{
	dis_ctx_t *ctx = ptrj(handle);
	if (!ctx) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
			"invalid session handle");
		return NULL;
	}

	jlong vals[5];
	vals[0] = dis_sector_size(ctx);
	vals[1] = (jlong)dis_volume_size(ctx);
	vals[2] = dis_algorithm(ctx);
	vals[3] = dis_fvek_len(ctx);
	/* data offset = boot_backup (physical start of encrypted data) */
	vals[4] = (jlong)(ctx->information ? (int64_t)ctx->information->boot_sectors_backup : 0);

	jlongArray out = (*env)->NewLongArray(env, 5);
	if (!out)
		return NULL;
	(*env)->SetLongArrayRegion(env, out, 0, 5, vals);
	return out;
}

static jbyteArray native_read(JNIEnv *env, jobject thiz, jlong handle,
	jlong offset, jint size)
{
	dis_ctx_t *ctx = ptrj(handle);
	if (!ctx || size <= 0) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
			"invalid handle or size");
		return NULL;
	}

	uint8_t *buf = (uint8_t *)malloc((size_t)size);
	if (!buf)
		return NULL;

	int ret = dis_read_decrypted(ctx, buf, (off_t)offset, (size_t)size);
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

static void native_close(JNIEnv *env, jobject thiz, jlong handle)
{
	dis_close_volume(ptrj(handle));
}

/* Decrypts an already-read encrypted buffer at a sector-aligned offset.
 * input: the encrypted bytes; offset: volume-relative byte offset.
 * Returns the decrypted byte[] or null on failure. */
static jbyteArray native_decryptBuffer(JNIEnv *env, jobject thiz, jlong handle,
	jbyteArray input, jlong offset)
{
	dis_ctx_t *ctx = ptrj(handle);
	if (!ctx || input == NULL) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
			"invalid handle or input");
		return NULL;
	}

	jsize len = (*env)->GetArrayLength(env, input);
	if (len <= 0 || (size_t)len % ctx->sector_size != 0)
		return NULL;

	uint8_t *in = (uint8_t *)malloc((size_t)len);
	uint8_t *out = (uint8_t *)malloc((size_t)len);
	if (!in || !out) {
		free(in); free(out);
		return NULL;
	}
	(*env)->GetByteArrayRegion(env, input, 0, len, (jbyte *)in);

	int ret = dis_decrypt_region(ctx, in, out, (off_t)offset, (size_t)len);

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

static jstring native_getLastError(JNIEnv *env, jobject thiz)
{
	return (*env)->NewStringUTF(env, dis_get_last_error());
}

/* ---------------- registration ---------------- */

static const JNINativeMethod methods[] = {
	NATIVE_METHOD(env, cls, "nativeHasBitLockerHeader", "(Ljava/lang/String;)Z", native_hasBitLockerHeader),
	NATIVE_METHOD(env, cls, "nativeOpenVolume", "(Ljava/lang/String;J[B)J", native_openVolume),
	NATIVE_METHOD(env, cls, "nativeOpenVolumeRecovery", "(Ljava/lang/String;JLjava/lang/String;)J", native_openVolumeRecovery),
	NATIVE_METHOD(env, cls, "nativeSessionInfo", "(J)[J", native_sessionInfo),
	NATIVE_METHOD(env, cls, "nativeRead", "(JJI)[B", native_read),
	NATIVE_METHOD(env, cls, "nativeDecryptBuffer", "(J[BJ)[B", native_decryptBuffer),
	NATIVE_METHOD(env, cls, "nativeClose", "(J)V", native_close),
	NATIVE_METHOD(env, cls, "nativeGetLastError", "()Ljava/lang/String;", native_getLastError),
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
