#!/bin/bash
BT=/opt/android-sdk/build-tools/34.0.0
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
echo "=== full application node ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | sed -n '/E: application/,/E: activity/p' | head -25
echo ""
echo "=== badging name ==="
"$BT/aapt" dump badging "$APK" 2>&1 | grep -E "application:|launchable|package:"
