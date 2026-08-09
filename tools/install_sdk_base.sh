#!/bin/bash
# Install Android SDK command-line tools + platform-34 + build-tools-34
set -euo pipefail

export HTTPS_PROXY=http://127.0.0.1:7897
export HTTP_PROXY=http://127.0.0.1:7897
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

echo "=== install layout ==="
mkdir -p "$ANDROID_HOME/cmdline-tools"
ls -la "$ANDROID_HOME" 2>&1 || true

echo "=== find existing cmdline-tools zip or extracted ==="
ls -la /tmp/cmdline-tools.zip 2>&1 || true
ls -la /tmp/cmdline-tools 2>&1 || true

# Extract if the zip is available and latest/ not yet placed
if [ -f /tmp/cmdline-tools.zip ] && [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
    echo "=== extracting ==="
    rm -rf /tmp/clt
    mkdir -p /tmp/clt
    unzip -q /tmp/cmdline-tools.zip -d /tmp/clt
    ls /tmp/clt/
    mv /tmp/clt/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
fi

echo "=== verify sdkmanager ==="
ls -la "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 2>&1 || echo "NOT FOUND"
echo "DONE"
