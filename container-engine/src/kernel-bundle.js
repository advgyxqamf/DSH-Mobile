'use strict';

// 内核包契约（对齐 docs/BASE_SPEC.md §4）。
// 整个内核是一个目录：kernel/<version>/kernel.json（清单）+ manager/（控制面板代码）。
// 打包成 zip 后经 OTA 下发；设备端验签 + sha256 + 原子解包。

const fs = require('fs');
const path = require('path');
const { createZip } = require('./zip');
const { signManifest } = require('./sign');
const { sha256 } = require('./verify');

const DEFAULT_ENTRY = 'bin/dsh-supervisor';
const DEFAULT_ABI = 'node24-arm64-android35';
const DEFAULT_ENGINES = { node: '>=24 <25' };

/** 组装 kernel.json（不含签名）。 */
function buildKernelJson({ version, abi, engines, entry, requires, managedAgents }) {
  return {
    name: 'dsh-kernel',
    version,
    abi: abi || DEFAULT_ABI,
    engines: engines || DEFAULT_ENGINES,
    entry: entry || DEFAULT_ENTRY,
    requires: requires || [],
    managedAgents: managedAgents || [],
  };
}

/**
 * 打包时**永不**收录的路径片段。
 *
 * 为什么需要这个排除表 —— 一次真实事故：
 *   最初 `collectFiles` 无脑遍历整个内核源码目录，把 `ui/node_modules/`
 *   一起打了进去。结果基线包 **184 MB**（其中 191 MB 是 node_modules 的
 *   未压缩体积），APK 直接被撑爆。
 *
 * 更值得记的是"为什么测试没发现"：当时所有测试都用**自造的小目录**
 * （几个文件、几百字节），永远碰不到这个量级。也就是说，
 * 「测试全绿」和「产物可用」之间隔着一个**规模假设**。
 * 现在把它固化成构建期断言（见 packBundle 末尾的体积检查）。
 *
 * 排除理由分类：
 *   · 依赖缓存（node_modules / .pnpm-store）—— 设备端由内核自己按需装，
 *     且路径/平台可能不同，打进包反而是错的（含 darwin/win32 原生模块）；
 *   · 版本控制与 CI（.git / .github）—— 与运行无关，纯体积；
 *   · 构建中间产物（__pycache__ / .cache / .turbo / build）—— 可重建；
 *   · 测试与文档（test / docs / *.md）—— 运行期不需要，占体积；
 *   · 本仓库自身脚本（scripts）—— 那是容器仓的事，内核包不含。
 *
 * 注意保留 `ui/dist` —— 它是**构建产物**，但恰恰是运行期需要的
 * （内核控制面板的静态资源），所以不能一并排除 ui/。这个区分很关键：
 * 「产物」不都是垃圾，要看它是不是运行期输入。
 */
const EXCLUDED_SEGMENTS = new Set([
  'node_modules',
  '.git',
  '.github',
  '.pnpm-store',
  '__pycache__',
  '.cache',
  '.turbo',
  '.parcel-cache',
  'coverage',
]);

/** 目录级排除（匹配整个相对路径前缀）。 */
const EXCLUDED_DIRS = new Set([
  'test',
  'tests',
  '__tests__',
  'docs',
]);

/** 文件级排除（按扩展名）。 */
const EXCLUDED_EXT = ['.md', '.map', '.log', '.tsbuildinfo'];

function isExcluded(rel) {
  const segs = rel.split('/');
  if (segs.some((s) => EXCLUDED_SEGMENTS.has(s))) return true;
  // 顶层目录级排除：只匹配根下的一级目录，避免误伤
  // ui/src/test/ 这类"只是碰巧叫 test"的深层目录。
  if (segs.length > 1 && EXCLUDED_DIRS.has(segs[0])) return true;
  if (EXCLUDED_EXT.some((e) => rel.endsWith(e))) return true;
  return false;
}

