#include <jni.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <errno.h>
#include <string.h>
#include <android/log.h>

#define TAG "native_usb_ioctl"

static jboolean do_reset(int fd) {
    int ret = ioctl(fd, USBDEVFS_RESET);
    if (ret < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "ioctl USBDEVFS_RESET error %d, %s", errno, strerror(errno));
    }
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

static jboolean do_clear_halt(int fd, int endpoint) {
    int ret = ioctl(fd, USBDEVFS_CLEAR_HALT, &endpoint);
    if (ret < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "ioctl USBDEVFS_CLEAR_HALT error %d, %s", errno, strerror(errno));
    }
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_me_jahnen_libaums_usb_AndroidUsbCommunication_resetUsbDeviceNative(JNIEnv *env, jobject thiz, jint fd) {
    (void)env;
    (void)thiz;
    return do_reset(fd);
}

JNIEXPORT jboolean JNICALL
Java_me_jahnen_libaums_usb_AndroidUsbCommunication_clearHaltNative(JNIEnv *env, jobject thiz, jint fd, jint endpoint) {
    (void)env;
    (void)thiz;
    return do_clear_halt(fd, endpoint);
}

JNIEXPORT jboolean JNICALL
Java_me_jahnen_libaums_core_usb_AndroidUsbCommunication_resetUsbDeviceNative(JNIEnv *env, jobject thiz, jint fd) {
    (void)env;
    (void)thiz;
    return do_reset(fd);
}

JNIEXPORT jboolean JNICALL
Java_me_jahnen_libaums_core_usb_AndroidUsbCommunication_clearHaltNative(JNIEnv *env, jobject thiz, jint fd, jint endpoint) {
    (void)env;
    (void)thiz;
    return do_clear_halt(fd, endpoint);
}
