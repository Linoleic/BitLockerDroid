#!/bin/bash
BT=/opt/android-sdk/build-tools/34.0.0
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
echo "=== Application in manifest ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep -B0 -A1 "android:name(0x01010000)" | head -4
echo "=== BitLockerApplication in dex ==="
"$BT/dexdump" "$APK" 2>/dev/null | grep -c "Lcom/bitlockerdroid/BitLockerApplication;"
echo "=== AppCompatActivity in dex ==="
"$BT/dexdump" "$APK" 2>/dev/null | grep -c "Landroidx/appcompat/app/AppCompatActivity;"
echo "=== launcher ==="
"$BT/aapt" dump badging "$APK" 2>&1 | grep launchable-activity
echo "=== APK size ==="
ls -la "$APK"
