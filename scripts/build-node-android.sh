#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
#  用官方 Node.js 源码 + Android NDK 交叉编译 ARM64 的 node 可执行文件
#  产出: app/src/main/jniLibs/arm64-v8a/libnode.so
#        (bionic 链接；NDK r27+ 默认 16KB 页对齐，满足 Android 15+ 的 dlopen 要求)
#
#  为什么产物是 jniLibs 下的 libnode.so 而不是 assets 里的 node：
#    见下方 OUT_DIR 处的详细说明 —— Android 10+ 的 SELinux W^X 禁止执行
#    应用可写目录(files/)中的文件，只有 /data/app/.../lib/ 允许 exec。
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
# ---------------------------------------------------------------------------
# 产物落地目录：jniLibs/arm64-v8a/libnode.so —— 不是 assets。
#
# 【为什么必须是 jniLibs，不能放 assets】
# Android 10 (API 29) 起 SELinux 对「可写目录」强制 W^X：
#   /data/data/<pkg>/files/  (label app_data_file) → execve() 被拒 (EACCES/error=13)
#   /data/app/<pkg>/lib/<abi>/ (label exec_type)   → 允许执行
# 真机实证（Android 16 / API 36）：
#   IOException: Cannot run program ".../files/node/24.21.0/node": error=13, Permission denied
# 这是「设计如此」，不是权限位问题 —— 官方 issuetracker 128554619 明确回复：
#   "Calling exec() on writable application files is a W^X violation... exec() no
#    longer works on files within the application home directory, it continues to
#    be supported for files within the read-only /data/app directory. In particular,
#    it should be possible to package the binaries into your application's native
#    libs directory and enable android:extractNativeLibs=true, and then call exec()
#    on the /data/app artifacts."
# 所以走 jniLibs：安装时系统把库解压到 /data/app/.../lib/arm64-v8a/（只读、可执行）。
#
# 三点配套要求（缺一不可）：
#   1. 文件名必须是 lib*.so 形式，否则 AGP 不会把它当作 native lib 解压到 lib dir。
#   2. android:extractNativeLibs="true" 或 jniLibs.useLegacyPackaging=true，
#      否则 AGP 3.6+ 默认「压缩 .so 且不落盘」，运行时 lib dir 里根本没有这个文件。
#   3. 二进制解释器必须是 Android 的 linker（/system/bin/linker64）——
#      我们交叉编译出来的 node 正是 bionic 链接，已用 `file` 验证满足。
# ---------------------------------------------------------------------------
OUT_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
OUT_NAME="libnode.so"
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

# ---------------------------------------------------------------------------
# V8 trap-handler 补丁（必须，否则 host 工具 mksnapshot 链接失败）：
#
#   现象: 链接 out/Release/mksnapshot 时报
#           undefined reference to `v8::internal::trap_handler::TryHandleSignal(int, siginfo_t*, void*)'
#           undefined reference to `v8::internal::trap_handler::RegisterDefaultTrapHandler()'
#           undefined reference to `v8_internal_simulator_ProbeMemory'
#
#   根因: 交叉编译时 V8_HOST_ARCH_X64=1 且 V8_TARGET_ARCH_ARM64=1，
#         trap-handler.h 的判定阶梯会命中 "Arm64 simulator on x64" 分支，
#         设上 V8_TRAP_HANDLER_VIA_SIMULATOR + V8_TRAP_HANDLER_SUPPORTED true。
#         但 simulator 的 ProbeMemory 只存在于 arm64 翻译单元，
#         host(x64) 侧的 mksnapshot 自然链接不到这些符号。
#
#   修法: 把整条判定阶梯短路到 #else 分支（V8_TRAP_HANDLER_SUPPORTED false），
#         与 Node 官方 android-patches/trap-handler.h.patch 目的一致
#         （见 https://github.com/nodejs/node/issues/36287）。
#
#   注意: 官方的 patch 文件已过期，`patch -f` 会 "Hunk #1 FAILED"
#         （上游把注释从 "Arm64 native" 改成了 "Arm64 (non-simulator)"），
#         所以这里不调用 `./android-configure patch`，改用锚点替换。
#
#   踩过的坑（勿重犯）: 最初的做法是在第一个 #if 前面另插一个 `#if 0`，
#         想让整条阶梯短路。但那会让 #if/#endif 失去配对，编译器直接报
#         `trap-handler.h:5:2: error: unterminated conditional directive`，
#         整个头文件后续内容被吞掉，接着爆出几十条荒谬的
#         `no member named 'ArrayBuffer' in namespace 'v8::internal::trap_handler::v8'`。
#         正确做法是**不增删任何条件指令**，只给判定块里的 #if 与每个 #elif
#         的条件前面 AND 一个恒假项（`0 && ...`）。指令种类与配对保持逐字节不变。
#         另外只改第一个 #if 是不够的：后面的 #elif（arm64 simulator on x64）
#         仍会命中，所以 6 个 #elif 必须一并置为恒假。
# ---------------------------------------------------------------------------
TRAP_HDR="deps/v8/src/trap-handler/trap-handler.h"
if [ -f "$TRAP_HDR" ]; then
  echo "==> 应用 V8 trap-handler 补丁: $TRAP_HDR"
  python3 - "$TRAP_HDR" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8', errors='replace').read()
if 'android-container patch' in s:
    print("  already patched, skip")
    sys.exit(0)

START = "// X64 on Linux, Windows, MacOS, FreeBSD."
END = "// Everything else is unsupported."
if START not in s or END not in s:
    sys.exit("FATAL: trap-handler.h anchors not found; upstream layout changed, "
             "patch needs review")

i = s.index(START)
j = s.index(END)
block = s[i:j]

n_if = len(re.findall(r"^#if ", block, flags=re.M))
n_elif = len(re.findall(r"^#elif ", block, flags=re.M))
if n_if != 1 or n_elif < 1:
    sys.exit("FATAL: unexpected conditional structure in trap-handler ladder "
             "(#if=%d #elif=%d); patch needs review" % (n_if, n_elif))

