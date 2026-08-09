#!/bin/bash
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
BT=/opt/android-sdk/build-tools/34.0.0

echo "=== FINAL APK integrity check ==="
echo "1. ModuleMain class in dex:"
"$BT/dexdump" "$APK" 2>/dev/null | grep -c "Lcom/bitlockerdroid/ModuleMain;"
echo "2. xposed_init:"
unzip -p "$APK" assets/xposed_init
echo "3. native libs:"
unzip -l "$APK" | grep libdislocker.so
echo "4. xposed metadata entries:"
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep -cE 'xposedmodule|xposedminversion|xposedscope|xposeddescription'
echo "   (expected 4)"
echo "5. apk size:"
ls -la "$APK"
