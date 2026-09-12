/*
 * vmkey.c -- VMK/FVEK key retrieval for BitLocker volumes.
 *
 * Ported from dislocker v0.7.3 (src/accesses/user_pass/user_pass.c,
 * src/accesses/stretch_key.c, src/metadata/vmk.c, src/metadata/fvek.c,
 * src/encryption/decrypt.c, src/metadata/datums.c), GPL-2.0.
 *
 * Key chain:  user password -> SHA256(SHA256(UTF-16(password)))
 *             -> stretch (chain hash, 0x100000 rounds)
 *             -> AES-CCM unwrap of the VMK (nested AES_CCM datum, salt from
 *                the stretch-key datum)
 *             -> parse the decrypted VMK datum -> nested FVEK AES_CCM datum
 *             -> AES-CCM unwrap with the VMK -> FVEK (64 bytes for AES-XTS-256)
 */
#include "dislocker/dislocker_priv.h"

#ifdef __ANDROID__
#include <android/log.h>
#define DLOG(...) __android_log_print(ANDROID_LOG_ERROR, "BitLockerNative", __VA_ARGS__)
#else
#define DLOG(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

#include <stdlib.h>
#include <stddef.h>
#include <string.h>

/* header size per value type, from dislocker datums.c datum_value_types_prop */
const uint16_t datum_value_header_size[22] = {
	8,     /* 0x00 ERASED */
	0xc,   /* 0x01 KEY */
	8,     /* 0x02 UNICODE */
	0x1c,  /* 0x03 STRETCH */
	0xc,   /* 0x04 USE KEY */
	0x24,  /* 0x05 AES CCM */
	0xc,   /* 0x06 TPM ENCODED */
	8,     /* 0x07 VALIDATION */
	0x24,  /* 0x08 VMK */
	0x20,  /* 0x09 EXTERNAL KEY */
	0x2c,  /* 0x0a UPDATE */
	0x34,  /* 0x0b ERROR */
	8,     /* 0x0c ASYM ENC */
	8,     /* 0x0d EXPORTED KEY */
	8,     /* 0x0e PUBLIC KEY */
	0x18,  /* 0x0f VIRTUALIZATION INFO */
	0xc,   /* 0x10 SIMPLE */
	0xc,   /* 0x11 SIMPLE */
	0x1c,  /* 0x12 CONCAT HASH KEY */
	0xc,   /* 0x13 SIMPLE */
	8,     /* padding */
	8
};

/* ---------------- chain hash (stretch_key.c) ---------------- */

#define SHA256_DIGEST_LENGTH 32
#define SALT_LENGTH          16

typedef struct {
	uint8_t updated_hash[SHA256_DIGEST_LENGTH];
	uint8_t password_hash[SHA256_DIGEST_LENGTH];
	uint8_t salt[SALT_LENGTH];
	uint64_t hash_count;
} bitlocker_chain_hash_t;

static void stretch_key(bitlocker_chain_hash_t *ch, uint8_t *result)
{
	for (uint64_t loop = 0; loop < 0x100000; ++loop) {
		sha256((uint8_t *)ch, sizeof(bitlocker_chain_hash_t), ch->updated_hash);
		ch->hash_count++;
	}
	memcpy(result, ch->updated_hash, SHA256_DIGEST_LENGTH);
}

/* ---------------- AES-CCM key unwrap (decrypt.c) ---------------- */

#define AUTHENTICATOR_LENGTH 16