new_block = ("// [android-container patch] Force every branch of the ladder below to be\n"
             "// false so control falls through to '#else -> V8_TRAP_HANDLER_SUPPORTED\n"
             "// false'. See https://github.com/nodejs/node/issues/36287 : cross-compiling\n"
             "// an arm64 target from an x64 host otherwise matches the 'Arm64 simulator\n"
             "// on x64' branch, setting V8_TRAP_HANDLER_VIA_SIMULATOR -- but the\n"
             "// simulator's ProbeMemory only exists in an arm64 translation unit, so the\n"
             "// x64 host tool mksnapshot cannot link.\n"
             "// We AND a false term into each condition rather than wrapping the block in\n"
             "// an extra '#if 0', because adding a directive would unbalance\n"
             "// #if/#endif and the compiler would fail with 'unterminated conditional\n"
             "// directive', swallowing the rest of this header.\n"
             + re.sub(r"^#if (?!0 &&)", "#if 0 && ", block, count=1, flags=re.M)
             )
new_block = re.sub(r"^#elif (?!0 &&)", "#elif 0 && ", new_block, flags=re.M)

s = s[:i] + new_block + s[j:]
open(p, 'w', encoding='utf-8').write(s)
print("  patched: neutralised 1 #if + %d #elif" % n_elif)
PY
  # 自动断言（两道）：
  #   1) 结构检查：条件指令必须配平 —— 防止再犯 "unterminated conditional
  #      directive" 那种把整个头文件吞掉的错。
  #   2) 语义检查：把判定阶梯单独抽出来，按真实构建的宏环境
  #      （x64 宿主 / arm64 目标 / linux+android）做一次预处理求值，
  #      要求 V8_TRAP_HANDLER_SUPPORTED 必须求值为 0。
  #      注意：这里必须"真求值"，不能靠文本匹配 —— 曾经写过
  #      `s.replace("0 && ","")` 再找 arm64-simulator 分支的断言，那是反向
  #      逻辑（剥掉补丁标记后必然命中原始文本），会让补丁成功时反而报 FATAL。
  python3 - "$TRAP_HDR" <<'PY'
import re, sys, os, subprocess, tempfile
p = sys.argv[1]
s = open(p, encoding='utf-8').read()

# --- 1) 结构：条件指令配平 ---
depth = 0
for line in s.splitlines():
    if re.match(r"^#\s*(if|ifdef|ifndef)\b", line):
        depth += 1
    elif re.match(r"^#\s*endif\b", line):
        depth -= 1
    if depth < 0:
        sys.exit("FATAL: trap-handler.h has an extra #endif (depth went negative)")
if depth != 0:
    sys.exit("FATAL: trap-handler.h conditional directives are unbalanced "
             "(depth=%d) -- this is exactly the 'unterminated conditional "
             "directive' bug; refusing to build." % depth)
print("  [ok] conditional directives balanced (depth=0)")

# --- 2) 语义：真实求值 ---
START = "// X64 on Linux, Windows, MacOS, FreeBSD."
END = "// Everything else is unsupported."
if START not in s or END not in s:
    sys.exit("FATAL: cannot locate trap-handler ladder for semantic check")
ladder = s[s.index(START):s.index(END)]

defs = {"V8_HOST_ARCH_X64": 1, "V8_HOST_ARCH_ARM64": 0, "V8_HOST_ARCH_IA32": 0,
        "V8_HOST_ARCH_ARM": 0, "V8_HOST_ARCH_PPC64": 0, "V8_HOST_ARCH_S390X": 0,
        "V8_HOST_ARCH_RISCV64": 0, "V8_HOST_ARCH_LOONG64": 0,
        "V8_TARGET_ARCH_X64": 0, "V8_TARGET_ARCH_ARM64": 1, "V8_TARGET_ARCH_IA32": 0,
        "V8_TARGET_ARCH_ARM": 0, "V8_TARGET_ARCH_PPC64": 0, "V8_TARGET_ARCH_S390X": 0,
        "V8_TARGET_ARCH_RISCV64": 0, "V8_TARGET_ARCH_LOONG64": 0,
        "V8_OS_LINUX": 1, "V8_OS_ANDROID": 1, "V8_OS_WIN": 0, "V8_OS_DARWIN": 0,
        "V8_OS_FREEBSD": 0, "V8_OS_AIX": 0}
hdr = "\n".join("#define %s %d" % (k, v) for k, v in defs.items())
prog = (hdr + "\n" + ladder +
        "\n#else\n#define V8_TRAP_HANDLER_SUPPORTED 0\n#endif\n"
        "int probe_val = V8_TRAP_HANDLER_SUPPORTED;\n")

