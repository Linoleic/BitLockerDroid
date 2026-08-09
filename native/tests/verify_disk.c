/* verify_disk.c -- host verification: unlock an image with a password and
 * read+decrypt the NTFS boot sector at boot_backup. Usage:
 *   verify_disk <image> <password>
 * Builds on dislocker_core + mbedtls + host_read.c (file-backed dis_blk_read).
 */
#define _GNU_SOURCE 1
#include "dislocker/dislocker.h"
#include "dislocker/dislocker_priv.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

void host_set_image(const char* path);

static void hexdump16(const uint8_t* b, size_t n) {
    for (size_t i = 0; i < n; i++) printf("%02x", b[i]);
    printf("\n");
}

int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s <image> <password>\n", argv[0]); return 2; }
    host_set_image(argv[1]);

    dis_session_info_t info;
    dis_ctx_t* ctx = dis_open_volume(argv[1], 0, (const uint8_t*)argv[2], strlen(argv[2]), &info);
    if (!ctx) {
        printf("OPEN FAILED: %s\n", dis_get_last_error());
        return 1;
    }
    printf("OPEN OK: sector=%u alg=0x%04x fvek_len=%d data_offset=0x%llx\n",
        info.sector_size, info.algorithm, info.fvek_len,
        (unsigned long long)info.data_offset);

    // Read the decrypted boot sector at data_offset (boot_backup).
    uint8_t boot[512];
    int n = dis_read_decrypted(ctx, boot, info.data_offset, 512);
    printf("read_decrypted(%d) = %d\n", (int)info.data_offset, n);
    if (n > 0) {
        printf("decrypted[0:32]: "); hexdump16(boot, 32);
        if (memcmp(boot+3, "NTFS    ", 8) == 0)
            printf("RESULT: NTFS boot sector OK\n");
        else
            printf("RESULT: not NTFS (expected NTFS    at +3)\n");
    } else {
        printf("read failed: %s\n", dis_get_last_error());
    }
    dis_close_volume(ctx);
    return 0;
}
