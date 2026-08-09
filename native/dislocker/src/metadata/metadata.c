/*
 * metadata.c -- BitLocker volume header + metadata parsing.
 *
 * Ported from dislocker v0.7.3 (src/metadata/metadata.c), GPL-2.0.
 * Reads the 512-byte volume header, computes metadata regions, validates
 * metadata with CRC-32, and extracts the dataset (which holds the disk
 * encryption algorithm).
 */
#define _GNU_SOURCE 1
#include "dislocker/dislocker_priv.h"

#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define metadata_size_from_info(info) \
	((info)->version == V_SEVEN ? ((size_t)(info)->size << 4) : (info)->size)

static int get_volume_header(dis_ctx_t *ctx, volume_header_t *vh)
{
	ssize_t nb = dis_blk_read(ctx, (uint8_t *)vh, 0, sizeof(volume_header_t));
	if (nb != (ssize_t)sizeof(volume_header_t)) {
		dis_set_error("Cannot read volume header (%zd bytes read)", nb);
		return FALSE;
	}
	return TRUE;
}

static int check_volume_header(const volume_header_t *vh)
{
	if (vh->sector_size == 0) {
		dis_set_error("Volume header has a null sector size");
		return FALSE;
	}
	if (memcmp(BITLOCKER_SIGNATURE, vh->signature, strlen(BITLOCKER_SIGNATURE)) == 0)
		return TRUE;
	if (memcmp(BITLOCKER_TO_GO_SIGNATURE, vh->signature, strlen(BITLOCKER_TO_GO_SIGNATURE)) == 0)
		return TRUE;

	dis_set_error("Volume signature (%.8s) is not BitLocker", vh->signature);
	return FALSE;
}

static int begin_compute_regions(dis_ctx_t *ctx, volume_header_t *vh, uint64_t *regions)
{
	if (memcmp(BITLOCKER_SIGNATURE, vh->signature, strlen(BITLOCKER_SIGNATURE)) == 0) {
		/* Windows 7/8: metadata offsets are stored directly in the header.
		 * Vista stores only metadata_lcn and reads the rest from the first block. */
		if (vh->information_off[0] != 0 && vh->information_off[0] != 0xffffffffffffffffULL) {
			regions[0] = vh->information_off[0];
			regions[1] = vh->information_off[1];
			regions[2] = vh->information_off[2];
			return TRUE;
		}
		/* Vista: compute from metadata_lcn */
		uint64_t new_offset = vh->metadata_lcn * vh->sectors_per_cluster * vh->sector_size;
		if (new_offset == 0) {
			dis_set_error("Cannot compute metadata region (Vista-style header)");
			return FALSE;
		}
		regions[0] = new_offset;
		/* read info_off[1..2] from the first metadata block */
		bitlocker_information_t info;
		ssize_t nb = dis_blk_read(ctx, (uint8_t *)&info, ctx->offset + new_offset, sizeof(info));
		if (nb != (ssize_t)sizeof(info)) {
			dis_set_error("Cannot read first Vista metadata block");
			return FALSE;
		}
		regions[1] = info.information_off[1];
		regions[2] = info.information_off[2];
		return TRUE;
	}

	if (memcmp(BITLOCKER_TO_GO_SIGNATURE, vh->signature, strlen(BITLOCKER_TO_GO_SIGNATURE)) == 0) {
		regions[0] = vh->bltg_header[0];
		regions[1] = vh->bltg_header[1];
		regions[2] = vh->bltg_header[2];
		return TRUE;
	}

	dis_set_error("Unknown volume signature");
	return FALSE;
}

static int get_metadata(dis_ctx_t *ctx, off_t source, uint8_t **metadata_out, size_t *size_out)
{
	bitlocker_information_t information;

	ssize_t nb = dis_blk_read(ctx, (uint8_t *)&information, source, sizeof(information));
	if (nb != (ssize_t)sizeof(information)) {
		dis_set_error("Cannot read metadata header at %lld", (long long)source);
		return FALSE;
	}

	size_t size = metadata_size_from_info(&information);
	if (size <= sizeof(bitlocker_information_t)) {
		dis_set_error("Metadata size (%zu) too small", size);
		return FALSE;
	}

	uint8_t *metadata = malloc(size);
	if (!metadata) {
		dis_set_error("Out of memory for metadata");
		return FALSE;
	}

	memcpy(metadata, &information, sizeof(information));

	size_t rest = size - sizeof(information);
	ssize_t nb2 = dis_blk_read(ctx, metadata + sizeof(information), source + sizeof(information), rest);
	if (nb2 != (ssize_t)rest) {
		dis_set_error("Cannot read full metadata at %lld (%zd/%zu)", (long long)source, nb2, rest);
		free(metadata);
		return FALSE;
	}

	*metadata_out = metadata;
	*size_out = size;
	return TRUE;
}