with tempfile.TemporaryDirectory() as d:
    f = os.path.join(d, "probe.cpp")
    open(f, "w").write(prog)
    r = subprocess.run(["g++", "-std=c++20", "-E", "-P", f],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("FATAL: trap-handler semantic probe failed to preprocess:\n"
                 + r.stderr[:1500])
    m = re.search(r"int probe_val = (\w+);", r.stdout)
    val = m.group(1) if m else None
if val == "0":
    print("  [ok] semantic check: x64-host/arm64-target -> "
          "V8_TRAP_HANDLER_SUPPORTED = false (trap handler disabled)")
elif val is None:
    sys.exit("FATAL: could not evaluate V8_TRAP_HANDLER_SUPPORTED "
             "(preprocessor output did not contain the probe line)")
else:
    sys.exit("FATAL: V8_TRAP_HANDLER_SUPPORTED evaluated to %r for "
             "x64-host/arm64-target; the arm64-simulator branch is still live "
             "and mksnapshot will fail to link." % val)
PY
  grep -c "^#if 0 && \|^#elif 0 && " "$TRAP_HDR" | sed 's/^/  neutralised branches: /'
else
  echo "==> [warn] $TRAP_HDR 不存在，跳过 trap-handler 补丁"
fi

# ---------------------------------------------------------------------------
# cctest / aligned_alloc 补丁（必须，否则 make 的 node 目标在最后一步失败）：
#
#   现象: 编译到 cctest 时停在
#           ../test/cctest/test_crypto_clienthello.cc:57:40: error:
#             use of undeclared identifier 'aligned_alloc'
#              57 |  alloc_base = static_cast<uint8_t*>(aligned_alloc(page, 2 * page));
#           make[1]: *** [cctest.target.mk:267: .../test_crypto_clienthello.o] Error 1
#           make: *** [Makefile:143: node] Error 2
#
#   根因: aligned_alloc() 是 C11 函数，bionic 从 **API 28** 才提供。
#         实测矩阵（NDK r27c，aarch64-linux-android<API>-clang++）:
#             API 24: FAIL   API 28: OK   API 29: OK   API 30: OK
#         而本工程 ANDROID_API=24（见本脚本顶部），所以必然失败。
#
#   为什么必须修而不是"跳过 cctest"：
#         make 的 `node` 目标会把 cctest 一并编出来（不是独立目标），
#         想绕开就得改 node.gyp，动上游构建图的风险远大于改这一行测试代码。
#         而且抬高 ANDROID_API 到 28 会把整个 APK 的最低系统要求提到 Android 9，
#         属于用功能换编译，不划算 —— 这一行只是测试里的对齐分配，替换掉毫无损失。
#
#   修法: aligned_alloc(page, 2*page) → memalign(page, 2*page)。
#         两者语义在这段用法里完全等价（对齐值 = 页大小，必然是 2 的幂，
#         且分配大小 2*page 是页大小的整数倍），返回值同样可用 free() 释放。
#         选 memalign 而不是 posix_memalign 的原因：memalign 返回指针，
#         可以直接嵌进 static_cast<uint8_t*>(...) 而不必改写控制流；
#         它自 API 1 起就在 bionic 里（NDK 头 malloc.h:111 无 __INTRODUCED_IN 门槛，
#         而相邻的 reallocarray 明确标了 __INTRODUCED_IN(29) 作对照），
#         posix_memalign 同样自 API 1 可用，两者都实测过关。
#
#   实测验证（不是推断）：
#         1) 原始代码 API24 → 报同一条 undeclared identifier；API28 → 通过。
#         2) 改后代码 API24 编译通过，且
#              clang -Wl,--no-undefined  链接通过
#            → 证明 memalign 符号在 API 24 的 bionic 里确实存在（不只是头文件放行）。
#         3) 动态符号表确认解析到 memalign@LIBC。
# ---------------------------------------------------------------------------
CCTEST_HELLO="test/cctest/test_crypto_clienthello.cc"
if [ -f "$CCTEST_HELLO" ]; then
  echo "==> 应用 aligned_alloc 补丁（API ${ANDROID_API} < 28，bionic 无该函数）: $CCTEST_HELLO"
  python3 - "$CCTEST_HELLO" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8', errors='replace').read()
if 'android-container patch' in s:
    print("  already patched, skip")
    sys.exit(0)

old = "alloc_base = static_cast<uint8_t*>(aligned_alloc(page, 2 * page));"
new = ("// [android-container patch] aligned_alloc() only exists in bionic from API 28;\n"
       "    // this build targets API 24. memalign() has been available since API 1 and\n"
       "    // is equivalent here (alignment == page size, a power of two; size is a\n"
       "    // multiple of the alignment; result is free()-able).\n"
       "    alloc_base = static_cast<uint8_t*>(memalign(page, 2 * page));")
if old not in s:
    sys.exit("FATAL: aligned_alloc anchor not found in %s; upstream layout "
             "changed, patch needs review" % p)
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
print("  patched: aligned_alloc -> memalign (1 occurrence)")
PY
  # 自动断言：不允许【可编译的调用点】再出现 aligned_alloc（防止上游又加一处）。
  # 注意：必须排除注释行 —— 我们自己的补丁说明里就写着 "aligned_alloc()"，
  # 若用裸 grep 会误报（这个坑实测踩到过）。这里只匹配非注释行中的调用形态。
  ALIGNED_CALLS="$(grep -n "aligned_alloc[[:space:]]*(" "$CCTEST_HELLO" \
                     | grep -v ":[[:space:]]*//" \
                     | grep -v ":[[:space:]]*\*" || true)"
  if [ -n "$ALIGNED_CALLS" ]; then
    echo "==> [error] $CCTEST_HELLO 里仍有 aligned_alloc 调用："
    echo "$ALIGNED_CALLS" | sed 's/^/        | /'
    echo "            API ${ANDROID_API} 下会再次报 'use of undeclared identifier'。"
    exit 1
  fi
  echo "    [ok] 已无 aligned_alloc 调用点（注释中的说明文字已排除）"
  # 自动断言：memalign 必须能在 API ${ANDROID_API} 下真链接（--no-undefined 是硬校验）。
  NDKBIN_FOR_CHECK="$(ls -d "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -1)"
  if [ -n "$NDKBIN_FOR_CHECK" ] && [ -x "$NDKBIN_FOR_CHECK/aarch64-linux-android${ANDROID_API}-clang" ]; then
    cat > "$WORK/memalign_probe.c" <<'PROBE'
#include <malloc.h>
#include <stdlib.h>
int main(void) { void* p = memalign(4096, 8192); free(p); return 0; }
PROBE
    if "$NDKBIN_FOR_CHECK/aarch64-linux-android${ANDROID_API}-clang" -Wl,--no-undefined \
         "$WORK/memalign_probe.c" -o "$WORK/memalign_probe" >/dev/null 2>&1; then
      echo "    [ok] memalign 在 API ${ANDROID_API} 下可链接（--no-undefined 校验通过）"
    else
      echo "==> [warn] memalign 在 API ${ANDROID_API} 下 --no-undefined 校验未通过，"
      echo "            输出如下（若真失败，需改用其他对齐分配方案）："
      "$NDKBIN_FOR_CHECK/aarch64-linux-android${ANDROID_API}-clang" -Wl,--no-undefined \
        "$WORK/memalign_probe.c" -o "$WORK/memalign_probe" 2>&1 | head -5 | sed 's/^/        | /'
    fi
    rm -f "$WORK/memalign_probe.c" "$WORK/memalign_probe"
  fi
