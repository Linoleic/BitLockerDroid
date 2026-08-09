#!/bin/bash
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
BT=/opt/android-sdk/build-tools/34.0.0
echo "=== xposedscope in APK manifest ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep -A1 "xposedscope"
echo ""
echo "=== all xposed metadata ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep -B0 -A1 'xposed'
echo ""
echo "=== APK size ==="
ls -la "$APK"
