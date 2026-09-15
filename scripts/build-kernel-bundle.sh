#!/usr/bin/env bash
# ============================================================================
#  构建并签名内核 OTA 包（对齐 docs/BASE_SPEC.md §5 通道一）。
#
#  用法：
#    ./scripts/build-kernel-bundle.sh <kernel-src-dir> <version> [abi] [url-base]
#
#  例：
#    ./scripts/build-kernel-bundle.sh ../dsh-android-kernel 1.4.0 \
#        node24-arm64-android35 https://cdn.example.com/ota
#
#  前置：
#    - 私钥 keys/ota-private.pem 已就位（scripts/keygen.sh 生成；CI 由 secret 注入）。
#    - 公钥锚点 app/src/main/assets/ota-public.pem 已焊接（设备端验签用）。
#
#  产物（release/）：
#    kernel-<version>.zip        OTA 下发的内核包
#    kernel-manifest.json        版本/url/sha256/签名，供 OTA 引擎 fetchManifest
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:?用法: build-kernel-bundle.sh <kernel-src-dir> <version> [abi] [url-base]}"
VER="${2:?缺少 version 参数}"
ABI="${3:-node24-arm64-android35}"
URL_BASE="${4:-}"

if [ ! -f "$ROOT/keys/ota-private.pem" ]; then
  echo "[build-kernel-bundle] 私钥缺失: $ROOT/keys/ota-private.pem（先跑 ./scripts/keygen.sh 或注入 CI secret）" >&2
  exit 1
fi

exec node "$ROOT/container-engine/bin/build-bundle.js" "$SRC" "$VER" "$ABI" "$URL_BASE"