else
  echo "==> [warn] $CCTEST_HELLO 不存在，跳过 aligned_alloc 补丁"
fi

# ---------------------------------------------------------------------------
# 宿主工具链分离（必须在 android-configure 之前 export，否则 build 会在 ICU 阶段崩）：
#
#   现象: /bin/sh: 1: .../out/Release/icupkg: Exec format error
#         make[1]: *** [tools/icu/icudata.target.mk:13:
#                       .../obj/gen/icudt78l.dat] Error 126
#
#   根因: android-configure 把 CC/CXX 设成 aarch64-linux-android*-clang（供目标架构用）。
#         而 gyp 的 make 生成器里，宿主(host)工具的编译器是这样取的
#         （见 tools/gyp/pylib/gyp/generator/make.py）:
#             CC_host  = $(CC_host  or  CC)
#             CXX_host = $(CXX_host or  CXX)
#         即「没设 *_host 就回退到 CC」。于是 icupkg / genccode / genrep 这些
#         **要跑在构建机（x86_64）上**的工具被编成了 ARM64 二进制，
#         宿主无法执行 → Exec format error。
#
#   修法: 提前把 CC_host/CXX_host/LINK_host/AR_host 指向宿主编译器，
#         CC/CXX 由 android-configure 留给目标架构。实测: 设置后
#         out/Release/icupkg 从 aarch64 变为
#         'ELF 64-bit LSB pie executable, x86-64' 且可正常运行。
#
#   为什么宿主编译器**不能**用 NDK 自带的 clang：
#         这是本脚本踩过的第二个大坑。最初的想法是「用 NDK 自带的 clang 当宿主编译器，
#         与目标编译器同源、兼容性最好」，架构断言（x86-64）也确实通过了。
#         但宿主编译器**不只是要产出 x86-64，还要能链接宿主的 libstdc++ 与 libatomic**。
#         NDK 的 toolchains/llvm/prebuilt/<host>/bin/clang 虽然默认 target 是
#         x86_64-unknown-linux-gnu，但它的 sysroot / 库搜索路径指向 **Android 目标**：
#           - 找不到宿主 C++ 标准库头（实测直接 fatal error: 'atomic' file not found）
#           - NDK 里只有 aarch64/arm 版 libatomic.a，**没有 x86_64 宿主版**
#         结果就是 mksnapshot 这类 host 工具链接失败：
#             ld.lld: error: undefined symbol: __atomic_compare_exchange
#             >>> referenced by wasm-objects.cc / wasm-code-pointer-table.cc
#             make[1]: *** [tools/v8_gypfiles/mksnapshot.host.mk:230: .../mksnapshot] Error 1
#         官方 NDK 文档对此有明确说明（https://developer.android.google.cn/ndk/guides/common-problems）：
#             "Undefined reference to __atomic_* — Some ABIs need libatomic …
#              Solution: Add -latomic when linking."
#         且 NDK r23+ 不再自动链接 libatomic，必须显式处理。
#
#   修法（双保险）：
#         1) 宿主编译器优先用**系统 clang**（有完整宿主 sysroot、宿主 libstdc++ 与 libatomic）。
#            但必须**实测可编译**才采用 —— 不同镜像的 clang 可能缺 C++ 头
#            （沙箱里就出现过 clang 指向不存在的 gcc-14 include 路径而报 'atomic' not found）。
#            因此这里对候选编译器逐个跑「编译 + 链接」探针，挑第一个真正能用的。
#         2) 给宿主链接显式加 -latomic（走 LDFLAGS_host），兜住 outline atomics。
# ---------------------------------------------------------------------------
# 候选顺序：环境变量显式指定 > 系统 clang > 系统 gcc > NDK clang（最后手段）
HOST_CC="${CC_host:-}"
HOST_CXX="${CXX_host:-}"
NDK_HOST_BIN="$(ls -d "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -1)"

# 宿主编译器可用性探针：必须能「编译并链接」一段用到 C++ 标准库 + 原子操作的代码。
# 只测能不能产出 x86-64 是不够的 —— 那正是 NDK clang 当初骗过断言的原因。
probe_host_compiler() {
  local cxx="$1" tag="$2"
  [ -n "$cxx" ] || return 1
  command -v "$cxx" >/dev/null 2>&1 || [ -x "$cxx" ] || return 1
  cat > "$WORK/host_probe.cpp" <<'PROBE'
#include <atomic>
#include <cstdio>
#include <string>
#include <memory>
int main() {
    int v = 0, exp = 0;
    __atomic_compare_exchange_n(&v, &exp, 1, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    std::string s = "ok";
    auto p = std::make_shared<int>(42);
    std::printf("%s %d %d\n", s.c_str(), v, *p);
    return 0;
}
PROBE
  if "$cxx" -m64 -std=gnu++20 "$WORK/host_probe.cpp" -o "$WORK/host_probe.out" -latomic >/dev/null 2>&1; then
    local info; info="$(file -b "$WORK/host_probe.out" 2>/dev/null || echo unknown)"
    case "$info" in
      *x86-64*)
        echo "    [ok] $tag -> $cxx (x86-64, C++20 + stdlib + atomics 全部可用)"
        return 0 ;;
      *)
        echo "    [skip] $tag -> $cxx 产出非 x86-64: $info"
        return 1 ;;
    esac
  else
    # 打印真实原因，别只写一句「失败」——排查时非常依赖这条线索。
    # 典型: clang++ 报 "fatal error: 'atomic' file not found"，
    # 因为它的 C++ 头搜索路径指向一个不存在的 gcc include 目录。
    echo "    [skip] $tag -> $cxx 探针编译/链接失败，真实原因:"
    "$cxx" -m64 -std=gnu++20 "$WORK/host_probe.cpp" -o "$WORK/host_probe.out" -latomic 2>&1 \
      | head -3 | sed 's/^/        | /'
    return 1
  fi
}