static int aes_ccm_decrypt(aes_ctx_t *ctx,
	unsigned char *nonce, unsigned char nonce_length,
	unsigned char *input, unsigned int input_length,
	unsigned char *mac, unsigned int mac_length,
	unsigned char *output)
{
	unsigned char iv[16];
	unsigned int loop = 0;
	unsigned char tmp_buf[16] = {0};
	unsigned char *failsafe = NULL;

	memset(iv, 0, sizeof(iv));
	memcpy(iv + 1, nonce, (nonce_length % sizeof(iv)));

	if (15 - nonce_length - 1 < 0)
		return FALSE;

	*iv = (unsigned char)(15 - nonce_length - 1);

	aes_encrypt_ecb(ctx, iv, tmp_buf);

	for (unsigned int i = 0; i < mac_length; i++)
		mac[i] ^= tmp_buf[i];

	iv[15] = 1;

	if (input_length > sizeof(iv)) {
		loop = input_length >> 4;
		do {
			aes_encrypt_ecb(ctx, iv, tmp_buf);
			for (unsigned int i = 0; i < sizeof(iv); i++)
				output[i] = input[i] ^ tmp_buf[i];
			iv[15]++;
			if (!iv[15]) {
				failsafe = &iv[15];
				do {
					failsafe--;
					(*failsafe)++;
				} while (*failsafe == 0 && failsafe >= &iv[0]);
			}
			input += sizeof(iv);
			output += sizeof(iv);
			input_length = (unsigned int)(input_length - sizeof(iv));
		} while (--loop);
	}

	if (input_length) {
		aes_encrypt_ecb(ctx, iv, tmp_buf);
		for (unsigned int i = 0; i < input_length; i++)
			output[i] = input[i] ^ tmp_buf[i];
	}

	memset(iv, 0, sizeof(iv));
	memset(tmp_buf, 0, sizeof(tmp_buf));

	return TRUE;
}

static int aes_ccm_compute_tag(aes_ctx_t *ctx,
	unsigned char *nonce, unsigned char nonce_length,
	unsigned char *buffer, unsigned int buffer_length,
	unsigned char *mac)
{
	if (!ctx || !buffer || !mac || nonce_length > 0xe)
		return FALSE;

	unsigned char iv[16];
	unsigned int loop = 0;
	unsigned int tmp_size = buffer_length;

	memset(iv, 0, sizeof(iv));
	iv[0] = (unsigned char)((0xe - nonce_length) | ((AUTHENTICATOR_LENGTH - 2) & 0xfe) << 2);
	memcpy(iv + 1, nonce, (nonce_length % AUTHENTICATOR_LENGTH));
	for (loop = 15; loop > nonce_length; --loop) {
		*(iv + loop) = tmp_size & 0xff;
		tmp_size = tmp_size >> 8;
	}

	aes_encrypt_ecb(ctx, iv, iv);

	if (buffer_length > 16) {
		loop = buffer_length >> 4;
		do {
			for (unsigned int i = 0; i < AUTHENTICATOR_LENGTH; i++)
				iv[i] ^= buffer[i];
			aes_encrypt_ecb(ctx, iv, iv);
			buffer += AUTHENTICATOR_LENGTH;
			buffer_length -= AUTHENTICATOR_LENGTH;
		} while (--loop);
	}

	if (buffer_length) {
		for (unsigned int i = 0; i < buffer_length; i++)
			iv[i] ^= buffer[i];
		aes_encrypt_ecb(ctx, iv, iv);
	}

	memcpy(mac, iv, AUTHENTICATOR_LENGTH);
	memset(iv, 0, AUTHENTICATOR_LENGTH);

	return TRUE;
}

/* decrypt_key from dislocker decrypt.c */
static int decrypt_key(const unsigned char *input, unsigned int input_size,
	const unsigned char *mac_in, const unsigned char *nonce,
	const unsigned char *key, unsigned int keybits, uint8_t *output)
{
	aes_ctx_t ctx;
	uint8_t mac_first[16];
	uint8_t mac_second[16];

	aes_ctx_init(&ctx, key, (keybits == 256) ? AES_256 : AES_128);

	memcpy(mac_first, mac_in, AUTHENTICATOR_LENGTH);

	aes_ccm_decrypt(&ctx, (unsigned char *)nonce, 0xc,
		(unsigned char *)input, input_size, mac_first, AUTHENTICATOR_LENGTH,
		(unsigned char *)output);

	memset(mac_second, 0, AUTHENTICATOR_LENGTH);
	aes_ccm_compute_tag(&ctx, (unsigned char *)nonce, 0xc,
		(unsigned char *)output, input_size, mac_second);

	if (memcmp(mac_first, mac_second, AUTHENTICATOR_LENGTH) != 0)
		return FALSE;

	memset(mac_first, 0, sizeof(mac_first));
	memset(mac_second, 0, sizeof(mac_second));

	return TRUE;
}

