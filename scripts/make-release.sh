#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
#  把 build-node-android.sh 产出的 node 二进制打成 OTA 发布包，并计算 sha256。
#
#  ⚠️ 重要现状说明（别被这条命令误导）：
#  打包好的 OTA 包解压后落在 filesDir（应用可写目录），而 Android 10+ 的
#  SELinux 禁止 exec 该目录中的文件（W^X）。也就是说，当前 OTA 通道能完成
#  「下载 / sha256 校验 / 解压」这些数据层面的动作，但解压出来的 node
#  【无法被 ProcessBuilder 直接启动】。
#  真正可执行的 node 只有一份：随 APK 打进 jniLibs、由系统解压到
#  /data/app/.../lib/<abi>/libnode.so 的那份（见 NodeProvisioner 的说明）。
#  所以现阶段"升级 Node"的实际手段是重新构建 APK；本脚本保留作为将来接入
#  可执行型 OTA（例如经 app_process 拉起）的生产端工具。
# ============================================================================

VER="${1:?用法: ./scripts/make-release.sh <node-version>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# 产物源：与 build-node-android.sh 的 OUT_DIR 保持一致（jniLibs 下的 libnode.so）。
# 注意 zip 内部条目名仍叫 node —— 这是 OTA 包内的约定文件名，与本地打包形式无关。
SRC="$ROOT/app/src/main/jniLibs/arm64-v8a/libnode.so"
OUT_DIR="$ROOT/release"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/node-${VER}-android-arm64-v8a.zip"

[ -f "$SRC" ] || { echo "缺少 node 二进制: $SRC —— 请先跑 ./scripts/build-node-android.sh $VER"; exit 1; }

# 先复制成约定名，再打包，保证包内结构是 node（OTA 端解析依赖这个名字）。
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp -f "$SRC" "$STAGE/node"
( cd "$STAGE" && zip -q -X "$OUT" node ) || { echo "zip 失败，请安装 zip"; exit 1; }
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