echo "==> 挑选宿主编译器（逐个实测）"
HOST_CXX_PICKED=""
for cand in \
  "${CXX_host:-}" \
  "$(command -v clang++ 2>/dev/null)" \
  "/usr/bin/clang++" \
  "$(command -v g++ 2>/dev/null)" \
  "/usr/bin/g++" \
  "${NDK_HOST_BIN:+$NDK_HOST_BIN/clang++}"
do
  [ -n "$cand" ] || continue
  if probe_host_compiler "$cand" "host-cxx"; then
    HOST_CXX_PICKED="$cand"
    break
  fi
done
if [ -z "$HOST_CXX_PICKED" ]; then
  echo "==> [error] 找不到能用的宿主编译器。宿主构建（icupkg/mksnapshot 等）无法完成。"
  echo "    请安装 clang++ 或 g++ 以及 libstdc++/libatomic 开发包后重试。"
  exit 1
fi
HOST_CXX="$HOST_CXX_PICKED"
# 对应的 C 编译器：与 C++ 同源（clang++->clang, g++->gcc）
case "$HOST_CXX" in
  */clang++|clang++) HOST_CC="$(dirname "$HOST_CXX")/clang"; [ -x "$HOST_CC" ] || HOST_CC="$(command -v clang || echo "$HOST_CXX")" ;;
  */g++|g++)         HOST_CC="$(dirname "$HOST_CXX")/gcc";   [ -x "$HOST_CC" ] || HOST_CC="$(command -v gcc || echo "$HOST_CXX")" ;;
  *)                 HOST_CC="${CC_host:-$(command -v clang || command -v gcc)}" ;;
esac
echo "==> 宿主编译器确定: CXX_host=$HOST_CXX  CC_host=$HOST_CC"
export CC_host="$HOST_CC"
export CXX_host="$HOST_CXX"
export LINK_host="$HOST_CXX"
export AR_host="${AR_host:-$(command -v ar || echo ar)}"

# 宿主链接标志：显式补 -latomic（NDK r23+ 不再自动链接；官方文档要求手动加）。
# 只作用于 host 目标，不影响 Android 目标产物。
export LDFLAGS_host="${LDFLAGS_host:-} -latomic"
echo "==> 宿主工具链: CC_host=$CC_host  CXX_host=$CXX_host  AR_host=$AR_host  LDFLAGS_host=$LDFLAGS_host"

echo "==> 运行官方 android-configure (NDK ${ANDROID_NDK} + API ${ANDROID_API} + arch ${ARCH})"
# Node 24 官方参数顺序: ./android-configure [patch] <path to the Android NDK> <Android SDK version> <target architecture>
# 即 <ndk> <api> <arch>（注意：不是 <ndk> <arch> <api>）
# 内部会: 把 CC/CXX/AR/LD 指向 NDK clang，并 ./configure --dest-os=android --dest-cpu=${ARCH}
./android-configure "$ANDROID_NDK" "$ANDROID_API" "$ARCH"

# ---------------------------------------------------------------------------
# 宿主编译器落地断言（关键！别删）。
#
#   android-configure 会把 CC/CXX/AR 覆写成 NDK 的 aarch64-linux-android*-clang
#   （那是给【目标】架构用的），并调用 configure 生成 out/Makefile。
#   gyp 的 make 生成器据此写出：
#       CC.host  ?= $(CC_host  or CC)      →  out/Makefile 里的 "CC.host ?= ..."
#       CXX.host ?= $(CXX_host or CXX)
#   也就是说：只要我们的 CC_host/CXX_host 在 configure 时可见，宿主工具就会用系统编译器。
#   但如果这一步没生效，宿主侧就会拿 NDK clang 去编 x64 代码，报：
#       fatal error: 'atomic' file not found
#       fatal error: 'cstdint' file not found
#       make[1]: *** [tools/v8_gypfiles/abseil.host.mk:210: .../cycleclock.o] Error 1
#   这个报错发生在编译中途、看着像源码问题，实际是工具链选错，极难一眼看出来。
#   这里在进入漫长编译【之前】就把结论钉死，避免又浪费一两个小时才发现。
# ---------------------------------------------------------------------------
if [ ! -f out/Makefile ]; then
  echo "==> [error] android-configure 之后没有 out/Makefile，配置未生成。"
  exit 1
fi
HOST_CC_IN_MK="$(sed -n 's/^CC\.host[[:space:]]*?*=[[:space:]]*//p' out/Makefile | head -1)"
HOST_CXX_IN_MK="$(sed -n 's/^CXX\.host[[:space:]]*?*=[[:space:]]*//p' out/Makefile | head -1)"
echo "==> 校验 out/Makefile 中的宿主工具链:"
echo "    CC.host  = ${HOST_CC_IN_MK:-<空>}"
echo "    CXX.host = ${HOST_CXX_IN_MK:-<空>}"
if [ -z "$HOST_CXX_IN_MK" ]; then
  echo "==> [error] out/Makefile 里没有 CXX.host，gyp 未采用我们的宿主工具链。"
  echo "            宿主工具会被编成 ARM64，随后在构建机上 Exec format error。"
  exit 1
fi
case "$HOST_CXX_IN_MK" in
  *android*)
    echo "==> [error] 宿主编译器落到了 NDK 的 android 工具链（$HOST_CXX_IN_MK）。"
    echo "            宿主侧（mksnapshot/icupkg 等）必须用系统编译器，否则会报"
    echo "            \"fatal error: 'atomic' file not found\"。"
    echo "            期望: $HOST_CXX"
    exit 1 ;;
