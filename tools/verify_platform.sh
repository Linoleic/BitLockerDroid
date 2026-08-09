#!/bin/bash
BT=/opt/android-sdk/build-tools/34.0.0
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
echo "=== theme now platform-based ==="
"$BT/aapt" dump resources "$APK" 2>&1 | grep -A2 "style/Theme.BitLockerDroid " | head -4
echo ""
echo "=== crash handler class present ==="
"$BT/dexdump" "$APK" 2>/dev/null | grep -c "Lcom/bitlockerdroid/BitLockerApplication;"
echo "=== app component factory ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep "appComponentFactory"
echo "=== launcher ==="
"$BT/aapt" dump badging "$APK" 2>&1 | grep launchable-activity
