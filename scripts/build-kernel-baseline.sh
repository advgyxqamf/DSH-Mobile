#!/usr/bin/env bash
# ============================================================================
#  生成 APK 内置的基线内核包 → app/src/main/assets/kernel/baseline.zip
#
#  为什么需要这个脚本
#  ----------------
#  KernelManager.ensureBaseline() 一直想读 assets/kernel/baseline.zip，
#  但**这个文件从来不存在**（assets/ 下只有 node/ + node-versions.json +
#  ota-public.pem）。后果是真机无网首启时静默返回 null，表现为
#  「没有内核包」—— 而那是构建期缺陷，不是运行时状态。
#
#  本脚本把它补上，并坚持一条底线：
#    **基线包必须用容器私钥签名**（与 OTA 包同一把 ed25519 私钥）。
#  理由：APK 签名锚定发布者，内核签名锚定容器私钥，是两把独立钥匙。
#  若基线包跳过验签，任何能重打 APK 的人就能塞进任意内核，双信任根退化。
#
#  用法
#  ----
#    ./scripts/build-kernel-baseline.sh [kernel-src-dir] [version] [abi]
#    例：./scripts/build-kernel-baseline.sh ../dsh-android-kernel 0.1.0
#
#  前置
#  ----
#    - keys/ota-private.pem 存在（scripts/keygen.sh 或 CI secret 注入）
#    - kernel-src-dir 是一个可运行的内核源码目录（含 bin/dsh-supervisor）
#
#  产物
#  ----
#    app/src/main/assets/kernel/baseline.zip   设备端首启解包用
#    (release/kernel-<version>.zip 也会被 build-bundle 顺带产出，可忽略)
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-$ROOT/../dsh-android-kernel}"
VER="${2:-}"
ABI="${3:-node24-arm64-android35}"

# ---- 校验内核源 ----
if [ ! -d "$SRC" ]; then
  echo "[baseline] [error] 内核源码目录不存在: $SRC" >&2
  echo "[baseline]         用法: build-kernel-baseline.sh <kernel-src-dir> [version] [abi]" >&2
  exit 1
fi

ENTRY="$SRC/bin/dsh-supervisor"
if [ ! -f "$ENTRY" ]; then
  echo "[baseline] [error] 找不到内核入口: $ENTRY" >&2
  echo "[baseline]         内核包的结构约定是 <src>/bin/dsh-supervisor" >&2
  exit 1
fi

# ---- 从内核 package.json 推导版本（若未显式给）----
if [ -z "$VER" ]; then
  if [ -f "$SRC/package.json" ]; then
    VER="$(node -e "process.stdout.write(require('$SRC/package.json').version||'')" 2>/dev/null || true)"
  fi
  # 版本号里可能带 npm 的预发布后缀（0.1.0-android.1）。内核版本号会被用作
  # **目录名**（files/kernel/<version>/），所以只保留安全字符集合：
  # 字母数字、点、横线、下划线。其它一律换成下划线 —— 避免出现空格/斜杠
  # 之类的路径元字符，那会直接破坏落盘布局。
  VER="$(printf '%s' "$VER" | tr -c 'A-Za-z0-9._-' '_')"
fi
if [ -z "$VER" ]; then
  echo "[baseline] [error] 无法确定内核版本号（package.json 里没有 version？），请显式传入" >&2
  exit 1
fi

# ---- 私钥必须在 —— 缺失就明确失败，不产未签名包 ----
if [ ! -f "$ROOT/keys/ota-private.pem" ]; then
  echo "[baseline] [error] 私钥缺失: $ROOT/keys/ota-private.pem" >&2
  echo "[baseline]         基线包必须签名（理由见本脚本头注释）。" >&2
  echo "[baseline]         本地开发可跑 ./scripts/keygen.sh 生成开发密钥对；" >&2
  echo "[baseline]         CI 上由 secret OTA_PRIVATE_KEY_PEM 注入。" >&2
  exit 1
fi