/* ---------------- user password hash (user_pass.c) ---------------- */

int dis_ascii_to_utf16(const uint8_t *ascii, size_t ascii_len,
	uint8_t **utf16_out, size_t *utf16_len_out)
{
	/* include the trailing NUL in UTF-16, like upstream (strlen+1) */
	size_t chars = ascii_len + 1;
	uint8_t *utf16 = calloc(chars * 2, 1);
	if (!utf16)
		return FALSE;

	for (size_t i = 0; i < ascii_len; i++)
		utf16[i * 2] = ascii[i];
	/* last word = 0x0000 */

	*utf16_out = utf16;
	*utf16_len_out = chars * 2;
	return TRUE;
}

static int stretch_user_key(const uint8_t *user_hash, const uint8_t *salt, uint8_t *result)
{
	bitlocker_chain_hash_t ch;
	memset(&ch, 0, sizeof(ch));
	memcpy(ch.password_hash, user_hash, SHA256_DIGEST_LENGTH);
	memcpy(ch.salt, salt, SALT_LENGTH);

	stretch_key(&ch, result);

	memset(&ch, 0, sizeof(ch));
	return TRUE;
}

static int user_key(const uint8_t *user_password, size_t password_len,
	const uint8_t *salt, uint8_t *result_key)
{
	uint8_t *utf16 = NULL;
	size_t utf16_len = 0;
	uint8_t user_hash[32] = {0};

	if (!dis_ascii_to_utf16(user_password, password_len, &utf16, &utf16_len))
		return FALSE;

	/* skip the trailing 0x0000 */
	sha256(utf16, utf16_len - 2, user_hash);
	sha256(user_hash, 32, user_hash);

	int ok = stretch_user_key(user_hash, salt, result_key);

	memset(utf16, 0, utf16_len);
	free(utf16);
	memset(user_hash, 0, sizeof(user_hash));

	return ok;
}

/* ---------------- recovery key (rp/recovery_password.c) ---------------- */

/* Validate one 6-digit block of the recovery password, as upstream dislocker
 * valid_block()/is_valid_key(): the block must be divisible by 11, less than
 * 2^16*11, and its trailing digit must match the checksum
 * (d0 - d1 + d2 - d3 + d4 - 48) % 11. On success stores block/11 (uint16). */
static int recovery_valid_block(const uint8_t *digits, uint16_t *short_password)
{
	long block = 0;
	for (int i = 0; i < 6; i++) {
		if (digits[i] < '0' || digits[i] > '9')
			return FALSE;
		block = block * 10 + (digits[i] - '0');
	}

	/* 1st check -- divisible by 11 */
	if (block % 11 != 0)
		return FALSE;

	/* 2nd check -- less than 2**16 * 11 (720896) */
	if (block >= 720896)
		return FALSE;

	/* 3rd check -- checksum digit (d5) must equal the alternating sum mod 11 */
	int check_digit = (int8_t)(digits[0] - digits[1] + digits[2] - digits[3] +
		digits[4] - 48) % 11;
	while (check_digit < 0)
		check_digit += 11;
	if (check_digit != (digits[5] - '0'))
		return FALSE;

	if (short_password)
		*short_password = (uint16_t)(block / 11);
	return TRUE;
}

/* Convert a full 48-digit recovery key (with dashes) to the 16-byte binary
 * key: each 6-digit block divided by 11 is stored little-endian, giving
 * 8 * uint16 = 16 bytes. Returns FALSE on invalid input. */
