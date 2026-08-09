#!/bin/bash
# Precise crash diagnosis on MIUI/HyperOS.
# Usage: adb push this somewhere + bash, or run commands manually.
ADB=adb

echo "=== step 1: clear log + kill app ==="
$ADB logcat -c
$ADB shell am force-stop com.bitlockerdroid
sleep 1

echo "=== step 2: launch app ==="
$ADB shell am start -W -n com.bitlockerdroid/.ui.BitLockerSettingsActivity 2>&1

echo ""
echo "=== step 3: native crash tombstones? ==="
$ADB logcat -d -v time 2>&1 | grep -iE "tombstone|DEBUG|SIGSEGV|signal|crash_dump|Fatal signal|backtrace|dlopen|dl_error|Linker|dlnotfound|cannot locate" | head -40

echo ""
echo "=== step 4: app process death ==="
$ADB logcat -d -v time 2>&1 | grep -iE "Force finishing|has died|Process.*bitlockerdroid|am_crash|am_proc_died|am_kill|ActivityManager.*bitlockerdroid" | head -30

echo ""
echo "=== step 5: any AndroidRuntime ==="
$ADB logcat -d -v time 2>&1 | grep -iE "AndroidRuntime|FATAL EXCEPTION" | head -40

echo "=== done ==="