# ---- 公钥锚点必须与私钥配对 —— 否则产出的包在设备上一定验签失败 ----
#
# 这一步是"构建期就把问题挡住"。若不查，产出的 APK 会带着一个
# 永远验不过的基线包，真机上表现为「基线内核校验失败」——
# 而排查它需要走完整条 exec/验签链，代价极高。
ANCHOR="$ROOT/app/src/main/assets/ota-public.pem"
if [ ! -f "$ANCHOR" ]; then
  echo "[baseline] [error] 公钥锚点缺失: $ANCHOR" >&2
  exit 1
fi

echo "[baseline] 内核源 : $SRC"
echo "[baseline] 版本   : $VER"
echo "[baseline] abi    : $ABI"
echo "[baseline] 私钥   : keys/ota-private.pem"
echo "[baseline] 公钥锚 : app/src/main/assets/ota-public.pem"

# ---- 校验公私钥配对（用私钥签一段数据、用公钥验）----
node - "$ROOT/keys/ota-private.pem" "$ANCHOR" <<'NODE'
const fs = require('fs'), crypto = require('crypto');
const [privPath, pubPath] = process.argv.slice(2);
const priv = fs.readFileSync(privPath, 'utf8');
const pub = fs.readFileSync(pubPath, 'utf8');
const probe = Buffer.from('dsh-keypair-probe');
let sig;
try {
  sig = crypto.sign(null, probe, priv);
} catch (e) {
  console.error('[baseline] [error] 私钥不可用于 ed25519 签名: ' + e.message);
  process.exit(1);
}
let ok = false;
try {
  ok = crypto.verify(null, probe, pub, sig);
} catch (e) {
  console.error('[baseline] [error] 公钥不可用于 ed25519 验签: ' + e.message + '（路径 ' + pubPath + '）');
  process.exit(1);
}
if (!ok) {
  console.error('[baseline] [error] 公私钥**不配对**：');
  console.error('[baseline]         私钥 keys/ota-private.pem 与公钥锚点 assets/ota-public.pem 不是一对。');
  console.error('[baseline]         这样打出的 APK，基线内核在设备上必然验签失败。');
  console.error('[baseline]         请跑 ./scripts/keygen.sh 重新生成配对，或更新 CI secret。');
  process.exit(1);
}
console.log('[baseline] 公私钥配对校验通过 ✓');
NODE

# ---- 打包（复用 build-bundle，产物在 release/）----
node "$ROOT/container-engine/bin/build-bundle.js" "$SRC" "$VER" "$ABI" "" >/dev/null

SRC_ZIP="$ROOT/release/kernel-$VER.zip"
[ -f "$SRC_ZIP" ] || { echo "[baseline] [error] 打包未产出 $SRC_ZIP" >&2; exit 1; }

# ---- 投放为 assets 资产 ----
OUT_DIR="$ROOT/app/src/main/assets/kernel"
mkdir -p "$OUT_DIR"
cp "$SRC_ZIP" "$OUT_DIR/baseline.zip"

# ---- 自检：用**与设备端同一个校验器**验一遍 ----
#
# 这是本脚本最有价值的一步：设备端的 kernel-verify.js 就在这里，为什么不在
# 构建期用它？若这里过了而设备上不过，那差异只可能来自数据（而不是逻辑）。
# 反过来，若这里就不过，就没必要浪费一轮 CI + 一次真机安装。
VERIFY="$ROOT/app/src/main/assets/node/kernel-verify.js"
if [ -f "$VERIFY" ]; then
  echo "[baseline] 用设备端校验器自检…"
  set +e
  OUT="$(node "$VERIFY" --zip "$OUT_DIR/baseline.zip" --pubkey "$ANCHOR" 2>&1)"
  RC=$?
  set -e
  echo "$OUT" | sed 's/^/[baseline]   /'
  if [ "$RC" -ne 0 ]; then
    echo "[baseline] [error] 基线包未通过设备端校验器（退出码 $RC）—— 打出去的 APK 会带一个装不上的内核" >&2
    exit 1
  fi
else
  echo "[baseline] [warn] 找不到设备端校验器 $VERIFY，跳过自检" >&2
fi

echo "[baseline] 完成 → $OUT_DIR/baseline.zip ($(stat -c%s "$OUT_DIR/baseline.zip") 字节)"
