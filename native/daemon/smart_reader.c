/*
 * smart_reader.c -- Conservative SMART reader with bridge-specific NVMe support.
 *
 * Implements:
 * 1. Automatic partition-to-disk resolution via sysfs (/sys/dev/block/M:m)
 * 2. Standard SCSI INQUIRY (0x12)
 * 3. USB bridge allowlisting from the physical sysfs ancestry
 * 4. Realtek RTL9210 E4 NVMe Identify + SMART log only
 * 5. ATA SAT SMART on non-USB devices
 * 6. Standardized JSON output generation
 */
#define _GNU_SOURCE 1
#include "smart_reader.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <scsi/sg.h>
#include <stdint.h>
#include <limits.h>
#include <dirent.h>
#include <linux/fs.h>

static int sg_response_ok(int status, int host_status, int driver_status, int resid, int data_len) {
    return status == 0 && host_status == 0 && driver_status == 0 && resid == 0 && data_len >= 0;
}

static int do_sg_io(int fd, const unsigned char *cdb, int cdb_len,
                    unsigned char *data, int data_len, int dxfer_dir,
                    unsigned char *sense, int sense_len) {
    struct sg_io_hdr io_hdr;
    memset(&io_hdr, 0, sizeof(io_hdr));
    io_hdr.interface_id = 'S';
    io_hdr.cmd_len = cdb_len;
    io_hdr.cmdp = (unsigned char *)cdb;
    io_hdr.dxfer_direction = dxfer_dir;
    io_hdr.dxfer_len = data_len;
    io_hdr.dxferp = data;
    io_hdr.sbp = sense;
    io_hdr.mx_sb_len = sense_len;
    io_hdr.timeout = 5000;

    if (ioctl(fd, SG_IO, &io_hdr) < 0) {
        return -1;
    }
    if (!sg_response_ok(io_hdr.status, io_hdr.host_status, io_hdr.driver_status,
                        io_hdr.resid, data_len)) {
        return -1;
    }
    return 0;
}

enum usb_bridge_kind { USB_BRIDGE_NONE = 0, USB_BRIDGE_UNKNOWN = 1, USB_BRIDGE_RTL9210 = 2 };
static void trim_trailing(char *s);

static int read_hex_id(const char *path, unsigned int *value) {
    char text[32];
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    int ok = fgets(text, sizeof(text), f) != NULL;
    fclose(f);
    if (!ok) return -1;
    char *end = NULL;
    unsigned long parsed = strtoul(text, &end, 16);
    if (end == text || parsed > 0xffff) return -1;
    *value = (unsigned int)parsed;
    return 0;
}

/* Walk the resolved block-device ancestry. Vendor and product must come from
 * the same USB device directory; a nearby unrelated ancestor is not enough. */
static int detect_usb_ids_from_sysfs(const char *sysfs_path,
                                     unsigned int *vendor, unsigned int *product) {
    if (!sysfs_path || !vendor || !product) return USB_BRIDGE_UNKNOWN;
    char path[PATH_MAX];
    if (!realpath(sysfs_path, path)) return USB_BRIDGE_UNKNOWN;
    int saw_usb = 0;
    for (int depth = 0; depth < 16; depth++) {
        char vpath[PATH_MAX], ppath[PATH_MAX];
        if (snprintf(vpath, sizeof(vpath), "%s/idVendor", path) >= (int)sizeof(vpath) ||
            snprintf(ppath, sizeof(ppath), "%s/idProduct", path) >= (int)sizeof(ppath))
            return USB_BRIDGE_UNKNOWN;
        unsigned int vid = 0, pid = 0;
        int has_vid = read_hex_id(vpath, &vid) == 0;
        int has_pid = read_hex_id(ppath, &pid) == 0;
        if (has_vid || has_pid) {
            saw_usb = 1;
            if (has_vid && has_pid) {
                *vendor = vid;
                *product = pid;
                return (vid == 0x0bda && pid == 0x9210) ? USB_BRIDGE_RTL9210 : USB_BRIDGE_UNKNOWN;
            }
        }
        char *slash = strrchr(path, '/');
        if (!slash || slash == path) break;
        *slash = '\0';
    }
    return saw_usb ? USB_BRIDGE_UNKNOWN : USB_BRIDGE_NONE;
}

static int resolve_block_topology(const char *dev_path, const struct stat *st,
                                  char *actual_dev, size_t actual_dev_max,
                                  int *bridge, unsigned int *vendor, unsigned int *product) {
    if (!dev_path || !st || !actual_dev || actual_dev_max == 0 || !bridge) return -1;
    snprintf(actual_dev, actual_dev_max, "%s", dev_path);
    *bridge = USB_BRIDGE_UNKNOWN;
    if (!S_ISBLK(st->st_mode)) return -1;
    unsigned int maj = major(st->st_rdev), min = minor(st->st_rdev);
    char syslink[PATH_MAX], sysreal[PATH_MAX], part_path[PATH_MAX];
    snprintf(syslink, sizeof(syslink), "/sys/dev/block/%u:%u", maj, min);
    if (!realpath(syslink, sysreal)) return -1;
    snprintf(part_path, sizeof(part_path), "%s/partition", sysreal);
    if (access(part_path, F_OK) == 0) {
        char *slash = strrchr(sysreal, '/');
        if (!slash || slash == sysreal) return -1;
        *slash = '\0';
    }
    char *bname = strrchr(sysreal, '/');
    if (!bname || !bname[1]) return -1;
    if (snprintf(actual_dev, actual_dev_max, "/dev/block/%s", bname + 1) >= (int)actual_dev_max) return -1;
    unsigned int vid = 0, pid = 0;
    *bridge = detect_usb_ids_from_sysfs(sysreal, &vid, &pid);
    if (vendor) *vendor = vid;
    if (product) *product = pid;
    return 0;
}

