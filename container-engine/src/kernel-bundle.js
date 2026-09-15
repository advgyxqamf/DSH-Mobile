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

function collectFiles(srcDir) {
  const out = new Map();
  (function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const full = path.join(d, e.name);
      const rel = path.relative(srcDir, full).split(path.sep).join('/');
      if (e.isDirectory()) walk(full);
      else out.set(rel, fs.readFileSync(full));
    }
  })(srcDir);
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

  const zipBuf = createZip(zipFiles);
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
  return { zipBuf, kernelJson, manifest };
}

module.exports = { DEFAULT_ENTRY, DEFAULT_ABI, DEFAULT_ENGINES, buildKernelJson, collectFiles, packBundle };
