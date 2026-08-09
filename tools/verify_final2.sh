#!/bin/bash
BT=/opt/android-sdk/build-tools/34.0.0
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
echo "=== launchable ==="
"$BT/aapt" dump badging "$APK" 2>&1 | grep launchable-activity
echo "=== key classes in dex ==="
for cls in "ui/BitLockerSettingsActivity" "BitLockerApplication" "util/LogFile"; do
  n=$("$BT/dexdump" "$APK" 2>/dev/null | grep -c "Lcom/bitlockerdroid/$cls;")
  echo "$cls: $n"
done
echo "=== extractNativeLibs ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep extractNativeLibs
