#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
#  用官方 Node.js 源码 + Android NDK 交叉编译 ARM64 的 node 可执行文件
#  产出: app/src/main/assets/node-bin/arm64-v8a/node
#        (bionic 链接；NDK r27+ 默认 16KB 页对齐，满足 Android 15+ 的 dlopen 要求)
#
#  前置依赖（主机侧）:
#    git, python3, ninja, cmake, make, zip
#    Android NDK r27+  (设置 ANDROID_NDK 环境变量指向 NDK 根目录)
#
#  用法:
#    ANDROID_NDK=/path/to/ndk ./scripts/build-node-android.sh 24.21.0
#    ANDROID_NDK=/path/to/ndk ./scripts/build-node-android.sh        # 默认 24.21.0
#
#  这是整个容器“最难啃”的一步：没有现成的新版预编译安卓 Node
#  （node-on-mobile 最后提交 2019、nodejs-mobile 停在 Node 12，都已不可用），
#   只能自己用 NDK 编。编出来的 node 与安卓 system libc(bionic) 链接，
#   因此运行时不依赖 Termux、不依赖 root。
# ============================================================================

NODE_VERSION="${1:-24.21.0}"
export ANDROID_NDK="${ANDROID_NDK:?请先设置 ANDROID_NDK 指向 NDK 根目录 (r27+)}"
ANDROID_API="${ANDROID_API:-24}"
ARCH="arm64"   # 仅 arm64-v8a；如需 32 位改为 arm（并同步扩展 app/build.gradle.kts 的 abiFilters）

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/app/src/main/assets/node-bin/arm64-v8a"
mkdir -p "$OUT_DIR"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> 克隆 Node.js v${NODE_VERSION} 源码"
git clone --depth 1 --branch "v${NODE_VERSION}" https://github.com/nodejs/node "$WORK/node"
cd "$WORK/node"

echo "==> 运行官方 android-configure (NDK ${ANDROID_NDK} + API ${ANDROID_API} + arch ${ARCH})"
# Node 24 官方参数顺序: ./android-configure [patch] <path to the Android NDK> <Android SDK version> <target architecture>
# 即 <ndk> <api> <arch>（注意：不是 <ndk> <arch> <api>）
# 内部会: 把 CC/CXX/AR/LD 指向 NDK clang，并 ./configure --dest-os=android --dest-cpu=${ARCH}
./android-configure "$ANDROID_NDK" "$ANDROID_API" "$ARCH"

echo "==> 编译 (NDK r27+ 链接器默认 max-page-size=16384 → 16KB 页对齐)"
make -j"$(nproc)"

echo "==> 拷贝产物到 $OUT_DIR/node"
cp out/Release/node "$OUT_DIR/node"
chmod +x "$OUT_DIR/node"

echo "==> 完成。文件: $OUT_DIR/node"
echo "    下一步: ./gradlew assembleDebug 即可把该 Node 打进 APK（首启离线可跑）。"
echo "    若要做 OTA 升级包: ./scripts/make-release.sh ${NODE_VERSION}"
