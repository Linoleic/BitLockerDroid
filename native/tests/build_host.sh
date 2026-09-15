#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
MB=third_party/mbedtls
DL=dislocker
gcc -O2 -o /tmp/verify_disk tests/verify_disk.c tests/host_read.c \
  -I$DL/include -I$MB/include \
  $DL/src/dislocker.c $DL/src/metadata/metadata.c $DL/src/encryption/aes_xts.c \
  $DL/src/encryption/vmkey.c $DL/src/access/access.c $DL/src/crypto/crypto_mbedtls.c \
  $MB/library/aes.c $MB/library/sha256.c $MB/library/platform.c \
  $MB/library/platform_util.c $MB/library/error.c

gcc -O2 -o /tmp/verify_encrypt tests/verify_encrypt.c tests/host_read.c \
  -I$DL/include -I$MB/include \
  $DL/src/dislocker.c $DL/src/metadata/metadata.c $DL/src/encryption/aes_xts.c \
  $DL/src/encryption/vmkey.c $DL/src/access/access.c $DL/src/crypto/crypto_mbedtls.c \
  $MB/library/aes.c $MB/library/sha256.c $MB/library/platform.c \
  $MB/library/platform_util.c $MB/library/error.c

echo "BUILD OK (/tmp/verify_disk and /tmp/verify_encrypt)"

