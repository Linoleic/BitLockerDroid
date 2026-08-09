#!/bin/bash
# Download Gradle 8.4, set up local.properties, generate wrapper
set -euo pipefail

export HTTPS_PROXY=http://127.0.0.1:7897
export HTTP_PROXY=http://127.0.0.1:7897
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

cd /root/BitLockerDroid

echo "=== 1. download gradle 8.4 ==="
curl -sL --max-time 180 -o /tmp/gradle-8.4-bin.zip \
  'https://services.gradle.org/distributions/gradle-8.4-bin.zip' \
  -w 'http=%{http_code} size=%{size_download}\n'
ls -la /tmp/gradle-8.4-bin.zip

echo "=== 2. install gradle ==="
rm -rf /opt/gradle
mkdir -p /opt/gradle
unzip -q /tmp/gradle-8.4-bin.zip -d /opt/gradle
ls /opt/gradle/gradle-8.4/bin/gradle
/opt/gradle/gradle-8.4/bin/gradle --version 2>&1 | head -6

echo "=== 3. write local.properties ==="
cat > local.properties << 'EOF'
sdk.dir=/opt/android-sdk
ndk.dir=/opt/android-sdk/ndk/26.3.11579264
EOF
cat local.properties

echo "=== 4. generate wrapper ==="
/opt/gradle/gradle-8.4/bin/gradle wrapper --gradle-version 8.4 2>&1 | tail -8

echo "=== 5. verify wrapper ==="
ls -la gradlew gradle/wrapper/ 2>&1
echo "SETUP DONE"
