'use strict';

// ============================================================================
//  A'' 内核自举链测试
// ============================================================================
//  覆盖本轮从底层修复的四条链，每条都针对一个**已被实证的真缺陷**：
//
//   ① zip.js 只支持 Stored 条目
//      —— 任何标准工具打的包（Deflate）都解不开，OTA 实际上只能吃自造包。
//
//   ② OtaEngine 从未接进生产代码
//      —— 写好了、测过了，但 NodeRuntimeService 零引用。
//        即"设备端从未真正走过 OTA 路径"。本文件把它按**真实调用顺序**串起来跑。
//
//   ③ assets/kernel/baseline.zip 从来不存在
//      —— ensureBaseline() 静默返回 null，真机表现为"没有内核包"。
//
//   ④ 设备端校验器与落盘器可能对同一包理解不同（"验的是 A、装的是 B"）
//      —— 用**同一份 zip 字节**分别走 Node 校验器与 Java 解包路径，比对结果。
//
//  测试策略说明：这里刻意**不 mock zip 层**。因为①②两条缺陷恰恰是
//  「mock 掉了真实 IO 才没被发现」—— 所有测试都用自造的小数据，
//  于是"Deflate 解不开"和"184MB 撑爆 APK"都逃过了一轮。
//  现在改为：造**真实字节**、走**真实解压**、比对**真实文件数**。

const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');
const { execFileSync } = require('child_process');

const makeRunner = require('./harness');
const { check, finish } = makeRunner('kernel-selfboot');

const ROOT = path.resolve(__dirname, '..', '..');
const {
  extractZip, createZip, listZip, readEntry, crc32,
  METHOD_STORED, METHOD_DEFLATE,
} = require('../src/zip');
const { packBundle } = require('../src/kernel-bundle');
const { OtaEngine } = require('../src/ota-engine');
const { verifyManifest } = require('../src/verify');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'selfboot-'));
const kp = crypto.generateKeyPairSync('ed25519', {
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  publicKeyEncoding: { type: 'spki', format: 'pem' },
});

function makeSrc(tag, fileCount) {
  const src = fs.mkdtempSync(path.join(tmp, 'src-' + tag + '-'));
  fs.mkdirSync(path.join(src, 'bin'), { recursive: true });
  fs.writeFileSync(path.join(src, 'bin', 'dsh-supervisor'), '#!/usr/bin/env node\nconsole.log("dsh");\n');
  fs.mkdirSync(path.join(src, 'manager', 'dist'), { recursive: true });
  // 造可压缩内容（文本），用来验证 Deflate 路径
  for (let i = 0; i < (fileCount || 5); i += 1) {
    fs.writeFileSync(path.join(src, 'manager', 'dist', 'f' + i + '.js'), 'x'.repeat(2000) + i);
  }
  return src;
}

// ============================================================================
//  ① zip.js：压缩条目支持
// ============================================================================
console.log('--- ① zip.js 压缩条目 ---');

// 用 Python 造一个真·Deflate 包（模拟外部工具产出）
const pySrc = makeSrc('py', 3);
const pyZip = path.join(tmp, 'external-deflated.zip');
execFileSync('python3', ['-c', `
import zipfile, os
src = ${JSON.stringify(pySrc)}
with zipfile.ZipFile(${JSON.stringify(pyZip)}, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, src).replace(os.sep, '/')
            z.write(full, rel)
`]);

const pyBuf = fs.readFileSync(pyZip);
const pyEntries = listZip(pyBuf);
check('外部 Deflate 包可解析中央目录', pyEntries.length > 0, pyEntries.length + ' 条目');
check('外部包确实用了 Deflate（method=8）', pyEntries.some((e) => e.method === METHOD_DEFLATE),
  'methods=' + [...new Set(pyEntries.map((e) => e.method))].join(','));

const pyDest = path.join(tmp, 'out-py');
rmrf(pyDest);
let pyOk = true; let pyErr = '';
try { extractZip(pyBuf, pyDest); } catch (e) { pyOk = false; pyErr = e.message; }
check('外部 Deflate 包可解压（历史缺陷点）', pyOk, pyErr);
if (pyOk) {
  const top = fs.readdirSync(path.join(pyDest, 'bin'));
  check('外部 Deflate 包解出内容正确', fs.readFileSync(path.join(pyDest, 'bin', 'dsh-supervisor'), 'utf8').includes('dsh'));
}

