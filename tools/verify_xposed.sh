#!/bin/bash
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
BT=/opt/android-sdk/build-tools/34.0.0
echo "=== xposed_init content ==="
unzip -p "$APK" assets/xposed_init
echo "=== xposed files in APK ==="
unzip -l "$APK" | grep -i xposed
echo "=== xposed metadata ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep -E "xposedmodule|xposedminversion|xposedscope"
echo "=== ModuleMain class in dex ==="
"$BT/dexdump" "$APK" 2>/dev/null | grep -c "Lcom/bitlockerdroid/ModuleMain;"
