#!/usr/bin/env bash
# ============================================================================
#  产出内核 feed 目录（A'' 自举的「投递侧」）
#
#  解决什么问题
#  ------------
#  A'' 路线的完整闭环是三段：
#    ① 构建侧产出已签名内核包       ← build-kernel-bundle.sh（已存在）
#    ② 把包**组织成设备能消费的 feed** ← 本脚本（缺的就是这一段）
#    ③ 设备侧从 feed 安装           ← LocalKernelFeed.kt + KernelInstaller.kt（已存在）
#
#  ②缺位的后果：`kernel-ota.yml` 只把 kernel-<ver>.zip 与 kernel-manifest.json
#  丢到 Release，那是给人下载的**散件**；而设备侧 `LocalKernelFeed.scan()`
#  期望的是一份**目录约定**（kernel-feed/ 下的 kernel-*.zip + kernel-manifest.json）。
#  两者形状不一样 —— 用户拿到 Release 附件后仍需手工猜"该放哪、放成什么名字"，
#  「自举」在最后一百米断掉了。
#
#  本脚本把这一段补齐：产出一个**可以直接整体投递**的目录。
#
#  产出结构（feed/）
#  ---------------
#    feed/
#      kernel-<version>.zip        内核包（已签名）
#      kernel-manifest.json        设备端消费的清单（sha256/version 锚点）
#      KERNEL-FEED-README.txt      投递说明（给操作者看，设备端不认识它）
#
#  投递方式（三种，都可用）
#  ----------------------
#    · adb push feed/* /sdcard/dsh/kernel-feed/
#    · adb push feed/* /sdcard/Android/data/<pkg>/files/kernel-feed/
#    · 直接把 feed/ 目录拷进设备任意位置，再用文件管理器移进去
#  设备下次启动时 NodeRuntimeService 步骤 0b 会自动发现并安装。
#
#  为什么 manifest 是「增强」而非「必需」
#  ------------------------------------
#  包内的 ed25519 签名是**唯一的强制信任源**（公钥焊死在 APK）。
#  manifest 里的 sha256 是**额外锚点**，作用是挡住"用一个合法旧包替换新包"
#  这类重放。设备侧 [LocalKernelFeed] 对 manifest 缺失/损坏是容错的
#  （只记诊断、不阻断安装），本脚本也不把它当生存前提 ——
#  但**有就一定要对**，所以这里会把 manifest 与实际 zip 字节复核一遍。
#
#  用法
#  ----
#    ./scripts/build-kernel-feed.sh <kernel-src-dir> <version> [abi] [out-dir]
#    例：./scripts/build-kernel-feed.sh ../dsh-android-kernel 1.2.0 \
#            node24-arm64-android35 /tmp/feed
#
#  前置
#  ----
#    keys/ota-private.pem（与 APK 内 ota-public.pem 配对）—— 缺则明确失败
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:?用法: build-kernel-feed.sh <kernel-src-dir> <version> [abi] [out-dir]}"
VER="${2:?缺少 version 参数}"
ABI="${3:-node24-arm64-android35}"
OUT="${4:-$ROOT/feed}"

ANCHOR="$ROOT/app/src/main/assets/ota-public.pem"

# ---- 密钥位置可覆盖 ----
# 默认是仓库内的标准位置（keys/ota-private.pem + assets/ota-public.pem），
# 但允许用与 container-engine/src/keys.js **同名**的环境变量覆盖。
#
# 为什么需要可覆盖：
#   · 测试隔离 —— kernel-feed-test.js 要在临时目录里用**自造密钥对**跑完整流程，
#     不能碰生产私钥（否则测试依赖一个按设计不该存在于仓库的文件，CI 上必崩）；
#   · 密钥轮换 —— 换密钥时不必先把新密钥拷进仓库再跑。
#
# 这里刻意**复用 keys.js 已有的那两个变量名**，而不是自创一套：
# 两套变量名意味着"设了 A 却没生效"，是最难查的一类配置问题。
PRIV="${DSH_OTA_PRIVATE_KEY_PATH:-$ROOT/keys/ota-private.pem}"
ANCHOR="${DSH_OTA_PUBLIC_KEY_PATH:-$ANCHOR}"

# ---- 前置校验：三条都必须满足，一条不满足就不产 feed ----
# 顺序刻意是「越便宜越早」：路径检查 → 私钥 → 公私钥配对 → 才真去打包。
# 打包最贵（要遍历整个内核源码），错的东西不该走到那一步。

