#!/bin/bash

# VCAM 屏幕模式 - Linux 打包脚本
# 用于在 Linux 上构建 APK，然后使用 LSPatch 注入目标应用

set -e

echo "=========================================="
echo "  VCAM 屏幕模式 - Linux 打包脚本"
echo "=========================================="

# 检查 Java 环境
if ! command -v java &> /dev/null; then
    echo "错误: 未找到 Java，请先安装 JDK 11+"
    exit 1
fi

# 检查 ANDROID_HOME
if [ -z "$ANDROID_HOME" ]; then
    # 尝试常见路径
    if [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    elif [ -d "/opt/android-sdk" ]; then
        export ANDROID_HOME="/opt/android-sdk"
    else
        echo "错误: 未设置 ANDROID_HOME 环境变量"
        echo "请设置: export ANDROID_HOME=/path/to/android/sdk"
        exit 1
    fi
fi

echo "ANDROID_HOME: $ANDROID_HOME"

# 清理旧构建
echo ""
echo "[1/4] 清理旧构建..."
./gradlew clean

# 构建 Release APK
echo ""
echo "[2/4] 构建 Release APK..."
./gradlew assembleRelease

# 检查输出
APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
if [ ! -f "$APK_PATH" ]; then
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
fi

if [ ! -f "$APK_PATH" ]; then
    echo "错误: APK 构建失败"
    exit 1
fi

echo ""
echo "[3/4] APK 构建成功: $APK_PATH"

# 复制到输出目录
OUTPUT_DIR="output"
mkdir -p "$OUTPUT_DIR"
cp "$APK_PATH" "$OUTPUT_DIR/vcam-screen-mode.apk"

echo ""
echo "[4/4] 输出文件: $OUTPUT_DIR/vcam-screen-mode.apk"

echo ""
echo "=========================================="
echo "  构建完成！"
echo "=========================================="
echo ""
echo "下一步操作:"
echo ""
echo "1. 将 vcam-screen-mode.apk 传输到手机"
echo ""
echo "2. 使用 LSPatch 注入目标应用:"
echo "   - 打开 LSPatch 应用"
echo "   - 选择 '管理' -> '新建补丁'"
echo "   - 选择目标应用 APK"
echo "   - 在模块列表中选择 'vcam-screen-mode.apk'"
echo "   - 点击 '开始打补丁'"
echo ""
echo "3. 安装打补丁后的目标应用"
echo ""
echo "4. 在 VCAM 应用中启用 '屏幕录制模式'"
echo ""
echo "5. 打开目标应用，使用摄像头功能时会自动请求屏幕录制权限"
echo ""
