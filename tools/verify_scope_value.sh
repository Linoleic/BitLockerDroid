#!/bin/bash
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
BT=/opt/android-sdk/build-tools/34.0.0
echo "=== xposedscope value form ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep -A1 "xposedscope"
echo "=== APK size ==="
ls -la "$APK"
