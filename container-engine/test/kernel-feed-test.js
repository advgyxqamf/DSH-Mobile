'use strict';

// ============================================================================
//  内核 feed 构建脚本测试（scripts/build-kernel-feed.sh）
// ============================================================================
//  feed 是 A'' 自举闭环的**中间一段**：构建侧产出 → feed 组织成设备能消费的形状
//  → 设备侧安装。它的形状必须与设备侧 `LocalKernelFeed.scan()` 的期望**严格一致**，
//  而这两端分别用 bash 和 Kotlin 写的 —— 没有任何编译器能帮我们发现不一致。
//
//  所以这里验的全是**跨语言约定**，而不是"脚本能跑"：
//    · 文件名形态（设备端按 kernel-*.zip 匹配、按名字倒序取最大的）
//    · manifest 与 zip 字节的 sha256 必须一致（不一致 → 设备判 sha256-mismatch）
//    · manifest.version 必须与文件名里的版本一致（否则 --version 锚点冲突）
//    · 包必须过设备端校验器
//
//  这些约定各自都曾以"看起来正常、装机才炸"的形式存在过，很值得钉住。
//
//  依赖：bash + node + 一对**自造**ed25519 密钥（不碰生产私钥 —— 见
//  e2e-mock-kernel-test.js 顶部关于"读生产密钥导致 CI 崩"的说明）。
// ============================================================================

const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');

const makeRunner = require('./harness');
const { check, finish } = makeRunner('kernel-feed');

const ROOT = path.resolve(__dirname, '..', '..');
const SCRIPT = path.join(ROOT, 'scripts', 'build-kernel-feed.sh');

console.log('--- 内核 feed 构建 ---');

if (!fs.existsSync(SCRIPT)) {
  check('build-kernel-feed.sh 存在', false, SCRIPT);
  finish();
}
check('build-kernel-feed.sh 存在', true);

// ---- 纯静态检查：脚本必须坚持的几条底线 ----
const sh = fs.readFileSync(SCRIPT, 'utf8');

// 1) 设备端 LocalKernelFeed 的目录/命名约定必须在这里被复现
const FEED_KT = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'example', 'nodecontainer', 'LocalKernelFeed.kt');
if (fs.existsSync(FEED_KT)) {
  const kt = fs.readFileSync(FEED_KT, 'utf8');
  check('两端都约定 feed 目录名为 kernel-feed（Kotlin 侧）', kt.includes('"kernel-feed"'));
  // 脚本本身不拼 kernel-feed 目录名（由调用方传 out-dir），
  // 但**必须**把该约定写进给操作者的说明里，否则用户不知道放哪。
  check('脚本说明里给出 kernel-feed 投递路径',
    sh.includes('/sdcard/dsh/kernel-feed/'));
}

// 2) 私钥缺失必须**明确失败**，不能产未签名 feed
check('缺私钥时明确失败（不产未签名包）',
  /私钥缺失/.test(sh) && /exit 1/.test(sh));

// 3) 必须做公私钥配对校验（否则投到设备上一律 signature-invalid）
check('做公私钥配对校验', /配对校验通过|不配对/.test(sh));

// 4) 必须复核 manifest.sha256 与 zip 实际字节
check('复核 manifest.sha256 与 zip 字节一致', /manifest\.sha256 与实际 zip 字节不一致/.test(sh));

// 5) 必须用设备端同一个校验器自检
check('用设备端校验器自检 feed', /kernel-verify\.js/.test(sh) && /--sha256/.test(sh) && /--version/.test(sh));

// ---- 端到端：真跑一次（用自造密钥，不碰生产密钥）----
//
// 脚本固定读 keys/ota-private.pem 与 assets/ota-public.pem。
// 为了不动生产密钥，这里造一个**临时仓库布局**：把脚本与它需要的文件
// 按相对结构拷过去，密钥用自造的。这比"临时覆盖生产密钥文件再恢复"
// 安全得多 —— 后者一旦中途失败就会把真实私钥留在错误状态。
const kp = crypto.generateKeyPairSync('ed25519', {
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  publicKeyEncoding: { type: 'spki', format: 'pem' },
});

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'feed-test-'));
// 布局：sandbox/{scripts, container-engine/bin, container-engine/src, app/src/main/assets/{node,kernel}, keys}
fs.mkdirSync(path.join(sandbox, 'scripts'), { recursive: true });
fs.mkdirSync(path.join(sandbox, 'keys'), { recursive: true });
fs.mkdirSync(path.join(sandbox, 'app', 'src', 'main', 'assets', 'node'), { recursive: true });
fs.copyFileSync(SCRIPT, path.join(sandbox, 'scripts', 'build-kernel-feed.sh'));

// 复用真实仓库的 container-engine（脚本会调 bin/build-bundle.js）
fs.symlinkSync(path.join(ROOT, 'container-engine'), path.join(sandbox, 'container-engine'), 'dir');