static int recovery_key_to_binary(const uint8_t *key, size_t key_len, uint8_t *out)
{
	/* strip dashes; expect 48 digits */
	uint8_t digits[48];
	size_t nd = 0;
	for (size_t i = 0; i < key_len; i++) {
		if (key[i] == '-' || key[i] == ' ')
			continue;
		if (key[i] < '0' || key[i] > '9')
			return FALSE;
		if (nd >= 48)
			return FALSE;
		digits[nd++] = key[i];
	}
	if (nd != 48)
		return FALSE;

	/* each group of 6 digits -> uint16 (block/11), stored little-endian */
	for (int g = 0; g < 8; g++) {
		uint8_t group[6];
		memcpy(group, digits + g * 6, 6);

		uint16_t short_pw;
		if (!recovery_valid_block(group, &short_pw))
			return FALSE;

		out[g * 2]     = (uint8_t)(short_pw & 0xff);
		out[g * 2 + 1] = (uint8_t)(short_pw >> 8);
	}
	return TRUE;
}

/* ---------------- datum walking (datums.c) ---------------- */

static uint8_t *datum_payload(uint8_t *datum, const datum_header_safe_t *header)
{
	return datum + datum_value_header_size[header->value_type];
}

/* Iterate top-level datums in the metadata blob.
 * Ported from dislocker get_next_datum (datums.c): datums start after the
 * dataset header (dataset->header_size) and each datum's datum_size is the
 * offset to the next datum. */
static int get_next_datum(dis_ctx_t *ctx, uint8_t *datum_begin, uint8_t **datum_result)
{
	bitlocker_dataset_t *dataset = ctx->dataset;
	uint8_t *datum = NULL;
	uint8_t *limit = (uint8_t *)dataset + dataset->size;
	datum_header_safe_t header;

	*datum_result = NULL;
	memset(&header, 0, sizeof(datum_header_safe_t));
	if (datum_begin)
		datum = datum_begin + *(uint16_t *)datum_begin;
	else
		datum = (uint8_t *)dataset + dataset->header_size;

	if (datum + (int)sizeof(header) >= limit)
		return FALSE;

	/* copy header safely (unaligned access) */
	memcpy(&header, datum, sizeof(header));

	*datum_result = datum;
	return TRUE;
}

/* Iterate nested datums inside a datum's payload.
 * Ported from dislocker get_nested_datumvaluetype (datums.c): nested datums
 * are laid out in the outer datum's payload and advance by their own
 * datum_size. */
static int get_nested_datumvaluetype(uint8_t *datum, size_t payload_avail,
	dis_datums_value_type_t value_type, uint8_t **datum_nested)
{
	if (!datum)
		return FALSE;

	datum_header_safe_t header;
	datum_header_safe_t nested_header;
	memcpy(&header, datum, sizeof(header));

	/* first nested datum is at the payload start */
	uint8_t *nested = datum_payload(datum, &header);

	if (nested >= datum + header.datum_size)
		return FALSE;

	memcpy(&nested_header, nested, sizeof(nested_header));

	/* walk nested datums until value_type matches */
	while (nested_header.value_type != value_type) {
		nested += nested_header.datum_size;
		if ((uint8_t *)datum + header.datum_size <= nested)
			return FALSE;
		if (nested + (int)sizeof(nested_header) > datum + header.datum_size)
			return FALSE;
		memcpy(&nested_header, nested, sizeof(nested_header));
	}

	*datum_nested = nested;
	return TRUE;
}

/* ---------------- VMK retrieval (vmk.c + user_pass.c) ---------------- */

/* Find the VMK datum whose nonce range matches [0x2000, 0x2000] (user
 * password VMKs). */
