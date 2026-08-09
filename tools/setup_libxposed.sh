#!/bin/bash
set -e
cd /root/BitLockerDroid/app/src/main
echo "=== cleanup old ==="
rm -rf META-INF
mkdir -p resources/META-INF/xposed
echo "=== java_init.list ==="
echo "com.bitlockerdroid.ModuleMain" > resources/META-INF/xposed/java_init.list
echo "=== scope.list ==="
printf "system\ncom.android.settings\n" > resources/META-INF/xposed/scope.list
echo "=== module.prop ==="
printf 'minApiVersion=101\ntargetApiVersion=102\nstaticScope=true\nautoHotReload=true\n' > resources/META-INF/xposed/module.prop
echo "=== verify ==="
find resources -type f | sort
for f in resources/META-INF/xposed/*; do
  echo "--- $f ---"
  cat "$f"
done