esac
# 再复核一次：用 Makefile 里记录的编译器实测能否编 C++ 头（真编译，不看声明）。
if ! "$HOST_CXX_IN_MK" -m64 -std=gnu++20 -x c++ -c /dev/null -o /dev/null >/dev/null 2>&1; then
  echo "==> [error] out/Makefile 记录的宿主编译器无法编译 C++ 头：$HOST_CXX_IN_MK"
  "$HOST_CXX_IN_MK" -m64 -std=gnu++20 -x c++ -c /dev/null -o /dev/null 2>&1 | head -3 | sed 's/^/            | /'
  echo "            这就是 abseil.host.mk 报 'atomic' file not found 的直接原因。"
  echo "            可在环境变量里显式指定 CXX_host/CC_host 后重跑本脚本。"
  exit 1
fi
echo "    [ok] 宿主编译器校验通过（非 android 工具链，且实测能编 C++ 头）"

# ---------------------------------------------------------------------------
# 构建生成器：坚持用 make，**不要切换到 ninja**（这是花了很久才确认的结论）。
#
#   android_configure.py 内部那行是
#       ./configure --dest-cpu=... --dest-os=android --openssl-no-asm --cross-compiling
#   **没有 --ninja**，所以默认落到 make。而本工程要编【两份 V8】：
#       obj.host/   → x64，给 mksnapshot 等宿主工具用
#       obj.target/ → arm64，最终 node 二进制
#
#   曾经为了省时间，改成 `./configure --ninja` 重跑。结果 ninja 生成器在
#   「交叉编译 + host/target 双份 V8」这个组合下**有系统性缺陷**，连撞两堵墙：
#
#   墙 1：重复规则
#     ninja: error: obj.host/tools/v8_gypfiles/v8_inspector_headers.ninja:14:
#       multiple rules generate gen/inspector-generated-output-root/src/js_protocol.stamp
#     根因: ninja 生成器把 SHARED_INTERMEDIATE_DIR 展开成 "<product_dir>/gen"
#     （ninja.py 第 45 行 generator_default_variables），**丢掉了 host/target 前缀**；
#     而 make 生成器用的是 "$(obj)/gen"（.host.mk 里 obj := $(abs_obj)），天然隔离。
#     实测：该缺陷在原始 ninja 图里造成 1267 个重复输出。
#     自己给 ninja.py 打补丁（让 SHARED_INTERMEDIATE_DIR 走
#     GypPathToUniqueOutput("gen")）能消掉第 1 堵墙 —— 重复输出从 1267 降到 0，
#     但立刻撞上第 2 堵墙，说明这条路上还有成体系的问题。
#
#   墙 2：依赖路径分裂（改 gyp 也治不好）
#     ninja: error: 'obj/tools/v8_gypfiles/postmortem-metadata.gen/torque-generated/
#       instance-types.h', needed by '.../postmortem-metadata.gen/debug-support.cc',
#       missing and no known rule to make it
#     同一份 SHARED_INTERMEDIATE_DIR 产物，因为 GypPathToUniqueOutput 对
#     process_outputs_as_sources 的产物加了「各自目标名」前缀，生成端落在
#       .../run_torque.gen/torque-generated/instance-types.h
#     消费端却去找
#       .../postmortem-metadata.gen/torque-generated/instance-types.h
#     这是「目标名限定」与「跨目标引用」两种路径约定冲突，凡是用到
#     process_outputs_as_sources 的目标都会踩，属于系统性问题而不是孤例。
#     实测：修完墙 1 后，ninja 图里仍有 5309 个「无规则可生成」的缺失依赖。
#
#   结论: ninja 这条路是上游未验证的组合（官方 android_configure.py 从不用
#         --ninja），逐个打补丁是无底洞。**make 才是上游唯一验证过的路径**，
#         而且同一份重复规则在 make 下只是一句 warning
#         （"warning: overriding recipe for target ..."）不致命。
#
#   时间预算（据此把 CI 超时提到 330 分钟是够的）:
#     实测 make 模式下 host+target 合计约 3300 个编译单元
#     （host ≈1600 / target ≈1700）。4 vCPU runner 上粗估 210~260 分钟。
#     上一轮 146 分钟仍停在 host 阶段，真正原因不是时间不够，而是撞上了
#     下面那条「宿主编译器选了 clang++ → 缺宿主 C++ 头」的报错在反复重试。
#     把宿主工具链修好之后，make 可以在超时内跑完。
#
#   踩过的坑（勿重犯）: 用 GYP_DEFINES 在外层重跑 configure 时必须显式 export ——
#     android_configure.py 第 74 行是 os.environ['GYP_DEFINES'] = GYP_DEFINES，
#     只在它自己的 python 进程内生效，不会 export 到父 shell，否则 gyp 立刻报
#     "gyp: Undefined variable android_ndk_path in node.gyp"。而 configure 报错前
#     已改写 Makefile/config.gypi，随后 make 会报出误导性的
#     "No rule to make target 'out/Release/build.ninja'"。
#     既然已决定不用 ninja，这里就不再重跑 configure，保持 android-configure 的
#     原始 make 配置即可 —— 少一次 configure 就少一个污染 out/ 的机会。
# ---------------------------------------------------------------------------
echo "==> 使用 make 生成器（android-configure 的默认配置，上游唯一验证过的路径）"
echo "    注意: 构建生成器固定为 make；不要改成 --ninja（见上方注释）。"
unset GYP_DEFINES GYP_GENERATORS 2>/dev/null || true
USE_NINJA=0
if command -v ninja >/dev/null 2>&1; then
  echo "    [info] 系统里装了 ninja，但本构建刻意不使用它。"
fi
# 确认落到了 make（存在 out/Makefile 即说明是 make 生成器）
if [ ! -f out/Makefile ]; then
  echo "==> [error] 未找到 out/Makefile，说明配置没有落到 make 生成器。"
  echo "            out/ 可能被之前的 --ninja 配置污染，请清理后重跑。"
  exit 1
