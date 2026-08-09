#!/bin/bash
APK=/root/BitLockerDroid/app/build/outputs/apk/debug/app-debug.apk
BT=/opt/android-sdk/build-tools/34.0.0
echo "=== META-INF/xposed files in APK ==="
unzip -l "$APK" | grep -i "META-INF/xposed"
echo ""
echo "=== java_init.list content ==="
unzip -p "$APK" META-INF/xposed/java_init.list 2>/dev/null
echo "=== scope.list content ==="
unzip -p "$APK" META-INF/xposed/scope.list 2>/dev/null
echo "=== module.prop content ==="
unzip -p "$APK" META-INF/xposed/module.prop 2>/dev/null
echo ""
echo "=== ModuleMain class in dex ==="
"$BT/dexdump" "$APK" 2>/dev/null | grep -c "Lcom/bitlockerdroid/ModuleMain;"
echo "=== XposedModule parent in dex ==="
"$BT/dexdump" "$APK" 2>/dev/null | grep -A1 "Class descriptor.*ModuleMain;" | grep -i "super\|extends\|XposedModule" | head -3
echo "=== old assets/xposed_init (should be gone) ==="
unzip -l "$APK" | grep -i "xposed_init" || echo "  (none, correct)"
echo ""
echo "=== APK size ==="
ls -la "$APK"
