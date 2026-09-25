#include <errno.h>
#include <string.h>
#include <jni.h>

JNIEXPORT jint JNICALL
Java_com_github_mjdev_libaums_ErrNo_getErrnoNative(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return errno;
}

JNIEXPORT jstring JNICALL
Java_com_github_mjdev_libaums_ErrNo_getErrstrNative(JNIEnv *env, jobject thiz) {
    (void)thiz;
    char *error = strerror(errno);
    return (*env)->NewStringUTF(env, error);
}

JNIEXPORT jint JNICALL
Java_me_jahnen_libaums_core_ErrNo_getErrnoNative(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return errno;
}

JNIEXPORT jstring JNICALL
Java_me_jahnen_libaums_core_ErrNo_getErrstrNative(JNIEnv *env, jobject thiz) {
    (void)thiz;
    char *error = strerror(errno);
    return (*env)->NewStringUTF(env, error);
}
