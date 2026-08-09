#!/bin/bash
BT=/opt/android-sdk/build-tools/34.0.0
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
echo "=== package ==="
"$BT/aapt" dump badging "$APK" 2>&1 | grep "^package:"
echo "=== launchable activity ==="
"$BT/aapt" dump badging "$APK" 2>&1 | grep "launchable-activity"
echo "=== settings activity exported ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | grep -B2 -A6 "BitLockerSettingsActivity"