fs.writeFileSync(path.join(sandbox, 'keys', 'ota-private.pem'), kp.privateKey);
fs.writeFileSync(path.join(sandbox, 'app', 'src', 'main', 'assets', 'ota-public.pem'), kp.publicKey);
// 设备端校验器必须真实存在 —— 脚本会用它自检
fs.copyFileSync(
  path.join(ROOT, 'app', 'src', 'main', 'assets', 'node', 'kernel-verify.js'),
  path.join(sandbox, 'app', 'src', 'main', 'assets', 'node', 'kernel-verify.js')
);

// 造一个最小内核源码树
const src = path.join(sandbox, 'kernel-src');
fs.mkdirSync(path.join(src, 'bin'), { recursive: true });
fs.mkdirSync(path.join(src, 'manager', 'dist'), { recursive: true });
fs.writeFileSync(path.join(src, 'bin', 'dsh-supervisor'), '#!/usr/bin/env node\nconsole.log("dsh");\n');
fs.writeFileSync(path.join(src, 'manager', 'dist', 'index.js'), 'x'.repeat(3000));

const VER = '1.2.3';
const OUT = path.join(sandbox, 'feed-out');
let rc = 0; let out = '';
// 用环境变量把密钥指向沙箱里的自造密钥对 —— 脚本与 build-bundle 都认这两个
// 变量（同一个名字，见 build-kernel-feed.sh 的"密钥位置可覆盖"说明）。
// 不设的话脚本会去读真实仓库的 keys/ota-private.pem，测试就变成
// "依赖一个不该存在于仓库的文件"（CI 上必崩，且失败原因极具误导性）。
const sandboxEnv = Object.assign({}, process.env, {
  DSH_OTA_PRIVATE_KEY_PATH: path.join(sandbox, 'keys', 'ota-private.pem'),
  DSH_OTA_PUBLIC_KEY_PATH: path.join(sandbox, 'app', 'src', 'main', 'assets', 'ota-public.pem'),
});
try {
  out = execFileSync('bash',
    [path.join(sandbox, 'scripts', 'build-kernel-feed.sh'), src, VER, 'abi-test', OUT],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], env: sandboxEnv });
} catch (e) {
  rc = e.status;
  out = (e.stdout || '') + (e.stderr || '');
}

check('脚本在自造密钥/最小内核树下成功（rc=0）', rc === 0,
  rc === 0 ? 'ok' : out.split('\n').slice(-6).join(' | '));