// createZip 的压缩往返
const big = Buffer.from('y'.repeat(20000));
const zc = createZip(new Map([['big.txt', big]]), { compress: true });
const zcEntries = listZip(zc);
check('createZip(compress) 对小内容仍用 Stored', listZip(createZip(new Map([['s.txt', Buffer.from('abc')]]), { compress: true }))[0].method === METHOD_STORED);
check('createZip(compress) 对大内容改用 Deflate', zcEntries[0].method === METHOD_DEFLATE);
check('压缩确实减小体积', zc.length < big.length / 2, zc.length + ' < ' + big.length);
const zcDest = path.join(tmp, 'out-zc');
rmrf(zcDest);
extractZip(zc, zcDest);
check('压缩往返内容一致', fs.readFileSync(path.join(zcDest, 'big.txt')).equals(big));

// 目录穿越防护（内核包来自本地 feed，是不可信输入）
const evil = createZip(new Map([['../escape.txt', Buffer.from('pwn')]]));
let blocked = false;
try { extractZip(evil, path.join(tmp, 'out-evil')); } catch (_e) { blocked = true; }
check('解包拒绝目录穿越条目', blocked);

// 不支持的压缩方式要显式报错，不静默降级
const bz2 = (() => {
  // 手工构造一个 method=12 (BZIP2) 的中央目录条目
  const name = Buffer.from('a.txt');
  const data = Buffer.from('hi');
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(12, 8);
  local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22);
  local.writeUInt16LE(name.length, 26);
  const cen = Buffer.alloc(46);
  cen.writeUInt32LE(0x02014b50, 0); cen.writeUInt16LE(12, 10);
  cen.writeUInt32LE(data.length, 20); cen.writeUInt32LE(data.length, 24);
  cen.writeUInt16LE(name.length, 28); cen.writeUInt32LE(0, 42);
  const cd = Buffer.concat([cen, name]);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(1, 8); eocd.writeUInt16LE(1, 10);
  eocd.writeUInt32LE(cd.length, 12);
  eocd.writeUInt32LE(local.length + name.length + data.length, 16);
  return Buffer.concat([local, name, data, cd, eocd]);
})();
let methodRejected = false; let methodMsg = '';
try { extractZip(bz2, path.join(tmp, 'out-bz2')); } catch (e) { methodRejected = true; methodMsg = e.message; }
check('不支持的压缩方式显式报错（不静默降级）', methodRejected && /不支持的压缩方式/.test(methodMsg), methodMsg.slice(0, 60));

// listZip / readEntry
check('listZip 不落盘即可列出条目', listZip(zc).length === 1);
check('readEntry 按需取单个条目', readEntry(zc, 'big.txt').equals(big));
check('readEntry 找不到返回 null', readEntry(zc, 'nope.txt') === null);

// ============================================================================
//  ② 完整链：packBundle → OtaEngine.verifyPackage → apply
//     （复现 NodeRuntimeService 里真实发生的调用顺序）
// ============================================================================
console.log('\n--- ② 内核包 → 校验 → 落盘 全链 ---');

const good = packBundle({
  srcDir: makeSrc('good', 5), version: '4.2.0',
  privateKeyPem: kp.privateKey, url: 'https://x/kernel-4.2.0.zip',
});
check('packBundle 产出 zipBuf', good.zipBuf.length > 0);
check('packBundle 报告文件数（排除表生效的可观测点）', typeof good.fileCount === 'number' && good.fileCount > 0, good.fileCount + ' 文件');
check('kernel.json 带签名', !!good.kernelJson.signature);
check('manifest.sha256 与 zip 字节一致',
  good.manifest.sha256 === crypto.createHash('sha256').update(good.zipBuf).digest('hex'));

