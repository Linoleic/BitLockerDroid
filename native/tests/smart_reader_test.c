/* Host/Android unit harness for the SMART bridge safety helpers. */
#define SMART_READER_TEST 1
#include "smart_reader.h"

#include <assert.h>
#include <stdio.h>
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

int main(void)
{
	test_sysfs_usb_allowlist();
	test_e4_commands_and_identity();
	test_sg_completion_validation();
	test_nvme_smart_log_validation();
	puts("smart_reader_test: all tests passed");
	return 0;
}
