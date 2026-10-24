/* Host/Android unit harness for the SMART bridge safety helpers. */
#define SMART_READER_TEST 1
#include "smart_reader.h"

#include <assert.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void make_file(const char *path, const char *contents)
{
	FILE *f = fopen(path, "w");
	assert(f != NULL);
	assert(fputs(contents, f) >= 0);
	assert(fclose(f) == 0);
}

static void test_sysfs_usb_allowlist(void)
{
	const char *base = access("/data/local/tmp", W_OK) == 0 ? "/data/local/tmp" : "/tmp";
	char root[128];
	snprintf(root, sizeof(root), "%s/smart-sysfs-XXXXXX", base);
	assert(mkdtemp(root) != NULL);
	char usb[512], block[512], path[512];
	snprintf(usb, sizeof(usb), "%s/usb-device", root);
	snprintf(block, sizeof(block), "%s/usb-device/block/sda", root);
	snprintf(path, sizeof(path), "%s/usb-device/block", root);
	assert(mkdir(usb, 0700) == 0);
	assert(mkdir(path, 0700) == 0);
	assert(mkdir(block, 0700) == 0);
	unsigned int vid = 0, pid = 0;

	snprintf(path, sizeof(path), "%s/idVendor", usb); make_file(path, "0bda\n");
	snprintf(path, sizeof(path), "%s/idProduct", usb); make_file(path, "9210\n");
	assert(smart_test_detect_usb_ids(block, &vid, &pid) == 2);
	assert(vid == 0x0bda && pid == 0x9210);

	snprintf(path, sizeof(path), "%s/idVendor", usb); make_file(path, "1d6b\n");
	snprintf(path, sizeof(path), "%s/idProduct", usb); make_file(path, "0002\n");
	assert(smart_test_detect_usb_ids(block, &vid, &pid) == 1);
	assert(vid == 0x1d6b && pid == 0x0002);

	snprintf(path, sizeof(path), "%s/idVendor", usb); assert(unlink(path) == 0);
	snprintf(path, sizeof(path), "%s/idProduct", usb); assert(unlink(path) == 0);
	assert(smart_test_detect_usb_ids(block, &vid, &pid) == 0);
	rmdir(block);
	snprintf(path, sizeof(path), "%s/usb-device/block", root); rmdir(path);
	rmdir(usb);
	rmdir(root);
}

static void test_e4_commands_and_identity(void)
{
	unsigned char cdb[16];
	const unsigned char identify_prefix[] = { 0xe4, 0x00, 0x10, 0x06, 0x01 };
	smart_test_build_rtl_e4_cdb(cdb, 4096, 0x06, 0x01);
	assert(memcmp(cdb, identify_prefix, sizeof(identify_prefix)) == 0);
	const unsigned char log_prefix[] = { 0xe4, 0x00, 0x02, 0x02, 0x02 };
	smart_test_build_rtl_e4_cdb(cdb, 512, 0x02, 0x02);
	assert(memcmp(cdb, log_prefix, sizeof(log_prefix)) == 0);

	unsigned char identify[4096] = {0};
	identify[0] = 0x34; identify[1] = 0x12; /* controller VID */
	memcpy(identify + 4, "SERIAL-123           ", 20);
	memcpy(identify + 24, "P202 NVMe Controller                    ", 40);
	memcpy(identify + 64, "1.02    ", 8);
	char model[64], serial[64], firmware[32];
	assert(smart_test_parse_nvme_identity(identify, sizeof(identify), model, sizeof(model),
	                                      serial, sizeof(serial), firmware, sizeof(firmware)) == 0);
	assert(strcmp(model, "P202 NVMe Controller") == 0);
	assert(strcmp(serial, "SERIAL-123") == 0);
	assert(strcmp(firmware, "1.02") == 0);
	memset(identify + 24, 0, 40);
	assert(smart_test_parse_nvme_identity(identify, sizeof(identify), model, sizeof(model),
	                                      serial, sizeof(serial), firmware, sizeof(firmware)) < 0);
	identify[0] = identify[1] = 0;
	memcpy(identify + 24, "P202 NVMe Controller                    ", 40);
	assert(smart_test_parse_nvme_identity(identify, sizeof(identify), model, sizeof(model),
	                                      serial, sizeof(serial), firmware, sizeof(firmware)) < 0);
}

static void test_sg_completion_validation(void)
{
	assert(smart_test_sg_response_ok(0, 0, 0, 0, 512));
	assert(!smart_test_sg_response_ok(2, 0, 0, 0, 512));
	assert(!smart_test_sg_response_ok(0, 1, 0, 0, 512));
	assert(!smart_test_sg_response_ok(0, 0, 1, 0, 512));
	assert(!smart_test_sg_response_ok(0, 0, 0, 1, 512));
}