static void build_rtl_e4_cdb(unsigned char cdb[16], unsigned int length,
                             unsigned char opcode, unsigned int cdw10) {
    memset(cdb, 0, 16);
    cdb[0] = 0xe4;
    cdb[1] = (unsigned char)(length & 0xff);
    cdb[2] = (unsigned char)((length >> 8) & 0xff);
    cdb[3] = opcode;
    cdb[4] = (unsigned char)(cdw10 & 0xff);
}

static int parse_nvme_identity(const unsigned char *id, size_t len,
                               char *model, size_t model_max,
                               char *serial, size_t serial_max,
                               char *firmware, size_t firmware_max) {
    if (!id || len < 72 || !model || !serial || !firmware || model_max == 0 || serial_max == 0 || firmware_max == 0)
        return -1;
    uint16_t vid = (uint16_t)id[0] | ((uint16_t)id[1] << 8);
    if (vid == 0 || vid == 0xffff) return -1;
    memcpy(serial, id + 4, serial_max - 1 < 20 ? serial_max - 1 : 20);
    serial[(serial_max - 1 < 20 ? serial_max - 1 : 20)] = '\0';
    memcpy(model, id + 24, model_max - 1 < 40 ? model_max - 1 : 40);
    model[(model_max - 1 < 40 ? model_max - 1 : 40)] = '\0';
    memcpy(firmware, id + 64, firmware_max - 1 < 8 ? firmware_max - 1 : 8);
    firmware[(firmware_max - 1 < 8 ? firmware_max - 1 : 8)] = '\0';
    trim_trailing(serial);
    trim_trailing(model);
    trim_trailing(firmware);
    for (char *p = serial; *p; p++) if ((unsigned char)*p < 32 || (unsigned char)*p > 126) *p = ' ';
    for (char *p = model; *p; p++) if ((unsigned char)*p < 32 || (unsigned char)*p > 126) *p = ' ';
    for (char *p = firmware; *p; p++) if ((unsigned char)*p < 32 || (unsigned char)*p > 126) *p = ' ';
    trim_trailing(serial); trim_trailing(model); trim_trailing(firmware);
    return model[0] && serial[0] ? 0 : -1;
}

static int validate_nvme_smart_log(const unsigned char *page, size_t len) {
    if (!page || len != 512) return -1;
    int all_zero = 1, all_ff = 1;
    for (size_t i = 0; i < len; i++) {
        if (page[i] != 0) all_zero = 0;
        if (page[i] != 0xff) all_ff = 0;
    }
    if (all_zero || all_ff) return -1;
    /* NVMe SMART/Health Information: bits 6..7 are reserved. */
    if ((page[0] & 0xc0) != 0) return -1;
    if (page[3] > 100 || page[4] > 100) return -1;
    /* Zero means temperature unavailable. Otherwise require a plausible
     * Kelvin value; 200..500 K covers normal device operating conditions. */
    unsigned int kelvin = (unsigned int)page[1] | ((unsigned int)page[2] << 8);
    if (kelvin != 0 && (kelvin < 200 || kelvin > 500)) return -1;
    /* Percent Used is intentionally not capped: NVMe allows it to exceed 100. */
    return 0;
}

typedef struct {
    int temperature_c, has_temperature;
    int health_percent, has_health;
    uint64_t reallocated_sectors, pending_sectors, uncorrectable_sectors;
    uint64_t power_cycles, power_hours, total_bytes_written;
    int has_reallocated, has_pending, has_uncorrectable;
    int has_power_cycles, has_power_hours, has_total_bytes_written;
    const char *status;
} sata_metrics;

/* Parse only the standard 30 ATA SMART attribute slots. A successful SG_IO
 * alone is not enough to infer temperature, health, or zero-valued counters. */
