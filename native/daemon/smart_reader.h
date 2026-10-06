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

#ifdef __cplusplus
}
#endif

#endif // BITLOCKER_SMART_READER_H
