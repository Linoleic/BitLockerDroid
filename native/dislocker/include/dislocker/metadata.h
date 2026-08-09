#ifndef DISLOCKER_METADATA_H
#define DISLOCKER_METADATA_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define BITLOCKER_SIGNATURE       "-FVE-FS-"
#define BITLOCKER_TO_GO_SIGNATURE "MSWIN4.1"
#define GUID_SIZE                 16

typedef uint8_t guid_t[GUID_SIZE];

/* ---------------- Volume header (512 bytes, from dislocker metadata.priv.h) ---------------- */

#pragma pack(1)
typedef struct _volume_header {
	uint8_t  jump[3];                  /* offset 0x00 */
	uint8_t  signature[8];             /* offset 0x03  "-FVE-FS-" / "NTFS    " / "MSWIN4.1" */
	uint16_t sector_size;              /* offset 0x0b */
	uint8_t  sectors_per_cluster;      /* offset 0x0d */
	uint16_t reserved_clusters;        /* offset 0x0e */
	uint8_t  fat_count;                /* offset 0x10 */
	uint16_t root_entries;             /* offset 0x11 */
	uint16_t nb_sectors_16b;           /* offset 0x13 */
	uint8_t  media_descriptor;         /* offset 0x15 */
	uint16_t sectors_per_fat;          /* offset 0x16 */
	uint16_t sectors_per_track;        /* offset 0x18 */
	uint16_t nb_of_heads;              /* offset 0x1a */
	uint32_t hidden_sectors;           /* offset 0x1c */
	uint32_t nb_sectors_32b;           /* offset 0x20 */
	union {
		struct { /* Classic BitLocker */
			uint8_t  unknown2[4];
			uint64_t nb_sectors_64b;       /* offset 0x28 */
			uint64_t mft_start_cluster;    /* offset 0x30 */
			uint64_t metadata_lcn;         /* offset 0x38 */
			uint8_t  unknown3[96];         /* offset 0x40 */
			guid_t   guid;                 /* offset 0xa0 */
			uint64_t information_off[3];   /* offset 0xb0 */
			uint64_t eow_information_off[2]; /* offset 0xc8 */
			uint8_t  unknown4[294];        /* offset 0xd8 */
		};
		struct { /* BitLocker-To-Go */
			uint8_t  unknown5[35];
			uint8_t  fs_name[11];          /* offset 0x47 */
			uint8_t  fs_signature[8];      /* offset 0x52 */
			uint8_t  unknown6[334];        /* offset 0x5a */
			guid_t   bltg_guid;            /* offset 0x1a8 */
			uint64_t bltg_header[3];       /* offset 0x1b8 */
			uint8_t  unknown7[46];         /* offset 0x1d0 */
		};
	};
	uint16_t boot_partition_identifier; /* offset 0x1fe  = 0xaa55 */
} volume_header_t; /* 512 bytes */
#pragma pack()

/* ---------------- BitLocker metadata header ---------------- */

enum {
	V_VISTA = 1,
	V_SEVEN = 2
};
typedef uint16_t version_t;

enum state_types {
	METADATA_STATE_NULL = 0,
	METADATA_STATE_DECRYPTED = 1,
	METADATA_STATE_SWITCHING_ENCRYPTION = 2,
	METADATA_STATE_EOW_ACTIVATED = 3,
	METADATA_STATE_ENCRYPTED = 4,
	METADATA_STATE_SWITCH_ENCRYPTION_PAUSED = 5
};
typedef uint16_t dis_metadata_state_t;

#pragma pack(1)
typedef struct _bitlocker_dataset {
	uint32_t size;           /* offset 0x00 */
	uint32_t unknown1;       /* offset 0x04 */
	uint32_t header_size;    /* offset 0x08 = 0x30 */
	uint32_t copy_size;      /* offset 0x0c */
	guid_t   guid;           /* offset 0x10 */
	uint32_t next_counter;   /* offset 0x20 */
	uint16_t algorithm;      /* offset 0x24 */
	uint16_t trash;          /* offset 0x26 */
	uint64_t timestamp;      /* offset 0x28 */
} bitlocker_dataset_t; /* 0x30 */

typedef struct _bitlocker_information {
	uint8_t  signature[8];          /* offset 0x00 "-FVE-FS-" */
	uint16_t size;                  /* offset 0x08  (*16 when version 2) */
	version_t version;              /* offset 0x0a */
	dis_metadata_state_t curr_state; /* offset 0x0c */
	dis_metadata_state_t next_state; /* offset 0x0e */
	uint64_t encrypted_volume_size; /* offset 0x10 */
	uint32_t convert_size;          /* offset 0x18 */
	uint32_t nb_backup_sectors;     /* offset 0x1c */
	uint64_t information_off[3];    /* offset 0x20 */
	uint64_t boot_sectors_backup;   /* offset 0x38 */
	bitlocker_dataset_t dataset;    /* offset 0x40 */
} bitlocker_information_t; /* 0x40 + 0x30 = 0x70 */

