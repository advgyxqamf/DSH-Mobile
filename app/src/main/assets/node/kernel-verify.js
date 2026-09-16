#!/usr/bin/env node
'use strict';

// ============================================================================
//  内核包校验器（随 APK 冻结，运行于设备上的内置 Node）
// ============================================================================
//  为什么这个文件存在
//  ----------------
//  内核信任根是 ed25519。而 Android 官方 Signature 算法支持表列出
//  「Ed25519 33+」，本项目 minSdk=24 —— Kotlin 侧在 API 24~32 的设备上
//  连 Signature 实例都拿不到，**根本无法验签**。
//
//  Node 的 crypto.verify 走自带 OpenSSL，与 API level 无关。
//  所以验签下沉到这里：Kotlin 只负责「把活干完或干不成」，不做密码学。
//
//  为什么它是独立文件而不是内核的一部分
//  ------------------------------------
//  校验器若由被校验对象提供，则「一个签名无效的内核只要启动成功，就能
//  宣布自己有效」。这是自证循环，必须切断：校验器随 APK 冻结，与内核解耦。
//
//  契约（与 Kotlin 侧 NodeKernelVerifier 严格对齐）
//  -----------------------------------------------
//  入参：--zip <path> --pubkey <path> [--sha256 <hex>] [--version <v>]
//  出参：stdout 最后一行 `DSH_VERIFY_RESULT {"ok":bool,"version":..,"reason":..,
//        "detail":..,"entryOk":bool}`
//        退出码：0 = 校验通过；非 0 = 未通过（Kotlin 侧主要看结果行，退出码是双保险）
//
//  用「约定行」而非「整个 stdout 是 JSON」：脚本可以自由打调试信息，
//  不会因为多一行日志就让解析失败。
// ============================================================================

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');

const RESULT_PREFIX = 'DSH_VERIFY_RESULT ';

// ---- 诊断输出走 stderr，避免污染结果行 ----
function info(msg) { process.stderr.write('[verify] ' + msg + '\n'); }

function emit(obj) {
  process.stdout.write(RESULT_PREFIX + JSON.stringify(obj) + '\n');
  process.exit(obj.ok ? 0 : 1);
}

function fail(reason, detail, extra) {
  emit(Object.assign({ ok: false, reason, detail: detail || '' }, extra || {}));
}

// ---- 参数解析 ----
function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--zip') out.zip = argv[++i];
    else if (a === '--pubkey') out.pubkey = argv[++i];
    else if (a === '--sha256') out.sha256 = argv[++i];
    else if (a === '--version') out.version = argv[++i];
  }
  return out;
}

// ---- 最小 zip 读取（Stored + Deflate 双支持）----
//
// 为什么不 require('./zip')：设备上的 kernel-verify.js 是**独立投放**的，
// 它要能在「内核还没落地」时跑（首启验基线包）。依赖内核目录里的模块会
// 制造循环依赖。所以这里自带一份最小实现。
//
// 注意：这份实现**必须**支持 Deflate。历史教训 —— container-engine/src/zip.js
// 最初只认 Stored，导致任何标准工具打的包都解不开（CRC 必失败）。
// 校验器若也有同样缺陷，会把好包判成坏包，比漏判更糟（会阻断正常升级）。
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i += 1) crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buf[i]) & 0xFF];
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function readEocd(buf) {
  let eocdOff = -1;
  const from = Math.max(0, buf.length - 22 - 65535);
  for (let i = buf.length - 22; i >= from; i -= 1) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocdOff = i; break; }
  }
  if (eocdOff < 0) throw new Error('找不到 EOCD（不是有效 zip）');
  const count = buf.readUInt16LE(eocdOff + 10);
  const cdOffset = buf.readUInt32LE(eocdOff + 16);
  if (count === 0xFFFF || cdOffset === 0xFFFFFFFF) throw new Error('不支持 zip64');
  return { cdOffset, count };
}