// OtaEngine 端到端（本地无网络：直接喂 Buffer）
const baseDir = path.join(tmp, 'ota-home');
fs.mkdirSync(baseDir, { recursive: true });
const eng = new OtaEngine({
  baseDir,
  httpGet: async () => { throw new Error('本测试不走网络'); },
  publicKeyPem: kp.publicKey,
  capabilities: [],
  runtime: { node: 'v24.21.0' },
});

const vr = eng.verifyPackage(good.zipBuf, good.manifest);
check('OtaEngine 校验通过（sha256 + 验签 + engines）', vr.ok === true, vr.reason || '');
check('校验返回包内 kernelJson', vr.ok && vr.kernelJson.version === '4.2.0');

const dest = eng.apply('4.2.0', good.zipBuf);
check('apply 落盘到 kernel/<version>', fs.existsSync(path.join(dest, 'kernel.json')));
check('apply 后 CURRENT 指针已切换', eng.currentVersion() === '4.2.0');
check('落盘目录含内核入口', fs.existsSync(path.join(dest, 'bin', 'dsh-supervisor')));

// 坏包绝不切指针
const before = eng.currentVersion();
const bad1 = Buffer.from(good.zipBuf); bad1[10] ^= 0xff;
check('篡改字节 → sha256 不匹配', eng.verifyPackage(bad1, good.manifest).reason === 'sha256-mismatch');
const otherKp = crypto.generateKeyPairSync('ed25519', {
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  publicKeyEncoding: { type: 'spki', format: 'pem' },
});
const badSig = packBundle({ srcDir: makeSrc('badsig'), version: '4.2.1', privateKeyPem: otherKp.privateKey, url: '' });
check('他人私钥签的包 → signature-invalid',
  eng.verifyPackage(badSig.zipBuf, badSig.manifest).reason === 'signature-invalid');
check('坏包未导致指针变化', eng.currentVersion() === before);

// 能力不足的包被拒
const needCap = packBundle({
  srcDir: makeSrc('cap'), version: '4.3.0',
  privateKeyPem: kp.privateKey, requires: ['bridge:shield'], url: '',
});
const capRes = eng.verifyPackage(needCap.zipBuf, needCap.manifest);
check('requires 超出设备能力 → capability-missing',
  capRes.reason === 'capability-missing' && capRes.missing.includes('bridge:shield'), capRes.reason);

// 回滚：先装第二个版本，再回滚，应回到 4.2.0
//
// 这个测试的写法本身修正了一个认知错误：最初我直接调 rollback() 期望它返回
// 上一版本，但**此时只有一个版本被装过**，rollback 正确返回 null ——
// 是我把"回滚可用"错当成了"首次安装后就有备份"。
// 保留这段说明，因为「至少装过两个版本才能回滚」是运维时必须知道的前提。
const second = packBundle({
  srcDir: makeSrc('second'), version: '4.2.2',
  privateKeyPem: kp.privateKey, url: '',
});
check('第二个版本校验通过', eng.verifyPackage(second.zipBuf, second.manifest).ok === true);
eng.apply('4.2.2', second.zipBuf);
check('已切到 4.2.2', eng.currentVersion() === '4.2.2');
check('installedVersions 含两个版本', eng.installedVersions().length === 2,
  JSON.stringify(eng.installedVersions()));
const rb = eng.rollback();
check('rollback 回到 4.2.0', rb === '4.2.0' && eng.currentVersion() === '4.2.0', String(rb));

// ============================================================================
//  ③ 设备端校验器（assets/node/kernel-verify.js）与之行为一致
// ============================================================================
console.log('\n--- ③ 设备端校验器一致性 ---');

const VERIFIER = path.join(ROOT, 'app', 'src', 'main', 'assets', 'node', 'kernel-verify.js');
check('设备端校验器存在', fs.existsSync(VERIFIER));

const pubPath = path.join(tmp, 'pub.pem');
fs.writeFileSync(pubPath, kp.publicKey);
const zipPath = path.join(tmp, 'good.zip');
fs.writeFileSync(zipPath, good.zipBuf);