fi
echo "    [ok] out/Makefile 存在，确认使用 make 生成器"

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
#   注意: 补丁必须打在 gyp **生成物**上（而不是 config.gypi）——
#         ARMV8_OS_ANDROID 是 gyp 条件展开出的 -D，config.gypi 里根本不存在。
#         实测证据: 打补丁后 cpu_features.o 的未定义符号从
#         `U android_getCpuFeatures` 变为 `U getauxval`（libc 提供）。
#
#   产物位置（make 生成器）：out/deps/zlib/*.target.mk
#     -DARMV8_OS_ANDROID 是 gyp 条件展开出的 -D，直接写在生成的 .target.mk 里。
#   注意: 这里必须显式列出文件、不能用 `$ZMK_DIR/*.host.mk` 这类通配 ——
#     zsh / 某些 shell 在通配无匹配时会直接让**整条命令**失败（"no matches found"），
#     结果补丁静默不执行（曾真实踩到：total 0 而 ARMV8_OS_ANDROID 还在）。
#     因此改成先 ls 收集、再判断，缺 .host.mk 也不影响。
# ---------------------------------------------------------------------------
ZMK_DIR="out/deps/zlib"
ZMK_FILES=""
# 显式列出候选文件，逐个判断存在性（不用会在无匹配时让整条命令失败的 shell 通配）。
for f in "$ZMK_DIR"/zlib.target.mk \
         "$ZMK_DIR"/zlib_arm_crc32.target.mk \
         "$ZMK_DIR"/zlib_adler32_simd.target.mk \
         "$ZMK_DIR"/zlib_data_chunk_simd.target.mk; do
  [ -f "$f" ] && ZMK_FILES="$ZMK_FILES $f"
done
if [ -n "$ZMK_FILES" ]; then
  echo "==> 修补 gyp 生成的 zlib 目标: ARMV8_OS_ANDROID -> ARMV8_OS_LINUX"
  python3 - $ZMK_FILES <<'PY'
import os, sys
total_files = 0
total = 0
for f in sys.argv[1:]:
    s = open(f, encoding="utf-8", errors="replace").read()
    n = s.count("-DARMV8_OS_ANDROID")
    if n:
        s = s.replace("-DARMV8_OS_ANDROID", "-DARMV8_OS_LINUX")
        open(f, "w", encoding="utf-8").write(s)
        print("  patched %s (%d occurrence(s))" % (f, n))
        total += n
        total_files += 1
print("  total: %d occurrence(s) in %d file(s)" % (total, total_files))
if total == 0:
    sys.exit("FATAL: no -DARMV8_OS_ANDROID found in zlib gyp outputs "
             "(searched out/deps/zlib/*.target.mk); upstream layout changed, "
             "patch needs review")
PY
  echo "==> 补丁后核对（应只剩 ARMV8_OS_LINUX）:"
  grep -h -o "ARMV8_OS_[A-Z]*" $ZMK_FILES 2>/dev/null | sort -u | sed 's/^/    /'
else
  echo "==> [warn] 未找到 zlib gyp 产物（$ZMK_DIR），跳过补丁"
fi

echo "==> 编译 (NDK r27+ 链接器默认 max-page-size=16384 → 16KB 页对齐)"
# 进度可见性：构建耗时以小时计，而 CI 侧只能靠心跳判断「还在跑 vs 卡死」。
# 这里周期性打印一行进度（含时间戳与已产出目标数），让日志里也能一眼看出推进速度。

# ---------------------------------------------------------------------------
# 并行度必须按【内存】而不是按【核数】来定（这是决定成败的一步）。
#
#   现象: 用 nproc（CI 上是 4）跑 make -j4，job 在 144 分钟被硬终止；
#         配置的 timeout-minutes 是 330 分钟，所以**不是超时**。
#         异常还在于：连 if: always() 的收尾步骤都没留下任何记录 ——
#         这是进程/容器被内核直接杀掉的典型特征，而不是正常失败。
#
#   根因: 编译 V8 时单个编译进程的内存峰值可达 2~4 GB
#         （turboshaft / v8_compiler 那几个巨型翻译单元尤其突出）。
#         GitHub 标准 runner 是 4 vCPU / 16 GB，-j4 的峰值就能摸到 8~16 GB，
#         再叠加链接阶段的峰值，必然触发 OOM Killer。
#         本地用 8 GB cgroup 复现了同一现象：
#             g++: fatal error: Killed signal terminated program cc1plus
#             make[1]: *** [v8_compiler.host.mk:363: .../turboshaft/...] Error 1
#
#   修法: 按可用内存估算并行度，给每个编译进程预留约 3.5 GB（V8 巨型 TU 的
#         峰值确实能到 3 GB+），并留出 2 GB 余量；下限锁 2（实测 8 GB 环境下
#         -j2 可全程零 OOM，不必退到 -j1）。
#         可用内存优先读 cgroup 限额（容器里 MemTotal 是宿主的值，不能直接用），
#         取不到再退回 /proc/meminfo 的 MemAvailable。
#         另外允许用 NODE_BUILD_JOBS 环境变量显式覆盖。
#
#   取值预期: 4 核 16 GB 的 GitHub runner → (16384-2048)/3584 ≈ 4 → 但仍受
#         CPU 核数 4 限制，实际 make -j4；若 runner 内存较小会自动降到 -j3/-j2。
#         实测 8 GB cgroup 下 -j2 全程零 OOM（已越过之前必炸的
#         obj.host/v8_compiler/.../turboshaft/ 段）。
#         编译时间会变长，但换来的是不再被 OOM 打断（我们保留了全部功能：
#         TLS/crypto、Intl/ICU、inspector 都不裁剪）。
# ---------------------------------------------------------------------------
detect_mem_mb() {
  local lim
  # cgroup v2
  lim="$(cat /sys/fs/cgroup/memory.max 2>/dev/null || true)"
  # cgroup v1
  if [ -z "$lim" ] || [ "$lim" = "max" ]; then
    lim="$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null || true)"
  fi
  case "$lim" in
    ''|max|*[!0-9]*) lim="" ;;
  esac
  # 明显不合理的巨大值（未设限时的哨兵值）直接忽略
  if [ -n "$lim" ] && [ "$lim" -gt 1000000000000 ] 2>/dev/null; then lim=""; fi
  if [ -n "$lim" ]; then
    echo $((lim / 1024 / 1024))
    return
  fi
  awk '/^MemAvailable:/{print int($2/1024); exit}' /proc/meminfo 2>/dev/null || echo 0
}

