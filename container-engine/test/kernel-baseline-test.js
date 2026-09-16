'use strict';

// ============================================================================
//  基线内核包测试（**必须**在基线包生成之后运行）
// ============================================================================
//  为什么把它从 kernel-selfboot-test.js 里拆出来
//  ------------------------------------------------
//  基线包 `app/src/main/assets/kernel/baseline.zip` 是**构建产物**
//  （.gitignore 排除，见该文件说明），所以「它存不存在」取决于**流水线跑到了哪一步**：
//
//     · 刚 clone 下来的源码树        → 不存在
//     · fast-apk.yml 的第 11 步之后  → 存在
//
//  原先把这几条断言混在 kernel-selfboot-test.js 里，结果踩了一个自己造的坑：
//  CI 把「容器引擎测试」排在 gradle 之前（**为了快速失败**，这是对的），
//  却又用 DSH_REQUIRE_BASELINE=1 要求基线包必须存在（**但生成它的步骤在后面**）
//  → 测试在 13 秒内必然红，而原因是步骤顺序，与被测代码毫无关系。
//
//  拆开之后的编排变成两段，两个目的都保住：
//    · 第 1 段（无基线包）：跑不依赖产物的测试 → 秒级快速失败
//    · 第 11 步：生成基线包
//    · 第 2 段（本文件）：专门验基线包 → 产物出错时精确报错
//
//  这个拆分也顺带修正了一个语义问题：kernel-selfboot-test.js 验的是
//  **自举链的逻辑**（与产物无关，任何时候都该绿）；本文件验的是
//  **某一次构建的产物**（本质上是构建期检查）。两者生命周期不同，
//  混在一起会让"逻辑对不对"和"这次产物好不好"互相污染。
//
//  依赖：node（本仓库自带即可，不需要被测的内核运行时）
// ============================================================================

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const makeRunner = require('./harness');
const { check, finish } = makeRunner('kernel-baseline');

const ROOT = path.resolve(__dirname, '..', '..');
const { listZip } = require('../src/zip');

const BASELINE = path.join(ROOT, 'app', 'src', 'main', 'assets', 'kernel', 'baseline.zip');
const ANCHOR = path.join(ROOT, 'app', 'src', 'main', 'assets', 'ota-public.pem');
const VERIFIER = path.join(ROOT, 'app', 'src', 'main', 'assets', 'node', 'kernel-verify.js');

// REQUIRE_BASELINE 的语义在这里比在 selfboot 里更纯粹：
// 本文件**就是**为验基线包而存在的，所以"没包"基本等同于"这次没验成"。
// 但仍不能无条件判 FAIL —— 开发者本地跑 `npm test` 时包通常不存在
// （那是构建产物，按需生成）。所以：
//   · 未设 REQUIRE_BASELINE → 显式 SKIP，并说明"未验证什么"
//   · 设了 REQUIRE_BASELINE → 强制存在，不存在即 FAIL（CI 用）
const REQUIRE_BASELINE = process.env.DSH_REQUIRE_BASELINE === '1';

console.log('--- 基线内核包 ---');

if (!fs.existsSync(BASELINE)) {
  if (REQUIRE_BASELINE) {
    check('基线包存在（DSH_REQUIRE_BASELINE=1 时强制）', false,
      '未找到 ' + BASELINE + ' —— 请确认 fast-apk.yml 的 "Build kernel baseline bundle" ' +
      '步骤排在本次测试之前，且 secrets.OTA_PRIVATE_KEY_PEM 已配置');
  } else {
    // 说明"未验证什么"，而不是简单跳过 —— 读者必须知道这次没验到什么。
    console.log('SKIP 基线包未生成（构建产物，见 .gitignore）');
    console.log('     —— 未验证：基线包体积 / 内部结构 / 入口存在性 / 公钥验签。');
    console.log('     —— 生成: ./scripts/build-kernel-baseline.sh ../dsh-android-kernel');
    console.log('     —— 设 DSH_REQUIRE_BASELINE=1 可强制要求存在（CI 用）。');
  }
  finish();
}

const bBuf = fs.readFileSync(BASELINE);
console.log('基线包: ' + BASELINE + ' (' + bBuf.length + ' 字节)');

// ---- 体积 ----
// 上限 8MB 与 kernel-bundle.js 的 HARD_LIMIT 同源。这里再断言一次不是冗余：
// HARD_LIMIT 挡的是"打包时别塞进 node_modules"（构建期），
// 这里挡的是"落盘的产物别是个巨型文件"（产物期）。
// 两者失败模式不同 —— 前者是打包逻辑错，后者可能是构建脚本拷错了文件。
check('基线包体积在合理范围（<8MB）', bBuf.length < 8 * 1024 * 1024,
  (bBuf.length / 1048576).toFixed(2) + ' MB');

// ---- 内部结构 ----
let bList = [];
try {
  bList = listZip(bBuf);
  check('基线包是合法 zip（EOCD + 中央目录可解）', bList.length > 0, bList.length + ' 个条目');
} catch (e) {
  check('基线包是合法 zip（EOCD + 中央目录可解）', false, String(e && e.message));
}
if (bList.length) {
  check('基线包含 kernel.json', bList.some((e) => e.name.endsWith('kernel.json')));
  check('基线包含入口 bin/dsh-supervisor',
    bList.some((e) => e.name.endsWith('bin/dsh-supervisor')));
  // 包内路径必须只有一条 kernel/<version>/ 前缀（落盘逻辑依赖这个约定）
  const prefixes = new Set(bList.filter((e) => !e.name.endsWith('/'))
    .map((e) => e.name.split('/').slice(0, 2).join('/')));
  check('包内路径统一在 kernel/<version>/ 下', prefixes.size === 1,
    [...prefixes].join(', '));
  // 历史事故回归：node_modules 曾被无脑打进包（184MB → APK 撑爆）
  check('基线包不含 node_modules（历史事故回归）',
    !bList.some((e) => e.name.includes('node_modules')));
}

// ---- 验签（最关键的一条）----
// 基线包是"无网首启"的唯一来源，它验不过 = APK 开箱起不来。
// 用**与设备端同一个校验器**跑，而不是重新实现一遍 —— 若这里过了而
// 设备上不过，差异只可能来自数据而非逻辑。
if (!fs.existsSync(ANCHOR)) {
  check('公钥锚点存在 app/src/main/assets/ota-public.pem', false, ANCHOR);
} else if (!fs.existsSync(VERIFIER)) {
  check('设备端校验器存在 assets/node/kernel-verify.js', false, VERIFIER);
} else {
  let rc = 0; let out = '';
  try {
    out = execFileSync('node', [VERIFIER, '--zip', BASELINE, '--pubkey', ANCHOR],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (e) {
    rc = e.status;
    out = (e.stdout || '') + (e.stderr || '');
  }
  const line = out.split('\n').find((l) => l.startsWith('DSH_VERIFY_RESULT '));
  let json = {};
  try { json = line ? JSON.parse(line.slice('DSH_VERIFY_RESULT '.length)) : {}; } catch (_e) { /* 保持空对象 */ }

  check('基线包通过公钥锚点验签（否则装机必失败）', rc === 0 && json.ok === true,
    json.reason || ('rc=' + rc));
  check('基线包入口存在（entryOk）', json.entryOk === true,
    json.entryOk === undefined ? '校验器未返回 entryOk' : String(json.entryOk));
  if (json.version) console.log('基线包版本: ' + json.version);
}

finish();