if (rc === 0) {
  // ---- 产出形状 ----
  check('产出 kernel-<version>.zip（设备端按此模式匹配）',
    fs.existsSync(path.join(OUT, `kernel-${VER}.zip`)));
  check('产出 kernel-manifest.json（设备端读此文件名）',
    fs.existsSync(path.join(OUT, 'kernel-manifest.json')));
  // feed 里只该有一个包 —— 留旧版本会让"按文件名倒序取一个"的规则产生歧义
  const zips = fs.readdirSync(OUT).filter((f) => f.endsWith('.zip'));
  check('feed 内只有一个内核包（不留旧版本，避免倒序取值歧义）',
    zips.length === 1, zips.join(', '));

  // ---- manifest 与 zip 的一致性（设备端最依赖的一条）----
  const zipBuf = fs.readFileSync(path.join(OUT, `kernel-${VER}.zip`));
  const m = JSON.parse(fs.readFileSync(path.join(OUT, 'kernel-manifest.json'), 'utf8'));
  const actualSha = crypto.createHash('sha256').update(zipBuf).digest('hex');
  check('manifest.sha256 等于 zip 实际字节的 sha256', m.sha256 === actualSha,
    m.sha256 === actualSha ? 'ok' : `manifest=${m.sha256} actual=${actualSha}`);
  check('manifest.version 与文件名版本一致', m.version === VER, m.version);
  // 本地 feed 不该带 url：裸文件名做 url 没有 scheme/host，会让人误以为有远端通道
  check('本地 feed 的 manifest 不带 url（空串）', m.url === '',
    `url=${JSON.stringify(m.url)}`);
  check('manifest 含 ed25519 签名', typeof m.signature === 'string' && m.signature.length > 0);

  // ---- 产出的包必须能被设备端校验器接受 ----
  const VERIFIER = path.join(ROOT, 'app', 'src', 'main', 'assets', 'node', 'kernel-verify.js');
  const ANCHOR = path.join(sandbox, 'app', 'src', 'main', 'assets', 'ota-public.pem');
  let vrc = 0; let vout = '';
  try {
    vout = execFileSync('node', [VERIFIER,
      '--zip', path.join(OUT, `kernel-${VER}.zip`),
      '--pubkey', ANCHOR,
      '--sha256', m.sha256,
      '--version', VER,
    ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (e) {
    vrc = e.status;
    vout = (e.stdout || '') + (e.stderr || '');
  }
  check('产出的 feed 包通过设备端校验器（含 sha256+version 最严路径）', vrc === 0,
    vrc === 0 ? 'rc=0' : vout.split('\n').slice(-3).join(' | '));

  // ---- 投递说明（给人看的，必须含关键信息）----
  const README = path.join(OUT, 'KERNEL-FEED-README.txt');
  if (fs.existsSync(README)) {
    const r = fs.readFileSync(README, 'utf8');
    check('说明含 adb push 命令', /adb push/.test(r));
    check('说明含"验签不过会被拒"（避免用户以为随便放个包就行）', /验签/.test(r));
  } else {
    check('产出投递说明 KERNEL-FEED-README.txt', false, '未找到');
  }

  // ---- 反向：私钥缺失时必须失败（不能静默产未签名包）----
  const sandbox2 = fs.mkdtempSync(path.join(os.tmpdir(), 'feed-err-'));
  fs.mkdirSync(path.join(sandbox2, 'scripts'), { recursive: true });
  fs.copyFileSync(SCRIPT, path.join(sandbox2, 'scripts', 'build-kernel-feed.sh'));
  fs.symlinkSync(path.join(ROOT, 'container-engine'), path.join(sandbox2, 'container-engine'), 'dir');
  fs.mkdirSync(path.join(sandbox2, 'app', 'src', 'main', 'assets'), { recursive: true });
  fs.writeFileSync(path.join(sandbox2, 'app', 'src', 'main', 'assets', 'ota-public.pem'), kp.publicKey);
  // **不建 keys/** —— 模拟私钥缺失。
  // 同时把环境变量指到一个**不存在**的路径：脚本默认路径是仓库内的，
  // 若不覆盖，它会去读真实生产私钥，这个反向用例就测不到"缺失"了。
  const missingEnv = Object.assign({}, process.env, {
    DSH_OTA_PRIVATE_KEY_PATH: path.join(sandbox2, 'keys', 'ota-private.pem'),
    DSH_OTA_PUBLIC_KEY_PATH: path.join(sandbox2, 'app', 'src', 'main', 'assets', 'ota-public.pem'),
  });
  let rc2 = 0; let out2 = '';
  try {
    out2 = execFileSync('bash',
      [path.join(sandbox2, 'scripts', 'build-kernel-feed.sh'), src, VER, 'abi-test', path.join(sandbox2, 'out')],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], env: missingEnv });
  } catch (e) {
    rc2 = e.status;
    out2 = (e.stdout || '') + (e.stderr || '');
  }
  check('私钥缺失时脚本失败（rc≠0，不产未签名 feed）', rc2 !== 0, 'rc=' + rc2);
  check('私钥缺失的报错指明补救方式', /keygen\.sh|OTA_PRIVATE_KEY_PEM/.test(out2));
  check('私钥缺失时不留下输出目录', !fs.existsSync(path.join(sandbox2, 'out')));

  // ---- 反向：公私钥不配对时必须失败 ----
  const kp2 = crypto.generateKeyPairSync('ed25519', {
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    publicKeyEncoding: { type: 'spki', format: 'pem' },
  });
  const sandbox3 = fs.mkdtempSync(path.join(os.tmpdir(), 'feed-mm-'));
  fs.mkdirSync(path.join(sandbox3, 'scripts'), { recursive: true });
  fs.mkdirSync(path.join(sandbox3, 'keys'), { recursive: true });
  fs.mkdirSync(path.join(sandbox3, 'app', 'src', 'main', 'assets'), { recursive: true });
  fs.copyFileSync(SCRIPT, path.join(sandbox3, 'scripts', 'build-kernel-feed.sh'));
  fs.symlinkSync(path.join(ROOT, 'container-engine'), path.join(sandbox3, 'container-engine'), 'dir');
  fs.writeFileSync(path.join(sandbox3, 'keys', 'ota-private.pem'), kp.privateKey);       // 甲钥
  fs.writeFileSync(path.join(sandbox3, 'app', 'src', 'main', 'assets', 'ota-public.pem'), kp2.publicKey); // 乙钥
  const mismatchEnv = Object.assign({}, process.env, {
    DSH_OTA_PRIVATE_KEY_PATH: path.join(sandbox3, 'keys', 'ota-private.pem'),
    DSH_OTA_PUBLIC_KEY_PATH: path.join(sandbox3, 'app', 'src', 'main', 'assets', 'ota-public.pem'),
  });
  let rc3 = 0; let out3 = '';
  try {
    out3 = execFileSync('bash',
      [path.join(sandbox3, 'scripts', 'build-kernel-feed.sh'), src, VER, 'abi-test', path.join(sandbox3, 'out')],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], env: mismatchEnv });
  } catch (e) {
    rc3 = e.status;
    out3 = (e.stdout || '') + (e.stderr || '');
  }
  check('公私钥不配对时脚本失败（否则投到设备一律 signature-invalid）', rc3 !== 0, 'rc=' + rc3);
  check('不配对的报错明确指出原因', /不配对/.test(out3));
}

try { fs.rmSync(sandbox, { recursive: true, force: true }); } catch (_e) { /* 忽略 */ }
finish();
