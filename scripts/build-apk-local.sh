#!/usr/bin/env bash
# ============================================================================
#  本地一键出包（在【有公网 + 已装 Android SDK/NDK】的机器上运行）。
#
#  前置：
#    - Android SDK：设置 ANDROID_HOME 指向 SDK 根目录，且已装
#        platform-tools / platforms;android-34 / build-tools;34.0.0
#    - Android NDK r27+：设置 ANDROID_NDK 指向 NDK 根目录
#    - 主机侧：git python3 ninja cmake make zip + JDK 17 + Gradle 8.x
#
#  用法：
#    export ANDROID_HOME=/path/to/sdk
#    export ANDROID_NDK=/path/to/ndk
#    ./scripts/build-apk-local.sh
#
#  产出：app/build/outputs/apk/debug/app-debug.apk
# ============================================================================
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:?请设置 ANDROID_HOME 指向 Android SDK 根目录}"
export ANDROID_NDK="${ANDROID_NDK:?请设置 ANDROID_NDK 指向 NDK 根目录 (r27+)}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> [1/3] 用 NDK 交叉编译 Node for Android"
./scripts/build-node-android.sh 24.21.0

echo "==> [2/3] 生成 Gradle wrapper（若缺少）并编译 APK"
if [ ! -x gradlew ]; then
  gradle wrapper --gradle-version 8.9
  chmod +x gradlew
fi
./gradlew assembleDebug

echo "==> [3/3] 完成"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
ls -lh "$APK"
echo "    安装: adb install -r \"$APK\""
echo "    打开 App 即可在屏幕上看到逐阶段启动诊断；端口就绪后自动切到 Node 探针 UI。"
