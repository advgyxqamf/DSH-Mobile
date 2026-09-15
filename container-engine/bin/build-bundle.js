#!/usr/bin/env node
'use strict';

// 内核包构建 CLI（CI 用）：把内核源码目录打包成「签名内核 OTA 包」。
// 对齐 docs/BASE_SPEC.md §5 通道一（构建期签名）。
//
// 双信任根：ed25519 私钥（keys/ota-private.pem，gitignored / CI secret）仅在此签名用；
// 验签公钥焊死在 APK（app/src/main/assets/ota-public.pem），设备端只用它验签。
//
// 产物：release/kernel-<version>.zip（= OTA 下发的包）
//       release/kernel-manifest.json（版本/url/sha256/签名，供 OTA 引擎 fetchManifest）

const fs = require('fs');
const path = require('path');
const { packBundle, DEFAULT_ABI } = require('../src/kernel-bundle');
const { loadPrivateKey } = require('../src/keys');
const { sha256 } = require('../src/verify');

function main() {
  const srcDir = process.argv[2];
  const version = process.argv[3];
  const abi = process.argv[4] || process.env.KERNEL_ABI || DEFAULT_ABI;
  const urlBase = process.argv[5] || process.env.OTA_URL_BASE || '';

  if (!srcDir || !version) {
    console.error('用法: build-bundle.js <kernel-src-dir> <version> [abi] [url-base]');
    process.exit(2);
  }
  if (!fs.existsSync(srcDir)) {
    console.error('内核源码目录不存在: ' + srcDir);
    process.exit(1);
  }

  const privateKey = loadPrivateKey();
  const url = (urlBase ? urlBase.replace(/\/$/, '') + '/' : '') + `kernel-${version}.zip`;
  const { zipBuf, manifest } = packBundle({ srcDir, version, abi, privateKeyPem: privateKey, url });

  // 重新核算，确保 manifest.sha256 与落盘 zip 字节一致
  const actualSha = sha256(zipBuf);
  const manifestOut = Object.assign({}, manifest, { sha256: actualSha });

  const outDir = path.resolve(__dirname, '..', '..', 'release');
  fs.mkdirSync(outDir, { recursive: true });
  const zipPath = path.join(outDir, `kernel-${version}.zip`);
  fs.writeFileSync(zipPath, zipBuf);
  const manifestPath = path.join(outDir, 'kernel-manifest.json');
  fs.writeFileSync(manifestPath, JSON.stringify(manifestOut, null, 2));

  console.log('内核包: ' + zipPath + ' (' + zipBuf.length + ' bytes)');
  console.log('清单  : ' + manifestPath);
  console.log('sha256: ' + actualSha);
  console.log('签名  : ' + manifest.signature.slice(0, 32) + '…');
  console.log('url   : ' + url);
}

main();