function runVerifier(args) {
  try {
    const out = execFileSync('node', [VERIFIER, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    return { rc: 0, out };
  } catch (e) {
    return { rc: e.status, out: (e.stdout || '') + (e.stderr || '') };
  }
}

const vGood = runVerifier(['--zip', zipPath, '--pubkey', pubPath]);
const vGoodLine = vGood.out.split('\n').find((l) => l.startsWith('DSH_VERIFY_RESULT '));
const vGoodJson = vGoodLine ? JSON.parse(vGoodLine.slice('DSH_VERIFY_RESULT '.length)) : {};
check('校验器对合法包返回 ok:true', vGoodJson.ok === true, JSON.stringify(vGoodJson).slice(0, 120));
check('校验器返回 version', vGoodJson.version === '4.2.0');
check('校验器返回 entryOk', vGoodJson.entryOk === true);
check('校验器退出码 0', vGood.rc === 0);

// sha256 锚点
const vSha = runVerifier(['--zip', zipPath, '--pubkey', pubPath, '--sha256', good.manifest.sha256]);
check('校验器接受正确 sha256 锚点', vSha.rc === 0);
const vBadSha = runVerifier(['--zip', zipPath, '--pubkey', pubPath, '--sha256', '00'.repeat(32)]);
const vBadShaJson = JSON.parse(vBadSha.out.split('\n').find((l) => l.startsWith('DSH_VERIFY_RESULT ')).slice('DSH_VERIFY_RESULT '.length));
check('校验器拒绝错误 sha256', vBadSha.rc !== 0 && vBadShaJson.reason === 'sha256-mismatch', vBadShaJson.reason);

// 验签
const wrongPub = path.join(tmp, 'wrong.pem');
fs.writeFileSync(wrongPub, crypto.generateKeyPairSync('ed25519', {
  publicKeyEncoding: { type: 'spki', format: 'pem' },
}).publicKey);
const vWrong = runVerifier(['--zip', zipPath, '--pubkey', wrongPub]);
const vWrongJson = JSON.parse(vWrong.out.split('\n').find((l) => l.startsWith('DSH_VERIFY_RESULT ')).slice('DSH_VERIFY_RESULT '.length));
check('校验器拒绝不配对公钥', vWrong.rc !== 0 && vWrongJson.reason === 'signature-invalid', vWrongJson.reason);

// ---- 关键：篡改 kernel.json（提升 requires）后重打包，签名应失效 ----
const tamperMap = new Map();
for (const e of listZip(good.zipBuf)) {
  if (e.name.endsWith('/')) continue;
  tamperMap.set(e.name, readEntry(good.zipBuf, e.name));
}
const kjName = 'kernel/4.2.0/kernel.json';
const kjTampered = JSON.parse(tamperMap.get(kjName).toString('utf8'));
kjTampered.requires = ['bridge:shell', 'bridge:device_policy'];   // 提权
tamperMap.set(kjName, Buffer.from(JSON.stringify(kjTampered, null, 2)));
const tamperPath = path.join(tmp, 'tampered.zip');
fs.writeFileSync(tamperPath, createZip(tamperMap));
const vTamper = runVerifier(['--zip', tamperPath, '--pubkey', pubPath]);
const vTamperJson = JSON.parse(vTamper.out.split('\n').find((l) => l.startsWith('DSH_VERIFY_RESULT ')).slice('DSH_VERIFY_RESULT '.length));
check('篡改 requires 后验签失败（提权被挡）',
  vTamper.rc !== 0 && vTamperJson.reason === 'signature-invalid', vTamperJson.reason);

// ---- 校验器必须支持 Deflate（否则会把好包判成坏包，比漏判更糟）----
const deflatedPath = path.join(tmp, 'deflated-kernel.zip');
execFileSync('python3', ['-c', `
import zipfile
with zipfile.ZipFile(${JSON.stringify(zipPath)}) as zin:
    with zipfile.ZipFile(${JSON.stringify(deflatedPath)}, 'w', zipfile.ZIP_DEFLATED) as z:
        for i in zin.infolist():
            z.writestr(i.filename, zin.read(i.filename))
`]);
const vDef = runVerifier(['--zip', deflatedPath, '--pubkey', pubPath]);
check('校验器支持 Deflate 包（不误判好包）', vDef.rc === 0,
  vDef.out.split('DSH_VERIFY_RESULT ').pop().slice(0, 100));

// ---- 校验器不接受路径穿越 ----
const evilKernel = createZip(new Map([
  ['kernel/9.9.9/kernel.json', Buffer.from('{}')],
  ['kernel/9.9.9/../../../etc/pwn', Buffer.from('x')],
]));
const evilPath = path.join(tmp, 'evil-kernel.zip');
fs.writeFileSync(evilPath, evilKernel);
const vEvil = runVerifier(['--zip', evilPath, '--pubkey', pubPath]);
let evilReason = '';
try {
  evilReason = JSON.parse(vEvil.out.split('\n').find((l) => l.startsWith('DSH_VERIFY_RESULT ')).slice('DSH_VERIFY_RESULT '.length)).reason;
} catch (_e) { evilReason = '(no result line)'; }
check('校验器拒绝路径穿越包', vEvil.rc !== 0 && evilReason === 'zip-invalid', evilReason);

// ============================================================================
//  ④ 跨实现一致性：同一包，Node 校验器 与 Java 解包 结果必须一致
// ============================================================================
console.log('\n--- ④ 跨实现一致性（Node 解包 vs Java 解包）---');

// 用纯 JS 解（zip.js）与 Java ZipInputStream 解，比对文件清单
const jsDest = path.join(tmp, 'out-js');
rmrf(jsDest);
extractZip(good.zipBuf, jsDest);
function walkRel(root) {
  const out = [];
  (function w(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const full = path.join(d, e.name);
      if (e.isDirectory()) w(full);
      else out.push(path.relative(root, full).split(path.sep).join('/'));
    }
  })(root);
  return out.sort();
}
const jsFiles = walkRel(jsDest);

// 用设备的 Java 解包器（沙箱有 JDK 17，等价于 Android 的 ZipInputStream）
const JAVA_SRC = path.join(tmp, 'ZipX.java');
fs.writeFileSync(JAVA_SRC, `
import java.util.zip.*; import java.io.*;
public class ZipX {
  public static void main(String[] a) throws Exception {
    new File(a[1]).mkdirs();
    try (ZipInputStream zis = new ZipInputStream(new FileInputStream(a[0]))) {
      ZipEntry e;
      while ((e = zis.getNextEntry()) != null) {
        File out = new File(a[1], e.getName());
        if (e.isDirectory()) out.mkdirs();
        else { out.getParentFile().mkdirs();
          try (OutputStream os = new FileOutputStream(out)) { zis.transferTo(os); } }
        zis.closeEntry();
      }
    }
  }
}`);
let javaFiles = null;
try {
  execFileSync('javac', [JAVA_SRC], { cwd: tmp, stdio: 'pipe' });
  const jDest = path.join(tmp, 'out-java');
  rmrf(jDest);
  execFileSync('java', ['-cp', tmp, 'ZipX', zipPath, jDest], { stdio: 'pipe' });
  javaFiles = walkRel(jDest);
} catch (e) {
  javaFiles = null;
}

if (javaFiles === null) {
  check('Java 解包器可用（需 JDK）', false, '跳过一致性比对');
} else {
  check('Java 与 JS 解出的文件清单完全一致', JSON.stringify(jsFiles) === JSON.stringify(javaFiles),
    'js=' + jsFiles.length + ' java=' + javaFiles.length);
  // 逐字节比对首尾文件 —— 清单一致还不够，内容也要一致
  let sameBytes = true;
  for (const f of jsFiles) {
    if (!fs.readFileSync(path.join(jsDest, f)).equals(fs.readFileSync(path.join(tmp, 'out-java', f)))) { sameBytes = false; break; }
  }
  check('Java 与 JS 解出的文件内容逐字节一致', sameBytes);
}

// ============================================================================
//  ⑤ 打包排除表：防住「node_modules 混进包 → APK 撑爆」
// ============================================================================
console.log('\n--- ⑤ 打包排除表 ---');

const { isExcluded, EXCLUDED_SEGMENTS } = require('../src/kernel-bundle');
check('排除 node_modules', isExcluded('ui/node_modules/x/index.js'));
check('排除 .git', isExcluded('.git/config'));
check('排除顶层 test/', isExcluded('test/foo-test.js'));
check('排除 .md 文档', isExcluded('docs/README.md') && isExcluded('README.md'));
check('保留 ui/dist（构建产物但是运行期输入）', !isExcluded('ui/dist/index.html'));
check('保留 src/', !isExcluded('src/core.js'));
check('保留 bin/', !isExcluded('bin/dsh-supervisor'));
check('不误伤深层同名目录（src/test/ 不是顶层 test）', !isExcluded('src/test/helper.js'));

// 端到端：把 node_modules 塞进源目录，验证不进包
const fatSrc = makeSrc('fat', 2);
fs.mkdirSync(path.join(fatSrc, 'ui', 'node_modules', 'huge'), { recursive: true });
fs.writeFileSync(path.join(fatSrc, 'ui', 'node_modules', 'huge', 'blob.js'), Buffer.alloc(3 * 1024 * 1024, 7));
const fatPack = packBundle({ srcDir: fatSrc, version: '9.0.0', privateKeyPem: kp.privateKey, url: '' });
check('node_modules 体积未被计入包', fatPack.zipBuf.length < 1024 * 1024,
  fatPack.zipBuf.length + ' 字节（若含 3MB blob 必然超）');
const fatEntries = listZip(fatPack.zipBuf).map((e) => e.name);
check('包内确实没有 node_modules 条目', !fatEntries.some((n) => n.includes('node_modules')));

// 体积硬上限
const hugeSrc = makeSrc('huge', 2);
fs.mkdirSync(path.join(hugeSrc, 'assets'), { recursive: true });
// 用不可压缩数据（随机）确保不会被 deflate 压下去
fs.writeFileSync(path.join(hugeSrc, 'assets', 'random.bin'), crypto.randomBytes(9 * 1024 * 1024));
let limitTripped = false; let limitMsg = '';
try { packBundle({ srcDir: hugeSrc, version: '9.1.0', privateKeyPem: kp.privateKey, url: '' }); }
catch (e) { limitTripped = true; limitMsg = e.message; }
check('超过体积硬上限时构建期报错', limitTripped && /超过硬上限/.test(limitMsg), limitMsg.slice(0, 70));

// 排除表覆盖检查
check('排除表含 node_modules（防止被误删）', EXCLUDED_SEGMENTS.has('node_modules'));

// ============================================================================
//  ⑥ 基线资产与桥契约
// ============================================================================
console.log('\n--- ⑥ 基线资产与桥契约 ---');

const BASELINE = path.join(ROOT, 'app', 'src', 'main', 'assets', 'kernel', 'baseline.zip');
const hasBaseline = fs.existsSync(BASELINE);
// CI 上基线包由 fast-apk.yml 实时生成，此时**必须**存在 —— 用环境变量把
// "这次运行要求它存在"显式化，避免"源码树没有 → 永远 SKIP → 永远没人发现它坏了"。
const REQUIRE_BASELINE = process.env.DSH_REQUIRE_BASELINE === '1';

if (!hasBaseline && REQUIRE_BASELINE) {
  check('基线包存在（DSH_REQUIRE_BASELINE=1 时强制）', false,
    '未找到 ' + BASELINE + ' —— CI 的基线生成步骤应已产出它');
} else if (!hasBaseline) {
  // 基线包是**构建产物**（.gitignore 里排除），源码树里本来就可能没有。
  // 所以这里不能直接判 FAIL —— 那会让"刚 clone 下来"的仓库永远红。
  //
  // 正确做法是：把它当**条件断言**，但必须显式说明"未验证什么"。
  // 这样既不会假绿（读者知道这项没验），也不会假红（阻塞无关开发）。
  console.log('SKIP 基线包未生成（构建产物，见 .gitignore）');
  console.log('     —— 未验证：基线包体积/内容/验签。');
  console.log('     —— 生成: ./scripts/build-kernel-baseline.sh ../dsh-android-kernel');
  console.log('     —— 设 DSH_REQUIRE_BASELINE=1 可强制要求存在（CI 用）。');
} else {
  const bBuf = fs.readFileSync(BASELINE);
  check('基线包体积在合理范围（<8MB）', bBuf.length < 8 * 1024 * 1024,
    (bBuf.length / 1048576).toFixed(2) + ' MB');
  const bList = listZip(bBuf);
  check('基线包含 kernel.json', bList.some((e) => e.name.endsWith('kernel.json')));
  check('基线包含入口 bin/dsh-supervisor', bList.some((e) => e.name.endsWith('bin/dsh-supervisor')));
  // 基线包必须能通过公钥锚点验签（否则装机后必失败）
  const ANCHOR = path.join(ROOT, 'app', 'src', 'main', 'assets', 'ota-public.pem');
  if (fs.existsSync(ANCHOR)) {
    const bv = runVerifier(['--zip', BASELINE, '--pubkey', ANCHOR]);
    let bvJson = {};
    try { bvJson = JSON.parse(bv.out.split('\n').find((l) => l.startsWith('DSH_VERIFY_RESULT ')).slice('DSH_VERIFY_RESULT '.length)); } catch (_e) {}
    check('基线包通过公钥锚点验签（否则装机必失败）', bv.rc === 0 && bvJson.ok === true,
      bvJson.reason || ('rc=' + bv.rc));
  } else {
    check('公钥锚点存在', false, ANCHOR);
  }
}

// 桥方法表
const methods = require('../src/bridge/methods');
check('build.kernelInstall 已注册', !!methods.METHODS['build.kernelInstall']);
check('build.kernelInstall 需要 kernel_update 能力',
  methods.methodCaps('build.kernelInstall').includes('kernel_update'));
check('build 组代表能力已改为 kernel_update',
  methods.groupCaps('build').includes('kernel_update'));
check('build 组不再依赖永不具备的 build_chain',
  !methods.groupCaps('build').includes('build_chain'));
check('kernel_update 在 DEVICE_CAPS 里', methods.DEVICE_CAPS.includes('kernel_update'));
check('build.apk 仍可解析（老调用方拿到带解释的错误而非 -32601）',
  methods.methodCaps('build.apk') !== null);

// 校验器契约：Kotlin 侧约定的前缀必须与 JS 一致
const KOTLIN_VERIFIER = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'example', 'nodecontainer', 'NodeKernelVerifier.kt');
if (fs.existsSync(KOTLIN_VERIFIER)) {
  const kt = fs.readFileSync(KOTLIN_VERIFIER, 'utf8');
  check('Kotlin 侧结果前缀与 JS 一致', kt.includes('DSH_VERIFY_RESULT '));
  check('Kotlin 侧引用 kernel-verify.js 资产',
    kt.includes('kernel-verify.js') || fs.readFileSync(
      path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'example', 'nodecontainer', 'NodeProvisioner.kt'), 'utf8'
    ).includes('kernel-verify.js'));
}

