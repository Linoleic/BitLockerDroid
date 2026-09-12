/* verify_encrypt.c -- host verification: test sector re-encryption,
 * round-trip decrypt matching, and write barrier protection.
 *
 * Usage:
 *   verify_encrypt <image> <password>
 */
#define _GNU_SOURCE 1
#include "dislocker/dislocker.h"
#include "dislocker/dislocker_priv.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <assert.h>

void host_set_image(const char* path);

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <image> <password>\n", argv[0]);
        return 2;
    }
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

    uint16_t sector_size = info.sector_size;
    uint8_t *plaintext1 = malloc(sector_size);
    uint8_t *ciphertext = malloc(sector_size);
    uint8_t *plaintext2 = malloc(sector_size);

    // 1. Read decrypted sector at data_offset
    int n = dis_read_decrypted(ctx, plaintext1, info.data_offset, sector_size);
    if (n != sector_size) {
        printf("read_decrypted failed: %s\n", dis_get_last_error());
        return 1;
    }
    printf("1. read_decrypted OK (%d bytes)\n", n);

    // 2. Encrypt the plaintext sector at data_offset
    int enc_ret = dis_encrypt_region(ctx, plaintext1, ciphertext, (off_t)info.data_offset, sector_size);
    if (enc_ret != sector_size) {
        printf("dis_encrypt_region failed: %s\n", dis_get_last_error());
        return 1;
    }
    printf("2. dis_encrypt_region OK (%d bytes)\n", enc_ret);

    // 3. Decrypt the ciphertext back
    int dec_ret = dis_decrypt_region(ctx, ciphertext, plaintext2, (off_t)info.data_offset, sector_size);
    if (dec_ret != sector_size) {
        printf("dis_decrypt_region failed: %s\n", dis_get_last_error());
        return 1;
    }
    printf("3. dis_decrypt_region OK (%d bytes)\n", dec_ret);

    // 4. Verify round-trip match
    if (memcmp(plaintext1, plaintext2, sector_size) == 0) {
        printf("4. ROUND-TRIP TEST PASSED: Decrypted ciphertext exactly matches original plaintext!\n");
    } else {
        printf("4. ROUND-TRIP TEST FAILED: Data mismatch!\n");
        return 1;
    }

    // 5. Test Write Barrier
    // Attempting to encrypt at offset 0 (protected metadata area) must be blocked!
    int barrier_ret = dis_encrypt_region(ctx, plaintext1, ciphertext, 0, sector_size);
    if (barrier_ret < 0) {
        printf("5. WRITE BARRIER TEST PASSED: Blocked write to offset 0 (protected area): %s\n",
            dis_get_last_error());
    } else {
        printf("5. WRITE BARRIER TEST FAILED: Permitted write to offset 0!\n");
        return 1;
    }

    free(plaintext1);
    free(ciphertext);
    free(plaintext2);
    dis_close_volume(ctx);

    printf("ALL TESTS PASSED!\n");
    return 0;
}
