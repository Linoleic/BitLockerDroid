#!/bin/bash
BT=/opt/android-sdk/build-tools/34.0.0
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
echo "=== theme styles dump ==="
"$BT/aapt" dump resources "$APK" 2>&1 | grep -A4 "Theme.BitLockerDroid"
echo ""
echo "=== all colors ==="
"$BT/aapt" dump resources "$APK" 2>&1 | grep -A3 "color/bitlocker"
echo ""
echo "=== check for resource errors ==="
"$BT/aapt" dump resources "$APK" 2>&1 | grep -iE "error|not found|warning" | head
echo "=== verify the theme resolves (aapt2 compile of themes) ==="
cd /tmp && rm -rf rescheck && mkdir rescheck && cd rescheck
unzip -q "$APK" res/* 2>/dev/null
ls res/ 2>/dev/null | head
