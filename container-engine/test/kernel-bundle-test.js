'use strict';

// 内核包：打包成 zip + kernel.json 签名 + manifest.sha256 一致；解包后可验签、含 manager 文件。
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { packBundle, DEFAULT_ENTRY } = require('../src/kernel-bundle');
const { extractZip } = require('../src/zip');
const { verifyManifest, sha256 } = require('../src/verify');
const makeRunner = require('./harness');

const { check, finish } = makeRunner('kernel-bundle');

const kp = crypto.generateKeyPairSync('ed25519', {
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  publicKeyEncoding: { type: 'spki', format: 'pem' },
});

const src = fs.mkdtempSync(path.join(os.tmpdir(), 'ksrc-'));
fs.mkdirSync(path.join(src, 'bin'), { recursive: true });
fs.writeFileSync(path.join(src, 'bin', 'dsh-supervisor'), '#!/usr/bin/env node\nrequire("../src/supervisor").start();\n');
fs.mkdirSync(path.join(src, 'src'), { recursive: true });
fs.writeFileSync(path.join(src, 'src', 'x.js'), 'module.exports={};');
fs.mkdirSync(path.join(src, 'ui', 'dist'), { recursive: true });
fs.writeFileSync(path.join(src, 'ui', 'dist', 'supervisor.html'), '<html></html>');

const { zipBuf, kernelJson, manifest } = packBundle({
  srcDir: src, version: '1.4.0', privateKeyPem: kp.privateKey,
  requires: ['bridge:app_control'], url: 'http://ota.example/k.zip',
});

check('zipBuf 为 Buffer 且非空', Buffer.isBuffer(zipBuf) && zipBuf.length > 0);
check('kernel.json 含签名', typeof kernelJson.signature === 'string' && kernelJson.signature.length > 0);
check('entry 默认 bin/dsh-supervisor', kernelJson.entry === DEFAULT_ENTRY);
check('manifest.sha256 === zip sha256', manifest.sha256 === sha256(zipBuf));
check('manifest.version 一致', manifest.version === '1.4.0');

const out = fs.mkdtempSync(path.join(os.tmpdir(), 'kext-'));
extractZip(zipBuf, out);
const kjPath = path.join(out, 'kernel', '1.4.0', 'kernel.json');
check('解包得到 kernel/<version>/kernel.json', fs.existsSync(kjPath));
const kj2 = JSON.parse(fs.readFileSync(kjPath, 'utf8'));
check('解包 kernel.json 验签通过', verifyManifest(kp.publicKey, kj2, kj2.signature));
check('解包含 manager 文件(bin/dsh-supervisor)', fs.existsSync(path.join(out, 'kernel', '1.4.0', 'bin', 'dsh-supervisor')));
check('解包含 ui/dist/supervisor.html', fs.existsSync(path.join(out, 'kernel', '1.4.0', 'ui', 'dist', 'supervisor.html')));

finish();
