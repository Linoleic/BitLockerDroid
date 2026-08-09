#!/bin/bash
# Diagnose why LSPosed isn't injecting the module into system_server.
ADB=adb
echo "=== 1. LSPosed module load logs ==="
$ADB logcat -d -v time 2>&1 | grep -iE "LSPosed|XposedBridge|XposedModules|xposed.*bitlocker|bitlockerdroid.*xposed" | tail -40
echo ""
echo "=== 2. Is the module recognized? (package manager) ==="
$ADB shell pm list packages | grep bitlocker
echo ""
echo "=== 3. LSPosed manager data (scope state) ==="
$ADB shell "su -c 'ls /data/adb/lspd/ 2>/dev/null; ls /data/adb/modules/ 2>/dev/null'"
echo ""
echo "=== 4. Is system_server running with our classes loaded? ==="
$ADB shell "su -c 'cat /proc/*/maps 2>/dev/null | grep -i bitlockerdroid | head'"
echo "=== done ==="
