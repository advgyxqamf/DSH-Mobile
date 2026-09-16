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
    console.error('环境变量: DSH_BUNDLE_OUT_DIR 可指定输出目录（默认 <repo>/release）');
    process.exit(2);
  }
  if (!fs.existsSync(srcDir)) {
    console.error('内核源码目录不存在: ' + srcDir);
    process.exit(1);
  }

  const privateKey = loadPrivateKey();
  // url 只在**确实给了 urlBase** 时才写。
  //
  // 原实现是无条件拼 `(urlBase ? ... : '') + 'kernel-<ver>.zip'`，
  // 于是 urlBase 为空时 url 变成了**裸文件名** `kernel-1.2.0.zip`。
  // 那不是 url —— 它没有 scheme、没有 host，设备端拿它既不能下载也无法解析，
  // 却会因为"manifest 里有 url 字段"而让人以为存在远端通道。
  // 本地 feed 场景（scripts/build-kernel-feed.sh）把它暴露了出来。
  //
  // 正确语义：**没有基址就是没有 url**（用空串表示"本地投递，无远端"）。
  const url = urlBase ? urlBase.replace(/\/$/, '') + `/kernel-${version}.zip` : '';
  const { zipBuf, manifest } = packBundle({ srcDir, version, abi, privateKeyPem: privateKey, url });

  // 重新核算，确保 manifest.sha256 与落盘 zip 字节一致
  const actualSha = sha256(zipBuf);
  const manifestOut = Object.assign({}, manifest, { sha256: actualSha });

  // 输出目录可用 DSH_BUNDLE_OUT_DIR 覆盖。
  //
  // 为什么需要这个开关：原先输出目录**硬编码**为 `path.resolve(__dirname,'../..','release')`
  // —— 即「本脚本所在仓库的 release/」。这在正常用法下没问题，但它把
  // 「往哪写产物」与「脚本装在哪」绑死了：任何想把产物放到别处的调用方
  // （例如在临时目录里跑集成测试、或 CI 要把产物直接投到另一个路径）
  // 都只能去翻这个硬编码。
  //
  // 这在 kernel-feed-test.js 里真实暴露过：测试把脚本拷到沙箱再跑，
  // 于是 release/ 落到了沙箱里的错误位置，而脚本按自己推导的路径找不到包。
  // 加一个环境变量开关是最小改动，同时保持默认行为完全不变。
  const outDir = process.env.DSH_BUNDLE_OUT_DIR
    ? path.resolve(process.env.DSH_BUNDLE_OUT_DIR)
    : path.resolve(__dirname, '..', '..', 'release');
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
