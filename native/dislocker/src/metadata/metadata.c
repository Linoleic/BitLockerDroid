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
#include <sys/time.h>
#include <sys/ioctl.h>

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

	/* Known ciphers: AES-XTS (Win10+) and AES-CBC with/without diffuser
	 * (legacy). Diffuser modes are rejected in access init — the Elephant
	 * diffuser is not implemented here, so such volumes must not open. */
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

/* ---------------- FVE Metadata Disaster Recovery Package ---------------- */

#pragma pack(push, 1)
typedef struct {
	char     magic[11];             /* "BLDFVEMETA\0" */
	uint32_t version;               /* 1 */
	uint32_t header_len;            /* sizeof(fve_meta_header_t) */
	char     volume_guid[36];       /* formatted GUID string */
	char     disk_label[64];        /* Disk label */
	uint32_t sector_size;           /* 512 or 4096 */
	uint64_t volume_size;           /* total volume bytes */
	uint64_t timestamp_ms;          /* milliseconds epoch */
	uint8_t  payload_sha256[32];    /* SHA-256 of all subsequent payload */
} fve_meta_header_t;

typedef struct {
	uint32_t vbr_size;
} fve_meta_vbr_hdr_t;

typedef struct {
	uint32_t region_count;          /* 3 */
} fve_meta_regions_hdr_t;

typedef struct {
	uint64_t offset;
	uint32_t length;
	uint32_t crc32;
} fve_meta_region_entry_t;
#pragma pack(pop)