static uint8_t *find_vmk_datum_in_range(dis_ctx_t *ctx,
	uint16_t min_range, uint16_t max_range)
{
	uint8_t *cur = NULL;
	while (get_next_datum(ctx, cur, &cur)) {
		datum_header_safe_t *h = (datum_header_safe_t *)cur;
		if (h->entry_type == DATUMS_ENTRY_VMK && h->value_type == DATUMS_VALUE_VMK) {
			datum_vmk_t *vmk = (datum_vmk_t *)cur;
			uint16_t datum_range;
			memcpy(&datum_range, &vmk->nonce[10], 2);
			if (min_range <= datum_range && datum_range <= max_range)
				return cur;
		}
	}
	return NULL;
}

static int get_vmk(datum_aes_ccm_t *vmk_datum, const uint8_t *key,
	size_t key_size, uint8_t *vmk_out, size_t vmk_out_cap, size_t *vmk_out_len)
{
	unsigned int header_size = datum_value_header_size[vmk_datum->header.value_type];
	unsigned int vmk_size = vmk_datum->header.datum_size - header_size;

	if (vmk_size > vmk_out_cap) {
		dis_set_error("vmk_size %u exceeds buffer %zu", vmk_size, vmk_out_cap);
		return FALSE;
	}

	if (!decrypt_key((unsigned char *)vmk_datum + header_size, vmk_size,
		vmk_datum->mac, vmk_datum->nonce, key, (unsigned int)key_size * 8,
		vmk_out))
	{
		dis_set_error("AES-CCM decrypt failed (bad key or corrupted datum): type=%u size=%u",
			vmk_datum->header.value_type, vmk_size);
		return FALSE;
	}

	*vmk_out_len = vmk_size;
	return TRUE;
}

/* ---------------- public: retrieve VMK/FVEK ---------------- */

