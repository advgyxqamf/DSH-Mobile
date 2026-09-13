#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
#  把 build-node-android.sh 产出的 node 二进制打成 OTA 发布包，并计算 sha256。
#  这条命令是“Node 可升级”架构的生产端：每次你用 NDK 编出新版本 Node，
#  跑它生成 zip + 校验和，上传到自己的 OTA 服务器，再在 node-versions.json
#  追加一条记录——App 端 NodeVersionManager 即可在不发新版 APK 的前提下升级 Node。
# ============================================================================

VER="${1:?用法: ./scripts/make-release.sh <node-version>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/assets/node-bin/arm64-v8a/node"
OUT_DIR="$ROOT/release"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/node-${VER}-android-arm64-v8a.zip"

[ -f "$SRC" ] || { echo "缺少 node 二进制: $SRC —— 请先跑 ./scripts/build-node-android.sh $VER"; exit 1; }

( cd "$(dirname "$SRC")" && zip -q -X "$OUT" node ) || { echo "zip 失败，请安装 zip"; exit 1; }
SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$OUT")"

echo "发布包: $OUT"
echo "sha256: $SHA"
echo "size:   $SIZE"
echo
echo "把该 zip 上传到你的 OTA 服务器后，在 app/src/main/assets/node-versions.json 的 versions 中追加一条:"
cat <<JSON
  {
    "version": "$VER",
    "channel": "lts",
    "minAndroidApi": 24,
    "bundled": false,
    "url": "<你的 OTA 基址>/node-${VER}-android-arm64-v8a.zip",
    "sha256": "$SHA",
    "size": $SIZE
  }
JSON
echo
echo "App 端 NodeVersionManager 会在『检查更新』时发现它，下载后做 sha256 校验并原子切换当前版本指针。"