function listZip(buf) {
  const { cdOffset, count } = readEocd(buf);
  const out = [];
  let p = cdOffset;
  for (let n = 0; n < count; n += 1) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('中央目录损坏');
    const method = buf.readUInt16LE(p + 10);
    const crc = buf.readUInt32LE(p + 16);
    const compSize = buf.readUInt32LE(p + 20);
    const usize = buf.readUInt32LE(p + 24);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localOffset = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    out.push({ name, method, crc, compSize, usize, localOffset });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

function readEntry(buf, entry) {
  const off = entry.localOffset;
  if (buf.readUInt32LE(off) !== 0x04034b50) throw new Error('局部头损坏: ' + entry.name);
  const lhNameLen = buf.readUInt16LE(off + 26);
  const lhExtraLen = buf.readUInt16LE(off + 28);
  const dataStart = off + 30 + lhNameLen + lhExtraLen;
  const raw = buf.subarray(dataStart, dataStart + entry.compSize);
  let data;
  if (entry.method === 0) data = Buffer.from(raw);
  else if (entry.method === 8) data = zlib.inflateRawSync(raw);
  else throw new Error('不支持的压缩方式 method=' + entry.method + ': ' + entry.name);
  if (crc32(data) !== entry.crc) throw new Error('CRC 校验失败: ' + entry.name);
  return data;
}

/** 校验 zip 自洽性：EOCD 可解、每个条目 CRC 正确、无路径穿越。 */
function checkZipIntegrity(buf) {
  const entries = listZip(buf);      // 抛错即 zip 结构坏
  for (const e of entries) {
    // 路径穿越：内核包可能来自用户放的本地文件，属不可信输入。
    const norm = path.posix.normalize(e.name);
    if (norm.startsWith('..') || path.posix.isAbsolute(norm)) {
      throw new Error('条目路径越界（疑似目录穿越）: ' + e.name);
    }
    if (e.name.endsWith('/')) continue;
    readEntry(buf, e);               // 顺带验 CRC
  }
  return entries;
}

// ---- 签名规范化：必须与 container-engine/src/sign.js 逐字节一致 ----
//
// 算法：剔除 signature 字段 → 按 key 排序 → JSON.stringify。
// 两仓必须用同一算法，否则「签发端算的摘要」与「设备端算的摘要」不同，
// 合法包会被判无效。这里刻意照抄 sign.js，不做任何"优化"。
function canonical(obj) {
  const { signature, ...rest } = obj;
  return JSON.stringify(rest, Object.keys(rest).sort());
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.zip) fail('bad-args', '缺少 --zip');
  if (!args.pubkey) fail('bad-args', '缺少 --pubkey');
  if (!fs.existsSync(args.zip)) fail('zip-missing', '文件不存在: ' + args.zip);
  if (!fs.existsSync(args.pubkey)) fail('pubkey-missing', '公钥不存在: ' + args.pubkey);

  const zipBuf = fs.readFileSync(args.zip);
  const actualSha = crypto.createHash('sha256').update(zipBuf).digest('hex');
  info('zip=' + args.zip + ' (' + zipBuf.length + ' 字节) sha256=' + actualSha);

  // ---- 1) sha256 锚点比对（若提供）----
  // 放在最前面：它最便宜，且能立刻挡住"包被截断/替换"这类问题，
  // 不必浪费一次结构解析。
  if (args.sha256) {
    if (actualSha !== args.sha256.toLowerCase()) {
      fail('sha256-mismatch',
        '期望 ' + args.sha256 + '，实际 ' + actualSha,
        { actualSha256: actualSha });
    }
    info('sha256 锚点匹配');
  } else {
    info('未提供 --sha256：跳过锚点比对（只做包内自校验）');
  }

  // ---- 2) zip 结构与完整性 ----
  let entries;
  try {
    entries = checkZipIntegrity(zipBuf);
  } catch (e) {
    fail('zip-invalid', e.message);
  }
  info('zip 完整：' + entries.length + ' 个条目');

  // ---- 3) 定位 kernel.json ----
  // 包内路径恒为 kernel/<version>/kernel.json，而 version 事先未知 —— 这正是要读它的原因。
  const kjEntry = entries.find((e) => !e.name.endsWith('/') && e.name.endsWith('kernel.json'));
  if (!kjEntry) fail('no-kernel-json', '包内找不到 kernel.json');

  let kernelJson;
  try {
    kernelJson = JSON.parse(readEntry(zipBuf, kjEntry).toString('utf8'));
  } catch (e) {
    fail('kernel-json-bad', 'kernel.json 不可解析: ' + e.message);
  }

  const version = kernelJson.version;
  if (!version) fail('no-version', 'kernel.json 缺 version 字段');
  info('包内 version=' + version + '，路径=' + kjEntry.name);

  // 路径与 version 必须自洽。不一致意味着包是手工拼的（或构建脚本有 bug），
  // 而落盘逻辑依赖 `kernel/<version>/` 这个约定，不自洽会导致解包后目录错位。
  if (kjEntry.name !== 'kernel/' + version + '/kernel.json') {
    fail('path-version-mismatch',
      'kernel.json 位于 ' + kjEntry.name + '，但 version=' + version +
      '（约定为 kernel/<version>/kernel.json）');
  }

  if (args.version && args.version !== version) {
    fail('version-mismatch', '期望 version=' + args.version + '，包内=' + version);
  }

  // ---- 4) ed25519 验签（本脚本存在的核心理由）----
  const signature = kernelJson.signature;
  if (!signature) fail('signature-missing', 'kernel.json 无 signature 字段');

  let pubKeyPem;
  try {
    pubKeyPem = fs.readFileSync(args.pubkey, 'utf8');
  } catch (e) {
    fail('pubkey-unreadable', e.message);
  }

  let sigOk = false;
  try {
    const data = Buffer.from(canonical(kernelJson), 'utf8');
    const sig = Buffer.from(signature, 'base64');
    if (sig.length !== 64) {
      // ed25519 签名恒为 64 字节。长度不对说明签名被截断/损坏 —— 明确报出来，
      // 比让 crypto.verify 返回 false 更有诊断价值。
      fail('signature-bad-length', 'ed25519 签名应为 64 字节，实际 ' + sig.length);
    }
    sigOk = crypto.verify(null, data, pubKeyPem, sig);
  } catch (e) {
    fail('signature-verify-error', e.message);
  }
  if (!sigOk) {
    fail('signature-invalid',
      'ed25519 验签未通过（公钥 ' + args.pubkey + '）—— 包不是用配对私钥签的，或 kernel.json 被改过');
  }
  info('ed25519 验签通过');

  // ---- 5) 入口存在性（结构完整性）----
  const entryRel = kernelJson.entry || 'bin/dsh-supervisor';
  const entryPath = 'kernel/' + version + '/' + entryRel;
  const hasEntry = entries.some((e) => e.name === entryPath && !e.name.endsWith('/'));
  if (!hasEntry) {
    // 这里**不**直接判失败，而是把 entryOk=false 报上去。
    // 理由：入口缺失是"结构不完整"，而签名有效说明"包确实是官方签的"。
    // 两者性质不同 —— 前者可能是打包 bug（该修），后者是安全问题（该拒）。
    // 交给调用方决定是否阻断，本脚本只如实报告。
    info('警告：包内缺少入口 ' + entryPath);
  }

  emit({
    ok: true,
    version,
    reason: null,
    detail: 'sha256=' + actualSha + '，验签通过，' + entries.length + ' 条目',
    entryOk: hasEntry,
    actualSha256: actualSha,
  });
}

try {
  main();
} catch (e) {
  // 兜底：任何未预期异常也要产出结果行，否则 Kotlin 侧只会看到 "no-result"，
  // 拿不到真正的原因。
  fail('verifier-crash', (e && e.stack) ? e.stack.split('\n').slice(0, 3).join(' | ') : String(e));
}
