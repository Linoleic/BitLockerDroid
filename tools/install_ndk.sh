#!/bin/bash
# Install NDK r26d via sdkmanager
set -euo pipefail

export HTTPS_PROXY=http://127.0.0.1:7897
export HTTP_PROXY=http://127.0.0.1:7897
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

echo "=== installing NDK r26d ==="
yes | "$SDKM" 'ndk;26.3.11579264' 2>&1 | grep -oE '\[.*%\]|(Downloading|Unzipping|Installing)[^ ]*|NDK is at|done|error.*' | tail -20

echo "=== verify ==="
ls "$ANDROID_HOME/ndk/" 2>&1
echo "NDK INSTALLED"