static void test_nvme_smart_log_validation(void)
{
	unsigned char page[512];
	memset(page, 0, sizeof(page));
	assert(smart_test_validate_nvme_smart_log(page, sizeof(page)) < 0);
	memset(page, 0xff, sizeof(page));
	assert(smart_test_validate_nvme_smart_log(page, sizeof(page)) < 0);

	memset(page, 0, sizeof(page));
	page[0] = 1;                 /* valid critical warning bit */
	page[1] = 300 & 0xff;        /* 300 K */
	page[2] = (300 >> 8) & 0xff;
	page[3] = 99;                /* available spare */
	page[4] = 10;                /* spare threshold */
	page[5] = 150;               /* Percent Used may exceed 100 */
	page[32] = 1;                /* nonzero telemetry counter */
	assert(smart_test_validate_nvme_smart_log(page, sizeof(page)) == 0);
	assert(smart_test_validate_nvme_smart_log(page, sizeof(page) - 1) < 0);

    page[0] = 0x40;              /* reserved critical-warning bit */
	assert(smart_test_validate_nvme_smart_log(page, sizeof(page)) < 0);
	page[0] = 0;
	page[3] = 101;
	assert(smart_test_validate_nvme_smart_log(page, sizeof(page)) < 0);
	page[3] = 99;
	page[1] = 0x01; page[2] = 0x00; /* nonzero but implausible Kelvin */
	assert(smart_test_validate_nvme_smart_log(page, sizeof(page)) < 0);
}

static void set_sata_attr(unsigned char *page, int slot, unsigned char id,
                          unsigned char current, uint64_t raw)
{
	unsigned char *attr = page + 2 + slot * 12;
	attr[0] = id;
	attr[3] = current;
	for (int i = 0; i < 6; i++) attr[5 + i] = (unsigned char)(raw >> (i * 8));
}

static void test_sata_missing_and_zero_metrics(void)
{
	unsigned char page[512] = {0};
	char fields[512];
	page[0] = 1; /* A non-empty attribute page with no relevant attributes. */
	assert(smart_test_format_sata_metrics_json(page, sizeof(page), fields, sizeof(fields)) > 0);
	assert(strstr(fields, "\"status\":\"UNCONFIRMED\"") != NULL);
	assert(strstr(fields, "\"temperature_c\":null") != NULL);
	assert(strstr(fields, "\"health_percent\":null") != NULL);
	assert(strstr(fields, "\"reallocated_sectors\":null") != NULL);
	assert(strstr(fields, "\"power_hours\":null") != NULL);

	/* Vendor attributes have no shared health or byte-count meaning. */
	set_sata_attr(page, 0, 0xE7, 90, 0);
	set_sata_attr(page, 1, 0xA9, 80, 0);
	set_sata_attr(page, 2, 0xB1, 70, 0);
	set_sata_attr(page, 3, 0xE8, 60, 0);
	set_sata_attr(page, 4, 0xF1, 100, 1234);
	assert(smart_test_format_sata_metrics_json(page, sizeof(page), fields, sizeof(fields)) > 0);
	assert(strstr(fields, "\"status\":\"UNCONFIRMED\"") != NULL);
	assert(strstr(fields, "\"health_percent\":null") != NULL);
	assert(strstr(fields, "\"total_bytes_written\":null") != NULL);

	memset(page, 0, sizeof(page));
	page[0] = 1;
	set_sata_attr(page, 0, 0xC2, 0, 0); /* A present zero temperature is real. */
	set_sata_attr(page, 1, 0x05, 100, 0);
	set_sata_attr(page, 2, 0xC5, 100, 0);
	set_sata_attr(page, 3, 0xC6, 100, 0);
	set_sata_attr(page, 4, 0x09, 100, 0);
	assert(smart_test_format_sata_metrics_json(page, sizeof(page), fields, sizeof(fields)) > 0);
	assert(strstr(fields, "\"status\":\"UNCONFIRMED\"") != NULL);
	assert(strstr(fields, "\"temperature_c\":0") != NULL);
	assert(strstr(fields, "\"health_percent\":null") != NULL);
	assert(strstr(fields, "\"reallocated_sectors\":0") != NULL);
	assert(strstr(fields, "\"power_hours\":0") != NULL);
	assert(strstr(fields, "\"uncorrectable_sectors\":0") != NULL);

	set_sata_attr(page, 3, 0xC6, 100, 1);
	assert(smart_test_format_sata_metrics_json(page, sizeof(page), fields, sizeof(fields)) > 0);
	assert(strstr(fields, "\"status\":\"WARNING\"") != NULL);
	assert(strstr(fields, "\"uncorrectable_sectors\":1") != NULL);

	memset(page, 0xff, sizeof(page));
	assert(smart_test_format_sata_metrics_json(page, sizeof(page), fields, sizeof(fields)) < 0);
}