// feed 目录约定（LocalKernelFeed）必须与文档/脚本一致
const FEED_KT = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'example', 'nodecontainer', 'LocalKernelFeed.kt');
if (fs.existsSync(FEED_KT)) {
  const fk = fs.readFileSync(FEED_KT, 'utf8');
  check('feed 目录名约定为 kernel-feed', fk.includes('"kernel-feed"'));
  check('feed 包名前缀约定为 kernel-', fk.includes('"kernel-"'));
  check('feed manifest 名约定为 kernel-manifest.json', fk.includes('kernel-manifest.json'));
}

// KernelInstaller 必须复用 Node 校验器（不能自己验签）
const INSTALLER_KT = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'example', 'nodecontainer', 'KernelInstaller.kt');
if (fs.existsSync(INSTALLER_KT)) {
  const ik = fs.readFileSync(INSTALLER_KT, 'utf8');
  check('KernelInstaller 调用 NodeKernelVerifier', ik.includes('NodeKernelVerifier.verify'));
  check('KernelInstaller 未自行做密码学（不该出现 Signature/Ed25519）',
    !/Signature\.getInstance|Ed25519/.test(ik.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')));
}

function rmrf(p) { fs.rmSync(p, { recursive: true, force: true }); }

try { fs.rmSync(tmp, { recursive: true, force: true }); } catch (_e) {}
finish();
