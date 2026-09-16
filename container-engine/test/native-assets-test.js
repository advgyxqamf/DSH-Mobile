'use strict';

// 原生资产清单一致性检查（纯静态，不依赖 Android / 不依赖构建）。
//
// ============================================================================
//  为什么要有这个测试
// ============================================================================
// 「哪些 .so 随包、谁要能 exec、谁依赖谁」这件事，在仓库里以 **5 种形态**存在：
//
//   ① 权威源  NativeAssetRegistry.kt             （Kotlin，运行期校验用）
//   ② 打包豁免 app/build.gradle.kts keepDebugSymbols
//   ③ CI 清单  .github/native-assets.txt          （下载校验 + APK 审计）
//   ④ 系统白名单 scripts/native-deps.txt          （构建期 NEEDED 闭环自检）
//   ⑤ 注入脚本 scripts/inject-libcxx-into-apk.py   （anchor 名字）
//
// 历史上这 5 处是**各自硬编码**的，加第三个二进制要同时改 8 个位置，漏一处
// 就在真机上炸 —— 而且往往不是立刻炸，而是「命中缓存后才炸」这种间歇形态。
//
// 这个测试把 ① 当唯一事实来源，逐项核对 ②③④⑤。**双向**检查：
//   正向 —— 注册表里的每一项，下游是否都有
//   反向 —— 下游里的每一项，注册表是否都声明了（防漏声明）
//
// 它不依赖 Android 运行时，所以能在 CI 的几秒内跑完；而它能拦住的
// 恰恰是「必须跑 3 小时 CI + 真机验证才能发现」的那类问题。
// ============================================================================

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const makeRunner = require('./harness');

const { check, finish } = makeRunner('native-assets');

const ROOT = path.resolve(__dirname, '..', '..');

// ---------------------------------------------------------------------------
// 读取权威源：NativeAssetRegistry.kt
// ---------------------------------------------------------------------------

const REGISTRY_KT = path.join(
  ROOT, 'app/src/main/java/com/example/nodecontainer/native/NativeAssetRegistry.kt'
);

/** 去掉注释（行注释 + 块注释），避免注释里的示例被当成真声明。 */
function stripKotlinComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\n]*/g, '');
}

/**
 * 从注册表源码里解析出资产声明。
 *
 * 不引 Kotlin 解析器：这里的形态很固定（`val NAME = NativeExecutable( ... )`），
 * 用「按字段名抽字符串/列表字面量」的方式足够稳，且改坏了会被断言抓到。
 */
function parseRegistry(src) {
  const body = stripKotlinComments(src);
  const assets = [];

  // 匹配 val XXX = NativeExecutable( ... )，括号配对取到对应的 ')'
  const re = /val\s+(\w+)\s*=\s*NativeExecutable\s*\(/g;
  let m;
  while ((m = re.exec(body)) !== null) {
    const name = m[1];
    let i = re.lastIndex - 1; // 指向 '('
    let depth = 0;
    let end = -1;
    for (; i < body.length; i++) {
      if (body[i] === '(') depth++;
      else if (body[i] === ')') { depth--; if (depth === 0) { end = i; break; } }
    }
    if (end < 0) continue;
    const argSrc = body.slice(re.lastIndex, end);

    const strField = (f) => {
      const mm = new RegExp(`\\b${f}\\s*=\\s*"([^"]*)"`).exec(argSrc);
      return mm ? mm[1] : null;
    };
    const listField = (f) => {
      const mm = new RegExp(`\\b${f}\\s*=\\s*(?:listOf|emptyList)\\s*\\(([^)]*)\\)`).exec(argSrc);
      if (!mm) return [];
      return mm[1]
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean)
        .map((s) => s.replace(/^"|"$/g, ''));
    };
    const boolField = (f) => {
      const mm = new RegExp(`\\b${f}\\s*=\\s*(true|false)`).exec(argSrc);
      return mm ? mm[1] === 'true' : null;
    };

    assets.push({
      varName: name,
      id: strField('id'),
      libName: strField('libName'),
      humanName: strField('humanName'),
      probeArgs: listField('probeArgs'),
      requiredDeps: listField('requiredDeps'),
      required: boolField('required'),
    });
  }
  return assets;
}

if (!fs.existsSync(REGISTRY_KT)) {
  check('注册表文件存在', false, REGISTRY_KT + ' 不存在');
  finish();
  return;
}
const registrySrc = fs.readFileSync(REGISTRY_KT, 'utf8');
const ASSETS = parseRegistry(registrySrc);

// 解析本身必须可信，否则后面的断言全是假绿
check('注册表解析出资产（>=2 项）', ASSETS.length >= 2, `解析到 ${ASSETS.length} 项`);
check(
  '每个资产都有 id / libName / humanName / required',
  ASSETS.every((a) => a.id && a.libName && a.humanName && a.required !== null),
  JSON.stringify(ASSETS.map((a) => ({ id: a.id, libName: a.libName, required: a.required })))
);
check(
  'libName 全部符合 lib*.so 形态（否则 PackageManager 不会解压到 nativeLibraryDir）',
  ASSETS.every((a) => /^lib.*\.so$/.test(a.libName)),
  ASSETS.map((a) => a.libName).join(', ')
);
check(
  'id 唯一',
  new Set(ASSETS.map((a) => a.id)).size === ASSETS.length
);
check(
  'libName 唯一',
  new Set(ASSETS.map((a) => a.libName)).size === ASSETS.length
);

