'use strict';

// 零依赖的最小 ZIP 实现（Stored + Deflate 双向）。
//
// 历史与教训（别把这条删掉）
// --------------------------
// 本文件最初只实现了 store（不压缩），且 extractZip **完全无视 method 字段** ——
// 它把「压缩后数据」当成「原始数据」直接写盘。后果：
//   · 用 createZip 自己打的包（全 Stored）能解开，测试全绿；
//   · 任何用标准工具（python zipfile / zip / 构建脚本）打的 Deflate 包，
//     解开后 CRC 必然对不上，报 "zip 条目 CRC 校验失败"。
// 也就是说：**OTA 引擎实际上只能吃自己造的全未压缩包**，
// 而外部产物（含 future 的本地 feed）一律进不来。这是个被测试掩盖的静默缺陷。
//
// 现在按中央目录的 method 字段分派：
//   0 = Stored   → 直接用
//   8 = Deflate  → zlib.inflateRawSync（**raw**，不是 zlib 头格式）
// 其余 method → 显式抛错（不静默降级，否则又是同一个坑的变体）。
//
// 为什么保留 createZip 默认 Stored：安卓侧 KernelManager 用 java.util.zip 解基线包，
// Stored 保证 100% 兼容且无需 zlib；而 OTA 包体积小（内核源码），压缩收益有限。

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const METHOD_STORED = 0;
const METHOD_DEFLATE = 8;

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

/**
 * 建 zip。
 * @param {Map<string,Buffer>} files 名称 → 内容
 * @param {object} [opts]
 *  - compress?: boolean  默认 false（Stored）。true 时对 >512B 的条目用 Deflate。
 *    ZIP_BZIP2/ZIP_LZMA 等一律不支持 —— 只有这两个 method 是安卓 zipfile 的公约数。
 */
function createZip(files, opts) {
  const compress = !!(opts && opts.compress);
  const chunks = [];
  const central = [];
  let offset = 0;
  for (const [name, data] of files) {
    const nameBuf = Buffer.from(name, 'utf8');
    const crc = crc32(data);
    // 小条目压缩后反而更大（deflate 有 ~5 字节头 + 熵），不值得压。
    let method = METHOD_STORED;
    let payload = data;
    if (compress && data.length > 512) {
      const def = zlib.deflateRawSync(data, { level: 6 });
      if (def.length < data.length) { method = METHOD_DEFLATE; payload = def; }
    }
    // 目录条目（名字以 / 结尾）恒为 Stored 空体。
    if (name.endsWith('/')) { method = METHOD_STORED; payload = Buffer.alloc(0); }

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); // 本地文件头签名
    local.writeUInt16LE(method === METHOD_DEFLATE ? 20 : 20, 4); // version needed
    local.writeUInt16LE(0, 6);          // flags
    local.writeUInt16LE(method, 8);     // compression
    local.writeUInt16LE(0, 10);         // mod time
    local.writeUInt16LE(0, 12);         // mod date
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(payload.length, 18); // compressed size
    local.writeUInt32LE(data.length, 22);    // uncompressed size
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);          // extra len
    chunks.push(local, nameBuf, payload);

    const cen = Buffer.alloc(46);
    cen.writeUInt32LE(0x02014b50, 0);    // 中央目录头签名
    cen.writeUInt16LE(20, 4);            // version made by
    cen.writeUInt16LE(20, 6);            // version needed
    cen.writeUInt16LE(0, 8);
    cen.writeUInt16LE(method, 10);       // ★ 必须与局部头一致，否则解方按错 method 处理
    cen.writeUInt16LE(0, 12);
    cen.writeUInt16LE(0, 14);
    cen.writeUInt32LE(crc, 16);
    cen.writeUInt32LE(payload.length, 20);
    cen.writeUInt32LE(data.length, 24);
    cen.writeUInt16LE(nameBuf.length, 28);
    cen.writeUInt16LE(0, 30);
    cen.writeUInt16LE(0, 32);
    cen.writeUInt16LE(0, 34);
    cen.writeUInt16LE(0, 36);
    cen.writeUInt32LE(0, 38);
    cen.writeUInt32LE(offset, 42);       // 本地头偏移
    central.push(Buffer.concat([cen, nameBuf]));
    offset += local.length + nameBuf.length + payload.length;
  }
  const centralBuf = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); // EOCD 签名
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(files.size, 8);  // 本盘条目数
  eocd.writeUInt16LE(files.size, 10); // 总条目数
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);
  return Buffer.concat([...chunks, centralBuf, eocd]);
}

/** 从 EOCD 起算并返回运行期需要的元信息。
 *  zip64 不支持 —— 内核包是几 MB 级，且显式报错比静默截断好。
 *  返回 { cdOffset, count }；count 为实际解析出的条目数。
 */