function collectFiles(srcDir) {
  const out = new Map();
  let skipped = 0;
  (function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const full = path.join(d, e.name);
      const rel = path.relative(srcDir, full).split(path.sep).join('/');
      if (isExcluded(rel)) { skipped += 1; continue; }
      if (e.isDirectory()) walk(full);
      else out.set(rel, fs.readFileSync(full));
    }
  })(srcDir);
  if (skipped) {
    // 打出来而不是静默跳过：体积异常时，第一眼就能看出是不是排除表的问题。
    process.stderr.write('[kernel-bundle] 已跳过 ' + skipped + ' 个被排除的路径\n');
  }
  return out;
}

/**
 * 打包内核包。
 * @param {object} o
 *  - srcDir: 内核源码根（bin/ src/ ui/dist 等）
 *  - version, abi, engines, entry, requires, managedAgents
 *  - privateKeyPem: 签名私钥
 * @returns {{zipBuf: Buffer, kernelJson: object, manifest: object}}
 */
function packBundle(o) {
  const kernelJson = buildKernelJson(o);
  kernelJson.signature = signManifest(o.privateKeyPem, kernelJson);

  const files = collectFiles(o.srcDir);
  const prefix = `kernel/${kernelJson.version}/`;
  const zipFiles = new Map();
  for (const [rel, buf] of files) zipFiles.set(prefix + rel, buf);
  zipFiles.set(prefix + 'kernel.json', Buffer.from(JSON.stringify(kernelJson, null, 2)));

  // 压缩：内核包是要进 APK 的（基线包）或被 OTA 下发的，体积直接影响
  // APK 大小与下载耗时。文本类（js/json/html/css）压缩率通常 5-10 倍。
  // 这是安全的 —— 设备端 zip 读取（Java ZipInputStream 与 kernel-verify.js）
  // 都支持 Deflate 了；而历史上不支持的那段时间，正是这里保持 Stored 的原因。
  const zipBuf = createZip(zipFiles, { compress: true });

  // ---- 体积断言：把"规模假设"变成可执行的检查 ----
  //
  // 上限 8 MB 的依据：内核本体是纯 JS + 静态资源，实测在 1 MB 量级。
  // 一旦超限，最可能的原因**不是**内核变大，而是排除表漏了某个目录
  // （真实事故：node_modules 漏进包 → 184 MB → APK 撑爆）。
  // 所以在构建期就炸，而不是等 CI 传了半天的产物在真机上装不上。
  const HARD_LIMIT = 8 * 1024 * 1024;
  const WARN_LIMIT = 2 * 1024 * 1024;
  if (zipBuf.length > HARD_LIMIT) {
    throw new Error(
      '内核包体积 ' + (zipBuf.length / 1048576).toFixed(1) + ' MB 超过硬上限 ' +
      (HARD_LIMIT / 1048576) + ' MB。最可能的原因：EXCLUDED_SEGMENTS 漏了某个目录。' +
      '（本包收录 ' + files.size + ' 个文件，请检查内核源码目录里是否有 node_modules / 构建缓存）'
    );
  }
  if (zipBuf.length > WARN_LIMIT) {
    process.stderr.write(
      '[kernel-bundle] 警告：内核包 ' + (zipBuf.length / 1048576).toFixed(1) +
      ' MB 偏大（软阈值 ' + (WARN_LIMIT / 1048576) + ' MB，收录 ' + files.size + ' 个文件）\n'
    );
  }

  const manifest = {
    name: 'dsh-kernel',
    version: kernelJson.version,
    abi: kernelJson.abi,
    engines: kernelJson.engines,
    requires: kernelJson.requires,
    url: o.url || '',
    sha256: sha256(zipBuf),
    signature: kernelJson.signature,
  };
  return { zipBuf, kernelJson, manifest, fileCount: files.size };
}

module.exports = {
  DEFAULT_ENTRY, DEFAULT_ABI, DEFAULT_ENGINES,
  buildKernelJson, collectFiles, packBundle,
  isExcluded, EXCLUDED_SEGMENTS, EXCLUDED_DIRS, EXCLUDED_EXT,
};
