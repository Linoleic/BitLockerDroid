#!/bin/bash
# Install Android platform-34, build-tools-34, platform-tools via sdkmanager
set -euo pipefail

export HTTPS_PROXY=http://127.0.0.1:7897
export HTTP_PROXY=http://127.0.0.1:7897
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

echo "=== accepting licenses ==="
yes | "$SDKM" --licenses >/dev/null 2>&1 || true

echo "=== installing packages ==="
yes | "$SDKM" \
  'platform-tools' \
  'platforms;android-34' \
  'build-tools;34.0.0' 2>&1 | grep -vE '^\[' | tail -20

echo "=== verify ==="
echo "platforms:"; ls "$ANDROID_HOME/platforms/" 2>&1
echo "build-tools:"; ls "$ANDROID_HOME/build-tools/" 2>&1
echo "platform-tools:"; ls "$ANDROID_HOME/platform-tools/" 2>&1 | head -5
echo "ALL PACKAGES INSTALLED"