[ -d "$SRC" ] || { echo "[feed] [error] 内核源码目录不存在: $SRC" >&2; exit 1; }
[ -f "$SRC/bin/dsh-supervisor" ] || {
  echo "[feed] [error] 找不到内核入口: $SRC/bin/dsh-supervisor" >&2
  echo "[feed]         内核包结构约定为 <src>/bin/dsh-supervisor" >&2
  exit 1
}
[ -f "$PRIV" ] || {
  echo "[feed] [error] 私钥缺失: $PRIV" >&2
  echo "[feed]         feed 里的包必须是签名的（公钥焊死在 APK，未签名的包设备一律拒收）" >&2
  echo "[feed]         本地开发: ./scripts/keygen.sh ；CI: secret OTA_PRIVATE_KEY_PEM" >&2
  echo "[feed]         也可用 DSH_OTA_PRIVATE_KEY_PATH 指定其它位置的私钥。" >&2
  exit 1
}
[ -f "$ANCHOR" ] || { echo "[feed] [error] 公钥锚点缺失: $ANCHOR" >&2; exit 1; }

echo "[feed] 内核源 : $SRC"
echo "[feed] 版本   : $VER"
echo "[feed] abi    : $ABI"
echo "[feed] 输出   : $OUT"
echo "[feed] 私钥   : $PRIV"
echo "[feed] 公钥锚 : $ANCHOR"

# ---- 公私钥配对校验 ----
# 不查的后果：产出的 feed 里每个包在设备上都会 `signature-invalid`，
# 而排查它要一路走到 KernelInstaller 的诊断里才发现，代价极高。
# 花钱在构建期一秒，省掉真机上一小时。
node - "$PRIV" "$ANCHOR" <<'NODE'
const fs = require('fs'), crypto = require('crypto');
const [privPath, pubPath] = process.argv.slice(2);
const priv = fs.readFileSync(privPath, 'utf8');
const pub = fs.readFileSync(pubPath, 'utf8');
const probe = Buffer.from('dsh-feed-keypair-probe');
let sig;
try { sig = crypto.sign(null, probe, priv); }
catch (e) { console.error('[feed] [error] 私钥不可用于 ed25519 签名: ' + e.message); process.exit(1); }
let ok = false;
try { ok = crypto.verify(null, probe, pub, sig); }
catch (e) { console.error('[feed] [error] 公钥不可用于 ed25519 验签: ' + e.message); process.exit(1); }
if (!ok) {
  console.error('[feed] [error] 公私钥**不配对**：keys/ota-private.pem 与 assets/ota-public.pem 不是一对。');
  console.error('[feed]         这样的 feed 投到设备上，包会被一律判 signature-invalid。');
  process.exit(1);
}
console.log('[feed] 公私钥配对校验通过 ✓');
NODE

# ---- 打包（复用 build-bundle，产物落 release/）----
# 注意 url 传空：feed 是**本地投递**用的，包内不该写一个指向公网的 url ——
# 设备端从本地文件安装，那个 url 无意义，写了反而会误导（让人以为要走网络）。
#
# 用 DSH_BUNDLE_OUT_DIR 把中间产物导到一个**临时目录**，而不是仓库的 release/。
# 理由：feed 的交付物是 $OUT 那个目录；release/ 只是 build-bundle 的默认落点，
# 在这里属于"顺手产生的中间物"。把它导走有两个好处：
#   · 不污染仓库（release/ 是 gitignored，但留着陈旧版本会让人误以为它是最新的）
#   · 脚本可重定位 —— 原先产物路径由脚本自身位置推导，把脚本拷到别处
#     就会在错误的位置找包（kernel-feed-test.js 真实暴露过这个耦合）
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
# DSH_OTA_PRIVATE_KEY_PATH 直接透传（build-bundle → keys.js 读的就是这个变量），
# 于是"配对校验用的私钥"与"实际签名用的私钥"必然是同一把 —— 不会出现
# 「校验过了配对、签名却换了一把」这种荒诞但极难查的情况。
DSH_OTA_PRIVATE_KEY_PATH="$PRIV" \
DSH_BUNDLE_OUT_DIR="$STAGE" \
  node "$ROOT/container-engine/bin/build-bundle.js" "$SRC" "$VER" "$ABI" "" >/dev/null
SRC_ZIP="$STAGE/kernel-$VER.zip"
[ -f "$SRC_ZIP" ] || { echo "[feed] [error] 打包未产出 $SRC_ZIP" >&2; exit 1; }

# ---- 就位 ----
# 先清空再建：feed 里**只该有当前这一个版本**。
# 留旧版本会让设备端「按文件名倒序取一个」的规则产生歧义
# （虽然倒序通常能取到新的，但把歧义留着迟早会成为排查负担）。
rm -rf "$OUT"
mkdir -p "$OUT"
cp "$SRC_ZIP" "$OUT/"

