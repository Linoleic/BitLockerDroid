#!/usr/bin/env bash
set -e

# Color definitions
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}======================================================${NC}"
echo -e "${BLUE}  BitLockerDroid 一键构建、安装与 ADB 自动化测试脚本  ${NC}"
echo -e "${BLUE}======================================================${NC}"

# 1. 环境变量配置
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0:$PATH"

BT="$ANDROID_HOME/build-tools/34.0.0"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

# 代理检测（如果本地代理端口 7897 开启则自动使用）
if nc -z 127.0.0.1 7897 2>/dev/null; then
    echo -e "${YELLOW}[*] 检测到本地代理 127.0.0.1:7897，已配置代理环境变量${NC}"
    export HTTP_PROXY="http://127.0.0.1:7897"
    export HTTPS_PROXY="http://127.0.0.1:7897"
fi

# 2. 编译 APK
echo -e "\n${BLUE}[步骤 1/5] 正在编译 Debug APK...${NC}"
chmod +x gradlew
./gradlew :app:assembleDebug

if [ ! -f "$APK" ]; then
    echo -e "${RED}[!] 编译失败：未找到生成的 APK 文件 ($APK)${NC}"
    exit 1
fi
echo -e "${GREEN}[✓] APK 编译成功: $APK ($(du -h "$APK" | cut -f1))${NC}"

# 3. APK 静态安全与规范校验
echo -e "\n${BLUE}[步骤 2/5] 校验 APK 安全加固与 16KB 架构对齐规范...${NC}"

# 3.1 验证 16KB 页对齐
TMP_DIR=$(mktemp -d)
unzip -q -j "$APK" "lib/arm64-v8a/libdislocker.so" -d "$TMP_DIR" 2>/dev/null || true
if [ -f "$TMP_DIR/libdislocker.so" ]; then
    ALIGN=$(readelf -W -l "$TMP_DIR/libdislocker.so" | grep -m1 "LOAD" | awk '{print $NF}')
    if [ "$ALIGN" == "0x4000" ]; then
        echo -e "${GREEN}[✓] 16KB 内存页对齐检查通过 (LOAD segment Align: 0x4000 / 16384B)${NC}"
    else
        echo -e "${RED}[!] 警告：libdislocker.so 的 Align 为 $ALIGN，非 16KB 对齐${NC}"
    fi
else
    echo -e "${YELLOW}[!] 未在 APK 中找到 arm64-v8a/libdislocker.so${NC}"
fi
rm -rf "$TMP_DIR"

# 3.2 验证冗余 C++ 库已剔除
if unzip -l "$APK" | grep -q "libc++_shared.so"; then
    echo -e "${RED}[!] 警告：APK 中仍包含 libc++_shared.so 冗余库！${NC}"
else
    echo -e "${GREEN}[✓] 冗余库检查通过：libc++_shared.so 已完全剔除${NC}"
fi

# 3.3 验证 Manifest 安全配置
if [ -x "$BT/aapt" ]; then
    AAPT_DUMP=$("$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>&1)
    
    # 检查 allowBackup
    if echo "$AAPT_DUMP" | grep -A2 "allowBackup" | grep -q "0x0"; then
        echo -e "${GREEN}[✓] 清单加固检查通过：android:allowBackup=\"false\"${NC}"
    else
        echo -e "${YELLOW}[!] 提示：未能确认 allowBackup=false${NC}"
    fi

    # 检查 UnlockDialogActivity 是否未导出
    if echo "$AAPT_DUMP" | grep -A5 "UnlockDialogActivity" | grep -q 'exported.*0x0'; then
        echo -e "${GREEN}[✓] 组件加固检查通过：UnlockDialogActivity 已设为 unexported${NC}"
    else
        echo -e "${YELLOW}[!] 提示：未能确认 UnlockDialogActivity exported=false${NC}"
    fi
fi

# 4. 检测 ADB 设备并安装
echo -e "\n${BLUE}[步骤 3/5] 检测 ADB 连接...${NC}"
DEVICES_COUNT=$(adb devices | grep -v "^List" | grep -c "device$" || true)

if [ "$DEVICES_COUNT" -eq 0 ]; then
    echo -e "${RED}[!] 未检测到任何已连接的 ADB 设备！${NC}"
    echo -e "${YELLOW}请确保手机已连接 USB 并启用 USB 调试，或通过无线 ADB 连接：${NC}"
    echo -e "   adb connect <手机IP>:5555"
    echo -e "当前 adb devices 状态："
    adb devices
    exit 1
elif [ "$DEVICES_COUNT" -eq 1 ]; then
    ADB_TARGET=$(adb devices | grep -v "^List" | grep "device$" | awk '{print $1}')
    echo -e "${GREEN}[✓] 发现目标设备: $ADB_TARGET${NC}"
    ADB_CMD="adb -s $ADB_TARGET"
else
    if [ -n "$ANDROID_SERIAL" ]; then
        ADB_TARGET="$ANDROID_SERIAL"
        echo -e "${GREEN}[✓] 使用环境变量指定设备: $ADB_TARGET${NC}"
    else
        ADB_TARGET=$(adb devices | grep -v "^List" | grep "device$" | head -n 1 | awk '{print $1}')
        echo -e "${YELLOW}[*] 发现多个设备，默认选中首个设备: $ADB_TARGET${NC}"
    fi
    ADB_CMD="adb -s $ADB_TARGET"
fi

echo -e "\n${BLUE}[步骤 4/5] 正在安装 APK 到设备 [$ADB_TARGET]...${NC}"
$ADB_CMD install -r "$APK"
echo -e "${GREEN}[✓] APK 安装成功！${NC}"

# 5. 运行时验证与诊断
echo -e "\n${BLUE}[步骤 5/5] 运行状态与组件接口诊断...${NC}"

# 启动设置界面
echo -e "${YELLOW}[*] 启动应用设置中心 (BitLockerSettingsActivity)...${NC}"
$ADB_CMD shell am start -n com.bitlockerdroid/.ui.BitLockerSettingsActivity

# 验证 DocumentsProvider 根接口响应
echo -e "\n${YELLOW}[*] 查询 DocumentsProvider 根节点 (content query)...${NC}"
$ADB_CMD shell content query --uri content://com.bitlockerdroid.provider/root || true

# 检查私有日志目录
echo -e "\n${YELLOW}[*] 检查应用私有沙箱日志 (/data/user/0/com.bitlockerdroid/files/logs/bitlocker.log)...${NC}"
$ADB_CMD shell su -c "cat /data/user/0/com.bitlockerdroid/files/logs/bitlocker.log 2>/dev/null | tail -n 25" || true

# 检查是否有崩溃日志
echo -e "\n${YELLOW}[*] 检查是否存在崩溃堆栈 (crash.txt)...${NC}"
CRASH_LOG=$($ADB_CMD shell su -c "cat /data/user/0/com.bitlockerdroid/files/logs/crash.txt 2>/dev/null" || true)
if [ -n "$CRASH_LOG" ]; then
    echo -e "${RED}[!] 发现异常崩溃堆栈：${NC}"
    echo "$CRASH_LOG"
else
    echo -e "${GREEN}[✓] 运行良好，无未捕获崩溃记录。${NC}"
fi

echo -e "\n${GREEN}======================================================${NC}"
echo -e "${GREEN}             🎉 测试完成！应用安装与自检就绪          ${NC}"
echo -e "${GREEN}======================================================${NC}"
