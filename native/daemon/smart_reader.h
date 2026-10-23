#ifndef BITLOCKER_SMART_READER_H
#define BITLOCKER_SMART_READER_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Queries SMART telemetry using a conservative transport policy. Only the
 * allowlisted Realtek RTL9210 USB bridge receives vendor-specific E4 commands;
 * unknown USB bridges are reported unsupported. Non-USB devices retain SAT.
 *
 * @param dev_path Device node path (e.g., "/dev/block/sdg", "/dev/block/sdg1", "/dev/block/vold/public:8,101")
 * @param json Destination buffer for JSON output
 * @param json_max Buffer capacity
 * @return 0 on query completion, negative errno on critical failure
 */
int read_smart_json(const char *dev_path, char *json, size_t json_max);

/**
 * Safely flushes caches, spins down, and offlines a physical SCSI/USB block device:
 * 1. Resolves partition device nodes to parent physical disk
 * 2. Validates that the device is external (never internal flash/UFS)
 * 3. Flushes OS buffer cache (sync & BLKFLSBUF)
 * 4. Issues SCSI SYNCHRONIZE CACHE (10) (0x35)
 * 5. Issues SCSI START STOP UNIT (0x1B) with START=0
 * 6. Enables sysfs manage_start_stop and writes 1 to sysfs delete
 *
 * @param dev_path Device node path
 * @return 0 on success, negative errno on failure
 */
int scsi_spindown_device(const char *dev_path);

#ifdef SMART_READER_TEST
/* Pure helper hooks used by native/tests/smart_reader_test.c. */
int smart_test_detect_usb_ids(const char *sysfs_path, unsigned int *vendor, unsigned int *product);
void smart_test_build_rtl_e4_cdb(unsigned char cdb[16], unsigned int length,
                                 unsigned char opcode, unsigned int cdw10);
int smart_test_parse_nvme_identity(const unsigned char *id, size_t len,
                                   char *model, size_t model_max,
                                   char *serial, size_t serial_max,
                                   char *firmware, size_t firmware_max);
int smart_test_validate_nvme_smart_log(const unsigned char *page, size_t len);
int smart_test_sg_response_ok(int status, int host_status, int driver_status,
                              int resid, int data_len);
#endif

#ifdef __cplusplus
}
#endif

#endif // BITLOCKER_SMART_READER_H