int dis_retrieve_keys(dis_ctx_t *ctx, const uint8_t *user_password,
	size_t password_len, const uint8_t *recovery_key, size_t recovery_key_len)
{
	uint8_t *vmk_datum = NULL;
	uint8_t salt[16] = {0};
	uint8_t unwrap_key[32] = {0};
	size_t unwrap_key_len = 0;
	int is_recovery = (recovery_key != NULL);

	/* locate the VMK datum: password VMKs live in 0x2000..0x2000, recovery
	 * key VMKs in 0x800..0xfff (as upstream get_vmk_from_rp2). */
	uint16_t vmk_min = is_recovery ? 0x800 : 0x2000;
	uint16_t vmk_max = is_recovery ? 0xfff : 0x2000;
	vmk_datum = find_vmk_datum_in_range(ctx, vmk_min, vmk_max);
	if (!vmk_datum) {
		if (is_recovery) {
			dis_set_error("Volume does not contain a recovery key protector (range 0x%04x-0x%04x)", vmk_min, vmk_max);
		} else {
			dis_set_error("Volume does not contain a password protector (range 0x%04x-0x%04x)", vmk_min, vmk_max);
		}
		return FALSE;
	}

	/* get the nested stretch-key datum for the salt */
	uint8_t *stretch_datum = NULL;
	if (!get_nested_datumvaluetype(vmk_datum, 0xffff, DATUMS_VALUE_STRETCH_KEY, &stretch_datum)) {
		dis_set_error("Corrupted metadata: no stretch-key datum found in VMK");
		return FALSE;
	}
	memcpy(salt, ((datum_stretch_key_t *)stretch_datum)->salt, 16);

	/* get the nested AES-CCM datum holding the encrypted VMK */
	uint8_t *aesccm_datum = NULL;
	if (!get_nested_datumvaluetype(vmk_datum, 0xffff, DATUMS_VALUE_AES_CCM, &aesccm_datum)) {
		dis_set_error("Corrupted metadata: no AES-CCM datum found in VMK");
		return FALSE;
	}

	/* build the unwrapping key */
	if (is_recovery) {
		if (!recovery_key_to_binary(recovery_key, recovery_key_len, unwrap_key)) {
			dis_set_error("Invalid recovery key format (must be 48 digits in 8 groups)");
			return FALSE;
		}
		/* stretch_recovery_key: SHA256(binary recovery key) then chain hash.
		 * Use a separate buffer: mbedtls_sha256 must not be given an in-place
		 * (aliased) output, that corrupts the result. */
		uint8_t rk_pw_hash[32];
		sha256(unwrap_key, RECOVERY_KEY_BINARY_LEN, rk_pw_hash);
		stretch_user_key(rk_pw_hash, salt, unwrap_key);
		unwrap_key_len = 32;
	} else {
		if (!user_key(user_password, password_len, salt, unwrap_key)) {
			dis_set_error("Password stretching failed");
			return FALSE;
		}
		unwrap_key_len = 32;
	}

	/* decrypt the VMK into a temporary datum_key_t-shaped buffer */
	uint8_t vmk_buf[256];
	size_t vmk_len = 0;
	if (!get_vmk((datum_aes_ccm_t *)aesccm_datum, unwrap_key, unwrap_key_len,
		vmk_buf, sizeof(vmk_buf), &vmk_len))
	{
		if (is_recovery) {
			dis_set_error("Wrong recovery key: VMK decryption failed (MAC mismatch)");
		} else {
			dis_set_error("Wrong password: VMK decryption failed (MAC mismatch)");
		}
		memset(unwrap_key, 0, sizeof(unwrap_key));
		return FALSE;
	}
	memset(unwrap_key, 0, sizeof(unwrap_key));

	/* The decrypted VMK buffer IS a datum_key_t: the VMK value follows the
	 * datum_key_t header directly (dislocker get_vmk returns the decrypted
	 * AES-CCM payload as a datum_key_t*). */
	if (vmk_len < sizeof(datum_key_t)) {
		dis_set_error("Decrypted VMK too short");
		return FALSE;
	}
	uint8_t *vmk_key = vmk_buf + sizeof(datum_key_t);
	size_t vmk_key_size = vmk_len - sizeof(datum_key_t);
	if (vmk_key_size > 32)
		vmk_key_size = 32;

	/* now find the FVEK datum (entry FVEK, value AES_CCM) and unwrap it with
	 * the VMK key. Ported from dislocker get_fvek (fvek.c). */
	uint8_t fvek_buf[128];
	size_t fvek_len = 0;
	int found = FALSE;
	uint8_t *cur = NULL;

	while (get_next_datum(ctx, cur, &cur)) {
		datum_header_safe_t *h = (datum_header_safe_t *)cur;
		if ((h->entry_type == DATUMS_ENTRY_FVEK || h->entry_type == DATUMS_ENTRY_FVEK_2)
			&& h->value_type == DATUMS_VALUE_AES_CCM) {
			if (get_vmk((datum_aes_ccm_t *)cur, vmk_key, vmk_key_size,
				(uint8_t *)fvek_buf, sizeof(fvek_buf), &fvek_len))
			{
				found = TRUE;
				break;
			}
		}
	}

	if (!found) {
		dis_set_error("No FVEK datum could be decrypted with the VMK");
		return FALSE;
	}

	/* The decrypted FVEK is a datum_key_t: header (algo/padd) + key bytes.
	 * Skip the datum_key_t header to get the raw FVEK key material. */
	if (fvek_len <= sizeof(datum_key_t)) {
		dis_set_error("FVEK too short (%zu bytes)", fvek_len);
		return FALSE;
	}
	uint8_t *fvek_key = fvek_buf + sizeof(datum_key_t);
	size_t fvek_key_len = fvek_len - sizeof(datum_key_t);

	/* store the FVEK key material */
	memcpy(ctx->fvek, fvek_key, fvek_key_len > 64 ? 64 : fvek_key_len);
	ctx->fvek_len = (int)(fvek_key_len > 64 ? 64 : fvek_key_len);

	memset(vmk_buf, 0, sizeof(vmk_buf));
	memset(fvek_buf, 0, sizeof(fvek_buf));

	return TRUE;
}
