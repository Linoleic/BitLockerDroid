#ifndef BITLOCKER_SMART_READER_H
#define BITLOCKER_SMART_READER_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Queries SCSI, ATA SAT, and NVMe-over-SCSI SMART telemetry for a block device.
 * Automatically resolves partition device nodes to parent physical disk nodes.
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

#ifdef __cplusplus
}
#endif

#endif // BITLOCKER_SMART_READER_H
