#!/bin/bash
BT=/opt/android-sdk/build-tools/34.0.0
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
echo "=== extractNativeLibs now 1 (extract) ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep "extractNativeLibs"
echo "=== so files compressed or stored? ==="
unzip -lv "$APK" | grep "libdislocker.so"
echo "=== APK size ==="
ls -la "$APK"
