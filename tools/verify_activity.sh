#!/bin/bash
BT=/opt/android-sdk/build-tools/34.0.0
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
echo "=== aapt2 exists? ==="
ls -la "$BT/aapt2" 2>&1
echo "=== AppCompatActivity refs in dex ==="
"$BT/dexdump" "$APK" 2>/dev/null | grep -c "Landroidx/appcompat/app/AppCompatActivity;"
echo "=== manifest theme ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep -B1 -A1 "theme"
echo "=== classes.dex sizes ==="
unzip -l "$APK" | grep classes
