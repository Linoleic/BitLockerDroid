#!/bin/bash
# setup_ntfs3g.sh -- Sets up trimmed libntfs-3g in native/third_party/libntfs-3g
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="$DIR/native/third_party/libntfs-3g"

echo "=== Target Directory: $TARGET_DIR ==="
mkdir -p "$TARGET_DIR"

TMP_DIR="/tmp/ntfs3g_setup"
rm -rf "$TMP_DIR"
mkdir -p "$TMP_DIR"

echo "=== Downloading ntfs-3g source archive ==="
TARBALL_URL="https://github.com/tuxera/ntfs-3g/archive/refs/tags/2022.10.3.tar.gz"
BACKUP_URL="https://tuxera.com/opensource/ntfs-3g_ntfsprogs-2022.10.3.tgz"

DOWNLOADED=0
if command -v curl >/dev/null 2>&1; then
    echo "Trying curl -k..."
    if curl -k -L "$TARBALL_URL" -o "$TMP_DIR/ntfs3g.tar.gz" 2>/dev/null && [ -s "$TMP_DIR/ntfs3g.tar.gz" ]; then
        DOWNLOADED=1
    elif curl -k -L "$BACKUP_URL" -o "$TMP_DIR/ntfs3g.tar.gz" 2>/dev/null && [ -s "$TMP_DIR/ntfs3g.tar.gz" ]; then
        DOWNLOADED=1
    fi
fi

if [ "$DOWNLOADED" -eq 0 ] && command -v wget >/dev/null 2>&1; then
    echo "Trying wget --no-check-certificate..."
    if wget --no-check-certificate -O "$TMP_DIR/ntfs3g.tar.gz" "$TARBALL_URL" 2>/dev/null && [ -s "$TMP_DIR/ntfs3g.tar.gz" ]; then
        DOWNLOADED=1
    elif wget --no-check-certificate -O "$TMP_DIR/ntfs3g.tar.gz" "$BACKUP_URL" 2>/dev/null && [ -s "$TMP_DIR/ntfs3g.tar.gz" ]; then
        DOWNLOADED=1
    fi
fi

if [ "$DOWNLOADED" -eq 0 ]; then
    echo "Falling back to git clone (with sslVerify=false)..."
    git -c http.sslVerify=false clone --depth 1 https://github.com/tuxera/ntfs-3g.git "$TMP_DIR/ntfs-3g" || \
    git -c http.sslVerify=false clone --depth 1 https://gitcode.com/gh_mirrors/nt/ntfs-3g.git "$TMP_DIR/ntfs-3g"
fi

if [ -f "$TMP_DIR/ntfs3g.tar.gz" ]; then
    echo "=== Extracting archive ==="
    tar -xzf "$TMP_DIR/ntfs3g.tar.gz" -C "$TMP_DIR"
    EXTRACTED_SRC=$(find "$TMP_DIR" -maxdepth 1 -type d -name "ntfs-3g*" | head -n 1)
else
    EXTRACTED_SRC="$TMP_DIR/ntfs-3g"
fi

echo "=== Copying core library sources and headers ==="
rm -rf "$TARGET_DIR/src" "$TARGET_DIR/include"
mkdir -p "$TARGET_DIR/src"
mkdir -p "$TARGET_DIR/include/ntfs-3g"

# Copy libntfs-3g .c and .h files (excluding FUSE wrapper)
cp "$EXTRACTED_SRC"/libntfs-3g/*.c "$TARGET_DIR/src/"
cp "$EXTRACTED_SRC"/include/ntfs-3g/*.h "$TARGET_DIR/include/ntfs-3g/"

# Generate Android/Linux compatible config.h
cat << 'EOF' > "$TARGET_DIR/include/config.h"
#ifndef _NTFS_CONFIG_H
#define _NTFS_CONFIG_H

#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_UNISTD_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDINT_H 1
#define HAVE_TIME_H 1
#define HAVE_LIMITS_H 1
#define HAVE_WCHAR_H 1
#define HAVE_PTHREAD_H 1

#define WORDS_LITTLEENDIAN 1
#define _FILE_OFFSET_BITS 64
#define _GNU_SOURCE 1
#define PACKAGE_VERSION "2022.10.3"

#endif /* _NTFS_CONFIG_H */
EOF

echo "=== Cleaning up ==="
rm -rf "$TMP_DIR"

echo "=== libntfs-3g setup completed successfully ==="
ls -la "$TARGET_DIR/src" | head -n 10
ls -la "$TARGET_DIR/include/ntfs-3g" | head -n 10