const REGISTRY_LIBNAMES = ASSETS.map((a) => a.libName);
const REGISTRY_DEPS = [...new Set(ASSETS.flatMap((a) => a.requiredDeps))];

// ---------------------------------------------------------------------------
// 读取通用清单读取器
// ---------------------------------------------------------------------------

/** 读 `# 注释 + 每行一项` 形态的清单。 */
function readList(file) {
  if (!fs.existsSync(file)) return null;
  return fs.readFileSync(file, 'utf8')
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#'));
}

// ---------------------------------------------------------------------------
// ① → ③  .github/native-assets.txt
// ---------------------------------------------------------------------------

const ASSETS_TXT = path.join(ROOT, '.github/native-assets.txt');
const manifest = readList(ASSETS_TXT);

check('.github/native-assets.txt 存在', manifest !== null, ASSETS_TXT);
if (manifest) {
  const missing = REGISTRY_LIBNAMES.filter((n) => !manifest.includes(n));
  check(
    '③ 清单 ⊇ 注册表（正向：注册表每项都在清单里）',
    missing.length === 0,
    missing.length ? `清单缺: ${missing.join(', ')}` : ''
  );

  const extra = manifest.filter((n) => !REGISTRY_LIBNAMES.includes(n));
  check(
    '③ 清单 ⊆ 注册表（反向：清单没有注册表未声明的项）',
    extra.length === 0,
    extra.length ? `清单多出: ${extra.join(', ')}（注册表未声明 → CI 下载会 404）` : ''
  );
}

// ---------------------------------------------------------------------------
// ① → ②  app/build.gradle.kts 的 keepDebugSymbols
//
//      gradle 侧已改为直接读 .github/native-assets.txt（nativeAssetNames），
//      所以这里核对的是「gradle 确实在读清单」而非硬编码 —— 防有人图省事改回去。
// ---------------------------------------------------------------------------

const GRADLE_KTS = path.join(ROOT, 'app/build.gradle.kts');
check('app/build.gradle.kts 存在', fs.existsSync(GRADLE_KTS));
if (fs.existsSync(GRADLE_KTS)) {
  const g = stripKotlinComments(fs.readFileSync(GRADLE_KTS, 'utf8'));
  check(
    '② keepDebugSymbols 由清单派生（不是硬编码文件名）',
    /keepDebugSymbols\s*\+=\s*nativeAssetNames\.map/.test(g),
    '期望出现 keepDebugSymbols += nativeAssetNames.map { "**/$it" }'
  );
  check(
    '② nativeAssetNames 读的是 .github/native-assets.txt',
    /native-assets\.txt/.test(g),
    'gradle 里应引用 .github/native-assets.txt'
  );
  check(
    '② 清单缺失时抛异常（不静默降级）',
    /GradleException/.test(g) && /native-assets\.txt/.test(g),
    '缺清单应直接 fail —— 否则会打出看似正常、实则缺符号的 APK'
  );
  // 反向：不应再出现硬编码的 lib*.so 字面量（注释已剥离）
  const hardcoded = [...g.matchAll(/"\*\*\/([^"]+\.so)"/g)].map((m) => m[1]);
  check(
    '② 无硬编码的 keepDebugSymbols 条目',
    hardcoded.length === 0,
    hardcoded.length ? `仍硬编码: ${hardcoded.join(', ')}` : ''
  );
}

// ---------------------------------------------------------------------------
// ① → ④  scripts/native-deps.txt（系统库白名单）
//
//      作用域不同但必须互相自洽：白名单描述「系统提供」，
//      注册表 requiredDeps 描述「我们随包」。二者**不可重叠** ——
//      同一库既说系统提供又说随包，说明有一边错了。
// ---------------------------------------------------------------------------

const DEPS_TXT = path.join(ROOT, 'scripts/native-deps.txt');
const sysDeps = readList(DEPS_TXT);

check('scripts/native-deps.txt 存在', sysDeps !== null, DEPS_TXT);
if (sysDeps) {
  check(
    '④ 白名单非空（清空会让构建期依赖自检失去意义）',
    sysDeps.length > 0
  );
  const overlap = REGISTRY_DEPS.filter((d) => sysDeps.includes(d));
  check(
    '④ 白名单与注册表 requiredDeps 无重叠',
    overlap.length === 0,
    overlap.length
      ? `重叠: ${overlap.join(', ')}（同一库既称系统提供又称随包，必有一边错）`
      : ''
  );
  const dup = sysDeps.filter((d, i) => sysDeps.indexOf(d) !== i);
  check('④ 白名单无重复项', dup.length === 0, dup.join(', '));
}

