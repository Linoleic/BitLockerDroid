#!/bin/bash
# Get the actual crash from the device via logcat.
# Usage: run this, then tap the app icon, then the logcat buffer will have it.
ADB=adb
echo "=== clearing logcat ==="
$ADB logcat -c 2>&1
echo "=== starting app (watch for crash) ==="
$ADB shell am start -n com.bitlockerdroid/.ui.BitLockerSettingsActivity 2>&1
sleep 4
echo "=== capturing crash from logcat (last 5s) ==="
$ADB logcat -d -v time 2>&1 | grep -iE "bitlocker|AndroidRuntime|FATAL|Process.*bitlockerdroid|Exception|caused by" | tail -50
echo "=== done ==="
