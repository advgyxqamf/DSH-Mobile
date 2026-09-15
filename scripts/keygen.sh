#!/usr/bin/env bash
# ============================================================================
#  生成容器 OTA 开发期 ed25519 密钥对。
#
#  双信任根（见 docs/BASE_SPEC.md §8）：
#   - 私钥（keys/ota-private.pem）仅用于「签名内核包」，绝不入库、仅本地/CI secret。
#   - 公钥焊进 APK（app/src/main/assets/ota-public.pem）作只读锚点，
#     设备端验签只用这把焊死的公钥 —— 私钥轮换需发新版 APK。
#
#  私钥格式 PKCS8、公钥 SPKI，正好是 Node crypto（ed25519）verify/sign 需要的 PEM。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/keys"
PRIV="$ROOT/keys/ota-private.pem"
PUB="$ROOT/keys/ota-public.pem"
ANCHOR="$ROOT/app/src/main/assets/ota-public.pem"

if [ -f "$PRIV" ]; then
  echo "[keygen] 私钥已存在: $PRIV （跳过生成，保留现有密钥）"
else
  openssl genpkey -algorithm ed25519 -out "$PRIV"
  chmod 600 "$PRIV"
  echo "[keygen] 已生成私钥: $PRIV"
fi

openssl pkey -in "$PRIV" -pubout -out "$PUB"
mkdir -p "$(dirname "$ANCHOR")"
cp "$PUB" "$ANCHOR"
echo "[keygen] 已生成公钥: $PUB"
echo "[keygen] 公钥锚点已写入（焊进 APK）: $ANCHOR"
echo "[keygen] 注意：私钥仅用于签名内核包，切勿提交；CI 用 secret 注入。"