int dis_metadata_extract_package(dis_ctx_t *ctx, uint8_t **out_pkg, size_t *out_pkg_len)
{
	if (!ctx || !out_pkg || !out_pkg_len) {
		dis_set_error("Invalid argument to dis_metadata_extract_package");
		return DIS_RET_ERROR_DISLOCKER_INVAL;
	}

	if (!ctx->information) {
		int ret = dis_metadata_parse(ctx);
		if (ret != DIS_RET_SUCCESS) {
			dis_set_error("Cannot parse metadata for extraction: %s", dis_get_last_error());
			return ret;
		}
	}

	/* 1. Read Sector 0 (VBR) */
	size_t vbr_size = ctx->sector_size ? ctx->sector_size : 512;
	uint8_t *vbr_buf = malloc(vbr_size);
	if (!vbr_buf) {
		dis_set_error("Out of memory for VBR buffer");
		return DIS_RET_ERROR_ALLOC;
	}
	ssize_t nb = dis_blk_read(ctx, vbr_buf, 0, vbr_size);
	if (nb != (ssize_t)vbr_size) {
		dis_set_error("Failed to read Sector 0 VBR (%zd/%zu bytes)", nb, vbr_size);
		free(vbr_buf);
		return DIS_RET_ERROR_VOLUME_HEADER_READ;
	}

	/* 2. Read up to 3 FVE regions */
	uint8_t *region_bufs[3] = { NULL, NULL, NULL };
	uint32_t region_lens[3] = { 0, 0, 0 };
	uint32_t region_crcs[3] = { 0, 0, 0 };
	uint64_t region_offsets[3] = { 0, 0, 0 };

	for (int i = 0; i < 3; i++) {
		region_offsets[i] = ctx->regions[i];
		if (region_offsets[i] == 0)
			continue;

		bitlocker_information_t info;
		ssize_t r_nb = dis_blk_read(ctx, (uint8_t *)&info, (off_t)region_offsets[i], sizeof(info));
		if (r_nb != (ssize_t)sizeof(info))
			continue;

		size_t msize = metadata_size_from_info(&info);
		if (msize <= sizeof(bitlocker_information_t))
			continue;

		size_t total_reg_sz = (msize + sizeof(bitlocker_validations_t) > 0x10000) ?
			(msize + sizeof(bitlocker_validations_t)) : 0x10000;

		uint8_t *rbuf = malloc(total_reg_sz);
		if (!rbuf)
			continue;

		r_nb = dis_blk_read(ctx, rbuf, (off_t)region_offsets[i], total_reg_sz);
		if (r_nb < (ssize_t)(msize + sizeof(bitlocker_validations_t))) {
			r_nb = dis_blk_read(ctx, rbuf, (off_t)region_offsets[i], msize + sizeof(bitlocker_validations_t));
			if (r_nb != (ssize_t)(msize + sizeof(bitlocker_validations_t))) {
				free(rbuf);
				continue;
			}
			total_reg_sz = msize + sizeof(bitlocker_validations_t);
		} else {
			total_reg_sz = (size_t)r_nb;
		}

		uint32_t crc = crc32_buf(rbuf, (uint32_t)msize);
		region_bufs[i] = rbuf;
		region_lens[i] = (uint32_t)total_reg_sz;
		region_crcs[i] = crc;
	}

	/* 3. Compute total package length */
	size_t header_sz = sizeof(fve_meta_header_t);
	size_t vbr_section_sz = sizeof(fve_meta_vbr_hdr_t) + vbr_size;
	size_t regions_section_sz = sizeof(fve_meta_regions_hdr_t);
	for (int i = 0; i < 3; i++) {
		regions_section_sz += sizeof(fve_meta_region_entry_t) + region_lens[i];
	}
	size_t total_sz = header_sz + vbr_section_sz + regions_section_sz;

	uint8_t *pkg = calloc(1, total_sz);
	if (!pkg) {
		dis_set_error("Out of memory for package (size=%zu)", total_sz);
		free(vbr_buf);
		for (int i = 0; i < 3; i++) free(region_bufs[i]);
		return DIS_RET_ERROR_ALLOC;
	}

	/* Fill header */
	fve_meta_header_t *hdr = (fve_meta_header_t *)pkg;
	memcpy(hdr->magic, "BLDFVEMETA\0", 11);
	hdr->version = 1;
	hdr->header_len = (uint32_t)header_sz;

	const uint8_t *guid_bytes = NULL;
	if (ctx->dataset)
		guid_bytes = (const uint8_t *)ctx->dataset->guid;
	else
		guid_bytes = (const uint8_t *)ctx->volume_header.guid;

	if (guid_bytes) {
		uint32_t d1 = (uint32_t)guid_bytes[0] | ((uint32_t)guid_bytes[1] << 8) | ((uint32_t)guid_bytes[2] << 16) | ((uint32_t)guid_bytes[3] << 24);
		uint16_t d2 = (uint16_t)guid_bytes[4] | ((uint16_t)guid_bytes[5] << 8);
		uint16_t d3 = (uint16_t)guid_bytes[6] | ((uint16_t)guid_bytes[7] << 8);
		char guid_str[64];
		snprintf(guid_str, sizeof(guid_str),
			"%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
			d1, d2, d3,
			guid_bytes[8], guid_bytes[9],
			guid_bytes[10], guid_bytes[11], guid_bytes[12], guid_bytes[13], guid_bytes[14], guid_bytes[15]);
		memcpy(hdr->volume_guid, guid_str, 36);
	}

	hdr->sector_size = ctx->sector_size ? ctx->sector_size : 512;
	hdr->volume_size = ctx->volume_size;
	if (hdr->volume_size == 0) {
		hdr->volume_size = dis_blk_get_size(ctx);
	}

	struct timeval tv;
	gettimeofday(&tv, NULL);
	hdr->timestamp_ms = (uint64_t)tv.tv_sec * 1000ULL + (uint64_t)(tv.tv_usec / 1000);

	/* Write VBR section */
	size_t cur = header_sz;
	fve_meta_vbr_hdr_t *vbr_hdr = (fve_meta_vbr_hdr_t *)(pkg + cur);
	vbr_hdr->vbr_size = (uint32_t)vbr_size;
	cur += sizeof(fve_meta_vbr_hdr_t);
	memcpy(pkg + cur, vbr_buf, vbr_size);
	cur += vbr_size;
	free(vbr_buf);

	/* Write Regions section */
	fve_meta_regions_hdr_t *regs_hdr = (fve_meta_regions_hdr_t *)(pkg + cur);
	regs_hdr->region_count = 3;
	cur += sizeof(fve_meta_regions_hdr_t);

	for (int i = 0; i < 3; i++) {
		fve_meta_region_entry_t *entry = (fve_meta_region_entry_t *)(pkg + cur);
		entry->offset = region_offsets[i];
		entry->length = region_lens[i];
		entry->crc32 = region_crcs[i];
		cur += sizeof(fve_meta_region_entry_t);
		if (region_lens[i] > 0 && region_bufs[i]) {
			memcpy(pkg + cur, region_bufs[i], region_lens[i]);
			cur += region_lens[i];
			free(region_bufs[i]);
		}
	}

	/* Compute SHA-256 of all payload data following the header */
	size_t payload_len = total_sz - header_sz;
	sha256(pkg + header_sz, payload_len, hdr->payload_sha256);

	*out_pkg = pkg;
	*out_pkg_len = total_sz;
	return DIS_RET_SUCCESS;
}

