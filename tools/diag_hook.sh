#!/bin/bash
# Diagnose system_server hook state. Uses the first available device.
ADB="adb"
if [ -n "$ANDROID_SERIAL" ]; then
  ADB="adb -s $ANDROID_SERIAL"
elif [ "$(adb devices | grep -c 'device$')" -gt 1 ]; then
  SERIAL=$(adb devices | grep 'device$' | head -1 | awk '{print $1}')
  ADB="adb -s $SERIAL"
  echo "Multiple devices, using: $SERIAL"
fi

echo "=== 0. devices ==="
adb devices 2>&1 | grep -v "^List" | grep -v "^$"

echo ""
echo "=== 1. hook load status (BitLocker/UsbStorage) ==="
$ADB logcat -d -v time 2>&1 | grep -iE "BitLockerLog|UsbStorageHook|handleLoadPackage|hook complete|hooked |DETECTED" | tail -30

echo ""
echo "=== 2. scan signature reads ==="
$ADB logcat -d -v time 2>&1 | grep -iE "scan .*signature" | tail -20

echo ""
echo "=== 3. /dev/block nodes ==="
$ADB shell "ls /dev/block/ 2>/dev/null" 2>&1 | head -40

echo ""
echo "=== 4. /dev/block/vold nodes ==="
$ADB shell "ls /dev/block/vold/ 2>/dev/null" 2>&1

echo ""
echo "=== 5. by-name sample ==="
$ADB shell "ls /dev/block/by-name/ 2>/dev/null | head -10" 2>&1
echo "=== done ==="