// ---------------------------------------------------------------------------
// ① → ⑤  scripts/build-node-android.sh / inject-libcxx-into-apk.py
// ---------------------------------------------------------------------------

const BUILD_SH = path.join(ROOT, 'scripts/build-node-android.sh');
if (fs.existsSync(BUILD_SH)) {
  const sh = fs.readFileSync(BUILD_SH, 'utf8');
  check(
    '⑤ 构建脚本读 native-deps.txt（不再 case 硬编码）',
    /native-deps\.txt/.test(sh),
    '期望脚本引用 scripts/native-deps.txt'
  );
  check(
    '⑤ 构建脚本做产物↔清单一致性自检',
    /native-assets\.txt/.test(sh),
    '期望脚本核对 OUT_DIR 与 .github/native-assets.txt'
  );
  check(
    '⑤ 构建脚本不再有硬编码的 case 系统库列表',
    !/libc\.so\|libm\.so\|libdl\.so/.test(sh),
    '旧的 case "libc.so|libm.so|..." 应已移除'
  );
}

const INJECT_PY = path.join(ROOT, 'scripts/inject-libcxx-into-apk.py');
if (fs.existsSync(INJECT_PY)) {
  const py = fs.readFileSync(INJECT_PY, 'utf8');
  // anchor 必须指向注册表里真实存在的资产（当前是 libnode.so）
  const anchorMatch = /anchor\s*=\s*\(?'lib\/%s\/([^']+)'/.exec(py);
  check('⑤ inject 脚本的 anchor 可解析', anchorMatch !== null);
  if (anchorMatch) {
    check(
      '⑤ inject 脚本 anchor 指向注册表内资产',
      REGISTRY_LIBNAMES.includes(anchorMatch[1]),
      `anchor=${anchorMatch[1]}，注册表=${REGISTRY_LIBNAMES.join(', ')}`
    );
  }
  const target = /new_name\s*=\s*\(?'lib\/%s\/([^']+)'/.exec(py);
  check('⑤ inject 脚本的注入目标可解析', target !== null);
  if (target) {
    check(
      '⑤ inject 脚本注入目标在注册表 requiredDeps 内（它是依赖而非本体）',
      REGISTRY_DEPS.includes(target[1]),
      `注入 ${target[1]}，requiredDeps=${REGISTRY_DEPS.join(', ')}`
    );
  }
}

// ---------------------------------------------------------------------------
// ① → Kotlin 侧：硬编码文件名应已清除
//
//   NativeAssetRegistry.kt 自身除外（它就是定义处）。
// ---------------------------------------------------------------------------

const KT_DIR = path.join(ROOT, 'app/src/main/java/com/example/nodecontainer');
const REGISTRY_BASENAME = 'NativeAssetRegistry.kt';

/** 递归收集 .kt 文件。 */
function walkKt(dir) {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walkKt(p));
    else if (e.name.endsWith('.kt')) out.push(p);
  }
  return out;
}

if (fs.existsSync(KT_DIR)) {
  const offenders = [];
  for (const f of walkKt(KT_DIR)) {
    if (path.basename(f) === REGISTRY_BASENAME) continue;
    const body = stripKotlinComments(fs.readFileSync(f, 'utf8'));
    // 在【字符串字面量】里出现资产文件名 = 硬编码
    for (const asset of ASSETS) {
      if (body.includes(`"${asset.libName}"`)) {
        offenders.push(`${path.relative(ROOT, f)} → "${asset.libName}"`);
      }
    }
  }
  check(
    'Kotlin 侧无硬编码资产文件名（改由注册表派生）',
    offenders.length === 0,
    offenders.length ? offenders.join('; ') : ''
  );
}

// ---------------------------------------------------------------------------
// ① → Node 侧 mock：server.js 的 sys.nativeAssets 必须与注册表同形
// ---------------------------------------------------------------------------

const SERVER_JS = path.join(ROOT, 'container-engine/src/bridge/server.js');
if (fs.existsSync(SERVER_JS)) {
  const js = fs.readFileSync(SERVER_JS, 'utf8');
  check(
    'Node mock 有 sys.nativeAssets 分支',
    /case\s+'sys\.nativeAssets'/.test(js)
  );
  const missingMock = ASSETS.filter((a) => !js.includes(`'${a.libName}'`));
  check(
    'Node mock 覆盖注册表全部资产',
    missingMock.length === 0,
    missingMock.length
      ? `mock 缺: ${missingMock.map((a) => a.libName).join(', ')}`
      : ''
  );
}

const METHODS_JS = path.join(ROOT, 'container-engine/src/bridge/methods.js');
if (fs.existsSync(METHODS_JS)) {
  const js = fs.readFileSync(METHODS_JS, 'utf8');
  check(
    'methods.js 注册了 sys.nativeAssets',
    /'sys\.nativeAssets'\s*:/.test(js)
  );
}

// ---------------------------------------------------------------------------

finish();