static int extract_sata_metrics(const unsigned char *page, size_t len, sata_metrics *m) {
    if (!page || len != 512 || !m) return -1;
    int all_zero = 1, all_ff = 1;
    for (size_t i = 0; i < len; i++) {
        if (page[i] != 0) all_zero = 0;
        if (page[i] != 0xff) all_ff = 0;
    }
    if (all_zero || all_ff) return -1;
    memset(m, 0, sizeof(*m));
    for (int i = 0; i < 30; i++) {
        const unsigned char *attr = page + 2 + i * 12;
        int id = attr[0];
        if (id == 0) continue;
        int current = attr[3];
        uint64_t raw = (uint64_t)attr[5] | ((uint64_t)attr[6] << 8) |
                       ((uint64_t)attr[7] << 16) | ((uint64_t)attr[8] << 24) |
                       ((uint64_t)attr[9] << 32) | ((uint64_t)attr[10] << 40);
        if (id == 0x05) { m->reallocated_sectors = raw; m->has_reallocated = 1; }
        else if (id == 0x09) { m->power_hours = raw; m->has_power_hours = 1; }
        else if (id == 0x0C) { m->power_cycles = raw; m->has_power_cycles = 1; }
        else if (id == 0xC2 || id == 0xBE) { m->temperature_c = (int)(raw & 0xff); m->has_temperature = 1; }
        else if (id == 0xC5) { m->pending_sectors = raw; m->has_pending = 1; }
        else if (id == 0xC6) { m->uncorrectable_sectors = raw; m->has_uncorrectable = 1; }
        else if (id == 0xE7 || id == 0xA9 || id == 0xB1 || id == 0xE8) {
            if (current > 0 && current <= 100) { m->health_percent = current; m->has_health = 1; }
            else if (raw > 0 && raw <= 100) { m->health_percent = (int)raw; m->has_health = 1; }
        } else if (id == 0xF1) {
            m->total_bytes_written = raw * 512ULL; m->has_total_bytes_written = 1;
        }
    }
    int has_bad = (m->has_reallocated && m->reallocated_sectors > 0) ||
                  (m->has_pending && m->pending_sectors > 0) ||
                  (m->has_uncorrectable && m->uncorrectable_sectors > 0);
    if (m->has_uncorrectable && m->uncorrectable_sectors > 0) m->status = "CRITICAL";
    else if (has_bad) m->status = "WARNING";
    else if (m->has_reallocated && m->has_pending && m->has_uncorrectable) m->status = "HEALTHY";
    else m->status = "UNCONFIRMED";
    return 0;
}

static const char *format_optional_u64(uint64_t value, int present, char *buf, size_t max) {
    if (!present) return "null";
    snprintf(buf, max, "%llu", (unsigned long long)value);
    return buf;
}

static int format_nvme_temperature_json(const unsigned char *page, size_t len,
                                        char *out, size_t out_max) {
    if (!page || len < 3 || !out || out_max == 0) return -1;
    unsigned int kelvin = (unsigned int)page[1] | ((unsigned int)page[2] << 8);
    if (kelvin == 0) {
        int n = snprintf(out, out_max, "null");
        return n >= 0 && (size_t)n < out_max ? n : -1;
    }
    int n = snprintf(out, out_max, "%d", (int)kelvin - 273);
    return n >= 0 && (size_t)n < out_max ? n : -1;
}

/* Convert a little-endian 128-bit NVMe counter to exact decimal without
 * relying on compiler-specific wide integer types. The decimal digits are
 * multiplied by scale after conversion, so scaled values retain all 128 bits. */
static int format_nvme_counter_json(const unsigned char *counter, size_t len,
                                    unsigned int scale, char *out, size_t out_max) {
    if (!counter || len != 16 || (scale != 1 && scale != 512000) || !out || out_max == 0) return -1;
    out[0] = '\0';
    unsigned char digits[64] = {0}; /* least-significant decimal digit first */
    size_t digit_count = 1;
    for (size_t i = 16; i > 0; i--) {
        unsigned int carry = counter[i - 1];
        for (size_t j = 0; j < digit_count; j++) {
            unsigned int value = (unsigned int)digits[j] * 256U + carry;
            digits[j] = (unsigned char)(value % 10U);
            carry = value / 10U;
        }
        while (carry != 0) {
            if (digit_count >= sizeof(digits)) return -1;
            digits[digit_count++] = (unsigned char)(carry % 10U);
            carry /= 10U;
        }
    }
    unsigned int carry = 0;
    for (size_t j = 0; j < digit_count; j++) {
        unsigned int value = (unsigned int)digits[j] * scale + carry;
        digits[j] = (unsigned char)(value % 10U);
        carry = value / 10U;
    }
    while (carry != 0) {
        if (digit_count >= sizeof(digits)) return -1;
        digits[digit_count++] = (unsigned char)(carry % 10U);
        carry /= 10U;
    }
    if (digit_count + 1 > out_max) return -1;
    for (size_t j = 0; j < digit_count; j++) out[j] = (char)('0' + digits[digit_count - 1 - j]);
    out[digit_count] = '\0';
    return (int)digit_count;
}

static int format_nvme_counters_json(const unsigned char *page, size_t len,
                                     char *out, size_t out_max) {
    if (!page || len < 192 || !out || out_max == 0) return -1;
    char power_cycles[64], power_hours[64], unsafe_shutdowns[64];
    char bytes_read[64], bytes_written[64], host_reads[64], host_writes[64];
    char ctrl_busy[64], media_errs[64], err_entries[64];
    if (format_nvme_counter_json(page + 112, 16, 1, power_cycles, sizeof(power_cycles)) < 0 ||
        format_nvme_counter_json(page + 128, 16, 1, power_hours, sizeof(power_hours)) < 0 ||
        format_nvme_counter_json(page + 144, 16, 1, unsafe_shutdowns, sizeof(unsafe_shutdowns)) < 0 ||
        format_nvme_counter_json(page + 32, 16, 512000, bytes_read, sizeof(bytes_read)) < 0 ||
        format_nvme_counter_json(page + 48, 16, 512000, bytes_written, sizeof(bytes_written)) < 0 ||
        format_nvme_counter_json(page + 64, 16, 1, host_reads, sizeof(host_reads)) < 0 ||
        format_nvme_counter_json(page + 80, 16, 1, host_writes, sizeof(host_writes)) < 0 ||
        format_nvme_counter_json(page + 96, 16, 1, ctrl_busy, sizeof(ctrl_busy)) < 0 ||
        format_nvme_counter_json(page + 160, 16, 1, media_errs, sizeof(media_errs)) < 0 ||
        format_nvme_counter_json(page + 176, 16, 1, err_entries, sizeof(err_entries)) < 0) return -1;
    /* Worst-case numeric content is 402 digits (2 scaled 128-bit and 8
     * unscaled counters); a 1024-byte fragment covers names and punctuation. */
    int n = snprintf(out, out_max,
        "\"power_cycles\":\"%s\",\"power_hours\":\"%s\",\"unsafe_shutdowns\":\"%s\","
        "\"total_bytes_read\":\"%s\",\"total_bytes_written\":\"%s\","
        "\"host_read_commands\":\"%s\",\"host_write_commands\":\"%s\","
        "\"controller_busy_time\":\"%s\",\"media_errors\":\"%s\",\"error_log_entries\":\"%s\"",
        power_cycles, power_hours, unsafe_shutdowns, bytes_read, bytes_written,
        host_reads, host_writes, ctrl_busy, media_errs, err_entries);
    return n >= 0 && (size_t)n < out_max ? n : -1;
}