CPU_JOBS="$(nproc)"
MEM_MB="$(detect_mem_mb)"
# 每个编译进程按 3.5 GB 预留，另留 2 GB 给链接与系统。
# 下限锁在 2：实测 8 GB 环境下 -j2 可以全程零 OOM，不必退到 -j1
# （-j1 会让本就要几小时的构建再拖长很多，得不偿失）。
MEM_JOBS=2
if [ -n "$MEM_MB" ] && [ "$MEM_MB" -gt 0 ] 2>/dev/null; then
  MEM_JOBS=$(( (MEM_MB - 2048) / 3584 ))
  [ "$MEM_JOBS" -lt 2 ] && MEM_JOBS=2
else
  MEM_JOBS="$CPU_JOBS"
fi
JOBS="${NODE_BUILD_JOBS:-$CPU_JOBS}"
if [ "$MEM_JOBS" -lt "$JOBS" ]; then
  JOBS="$MEM_JOBS"
fi
[ "$JOBS" -lt 1 ] && JOBS=1

echo "==> 并行度决策（按内存而非核数）"
echo "    检测到可用内存: ${MEM_MB:-未知} MB   CPU: ${CPU_JOBS} 核"
echo "    按 3.5GB/编译进程 + 2GB 余量 → 内存上限 -j${MEM_JOBS}"
echo "    最终使用: make -j${JOBS}（可用 NODE_BUILD_JOBS 覆盖）"
echo "    预期: host+target 合计约 6800 个编译单元，耗时以小时计。"
echo "    注: 这里刻意不用满 CPU —— 编译 V8 是内存瓶颈而非 CPU 瓶颈，"
echo "        并发放大后峰值内存会撞穿 runner 限额，导致进程被 OOM 杀掉，"
echo "        表现是「任务在远早于超时的时刻突然消失、连收尾步骤都没记录」。"
(
  while true; do
    sleep 120
    t=$(find out/Release/obj.target -name '*.o' 2>/dev/null | wc -l)
    h=$(find out/Release/obj.host   -name '*.o' 2>/dev/null | wc -l)
    printf '[progress %s] host=%s target=%s\n' "$(date -u +%H:%M:%S)" "$h" "$t"
  done
) &
PROGRESS_PID=$!
trap 'kill "$PROGRESS_PID" 2>/dev/null || true' EXIT

# 用 make -j${JOBS} 走完全程。make 失败会非零退出，配合 set -e 让脚本干净收尾。
make -j"${JOBS}"

echo "==> 拷贝产物到 $OUT_DIR/$OUT_NAME"
cp out/Release/node "$OUT_DIR/$OUT_NAME"
chmod +x "$OUT_DIR/$OUT_NAME"

# ---- 自检：确认产物能满足「在 /data/app lib dir 里被执行」的全部前提 ----
# 说明：这里的检查要分清「硬条件」和「提示信息」，不要误杀。
#   · 关键认知修正：被改名为 lib*.so 的这个文件【并不是真的共享库】。
#     系统不会去 dlopen/加载它，只是在安装 APK 时把它从 lib/<abi>/ 目录
#     解压到文件系统上（因为 extractNativeLibs=true）。之后我们直接 exec 它。
#     所以「必须是 linker64 解释器」并不是系统强制的前提，只是个一致性提示。
#   · 真正的硬条件是：文件存在于 lib/<abi>/ 且解压落盘 —— 由打包配置保证。
echo "==> 产物自检"
file -b "$OUT_DIR/$OUT_NAME" | sed 's/^/    file: /'
"$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf -l "$OUT_DIR/$OUT_NAME" 2>/dev/null \
  | grep -i "interpreter\|LOAD" | head -6 | sed 's/^/    /' || true
if "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf -l "$OUT_DIR/$OUT_NAME" 2>/dev/null \
     | grep -q "interpreter.*linker64"; then
  echo "    [ok] 解释器为 Android linker64（bionic 动态链接，可正常 exec）"
else
  echo "    [info] 未检出 linker64 解释器。这不一定是问题："
  echo "           该文件本质是普通 ELF 可执行文件，被系统当作 native lib 解压落盘后直接 exec，"
  echo "           并非作为共享库加载。若是静态链接的二进制，同样可以执行。"
  echo "           但 Node 正常应为 bionic 动态链接 —— 若非预期，请核对 android-configure 参数。"
fi
# 16KB 页对齐：Android 15+ 的要求。注意这条对「可执行 ELF」依然有意义 ——
# 15+ 的设备若页大小是 16KB，4KB 对齐的可执行文件可能无法被内核加载（ELIBBAD）。
if "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf -l "$OUT_DIR/$OUT_NAME" 2>/dev/null \
     | grep -qE "0x4000"; then
  echo "    [ok] 检出 16KB (0x4000) 对齐的 LOAD 段（满足 Android 15+ 要求）"
else
  echo "    [warn] 未检出 16KB 对齐 LOAD 段。Android 15+ 在 16KB 页设备上可能"
  echo "           返回 ELIBBAD/Exec format error；NDK r27+ 默认应满足，若为旧 NDK 请升级后重编。"
fi

echo "==> 完成。文件: $OUT_DIR/$OUT_NAME"
echo "    下一步: ./gradlew assembleDebug 即可把该 Node 打进 APK（首启离线可跑）。"
echo "    若要做 OTA 升级包: ./scripts/make-release.sh ${NODE_VERSION}"