# ---- 复核 manifest 与实际 zip 字节一致 ----
# 这是本脚本最关键的一步校验。理由：
#   manifest 是设备侧**唯一**的 sha256 锚点，而它是独立于包生成的文件。
#   两者若不一致，设备端会直接判 `sha256-mismatch` 并拒绝安装 ——
#   表现为"包明明验签能过，却装不上"，非常费解。
#   这里在构建期就把这种不一致变成硬失败。
node - "$STAGE/kernel-manifest.json" "$OUT/kernel-$VER.zip" "$OUT/kernel-manifest.json" <<'NODE'
const fs = require('fs'), crypto = require('crypto'), path = require('path');
const [srcManifest, zipPath, dstManifest] = process.argv.slice(2);
const m = JSON.parse(fs.readFileSync(srcManifest, 'utf8'));
const buf = fs.readFileSync(zipPath);
const actual = crypto.createHash('sha256').update(buf).digest('hex');

if (m.sha256 !== actual) {
  console.error('[feed] [error] manifest.sha256 与实际 zip 字节不一致 —— 设备端会判 sha256-mismatch：');
  console.error('[feed]         manifest 说 ' + m.sha256);
  console.error('[feed]         实际是     ' + actual);
  process.exit(1);
}
if (m.url) {
  // feed 场景下包内不该带 url（见上文注释）。这里不判失败，只提醒 ——
  // 因为 build-bundle 是共用的，未来可能有"远端 feed"需求。
  console.error('[feed] [warn] manifest 里带了 url=' + m.url + '（本地 feed 通常不需要 url）');
}
// 复核版本号：manifest 与文件名必须一致，否则设备端 --version 锚点会冲突
const expectVer = path.basename(zipPath).replace(/^kernel-/, '').replace(/\.zip$/, '');
if (m.version !== expectVer) {
  console.error('[feed] [error] manifest.version=' + m.version + ' 与文件名版本 ' + expectVer + ' 不一致');
  process.exit(1);
}
fs.writeFileSync(dstManifest, JSON.stringify(m, null, 2));
console.log('[feed] manifest 复核通过 ✓ sha256=' + actual.slice(0, 16) + '… version=' + m.version);
NODE

# ---- 投递说明（给人看，设备端不认识这个文件）----
cat > "$OUT/KERNEL-FEED-README.txt" <<EOF
DSH 内核 feed（本地投递）

内容
  kernel-$VER.zip        已签名的内核包（ed25519，公钥焊死在 APK）
  kernel-manifest.json   sha256/version 锚点（可选但强烈建议一并投递）

投递到设备的两个位置（任一即可）
  1) /sdcard/dsh/kernel-feed/
  2) /sdcard/Android/data/<包名>/files/kernel-feed/

  例：
    adb push kernel-$VER.zip kernel-manifest.json /sdcard/dsh/kernel-feed/

生效方式
  下次启动容器时自动发现并安装（NodeRuntimeService 步骤 0b）。
  安装成功后会清掉 feed 里的候选包，避免重复安装。
  安装**必须**验签通过 —— 未签名或签名不匹配的包会被拒绝，
  这是刻意的：设备只安装已签名内核，不生产内核。

排查
  桥方法 build.kernelStatus 可查 current / installed / feedPending。
  安装失败细节（含 Node 校验器的原始输出）见运行时诊断。
EOF

# ---- 自检：用**与设备端同一个校验器**验一遍 ----
# 若这里过了而设备上不过，差异只可能来自数据（而非逻辑）。
VERIFY="$ROOT/app/src/main/assets/node/kernel-verify.js"
if [ -f "$VERIFY" ]; then
  echo "[feed] 用设备端校验器自检…"
  set +e
  # 带上 --sha256 与 --version：模拟设备端"有 manifest 锚点"的最严路径
  SHA="$(node -e "process.stdout.write(JSON.parse(require('fs').readFileSync('$OUT/kernel-manifest.json','utf8')).sha256)")"
  OUT_TXT="$(node "$VERIFY" --zip "$OUT/kernel-$VER.zip" --pubkey "$ANCHOR" --sha256 "$SHA" --version "$VER" 2>&1)"
  RC=$?
  set -e
  echo "$OUT_TXT" | sed 's/^/[feed]   /'
  if [ "$RC" -ne 0 ]; then
    echo "[feed] [error] feed 内的包未通过设备端校验器（退出码 $RC）—— 投到设备上也装不上" >&2
    exit 1
  fi
else
  echo "[feed] [warn] 找不到设备端校验器 $VERIFY，跳过自检" >&2
fi

echo
echo "[feed] 完成 → $OUT"
ls -la "$OUT" | sed 's/^/[feed]   /'
echo
echo "[feed] 投递: adb push $OUT/kernel-$VER.zip $OUT/kernel-manifest.json /sdcard/dsh/kernel-feed/"
