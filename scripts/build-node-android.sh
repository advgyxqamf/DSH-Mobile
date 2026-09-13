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

# ---------------------------------------------------------------------------
# Android/bionic 补丁：V8 的 stack_trace_posix.cc 通过
#   #if V8_LIBC_GLIBC || V8_LIBC_BSD || ...
#   #define HAVE_EXECINFO_H 1
# 判断是否可用 <execinfo.h>。在某些 NDK(clang) 下 bionic 会被误判为 glibc，
# 从而 #include <execinfo.h> 并调用 backtrace()/backtrace_symbols()，而 bionic
# 并不提供这些符号，导致：
#   error: use of undeclared identifier 'backtrace'
# 这里强制关闭 HAVE_EXECINFO_H，让 V8 走无 backtrace 的降级路径。
# ---------------------------------------------------------------------------
STACK_TRACE="deps/v8/src/base/debug/stack_trace_posix.cc"
if [ -f "$STACK_TRACE" ]; then
  echo "==> 应用 bionic backtrace 补丁: $STACK_TRACE"
  # 在 HAVE_EXECINFO_H 判定后强制置 0（即禁用 execinfo 路径）
  python3 - "$STACK_TRACE" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8', errors='replace').read()
old = """#if V8_LIBC_GLIBC || V8_LIBC_BSD || V8_LIBC_UCLIBC || V8_OS_SOLARIS
#define HAVE_EXECINFO_H 1
#endif"""
new = """#if V8_LIBC_GLIBC || V8_LIBC_BSD || V8_LIBC_UCLIBC || V8_OS_SOLARIS
#define HAVE_EXECINFO_H 1
#endif
// [android-container patch] bionic libc has no <execinfo.h>/backtrace();
// force-disable the execinfo path to avoid 'use of undeclared identifier backtrace'.
#if defined(__ANDROID__)
#undef HAVE_EXECINFO_H
#define HAVE_EXECINFO_H 0
#endif"""
if old in s:
    s = s.replace(old, new, 1)
    open(p, 'w', encoding='utf-8').write(s)
    print("patched:", p)
else:
    print("WARN: anchor not found, patch skipped (upstream may have changed)")
PY
  # 兜底：若上面锚点没匹配到，直接在文件开头插入强制定义
  if ! grep -q "android-container patch" "$STACK_TRACE"; then
    echo "==> 锚点补丁未生效，改用文件头强制定义"
    sed -i '1i #if defined(__ANDROID__)\n#undef HAVE_EXECINFO_H\n#define HAVE_EXECINFO_H 0\n#endif' "$STACK_TRACE"
  fi
  grep -n "HAVE_EXECINFO_H" "$STACK_TRACE" | head
else
  echo "==> [warn] $STACK_TRACE 不存在，跳过补丁"
fi

echo "==> 运行官方 android-configure (NDK ${ANDROID_NDK} + API ${ANDROID_API} + arch ${ARCH})"
# Node 24 官方参数顺序: ./android-configure [patch] <path to the Android NDK> <Android SDK version> <target architecture>
# 即 <ndk> <api> <arch>（注意：不是 <ndk> <arch> <api>）
# 内部会: 把 CC/CXX/AR/LD 指向 NDK clang，并 ./configure --dest-os=android --dest-cpu=${ARCH}
./android-configure "$ANDROID_NDK" "$ANDROID_API" "$ARCH"

# ---------------------------------------------------------------------------
# zlib cpufeatures 补丁（CI 实测验证版）：
#
#   现象: 链接期 ld.lld: error: undefined symbol: android_getCpuFeatures
#         >>> referenced by cpu_features.c
#             .../obj.target/zlib/deps/zlib/cpu_features.o:(_cpu_check_features)
#             in archive .../obj.target/deps/zlib/libzlib.a
#
#   根因: gyp 在 OS=="android" 时给 zlib 目标注入 -DARMV8_OS_ANDROID
#         （见 gyp 产物 out/deps/zlib/zlib.target.mk 与 zlib_arm_crc32.target.mk），
#         于是 deps/zlib/cpu_features.c 走 Android 分支:
#             #include <cpu-features.h>
#             android_getCpuFeatures();
#         该符号由 NDK 的 sources/android/cpufeatures 提供；
#         但 NDK r23+ 已移除该目录，common.gypi 却仍注入
#             -I$(android_ndk_path)/sources/android/cpufeatures
#         （CI 上指向不存在的路径），所以既编得过又链不上。
#
#   修法: 把 zlib 目标的 -DARMV8_OS_ANDROID 换成 -DARMV8_OS_LINUX。
#         cpu_features.c 的 Linux 分支用 getauxval(AT_HWCAP) + HWCAP_CRC32/PMULL,
#         而 bionic 的 <asm/hwcap.h> 提供了这些常量、libc 也导出 getauxval，
#         因此无需任何额外库，且 CRC32/PMULL 反而是"真检测"而非硬编码。
#
#   注意: 补丁必须打在 gyp 生成的 Makefile 上（而不是 config.gypi）——
#         ARMV8_OS_ANDROID 是 gyp 条件展开出的 -D，config.gypi 里根本不存在。
#         实测证据: 打补丁后 cpu_features.o 的未定义符号从
#         `U android_getCpuFeatures` 变为 `U getauxval`（libc 提供）。
# ---------------------------------------------------------------------------
ZMK_DIR="out/deps/zlib"
if [ -d "$ZMK_DIR" ]; then
  echo "==> 修补 gyp 生成的 zlib 目标: ARMV8_OS_ANDROID -> ARMV8_OS_LINUX"
  python3 - "$ZMK_DIR" <<'PY'
import os, sys, glob
d = sys.argv[1]
total = 0
for f in sorted(glob.glob(os.path.join(d, "*.target.mk")) +
                glob.glob(os.path.join(d, "*.host.mk"))):
    s = open(f, encoding="utf-8", errors="replace").read()
    n = s.count("-DARMV8_OS_ANDROID")
    if n:
        s = s.replace("-DARMV8_OS_ANDROID", "-DARMV8_OS_LINUX")
        open(f, "w", encoding="utf-8").write(s)
        print("  patched %s (%d occurrence(s))" % (os.path.basename(f), n))
        total += n
print("  total replaced:", total)
if total == 0:
    sys.exit("FATAL: no -DARMV8_OS_ANDROID found in zlib gyp makefiles; "
             "upstream layout changed, patch needs review")
PY
  echo "==> 补丁后核对（应只剩 ARMV8_OS_LINUX）:"
  grep -h "ARMV8_OS" "$ZMK_DIR"/*.target.mk | sort -u
else
  echo "==> [warn] $ZMK_DIR 不存在，跳过 zlib 补丁"
fi

echo "==> 编译 (NDK r27+ 链接器默认 max-page-size=16384 → 16KB 页对齐)"
make -j"$(nproc)"

echo "==> 拷贝产物到 $OUT_DIR/node"
cp out/Release/node "$OUT_DIR/node"
chmod +x "$OUT_DIR/node"

echo "==> 完成。文件: $OUT_DIR/node"
echo "    下一步: ./gradlew assembleDebug 即可把该 Node 打进 APK（首启离线可跑）。"
echo "    若要做 OTA 升级包: ./scripts/make-release.sh ${NODE_VERSION}"
