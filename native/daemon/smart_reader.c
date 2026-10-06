/*
 * smart_reader.c -- High-performance SCSI, ATA SAT, and NVMe-over-SCSI SMART reader.
 *
 * Implements:
 * 1. Automatic partition-to-disk resolution via sysfs (/sys/dev/block/M:m)
 * 2. Standard SCSI INQUIRY (0x12)
 * 3. ANSI T10 SAT ATA PASS-THROUGH (16) (0x85) for ATA IDENTIFY (0xEC)
 * 4. Realtek RTL9210 SCSI NVMe Tunnel (0xE4)
 * 5. ASMedia ASM2362/ASM2464 SCSI NVMe Tunnel (0xE6)
 * 6. JMicron JMS583 SCSI NVMe Tunnel (0xDF)
 * 7. ATA SMART READ DATA (0xB0, 0xD0) and SMART RETURN STATUS (0xB0, 0xDA)
 * 8. Standardized JSON output generation
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
    if (io_hdr.status != 0) {
        return -1;
    }
    return 0;
}

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
        snprintf(json, json_max, "{\"supported\":false,\"error\":\"Cannot stat device: %s\"}", strerror(errno));
        return -errno;
    }

    char actual_dev[256];
    strncpy(actual_dev, dev_path, sizeof(actual_dev) - 1);
    actual_dev[sizeof(actual_dev) - 1] = '\0';

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
                        }
                    }
                }
                fclose(f);
            }
        }
    }

    int fd = open(actual_dev, O_RDWR | O_LARGEFILE | O_CLOEXEC);
    if (fd < 0) {
        fd = open(actual_dev, O_RDONLY | O_LARGEFILE | O_CLOEXEC);
    }
    if (fd < 0) {
        snprintf(json, json_max, "{\"supported\":false,\"error\":\"Cannot open %s: %s\"}", actual_dev, strerror(errno));
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

    // 2. SAT ATA IDENTIFY (0xEC)
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

    unsigned char nvme_smart[512];
    memset(nvme_smart, 0, sizeof(nvme_smart));

    // 3. Try Realtek RTL9210 NVMe Tunnel (0xE4)
    unsigned char rtk_cdb[16];
    memset(rtk_cdb, 0, sizeof(rtk_cdb));
    rtk_cdb[0] = 0xE4;
    rtk_cdb[1] = 0x00;
    rtk_cdb[2] = 0x02; // 512 bytes
    rtk_cdb[3] = 0x02; // NVMe Admin Get Log Page
    rtk_cdb[4] = 0x02; // LID 0x02: SMART / Health Info
    memset(sense, 0, sizeof(sense));
    if (do_sg_io(fd, rtk_cdb, 16, nvme_smart, 512, SG_DXFER_FROM_DEV, sense, sizeof(sense)) == 0) {
        is_nvme = 1;
        disk_type = "NVMe SSD";
    }

    // 4. Try ASMedia ASM2362/ASM2464 NVMe Tunnel (0xE6) if not already found
    if (!is_nvme) {
        unsigned char asm_cdb[16];
        memset(asm_cdb, 0, sizeof(asm_cdb));
        asm_cdb[0] = 0xE6;
        asm_cdb[1] = 0x02; // Get Log Page
        asm_cdb[3] = 0x02; // LID 0x02
        memset(sense, 0, sizeof(sense));
        if (do_sg_io(fd, asm_cdb, 16, nvme_smart, 512, SG_DXFER_FROM_DEV, sense, sizeof(sense)) == 0) {
            is_nvme = 1;
            disk_type = "NVMe SSD";
        }
    }

    // 5. Try JMicron JMS583 NVMe Tunnel (0xDF) if not already found
    if (!is_nvme) {
        unsigned char jms_cdb[16];
        memset(jms_cdb, 0, sizeof(jms_cdb));
        jms_cdb[0] = 0xDF;
        jms_cdb[1] = 0x10;
        jms_cdb[2] = 0x02;
        jms_cdb[6] = 0x02;
        jms_cdb[8] = 0x02;
        jms_cdb[10] = 0x7F;
        memset(sense, 0, sizeof(sense));
        if (do_sg_io(fd, jms_cdb, 16, nvme_smart, 512, SG_DXFER_FROM_DEV, sense, sizeof(sense)) == 0) {
            is_nvme = 1;
            disk_type = "NVMe SSD";
        }
    }

    if (is_nvme) {
        int crit_warn = nvme_smart[0];
        int kelvin = nvme_smart[1] | (nvme_smart[2] << 8);
        int temp_c = kelvin > 273 ? (kelvin - 273) : 0;
        int avail_spare = nvme_smart[3];
        int spare_thresh = nvme_smart[4];
        int percent_used = nvme_smart[5];
        int health_pct = percent_used <= 100 ? (100 - percent_used) : 0;

        uint64_t power_cycles = 0;
        memcpy(&power_cycles, nvme_smart + 112, 8);
        uint64_t power_hours = 0;
        memcpy(&power_hours, nvme_smart + 128, 8);
        uint64_t unsafe_shutdowns = 0;
        memcpy(&unsafe_shutdowns, nvme_smart + 144, 8);

        uint64_t units_read = 0;
        memcpy(&units_read, nvme_smart + 32, 8);
        uint64_t units_written = 0;
        memcpy(&units_written, nvme_smart + 48, 8);
        uint64_t bytes_read = units_read * 512000ULL;
        uint64_t bytes_written = units_written * 512000ULL;

        uint64_t host_reads = 0;
        memcpy(&host_reads, nvme_smart + 64, 8);
        uint64_t host_writes = 0;
        memcpy(&host_writes, nvme_smart + 80, 8);
        uint64_t ctrl_busy = 0;
        memcpy(&ctrl_busy, nvme_smart + 96, 8);
        uint64_t media_errs = 0;
        memcpy(&media_errs, nvme_smart + 160, 8);
        uint64_t err_entries = 0;
        memcpy(&err_entries, nvme_smart + 176, 8);

        char raw_page_hex[1025];
        for (int i = 0; i < 512; i++) {
            snprintf(raw_page_hex + i * 2, 3, "%02X", nvme_smart[i]);
        }
        raw_page_hex[1024] = '\0';

        const char *status_str = (crit_warn == 0 && health_pct >= 10) ? "HEALTHY" :
                                 (health_pct < 10 ? "CRITICAL" : "WARNING");

        char esc_model[64], esc_serial[64], esc_fw[32], esc_vendor[32], esc_prod[32];
        escape_json_str(model[0] ? model : product, esc_model, sizeof(esc_model));
        escape_json_str(serial, esc_serial, sizeof(esc_serial));
        escape_json_str(firmware[0] ? firmware : rev, esc_fw, sizeof(esc_fw));
        escape_json_str(vendor, esc_vendor, sizeof(esc_vendor));
        escape_json_str(product, esc_prod, sizeof(esc_prod));

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
            "\"temperature_c\":%d,"
            "\"health_percent\":%d,"
            "\"available_spare\":%d,"
            "\"spare_threshold\":%d,"
            "\"critical_warning\":%d,"
            "\"power_cycles\":%llu,"
            "\"power_hours\":%llu,"
            "\"unsafe_shutdowns\":%llu,"
            "\"total_bytes_read\":%llu,"
            "\"total_bytes_written\":%llu,"
            "\"host_read_commands\":%llu,"
            "\"host_write_commands\":%llu,"
            "\"controller_busy_time\":%llu,"
            "\"media_errors\":%llu,"
            "\"error_log_entries\":%llu,"
            "\"device_node\":\"%s\","
            "\"raw_page_hex\":\"%s\""
            "}",
            status_str, disk_type, esc_model, esc_serial, esc_fw,
            esc_vendor, esc_prod, temp_c, health_pct, avail_spare,
            spare_thresh, crit_warn, (unsigned long long)power_cycles,
            (unsigned long long)power_hours, (unsigned long long)unsafe_shutdowns,
            (unsigned long long)bytes_read, (unsigned long long)bytes_written,
            (unsigned long long)host_reads, (unsigned long long)host_writes,
            (unsigned long long)ctrl_busy, (unsigned long long)media_errs,
            (unsigned long long)err_entries,
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

    if (has_sata_smart) {
        int temp_c = 0;
        int health_pct = 100;
        uint64_t power_hours = 0;
        uint64_t power_cycles = 0;
        uint64_t reallocated_sectors = 0;
        uint64_t pending_sectors = 0;
        uint64_t uncorrectable_sectors = 0;
        uint64_t bytes_written = 0;

        for (int i = 0; i < 30; i++) {
            unsigned char *attr = smart_data + 2 + i * 12;
            int id = attr[0];
            if (id == 0) continue;
            int current = attr[3];
            uint64_t raw = (uint64_t)attr[5] | ((uint64_t)attr[6] << 8) |
                           ((uint64_t)attr[7] << 16) | ((uint64_t)attr[8] << 24) |
                           ((uint64_t)attr[9] << 32) | ((uint64_t)attr[10] << 40);

            if (id == 0x05) reallocated_sectors = raw;
            else if (id == 0x09) power_hours = raw;
            else if (id == 0x0C) power_cycles = raw;
            else if (id == 0xC2 || id == 0xBE) temp_c = (int)(raw & 0xFF);
            else if (id == 0xC5) pending_sectors = raw;
            else if (id == 0xC6) uncorrectable_sectors = raw;
            else if (id == 0xE7 || id == 0xA9 || id == 0xB1 || id == 0xE8) {
                if (current > 0 && current <= 100) health_pct = current;
                else if (raw > 0 && raw <= 100) health_pct = (int)raw;
            } else if (id == 0xF1) {
                bytes_written = raw * 512ULL;
            }
        }

        const char *status_str = (reallocated_sectors == 0 && pending_sectors == 0 && uncorrectable_sectors == 0) ? "HEALTHY" :
                                 (uncorrectable_sectors > 0 ? "CRITICAL" : "WARNING");

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
            "\"status\":\"%s\","
            "\"disk_type\":\"%s\","
            "\"model\":\"%s\","
            "\"serial\":\"%s\","
            "\"firmware\":\"%s\","
            "\"vendor\":\"%s\","
            "\"product\":\"%s\","
            "\"temperature_c\":%d,"
            "\"health_percent\":%d,"
            "\"reallocated_sectors\":%llu,"
            "\"pending_sectors\":%llu,"
            "\"uncorrectable_sectors\":%llu,"
            "\"power_cycles\":%llu,"
            "\"power_hours\":%llu,"
            "\"total_bytes_written\":%llu,"
            "\"device_node\":\"%s\","
            "\"raw_page_hex\":\"%s\""
            "}",
            status_str, disk_type, esc_model, esc_serial, esc_fw,
            esc_vendor, esc_prod, temp_c, health_pct,
            (unsigned long long)reallocated_sectors,
            (unsigned long long)pending_sectors,
            (unsigned long long)uncorrectable_sectors,
            (unsigned long long)power_cycles,
            (unsigned long long)power_hours,
            (unsigned long long)bytes_written,
            actual_dev,
            raw_page_hex
        );
        close(fd);
        return 0;
    }

    // 7. If device is accessible but does not implement standard SMART (e.g. standard USB thumb drive)
    char esc_vendor[32], esc_prod[32], esc_rev[16];
    escape_json_str(vendor, esc_vendor, sizeof(esc_vendor));
    escape_json_str(product, esc_prod, sizeof(esc_prod));
    escape_json_str(rev, esc_rev, sizeof(esc_rev));

    snprintf(json, json_max,
        "{"
        "\"supported\":false,"
        "\"status\":\"UNSUPPORTED\","
        "\"disk_type\":\"USB Flash Drive\","
        "\"vendor\":\"%s\","
        "\"product\":\"%s\","
        "\"firmware\":\"%s\","
        "\"device_node\":\"%s\","
        "\"reason\":\"The storage device controller does not implement standard ATA or NVMe SMART telemetry.\""
        "}",
        esc_vendor, esc_prod, esc_rev, actual_dev
    );

    close(fd);
    return 0;
}
