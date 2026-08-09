#!/bin/bash
BT=/opt/android-sdk/build-tools/34.0.0
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
echo "=== provider permission ==="
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1 | sed -n '/BitLockerDocumentsProvider/,/meta-data/p' | grep -E "permission|android:name|authorit|exported"
echo ""
echo "=== done ==="