static const char *nvme_status(int critical_warning, int available_spare,
                               int spare_threshold, int percentage_used) {
    if (critical_warning != 0) return "CRITICAL";
    if (available_spare < spare_threshold || percentage_used >= 100) return "WARNING";
    return "HEALTHY";
}

static int format_sata_metrics_json(const unsigned char *page, size_t len,
                                    char *out, size_t out_max) {
    sata_metrics metrics;
    sata_metrics *m = &metrics;
    if (!out || out_max == 0 || extract_sata_metrics(page, len, m) != 0) return -1;
    char temp[24], health[24], reallocated[24], pending[24], uncorrectable[24];
    char cycles[24], hours[24], written[24];
    const char *temp_s = m->has_temperature ? format_optional_u64((uint64_t)m->temperature_c, 1, temp, sizeof(temp)) : "null";
    const char *health_s = m->has_health ? format_optional_u64((uint64_t)m->health_percent, 1, health, sizeof(health)) : "null";
    int n = snprintf(out, out_max,
        "\"status\":\"%s\",\"temperature_c\":%s,\"health_percent\":%s,"
        "\"reallocated_sectors\":%s,\"pending_sectors\":%s,\"uncorrectable_sectors\":%s,"
        "\"power_cycles\":%s,\"power_hours\":%s,\"total_bytes_written\":%s",
        m->status, temp_s, health_s,
        format_optional_u64(m->reallocated_sectors, m->has_reallocated, reallocated, sizeof(reallocated)),
        format_optional_u64(m->pending_sectors, m->has_pending, pending, sizeof(pending)),
        format_optional_u64(m->uncorrectable_sectors, m->has_uncorrectable, uncorrectable, sizeof(uncorrectable)),
        format_optional_u64(m->power_cycles, m->has_power_cycles, cycles, sizeof(cycles)),
        format_optional_u64(m->power_hours, m->has_power_hours, hours, sizeof(hours)),
        format_optional_u64(m->total_bytes_written, m->has_total_bytes_written, written, sizeof(written)));
    return n >= 0 && (size_t)n < out_max ? n : -1;
}

#ifdef SMART_READER_TEST
int smart_test_detect_usb_ids(const char *sysfs_path, unsigned int *vendor, unsigned int *product) {
    return detect_usb_ids_from_sysfs(sysfs_path, vendor, product);
}
void smart_test_build_rtl_e4_cdb(unsigned char cdb[16], unsigned int length,
                                 unsigned char opcode, unsigned int cdw10) {
    build_rtl_e4_cdb(cdb, length, opcode, cdw10);
}
int smart_test_parse_nvme_identity(const unsigned char *id, size_t len,
                                   char *model, size_t model_max,
                                   char *serial, size_t serial_max,
                                   char *firmware, size_t firmware_max) {
    return parse_nvme_identity(id, len, model, model_max, serial, serial_max, firmware, firmware_max);
}
int smart_test_validate_nvme_smart_log(const unsigned char *page, size_t len) {
    return validate_nvme_smart_log(page, len);
}
int smart_test_sg_response_ok(int status, int host_status, int driver_status,
                              int resid, int data_len) {
    return sg_response_ok(status, host_status, driver_status, resid, data_len);
}
int smart_test_format_sata_metrics_json(const unsigned char *page, size_t len,
                                        char *out, size_t out_max) {
    return format_sata_metrics_json(page, len, out, out_max);
}
int smart_test_format_nvme_temperature_json(const unsigned char *page, size_t len,
                                            char *out, size_t out_max) {
    return format_nvme_temperature_json(page, len, out, out_max);
}
int smart_test_format_nvme_counter_json(const unsigned char *counter, size_t len,
                                        unsigned int scale, char *out, size_t out_max) {
    return format_nvme_counter_json(counter, len, scale, out, out_max);
}
int smart_test_format_nvme_counters_json(const unsigned char *page, size_t len,
                                         char *out, size_t out_max) {
    return format_nvme_counters_json(page, len, out, out_max);
}
const char *smart_test_nvme_status(int critical_warning, int available_spare,
                                   int spare_threshold, int percentage_used) {
    return nvme_status(critical_warning, available_spare, spare_threshold, percentage_used);
}
#endif