function _readEocd(buf) {
  let eocdOff = -1;
  // EOCD 注释最长 65535，往前最多扫这么多字节
  const from = Math.max(0, buf.length - 22 - 65535);
  for (let i = buf.length - 22; i >= from; i -= 1) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocdOff = i; break; }
  }
  if (eocdOff < 0) throw new Error('无效的 zip：找不到 EOCD');
  const count = buf.readUInt16LE(eocdOff + 10);
  const cdOffset = buf.readUInt32LE(eocdOff + 16);
  // zip64 哨兵值：0xFFFF / 0xFFFFFFFF 表示字段被搬去 zip64 扩展记录
  if (count === 0xFFFF || cdOffset === 0xFFFFFFFF) {
    throw new Error('不支持 zip64 格式的内核包（条目数或中央目录偏移溢出 32 位）');
  }
  return { cdOffset, count };
}

/**
 * 解 zip 到 destDir（带 CRC32 校验）。
 * 支持 method=0（Stored）与 method=8（Deflate）；其余 method 显式抛错。
 * @param {Buffer} buf
 * @param {string} destDir
 * @returns {{entries:number, names:string[]}} 便于调用方核对内容
 */
function extractZip(buf, destDir) {
  const { cdOffset, count } = _readEocd(buf);
  const names = [];
  let p = cdOffset;
  for (let n = 0; n < count; n += 1) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('无效的 zip：中央目录损坏');
    const method = buf.readUInt16LE(p + 10);
    const crc = buf.readUInt32LE(p + 16);
    const compSize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localOffset = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);

    // 中央目录与局部头都记了 name/extra 长度，二者可能不同（局部头可带额外对齐 extra）。
    // 数据起点必须按**局部头**算，否则偏移出界。
    const lhNameLen = buf.readUInt16LE(localOffset + 26);
    const lhExtraLen = buf.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + lhNameLen + lhExtraLen;
    const raw = buf.subarray(dataStart, dataStart + compSize);

    names.push(name);

    // 目录条目：只建目录，不写文件、不校验 CRC。
    if (name.endsWith('/')) {
      fs.mkdirSync(path.join(destDir, name), { recursive: true });
      p += 46 + nameLen + extraLen + commentLen;
      continue;
    }

    // ★ 这里就是历史缺陷所在：按 method 分派，而不是无条件当 Stored。
    let data;
    if (method === METHOD_STORED) {
      data = Buffer.from(raw);
    } else if (method === METHOD_DEFLATE) {
      try {
        data = zlib.inflateRawSync(raw);
      } catch (e) {
        throw new Error('zip 条目 Deflate 解压失败: ' + name + ' (' + e.message + ')');
      }
    } else {
      throw new Error(
        'zip 条目使用了不支持的压缩方式 method=' + method + '（只支持 0=Stored / 8=Deflate）: ' + name
      );
    }

   const out = path.join(destDir, name);
    // 目录穿越防护：内核包来自外部（本地 feed / 网络），必须挡住 ../ 逃逸。
    const rel = path.relative(path.resolve(destDir), path.resolve(out));
    if (rel.startsWith('..') || path.isAbsolute(rel)) {
      throw new Error('zip 条目路径越界（疑似目录穿越攻击）: ' + name);
    }
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, data);
    if (crc32(data) !== crc) throw new Error('zip 条目 CRC 校验失败: ' + name);

    p += 46 + nameLen + extraLen + commentLen;
  }
  return { entries: names.length, names };
}

/** 只读中央目录，不落盘。用于「先看包里有什么」的场景（如基线的 version 探测）。 */
function listZip(buf) {
  const { cdOffset, count } = _readEocd(buf);
  const out = [];
  let p = cdOffset;
  for (let n = 0; n < count; n += 1) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('无效的 zip：中央目录损坏');
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

/** 从 zip 里取出单个条目的内容（按 method 解压 + CRC 校验）。找不到返回 null。 */
function readEntry(buf, wantName) {
  const { cdOffset, count } = _readEocd(buf);
  let p = cdOffset;
  for (let n = 0; n < count; n += 1) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('无效的 zip：中央目录损坏');
    const method = buf.readUInt16LE(p + 10);
    const crc = buf.readUInt32LE(p + 16);
    const compSize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localOffset = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    if (name === wantName) {
      const lhNameLen = buf.readUInt16LE(localOffset + 26);
      const lhExtraLen = buf.readUInt16LE(localOffset + 28);
      const dataStart = localOffset + 30 + lhNameLen + lhExtraLen;
      const raw = buf.subarray(dataStart, dataStart + compSize);
      let data;
      if (method === METHOD_STORED) data = Buffer.from(raw);
      else if (method === METHOD_DEFLATE) data = zlib.inflateRawSync(raw);
      else throw new Error('不支持的压缩方式 method=' + method + ': ' + name);
      if (crc32(data) !== crc) throw new Error('zip 条目 CRC 校验失败: ' + name);
      return data;
    }
    p += 46 + nameLen + extraLen + commentLen;
  }
  return null;
}

module.exports = {
  createZip, extractZip, crc32, listZip, readEntry,
  METHOD_STORED, METHOD_DEFLATE,
};
