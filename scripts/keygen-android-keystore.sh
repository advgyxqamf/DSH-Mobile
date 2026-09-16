#!/usr/bin/env bash
# ============================================================================
#  生成**APK 签名**用的 keystore（≠ 内核签名私钥，两把钥匙各管一段）
# ============================================================================
#  本项目有**两套独立的信任根**，很容易混，先说清：
#
#    ① APK 签名（本脚本）—— Java keystore（RSA/EC）
#       管的是「这个 APK 是不是同一个发布者发的」。
#       设备安装器据此决定能否覆盖安装、能否升级。
#       锚点是**设备上已装的那个包**的签名。
#
#    ② 内核签名—— ed25519（scripts/keygen.sh）
#       管的是「这个内核包是不是官方签的」。
#       锚点是**焊死在 APK 里**的 assets/ota-public.pem。
#
#  两者**没有任何关系**，不能互相替代：
#    · 内核包签名对了，不代表 APK 能装到设备上；
#    · APK 签名稳定了，也不代表内核包可信。
#
#  为什么需要①：此前项目**完全没有** signingConfig，每次 CI 出包都用
#  AGP 现场生成的 debug keystore，指纹每次都不同 ⇒ 新包无法覆盖安装
#  旧包（INSTALL_FAILED_UPDATE_INCOMPATIBLE）⇒「设备自我升级 APK」这条路
#  走不通。详见 app/build.gradle.kts 里 signingConfigs 段的说明。
#
#  ⚠ 一旦用于真实发布，**这把 keystore 必须永久保存**：
#    丢掉它 = 再也无法给已装该应用的设备推送升级（只能让用户卸载重装，
#    而那会清掉 files/ 下的全部内核与 Agent 数据）。
#    Android 生态里没有"换回旧签名"的机制。
#
#  用法
#  ----
#    ./scripts/keygen-android-keystore.sh [alias] [validity_days]
#    产物：keys/release.keystore（gitignored）+ keys/keystore.properties
#
#  之后构建：
#    DSH_KEYSTORE_PASSWORD=... DSH_KEY_ALIAS=dsh DSH_KEY_PASSWORD=... \
#        ./gradlew assembleRelease
#    （或把 keys/keystore.properties 里的值导成环境变量）
#
#  CI：
#    把 keystore base64 存进 secret ANDROID_KEYSTORE_BASE64，
#    密码存 ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS /
#    ANDROID_KEY_PASSWORD，workflow 里解码到 keys/release.keystore 即可。
#    （与 repack-apk.yml 用的那组 secret 同名 —— 复用已有配置，不另立一套。）
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ALIAS="${1:-dsh}"
DAYS="${2:-10000}"
KS="$ROOT/keys/release.keystore"
PROPS="$ROOT/keys/keystore.properties"

mkdir -p "$ROOT/keys"

if [ -f "$KS" ]; then
  echo "[keygen-apk] [error] 已存在: $KS" >&2
  echo "[keygen-apk]         若确实要重建，先手工删掉它。" >&2
  echo "[keygen-apk]         ⚠ 覆盖一个已用于发布的 keystore，等于放弃" >&2
  echo "[keygen-apk]           给已装设备升级的能力（Android 无回退机制）。" >&2
  exit 1
fi

# 随机生成强密码。用 /dev/urandom + base64，避免 openssl rand 在某些
# 精简环境里不可用。取 32 字节 → 44 字符，足够。
gen_pw() { head -c 32 /dev/urandom | base64 | tr -d '\n=+/' | cut -c1-40; }

STOREPASS="$(gen_pw)"
KEYPASS="$STOREPASS"   # 同一个密码：keystore 与 key 分开设密码只在多密钥场景有意义

echo "[keygen-apk] 正在生成 keystore…"
keytool -genkeypair \
  -v \
  -keystore "$KS" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity "$DAYS" \
  -storetype PKCS12 \
  -storepass "$STOREPASS" \
  -keypass "$KEYPASS" \
  -dname "CN=DSH Mobile, OU=Container, O=DSH, L=, ST=, C=CN" \
  >/dev/null 2>&1

chmod 600 "$KS"

# 属性文件（同样 gitignored）—— 让本地 gradle 构建不必每次手打密码
cat > "$PROPS" <<EOF
# APK 签名密码（由 scripts/keygen-android-keystore.sh 生成）
# ⚠ 与 keys/ota-private.pem 一样属于机密，绝不入库（keys/ 整体 gitignored）。
# CI 请用 secret，不要提交这个文件。
DSH_KEYSTORE_PASSWORD=$STOREPASS
DSH_KEY_ALIAS=$ALIAS
DSH_KEY_PASSWORD=$KEYPASS
EOF
chmod 600 "$PROPS"

echo "[keygen-apk] 完成"
echo "  keystore : $KS"
echo "  别名     : $ALIAS"
echo "  有效期   : $DAYS 天"
echo "  属性文件 : $PROPS"
echo
echo "  指纹（把它记下来，用于核对 CI 产物是否为同一签名）："
keytool -list -v -keystore "$KS" -storepass "$STOREPASS" 2>/dev/null \
  | grep -E "SHA1:|SHA256:" | sed 's/^/    /'
echo
echo "  用法："
echo "    source <(sed 's/^/export /' $PROPS) && ./gradlew assembleRelease"
