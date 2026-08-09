#!/bin/bash
NDK=/opt/android-sdk/ndk/26.3.11579264
echo "=== NDK size ==="
du -sh "$NDK"
echo "=== toolchains ==="
ls "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/" | grep -E 'clang$|aarch64-linux-android|armv7a-linux-androideabi' | head -8
echo "=== cmake bundled in NDK ==="
ls "$NDK/cmake/" 2>&1 | head
echo "=== check build.gradle ndkVersion ==="
grep -n ndkVersion /root/BitLockerDroid/app/build.gradle.kts || echo "not set (defaults to AGP default)"