static void trim_trailing(char *s) {
    if (!s) return;
    int len = (int)strlen(s);
    while (len > 0 && (s[len - 1] == ' ' || s[len - 1] == '\t' || s[len - 1] == '\r' || s[len - 1] == '\n')) {
        s[len - 1] = '\0';
        len--;
    }
}

static void escape_json_str(const char *in, char *out, size_t out_max) {
    size_t o = 0;
    for (size_t i = 0; in[i] && o + 2 < out_max; i++) {
        if (in[i] == '"' || in[i] == '\\') {
            out[o++] = '\\';
            out[o++] = in[i];
        } else if ((unsigned char)in[i] >= 32 && (unsigned char)in[i] < 127) {
            out[o++] = in[i];
        }
    }
    out[o] = '\0';
}

int read_smart_json(const char *dev_path, char *json, size_t json_max) {
    if (!dev_path || !json || json_max == 0) return -EINVAL;

    struct stat st;
    if (stat(dev_path, &st) < 0) {
        snprintf(json, json_max, "{\"supported\":false,\"status\":\"UNCONFIRMED\",\"reason\":\"Cannot stat device: %s\"}", strerror(errno));
        return -errno;
    }

    char actual_dev[256];
    int bridge = USB_BRIDGE_UNKNOWN;
    unsigned int usb_vendor = 0, usb_product = 0;
    if (resolve_block_topology(dev_path, &st, actual_dev, sizeof(actual_dev),
                               &bridge, &usb_vendor, &usb_product) != 0) {
        snprintf(json, json_max, "{\"supported\":false,\"status\":\"UNCONFIRMED\",\"reason\":\"Cannot resolve physical block-device USB ancestry safely.\"}");
        return 0;
    }

    int fd = open(actual_dev, O_RDWR | O_LARGEFILE | O_CLOEXEC);
    if (fd < 0) {
        fd = open(actual_dev, O_RDONLY | O_LARGEFILE | O_CLOEXEC);
    }
    if (fd < 0) {
        snprintf(json, json_max, "{\"supported\":false,\"status\":\"UNCONFIRMED\",\"reason\":\"Cannot open %s: %s\"}", actual_dev, strerror(errno));
        return -errno;
    }

    unsigned char sense[32];
    char vendor[32] = {0}, product[32] = {0}, rev[16] = {0};
    char model[64] = {0}, serial[64] = {0}, firmware[32] = {0};
    const char *disk_type = "Unknown";
    int is_nvme = 0;

    // 1. SCSI INQUIRY (36 bytes)
    unsigned char inq_cdb[6] = { 0x12, 0, 0, 0, 36, 0 };
    unsigned char inq_data[36];
    memset(inq_data, 0, sizeof(inq_data));
    memset(sense, 0, sizeof(sense));
    if (do_sg_io(fd, inq_cdb, 6, inq_data, 36, SG_DXFER_FROM_DEV, sense, sizeof(sense)) == 0) {
        memcpy(vendor, inq_data + 8, 8);
        memcpy(product, inq_data + 16, 16);
        memcpy(rev, inq_data + 32, 4);
        trim_trailing(vendor);
        trim_trailing(product);
        trim_trailing(rev);
    }

    unsigned char nvme_smart[512];
    memset(nvme_smart, 0, sizeof(nvme_smart));
    /* USB command policy is based on the resolved physical USB ancestor.
     * Unknown bridges receive no SAT or vendor-specific commands. */
    if (bridge == USB_BRIDGE_UNKNOWN) {
        close(fd);
        snprintf(json, json_max,
            "{\"supported\":false,\"status\":\"UNCONFIRMED\",\"disk_type\":\"USB Storage\",\"reason\":\"SMART passthrough is disabled for unrecognized USB bridges.\"}");
        return 0;
    }

    if (bridge == USB_BRIDGE_RTL9210) {
        unsigned char id_cdb[16], identify[4096];
        memset(identify, 0, sizeof(identify));
        build_rtl_e4_cdb(id_cdb, sizeof(identify), 0x06, 0x01);
        memset(sense, 0, sizeof(sense));
        if (do_sg_io(fd, id_cdb, sizeof(id_cdb), identify, sizeof(identify),
                     SG_DXFER_FROM_DEV, sense, sizeof(sense)) != 0 ||
            parse_nvme_identity(identify, sizeof(identify), model, sizeof(model),
                                serial, sizeof(serial), firmware, sizeof(firmware)) != 0) {
            close(fd);
            snprintf(json, json_max,
                "{\"supported\":false,\"status\":\"UNCONFIRMED\",\"reason\":\"RTL9210 NVMe Identify failed validation; no fallback bridge commands were sent.\"}");
            return 0;
        }
        unsigned char log_cdb[16];
        build_rtl_e4_cdb(log_cdb, sizeof(nvme_smart), 0x02, 0x02);
        memset(sense, 0, sizeof(sense));
        if (do_sg_io(fd, log_cdb, sizeof(log_cdb), nvme_smart, sizeof(nvme_smart),
                     SG_DXFER_FROM_DEV, sense, sizeof(sense)) != 0) {
            close(fd);
            snprintf(json, json_max,
                "{\"supported\":false,\"status\":\"UNCONFIRMED\",\"reason\":\"RTL9210 NVMe SMART log transfer failed; no fallback bridge commands were sent.\"}");
            return 0;
        }
        if (validate_nvme_smart_log(nvme_smart, sizeof(nvme_smart)) != 0) {
            close(fd);
            snprintf(json, json_max,
                "{\"supported\":false,\"status\":\"UNCONFIRMED\",\"reason\":\"RTL9210 returned an invalid NVMe SMART log; telemetry was not accepted.\"}");
            return 0;
        }
        is_nvme = 1;
        disk_type = "NVMe SSD";
    } else {
    // 2. SAT ATA IDENTIFY (0xEC), retained only for confirmed non-USB paths.
    unsigned char ata_id_cdb[16];
    memset(ata_id_cdb, 0, sizeof(ata_id_cdb));
    ata_id_cdb[0] = 0x85;
    ata_id_cdb[1] = (4 << 1); // PIO Data-In
    ata_id_cdb[2] = 0x0E;
    ata_id_cdb[6] = 1;        // 1 sector
    ata_id_cdb[14] = 0xEC;    // IDENTIFY DEVICE
    unsigned char ata_id[512];
    memset(ata_id, 0, sizeof(ata_id));
    memset(sense, 0, sizeof(sense));
    int has_ata_id = (do_sg_io(fd, ata_id_cdb, 16, ata_id, 512, SG_DXFER_FROM_DEV, sense, sizeof(sense)) == 0);
    if (has_ata_id) {
        for (int i = 0; i < 20; i++) {
            model[i * 2] = ata_id[54 + i * 2 + 1];
            model[i * 2 + 1] = ata_id[54 + i * 2];
        }
        for (int i = 0; i < 10; i++) {
            serial[i * 2] = ata_id[20 + i * 2 + 1];
            serial[i * 2 + 1] = ata_id[20 + i * 2];
        }
        for (int i = 0; i < 4; i++) {
            firmware[i * 2] = ata_id[46 + i * 2 + 1];
            firmware[i * 2 + 1] = ata_id[46 + i * 2];
        }
        trim_trailing(model);
        trim_trailing(serial);
        trim_trailing(firmware);

        uint16_t rot_rate = (uint16_t)ata_id[434] | ((uint16_t)ata_id[435] << 8);
        if (rot_rate == 0x0001) {
            disk_type = "SSD";
        } else if (rot_rate > 1) {
            disk_type = "HDD";
        }
    }

    }

    if (is_nvme) {
        int crit_warn = nvme_smart[0];
        char temp_text[24];
        format_nvme_temperature_json(nvme_smart, sizeof(nvme_smart), temp_text, sizeof(temp_text));
        int avail_spare = nvme_smart[3];
        int spare_thresh = nvme_smart[4];
        int percent_used = nvme_smart[5];
        int health_pct = percent_used <= 100 ? (100 - percent_used) : 0;
        char nvme_counter_fields[1024];
        if (format_nvme_counters_json(nvme_smart, sizeof(nvme_smart),
                                      nvme_counter_fields, sizeof(nvme_counter_fields)) < 0) {
            close(fd);
            snprintf(json, json_max, "{\"supported\":false,\"status\":\"UNCONFIRMED\",\"reason\":\"NVMe counters could not be formatted safely.\"}");
            return 0;
        }

        char raw_page_hex[1025];
        for (int i = 0; i < 512; i++) {
            snprintf(raw_page_hex + i * 2, 3, "%02X", nvme_smart[i]);
        }
        raw_page_hex[1024] = '\0';

        const char *status_str = nvme_status(crit_warn, avail_spare, spare_thresh, percent_used);

        char esc_model[64], esc_serial[64], esc_fw[32], esc_vendor[32], esc_prod[32];
        escape_json_str(model[0] ? model : product, esc_model, sizeof(esc_model));
        escape_json_str(serial, esc_serial, sizeof(esc_serial));
        escape_json_str(firmware[0] ? firmware : rev, esc_fw, sizeof(esc_fw));
        escape_json_str(vendor, esc_vendor, sizeof(esc_vendor));
        escape_json_str(product, esc_prod, sizeof(esc_prod));

        /* Under the daemon's 4096-byte JSON buffer, the fixed response fields
         * plus bounded identity strings (<=219), device path (<=255), raw
         * page (1024), and counter fragment (<1024) stay below 3000 bytes. */
        snprintf(json, json_max,
            "{"
            "\"supported\":true,"
            "\"status\":\"%s\","
            "\"disk_type\":\"%s\","
            "\"model\":\"%s\","
            "\"serial\":\"%s\","
            "\"firmware\":\"%s\","
            "\"vendor\":\"%s\","
            "\"product\":\"%s\","
            "\"temperature_c\":%s,"
            "\"health_percent\":%d,"
            "\"available_spare\":%d,"
            "\"spare_threshold\":%d,"
            "\"critical_warning\":%d,"
            "%s,"
            "\"device_node\":\"%s\","
            "\"raw_page_hex\":\"%s\""
            "}",
            status_str, disk_type, esc_model, esc_serial, esc_fw,
            esc_vendor, esc_prod, temp_text, health_pct, avail_spare,
            spare_thresh, crit_warn, nvme_counter_fields,
            actual_dev,
            raw_page_hex
        );
        close(fd);
        return 0;
    }

    // 6. Try SATA ATA SMART READ DATA (0xB0, 0xD0)
    unsigned char smart_cdb[16];
    memset(smart_cdb, 0, sizeof(smart_cdb));
    smart_cdb[0] = 0x85;
    smart_cdb[1] = (4 << 1);
    smart_cdb[2] = 0x0E;
    smart_cdb[4] = 0xD0;
    smart_cdb[6] = 1;
    smart_cdb[10] = 0x4F;
    smart_cdb[12] = 0xC2;
    smart_cdb[14] = 0xB0;
    unsigned char smart_data[512];
    memset(smart_data, 0, sizeof(smart_data));
    memset(sense, 0, sizeof(sense));
    int has_sata_smart = (do_sg_io(fd, smart_cdb, 16, smart_data, 512, SG_DXFER_FROM_DEV, sense, sizeof(sense)) == 0);
    char sata_fields[512];
    int valid_sata_smart = has_sata_smart && format_sata_metrics_json(smart_data, sizeof(smart_data), sata_fields, sizeof(sata_fields)) >= 0;

    if (valid_sata_smart) {
        char esc_model[64], esc_serial[64], esc_fw[32], esc_vendor[32], esc_prod[32];
        escape_json_str(model[0] ? model : product, esc_model, sizeof(esc_model));
        escape_json_str(serial, esc_serial, sizeof(esc_serial));
        escape_json_str(firmware[0] ? firmware : rev, esc_fw, sizeof(esc_fw));
        escape_json_str(vendor, esc_vendor, sizeof(esc_vendor));
        escape_json_str(product, esc_prod, sizeof(esc_prod));

        char raw_page_hex[1025];
        for (int i = 0; i < 512; i++) {
            snprintf(raw_page_hex + i * 2, 3, "%02X", smart_data[i]);
        }
        raw_page_hex[1024] = '\0';

        snprintf(json, json_max,
            "{"
            "\"supported\":true,"
            "\"disk_type\":\"%s\","
            "\"model\":\"%s\","
            "\"serial\":\"%s\","
            "\"firmware\":\"%s\","
            "\"vendor\":\"%s\","
            "\"product\":\"%s\","
            "%s,"
            "\"device_node\":\"%s\","
            "\"raw_page_hex\":\"%s\""
            "}",
            disk_type, esc_model, esc_serial, esc_fw,
            esc_vendor, esc_prod, sata_fields,
            actual_dev,
            raw_page_hex
        );
        close(fd);
        return 0;
    }

    // 7. A failed command or empty page cannot establish lack of SMART capability.
    char esc_vendor[32], esc_prod[32], esc_rev[16];
    escape_json_str(vendor, esc_vendor, sizeof(esc_vendor));
    escape_json_str(product, esc_prod, sizeof(esc_prod));
    escape_json_str(rev, esc_rev, sizeof(esc_rev));

    snprintf(json, json_max,
        "{"
        "\"supported\":false,"
        "\"status\":\"UNCONFIRMED\","
        "\"disk_type\":\"Unknown\","
        "\"vendor\":\"%s\","
        "\"product\":\"%s\","
        "\"firmware\":\"%s\","
        "\"device_node\":\"%s\","
        "\"reason\":\"%s\""
        "}",
        esc_vendor, esc_prod, esc_rev, actual_dev,
        has_sata_smart ? "The ATA SMART command returned an empty or invalid data page; capability is unconfirmed." :
                         "The ATA SMART command failed; capability is unconfirmed."
    );

    close(fd);
    return 0;
}