/* Try each metadata region; validate with CRC-32 stored right after the block. */
static int get_metadata_lazy_checked(dis_ctx_t *ctx, uint8_t **metadata_out, size_t *size_out)
{
	for (int i = 0; i < 3; i++) {
		uint8_t *metadata = NULL;
		size_t size = 0;
		if (!get_metadata(ctx, ctx->offset + ctx->regions[i], &metadata, &size)) {
			continue;
		}

		bitlocker_information_t *info = (bitlocker_information_t *)metadata;
		size_t msize = metadata_size_from_info(info);

		bitlocker_validations_t validations;
		memset(&validations, 0, sizeof(validations));
		ssize_t nb = dis_blk_read(ctx, (uint8_t *)&validations,
			ctx->offset + ctx->regions[i] + msize, sizeof(validations));
		if (nb != (ssize_t)sizeof(validations)) {
			free(metadata);
			continue;
		}

		uint32_t crc = crc32_buf(metadata, (uint32_t)msize);
		if (crc == validations.crc32) {
			*metadata_out = metadata;
			*size_out = size;
			return TRUE;
		}

		free(metadata);
	}

	dis_set_error("No valid metadata block found (CRC mismatch on all copies)");
	return FALSE;
}

static int get_dataset(uint8_t *metadata, bitlocker_dataset_t **dataset)
{
	bitlocker_information_t *information = (bitlocker_information_t *)metadata;
	bitlocker_dataset_t *ds = &information->dataset;

	if (ds->copy_size < ds->header_size || ds->size > ds->copy_size ||
		ds->copy_size - ds->header_size < 8)
	{
		return FALSE;
	}

	*dataset = ds;
	return TRUE;
}

int dis_metadata_parse(dis_ctx_t *ctx)
{
	/* 1. volume header */
	if (!get_volume_header(ctx, &ctx->volume_header)) {
		return DIS_RET_ERROR_VOLUME_HEADER_READ;
	}

	/* Windows 10 1903+ exFAT volumes leave the header's sector_size field as
	 * zero; fall back to the physical sector size (512 on USB/SD). This mirrors
	 * upstream dislocker's "Windows 10 1903 exFAT" handling. */
	if (ctx->volume_header.sector_size == 0) {
		ctx->volume_header.sector_size = 512;
	}

	if (!check_volume_header(&ctx->volume_header)) {
		return DIS_RET_ERROR_VOLUME_HEADER_CHECK;
	}

	ctx->sector_size = ctx->volume_header.sector_size;

	/* 2. compute metadata regions */
	if (!begin_compute_regions(ctx, &ctx->volume_header, ctx->regions)) {
		dis_set_error("Cannot compute metadata regions");
		return DIS_RET_ERROR_METADATA_OFFSET;
	}

	/* 3. read + validate metadata */
	uint8_t *metadata = NULL;
	size_t metadata_size = 0;
	if (!get_metadata_lazy_checked(ctx, &metadata, &metadata_size)) {
		return DIS_RET_ERROR_METADATA_CHECK;
	}

	bitlocker_information_t *info = (bitlocker_information_t *)metadata;
	if (info->version > V_SEVEN) {
		dis_set_error("Unsupported BitLocker metadata version %u", info->version);
		free(metadata);
		return DIS_RET_ERROR_METADATA_VERSION_UNSUPPORTED;
	}

	ctx->information = info;
	ctx->metadata = metadata;
	ctx->metadata_size = metadata_size;
	ctx->volume_state = info->curr_state;

	/* 4. dataset */
	if (!get_dataset(metadata, &ctx->dataset)) {
		dis_set_error("Invalid BitLocker dataset (size=%u copy=%u header=%u)",
			((bitlocker_information_t *)metadata)->dataset.size,
			((bitlocker_information_t *)metadata)->dataset.copy_size,
			((bitlocker_information_t *)metadata)->dataset.header_size);
		return DIS_RET_ERROR_DATASET_CHECK;
	}
	ctx->algorithm = ctx->dataset->algorithm;
	ctx->volume_size = info->encrypted_volume_size;

	/* Supported ciphers: AES-XTS (Win10+) and AES-CBC with/without diffuser
	 * (legacy). Diffuser modes currently fall back to plain CBC. */
	switch (ctx->algorithm) {
	case AES_XTS_128:
	case AES_XTS_256:
	case AES_128_NO_DIFFUSER:
	case AES_256_NO_DIFFUSER:
	case AES_128_DIFFUSER:
	case AES_256_DIFFUSER:
		break;
	default:
		dis_set_error("Unsupported disk cipher 0x%04x", ctx->algorithm);
		return DIS_RET_ERROR_CRYPTO_ALGORITHM_UNSUPPORTED;
	}

	return DIS_RET_SUCCESS;
}

void dis_metadata_free(dis_ctx_t *ctx)
{
	if (ctx->metadata) {
		/* wipe: contains key material / secrets */
		memset(ctx->metadata, 0, ctx->metadata_size);
		free(ctx->metadata);
	}
	ctx->metadata = NULL;
	ctx->information = NULL;
	ctx->dataset = NULL;
}
