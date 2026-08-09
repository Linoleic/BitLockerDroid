#!/bin/bash
set -euo pipefail
export HTTPS_PROXY=http://127.0.0.1:7897
export HTTP_PROXY=http://127.0.0.1:7897
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
echo "=== installing cmake 3.22.1 + ninja ==="
yes | "$SDKM" 'cmake;3.22.1' 2>&1 | grep -oE '(Done|Installing|Downloading|error|Warning|unzip|\[.*%\]).*' | tail -8
echo "=== verify ==="
ls "$ANDROID_HOME/cmake/" 2>&1
ls "$ANDROID_HOME/cmake/3.22.1/bin/" 2>&1 | head
echo "CMAKE INSTALLED"