int scsi_spindown_device(const char *dev_path) {
    if (!dev_path) return -EINVAL;

    struct stat st;
    if (stat(dev_path, &st) < 0) {
        return -errno;
    }

    char actual_dev[256];
    strncpy(actual_dev, dev_path, sizeof(actual_dev) - 1);
    actual_dev[sizeof(actual_dev) - 1] = '\0';

    char parent_disk_name[64] = {0};

    if (S_ISBLK(st.st_mode)) {
        unsigned int maj = major(st.st_rdev);
        unsigned int min = minor(st.st_rdev);
        char part_path[256];
        snprintf(part_path, sizeof(part_path), "/sys/dev/block/%u:%u/partition", maj, min);
        if (access(part_path, F_OK) == 0) {
            char parent_dev_path[256];
            snprintf(parent_dev_path, sizeof(parent_dev_path), "/sys/dev/block/%u:%u/../dev", maj, min);
            FILE *f = fopen(parent_dev_path, "r");
            if (f) {
                unsigned int pmaj = 0, pmin = 0;
                if (fscanf(f, "%u:%u", &pmaj, &pmin) == 2) {
                    char link_buf[256];
                    snprintf(link_buf, sizeof(link_buf), "/sys/dev/block/%u:%u", pmaj, pmin);
                    char real_path[PATH_MAX];
                    if (realpath(link_buf, real_path)) {
                        char *bname = strrchr(real_path, '/');
                        if (bname) {
                            snprintf(actual_dev, sizeof(actual_dev), "/dev/block/%s", bname + 1);
                            strncpy(parent_disk_name, bname + 1, sizeof(parent_disk_name) - 1);
                        }
                    }
                }
                fclose(f);
            }
        } else {
            char link_buf[256];
            snprintf(link_buf, sizeof(link_buf), "/sys/dev/block/%u:%u", maj, min);
            char real_path[PATH_MAX];
            if (realpath(link_buf, real_path)) {
                char *bname = strrchr(real_path, '/');
                if (bname) {
                    strncpy(parent_disk_name, bname + 1, sizeof(parent_disk_name) - 1);
                }
            }
        }
    }

    if (parent_disk_name[0] == '\0') {
        const char *b = strrchr(actual_dev, '/');
        if (b) {
            strncpy(parent_disk_name, b + 1, sizeof(parent_disk_name) - 1);
        }
    }

    // CRITICAL SAFETY CHECK: Refuse to touch internal Android flash storage
    char sys_block[256];
    snprintf(sys_block, sizeof(sys_block), "/sys/block/%s", parent_disk_name);
    char real_sys[PATH_MAX] = {0};
    if (realpath(sys_block, real_sys)) {
        if (strstr(real_sys, "ufshc") || strstr(real_sys, "bootdevice") || strstr(real_sys, "mmcblk")) {
            return -EPERM;
        }
        if (!strstr(real_sys, "usb") && !strstr(real_sys, "target")) {
            return -EPERM;
        }
    } else {
        return -ENOENT;
    }

    // Step 1: System-wide sync and block buffer flush
    sync();

    int fd = open(actual_dev, O_RDWR | O_LARGEFILE | O_CLOEXEC);
    if (fd < 0) {
        fd = open(actual_dev, O_RDONLY | O_LARGEFILE | O_CLOEXEC);
    }
    if (fd < 0) {
        return -errno != 0 ? -errno : -ENODEV;
    }

    ioctl(fd, BLKFLSBUF, 0);

    unsigned char sense[32];

    // Step 2: SCSI SYNCHRONIZE CACHE (10) (0x35)
    // Synchronize disk internal write cache before spindown or offline
    unsigned char sync_cdb[10] = { 0x35, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    memset(sense, 0, sizeof(sense));
    int sync_ret = do_sg_io(fd, sync_cdb, 10, NULL, 0, SG_DXFER_NONE, sense, sizeof(sense));
    if (sync_ret != 0) {
        int sense_key = (sense[0] & 0x7E) ? (sense[2] & 0x0F) : -1;
        // 0x05 is ILLEGAL REQUEST (e.g. simple thumb drive without volatile cache)
        if (sense_key != 0x05) {
            close(fd);
            return -EIO;
        }
    }

    // Step 3: SCSI START STOP UNIT (0x1B) with START=0 (Stop unit / spindown)
    unsigned char stop_cdb[6] = { 0x1B, 0, 0, 0, 0x00, 0 };
    memset(sense, 0, sizeof(sense));
    int stop_ret = do_sg_io(fd, stop_cdb, 6, NULL, 0, SG_DXFER_NONE, sense, sizeof(sense));
    close(fd);

    if (stop_ret != 0) {
        int sense_key = (sense[0] & 0x7E) ? (sense[2] & 0x0F) : -1;
        // If not ILLEGAL REQUEST, abort offlining
        if (sense_key != 0x05) {
            return -EIO;
        }
    }

    // Step 4: Configure sysfs manage_start_stop = 1 if present
    char scsi_disk_dir[256];
    snprintf(scsi_disk_dir, sizeof(scsi_disk_dir), "/sys/block/%s/device/scsi_disk", parent_disk_name);
    DIR *d = opendir(scsi_disk_dir);
    if (d) {
        struct dirent *de;
        while ((de = readdir(d)) != NULL) {
            if (de->d_name[0] == '.') continue;
            char mss_path[512];
            snprintf(mss_path, sizeof(mss_path), "%s/%s/manage_start_stop", scsi_disk_dir, de->d_name);
            int mss_fd = open(mss_path, O_WRONLY);
            if (mss_fd >= 0) {
                write(mss_fd, "1\n", 2);
                close(mss_fd);
            }
        }
        closedir(d);
    }

    // Step 5: Inform Linux SCSI subsystem to offline & delete device
    char delete_path[256];
    snprintf(delete_path, sizeof(delete_path), "/sys/block/%s/device/delete", parent_disk_name);
    int del_fd = open(delete_path, O_WRONLY);
    if (del_fd >= 0) {
        write(del_fd, "1\n", 2);
        close(del_fd);
    }

    // Step 6: Walk up parent sysfs directory to set USB power/control to auto
    char cur_path[PATH_MAX];
    strncpy(cur_path, real_sys, sizeof(cur_path) - 1);
    cur_path[sizeof(cur_path) - 1] = '\0';
    for (int i = 0; i < 8; i++) {
        char *slash = strrchr(cur_path, '/');
        if (!slash) break;
        *slash = '\0';
        char busnum_path[PATH_MAX];
        snprintf(busnum_path, sizeof(busnum_path), "%s/busnum", cur_path);
        if (access(busnum_path, F_OK) == 0) {
            char pwr_ctrl[PATH_MAX];
            snprintf(pwr_ctrl, sizeof(pwr_ctrl), "%s/power/control", cur_path);
            int pwr_fd = open(pwr_ctrl, O_WRONLY);
            if (pwr_fd >= 0) {
                write(pwr_fd, "auto\n", 5);
                close(pwr_fd);
            }
            char delay_path[PATH_MAX];
            snprintf(delay_path, sizeof(delay_path), "%s/power/autosuspend_delay_ms", cur_path);
            int delay_fd = open(delay_path, O_WRONLY);
            if (delay_fd >= 0) {
                write(delay_fd, "0\n", 2);
                close(delay_fd);
            }
            break;
        }
    }

    return 0;
}