static void test_missing_device_error_status(void)
{
	const char *base = access("/data/local/tmp", W_OK) == 0 ? "/data/local/tmp" : "/tmp";
	char root[128], missing[256], json[512];
	snprintf(root, sizeof(root), "%s/smart-error-XXXXXX", base);
	assert(mkdtemp(root) != NULL);
	snprintf(missing, sizeof(missing), "%s/no-device", root);
	assert(read_smart_json(missing, json, sizeof(json)) < 0);
	assert(strstr(json, "\"status\":\"UNCONFIRMED\"") != NULL);
	assert(strstr(json, "\"reason\":\"Cannot stat device:") != NULL);
	assert(rmdir(root) == 0);
}

static void test_nvme_temperature_missing_and_zero(void)
{
	unsigned char page[512] = {0};
	char temperature[24];
	assert(smart_test_format_nvme_temperature_json(page, sizeof(page), temperature, sizeof(temperature)) == 4);
	assert(strcmp(temperature, "null") == 0);
	page[1] = 0x11; page[2] = 0x01; /* 273 K */
	assert(smart_test_format_nvme_temperature_json(page, sizeof(page), temperature, sizeof(temperature)) == 1);
	assert(strcmp(temperature, "0") == 0);
}

static void assert_nvme_counter(const unsigned char counter[16], unsigned int scale,
                               const char *expected)
{
	char output[64];
	assert(smart_test_format_nvme_counter_json(counter, 16, scale, output, sizeof(output)) ==
	       (int)strlen(expected));
	assert(strcmp(output, expected) == 0);
}

static void test_nvme_full_width_counter_formatting(void)
{
	unsigned char counter[16] = {0};
	counter[6] = 1; /* 2^48 */
	assert_nvme_counter(counter, 1, "281474976710656");
	assert_nvme_counter(counter, 512000, "144115188075855872000");

	memset(counter, 0, sizeof(counter));
	counter[8] = 1; /* 2^64 */
	assert_nvme_counter(counter, 1, "18446744073709551616");

	memset(counter, 0, sizeof(counter));
	counter[15] = 0x80; /* 2^127 */
	assert_nvme_counter(counter, 1, "170141183460469231731687303715884105728");
	assert_nvme_counter(counter, 512000, "87112285931760246646623899502532662132736000");

	memset(counter, 0xff, sizeof(counter));
	assert_nvme_counter(counter, 1, "340282366920938463463374607431768211455");
	assert_nvme_counter(counter, 512000, "174224571863520493293247799005065324264960000");
	char too_small[8];
	assert(smart_test_format_nvme_counter_json(counter, sizeof(counter), 1,
	                                          too_small, sizeof(too_small)) < 0);

	unsigned char page[512] = {0};
	char fields[1024];
	page[32 + 6] = 1;  /* total_bytes_read: 2^48 units */
	page[112 + 8] = 1; /* power_cycles: 2^64 */
	memset(page + 48, 0xff, 16);
	page[64 + 15] = 0x80; /* host_read_commands: 2^127 */
	assert(smart_test_format_nvme_counters_json(page, sizeof(page), fields, sizeof(fields)) > 0);
	assert(strstr(fields, "\"total_bytes_read\":\"144115188075855872000\"") != NULL);
	assert(strstr(fields, "\"power_cycles\":\"18446744073709551616\"") != NULL);
	assert(strstr(fields, "\"total_bytes_written\":\"174224571863520493293247799005065324264960000\"") != NULL);
	assert(strstr(fields, "\"host_read_commands\":\"170141183460469231731687303715884105728\"") != NULL);
	assert(strstr(fields, "\"power_hours\":\"0\"") != NULL);
	char small_fields[8];
	assert(smart_test_format_nvme_counters_json(page, sizeof(page), small_fields, sizeof(small_fields)) < 0);
}

static void test_nvme_unified_status_thresholds(void)
{
	assert(strcmp(smart_test_nvme_status(0, 10, 10, 99), "HEALTHY") == 0);
	assert(strcmp(smart_test_nvme_status(0, 9, 10, 99), "WARNING") == 0);
	assert(strcmp(smart_test_nvme_status(0, 100, 10, 99), "HEALTHY") == 0);
	assert(strcmp(smart_test_nvme_status(0, 100, 10, 100), "WARNING") == 0);
	assert(strcmp(smart_test_nvme_status(0, 100, 10, 255), "WARNING") == 0);
	for (int flag = 1; flag <= 0x20; flag <<= 1) {
		assert(strcmp(smart_test_nvme_status(flag, 100, 10, 0), "CRITICAL") == 0);
	}
}

int main(void)
{
	test_sysfs_usb_allowlist();
	test_e4_commands_and_identity();
	test_sg_completion_validation();
	test_nvme_smart_log_validation();
	test_sata_missing_and_zero_metrics();
	test_nvme_temperature_missing_and_zero();
	test_nvme_full_width_counter_formatting();
	test_nvme_unified_status_thresholds();
	test_missing_device_error_status();
	puts("smart_reader_test: all tests passed");
	return 0;
}
