#!/bin/bash
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
BT=/opt/android-sdk/build-tools/34.0.0

echo "=== 1. xposedscope meta-data (resource form) ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep -A1 "xposedscope"

echo ""
echo "=== 2. xposed_scope array resource ==="
"$BT/aapt" dump resources "$APK" 2>&1 | grep -A6 "array/xposed_scope" | head -8

echo ""
echo "=== 3. ModuleMain in dex ==="
"$BT/dexdump" "$APK" 2>/dev/null | grep -c "Lcom/bitlockerdroid/ModuleMain;"

echo "=== 4. LogFile in dex ==="
"$BT/dexdump" "$APK" 2>/dev/null | grep -c "Lcom/bitlockerdroid/util/LogFile;"

echo "=== 5. xposed_init ==="
unzip -p "$APK" assets/xposed_init

echo "=== 6. all xposed metadata ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep -B0 -A1 'xposed'