int dis_metadata_restore_package(dis_ctx_t *ctx, const uint8_t *pkg, size_t pkg_len, uint64_t target_partition_size)
{
	if (!ctx || !pkg || pkg_len < sizeof(fve_meta_header_t)) {
		dis_set_error("Invalid package or parameter");
		return -1;
	}

	const fve_meta_header_t *hdr = (const fve_meta_header_t *)pkg;
	if (memcmp(hdr->magic, "BLDFVEMETA\0", 11) != 0 || hdr->version != 1) {
		dis_set_error("Invalid or unsupported .fvemeta format (magic or version mismatch)");
		return -2;
	}

	if (hdr->header_len != sizeof(fve_meta_header_t) || hdr->header_len >= pkg_len) {
		dis_set_error("Corrupted .fvemeta header length (%u)", hdr->header_len);
		return -3;
	}

	/* Check SHA-256 integrity of payload */
	size_t payload_len = pkg_len - hdr->header_len;
	uint8_t computed_sha[32];
	sha256(pkg + hdr->header_len, payload_len, computed_sha);
	if (memcmp(computed_sha, hdr->payload_sha256, 32) != 0) {
		dis_set_error("Integrity check failed: .fvemeta payload SHA-256 mismatch");
		return -4;
	}

	/* Safety Guard: Check sector size */
	if (ctx->sector_size != 0 && ctx->sector_size != hdr->sector_size) {
		dis_set_error("Safety violation: target sector size (%u) does not match backup (%u)",
			ctx->sector_size, hdr->sector_size);
		return -101;
	}

	/* Safety Guard: Check volume / partition size */
	uint64_t tgt_sz = target_partition_size;
	if (tgt_sz == 0 && ctx->volume_size > 0)
		tgt_sz = ctx->volume_size;
	if (tgt_sz == 0) {
		uint64_t blk_sz = dis_blk_get_size(ctx);
		if (blk_sz > (uint64_t)ctx->offset)
			tgt_sz = blk_sz - ctx->offset;
		else
			tgt_sz = blk_sz;
	}

	if (tgt_sz > 0 && hdr->volume_size > 0) {
		if (tgt_sz < hdr->volume_size) {
			dis_set_error("Safety violation: target partition size (%llu) is smaller than backup volume (%llu)",
				(unsigned long long)tgt_sz, (unsigned long long)hdr->volume_size);
			return -101;
		}
		if (tgt_sz > hdr->volume_size + (32ULL * 1024ULL * 1024ULL)) {
			dis_set_error("Safety violation: target partition size (%llu) differs significantly from backup volume (%llu)",
				(unsigned long long)tgt_sz, (unsigned long long)hdr->volume_size);
			return -101;
		}
	}

	/* Unpack and raw-write Sector 0 (VBR) */
	size_t cur = hdr->header_len;
	if (cur + sizeof(fve_meta_vbr_hdr_t) > pkg_len)
		return -3;
	const fve_meta_vbr_hdr_t *vbr_hdr = (const fve_meta_vbr_hdr_t *)(pkg + cur);
	cur += sizeof(fve_meta_vbr_hdr_t);

	if (cur + vbr_hdr->vbr_size > pkg_len)
		return -3;

	int wr = dis_blk_write_raw(ctx, pkg + cur, 0, vbr_hdr->vbr_size);
	if (wr != (int)vbr_hdr->vbr_size) {
		dis_set_error("Failed to restore Sector 0 VBR (wrote %d/%u bytes)", wr, vbr_hdr->vbr_size);
		return -102;
	}
	cur += vbr_hdr->vbr_size;

	/* Unpack and raw-write regions */
	if (cur + sizeof(fve_meta_regions_hdr_t) > pkg_len)
		return -3;
	const fve_meta_regions_hdr_t *regs_hdr = (const fve_meta_regions_hdr_t *)(pkg + cur);
	cur += sizeof(fve_meta_regions_hdr_t);

	for (uint32_t i = 0; i < regs_hdr->region_count; i++) {
		if (cur + sizeof(fve_meta_region_entry_t) > pkg_len)
			return -3;
		const fve_meta_region_entry_t *entry = (const fve_meta_region_entry_t *)(pkg + cur);
		cur += sizeof(fve_meta_region_entry_t);

		if (cur + entry->length > pkg_len)
			return -3;

		if (entry->length > 0 && entry->offset > 0) {
			int rwr = dis_blk_write_raw(ctx, pkg + cur, (off_t)entry->offset, entry->length);
			if (rwr != (int)entry->length) {
				dis_set_error("Failed to restore FVE region %u at offset %llu (wrote %d/%u bytes)",
					i, (unsigned long long)entry->offset, rwr, entry->length);
				return -102;
			}
		}
		cur += entry->length;
	}

	dis_blk_sync(ctx);
	return DIS_RET_SUCCESS;
}