typedef struct _bitlocker_validations {
	uint16_t size;
	version_t version;
	uint32_t crc32;
} bitlocker_validations_t; /* 8 */
#pragma pack()

/* ---------------- Encryption algorithms (from encommon.h) ---------------- */

enum cipher_types {
	STRETCH_KEY   = 0x1000,
	AES_CCM_256_0 = 0x2000,
	AES_CCM_256_1 = 0x2001,
	EXTERN_KEY    = 0x2002,
	VMK           = 0x2003,
	AES_CCM_256_2 = 0x2004,
	HASH_256      = 0x2005,
	AES_128_DIFFUSER    = 0x8000,
	AES_256_DIFFUSER    = 0x8001,
	AES_128_NO_DIFFUSER = 0x8002,
	AES_256_NO_DIFFUSER = 0x8003,
	AES_XTS_128         = 0x8004,
	AES_XTS_256         = 0x8005
};
typedef uint16_t cipher_t;

/* ---------------- Datums (from dislocker datums.h) ---------------- */

enum value_types {
	DATUMS_VALUE_ERASED = 0x0000,
	DATUMS_VALUE_KEY,
	DATUMS_VALUE_UNICODE,
	DATUMS_VALUE_STRETCH_KEY,
	DATUMS_VALUE_USE_KEY,
	DATUMS_VALUE_AES_CCM,
	DATUMS_VALUE_TPM_ENCODED,
	DATUMS_VALUE_VALIDATION,
	DATUMS_VALUE_VMK,
	DATUMS_VALUE_EXTERNAL_KEY,
	DATUMS_VALUE_UPDATE,
	DATUMS_VALUE_ERROR,
	DATUMS_VALUE_ASYM_ENC,
	DATUMS_VALUE_EXPORTED_KEY,
	DATUMS_VALUE_PUBLIC_KEY,
	DATUMS_VALUE_VIRTUALIZATION_INFO,
	DATUMS_VALUE_SIMPLE_1,
	DATUMS_VALUE_SIMPLE_2,
	DATUMS_VALUE_CONCAT_HASH_KEY,
	DATUMS_VALUE_SIMPLE_3
};
typedef uint16_t dis_datums_value_type_t;

enum entry_types {
	DATUMS_ENTRY_UNKNOWN1 = 0x0000,
	DATUMS_ENTRY_UNKNOWN2,
	DATUMS_ENTRY_VMK,
	DATUMS_ENTRY_FVEK,
	DATUMS_ENTRY_UNKNOWN3,
	DATUMS_ENTRY_UNKNOWN4,
	DATUMS_ENTRY_STARTUP_KEY,
	DATUMS_ENTRY_ENCTIME_INFORMATION,
	DATUMS_ENTRY_UNKNOWN7,
	DATUMS_ENTRY_UNKNOWN8,
	DATUMS_ENTRY_UNKNOWN9,
	DATUMS_ENTRY_UNKNOWN10,
	DATUMS_ENTRY_FVEK_2
};
typedef uint16_t dis_datums_entry_type_t;

#pragma pack(1)
typedef struct _datum_header_safe {
	uint16_t datum_size;
	dis_datums_entry_type_t entry_type;
	dis_datums_value_type_t value_type;
	uint16_t error_status;
} datum_header_safe_t; /* 8 */

typedef struct _datum_key {
	datum_header_safe_t header;
	cipher_t algo;
	uint16_t padd;
	/* key bytes follow */
} datum_key_t;

typedef struct _datum_stretch_key {
	datum_header_safe_t header;
	cipher_t algo;
	uint16_t padd;
	uint8_t salt[16];
} datum_stretch_key_t;

typedef struct _datum_aes_ccm {
	datum_header_safe_t header;
	uint8_t nonce[12];
	uint8_t mac[16];
	/* encrypted payload follows */
} datum_aes_ccm_t;

typedef struct _datum_vmk {
	datum_header_safe_t header;
	guid_t guid;
	uint8_t nonce[12];
	/* nested datums follow */
} datum_vmk_t;
#pragma pack()

/* size of header for each value type, from dislocker datums.c */
extern const uint16_t datum_value_header_size[22];

#ifdef __cplusplus
}
#endif

#endif /* DISLOCKER_METADATA_H */
