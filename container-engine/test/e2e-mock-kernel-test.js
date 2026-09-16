'use strict';

// 端到端集成测试：用一个小 node 进程扮演 dsh-supervisor，由容器引擎真正拉起，
// 走完「签名打包 → 验签解包 → CURRENT 指针切换 → bootKernel 真实 spawn → 健康检查」全链路。
// 这是把「冻结 APK 容器」与「可热更新内核」真正连起来的最后一环的实跑验证。

const fs = require('fs');
const os = require('os');
const path = require('path');
const makeRunner = require('./harness');
const crypto = require('crypto');
const { packBundle } = require('../src/kernel-bundle');
const { OtaEngine } = require('../src/ota-engine');
const { bootKernel, pollHealth } = require('../src/boot');

const { check, finish } = makeRunner('e2e-mock-kernel');

// ============================================================================
//  测试自造密钥对，**不读仓库外的生产私钥**
// ============================================================================
//  原先这里用 loadPrivateKey()/loadPublicKey() 去读
//  `keys/ota-private.pem` + `assets/ota-public.pem`。这在单仓 CI 上必然崩：
//  `keys/ota-private.pem` 被 .gitignore 排除（**这是刻意的** —— 私钥绝不入库），
//  checkout 出来的仓库里根本没有它，于是测试以
//  `ENOENT: .../keys/ota-private.pem` 崩掉，一条断言都跑不出来。
//
//  更根本的问题是**测试的语义**：本测试要验的是
//  「签名打包 → 验签解包 → 切指针 → spawn → 健康检查」这条**链路**，
//  而不是「生产密钥对不对」。用生产密钥当夹具，既让它依赖一个不该存在的文件，
//  又让"链路坏了"和"密钥轮换了"两种失败混在一起、无法区分。
//
//  改用自造密钥对后：测试自洽、可离线、可并行，且与生产密钥的轮换彻底解耦。
//  （其他测试如 kernel-selfboot-test.js 一直就是这么做的。）
// ============================================================================
const kp = crypto.generateKeyPairSync('ed25519', {
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  publicKeyEncoding: { type: 'spki', format: 'pem' },
});

const STATUS_PORT = 3081;
// 扮演 dsh-supervisor 的最小 node 进程：绑定健康检查端口并常驻直到被 kill。
const SUPERVISOR = `#!/usr/bin/env node
'use strict';
const http = require('http');
const port = +(process.env.DSH_STATUS_PORT || 3081);
const server = http.createServer((req, res) => {
  if (req.url === '/status') { res.writeHead(200); res.end(JSON.stringify({ ok: true, home: process.env.DSH_SUPERVISOR_HOME })); return; }
  res.writeHead(200); res.end('kernel-up');
});
server.listen(port, '127.0.0.1', () => { /* ready */ });
const keep = setInterval(() => {}, 1 << 30);
process.on('SIGTERM', () => { clearInterval(keep); server.close(() => process.exit(0)); });
process.on('SIGINT', () => { clearInterval(keep); server.close(() => process.exit(0)); });
`;

(async () => {
  const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'e2e-kernel-'));
  const filesDir = path.join(sandbox, 'files');
  fs.mkdirSync(filesDir, { recursive: true });

  // 1) 准备一个假内核源码树
  const srcDir = path.join(sandbox, 'src');
  fs.mkdirSync(path.join(srcDir, 'bin'), { recursive: true });
  fs.mkdirSync(path.join(srcDir, 'manager'), { recursive: true });
  fs.writeFileSync(path.join(srcDir, 'bin', 'dsh-supervisor'), SUPERVISOR);
  fs.writeFileSync(path.join(srcDir, 'manager', 'index.js'), 'module.exports = {};\n');

  // 2) 签名打包（engines 对齐沙箱实际 node，使 _nodeSatisfied 通过）
  const privateKey = kp.privateKey;
  const publicKey = kp.publicKey;
  const version = '1.0.0-mock';
  const nodeMajor = parseInt(process.versions.node, 10);
  const { zipBuf, manifest } = packBundle({
    srcDir, version, engines: { node: '>=' + nodeMajor }, privateKeyPem: privateKey, url: 'https://ota.example/kernel.zip',
  });
  check('打包产出 zipBuf 非空', Buffer.isBuffer(zipBuf) && zipBuf.length > 0);
  check('manifest 含 sha256 与签名', typeof manifest.sha256 === 'string' && typeof manifest.signature === 'string');

  // 3) OTA 引擎验签 + 解包 + 切指针
  const ota = new OtaEngine({
    baseDir: filesDir,
    httpGet: async () => zipBuf,
    publicKeyPem: publicKey,
    capabilities: ['base'],
  });
  const v = ota.verifyPackage(zipBuf, manifest);
  check('验签 + sha256 通过', v.ok === true);
  const dest = ota.apply(version, zipBuf);
  check('解包落盘到 kernel/<version>', fs.existsSync(path.join(dest, 'bin', 'dsh-supervisor')));
  check('CURRENT 指针切到新版本', ota.currentVersion() === version);

  // 4) 真实拉起内核（bootKernel spawn 真 node 进程）
  const nodeBin = process.execPath;
  const { child, kernelDir } = bootKernel({
    kernelHome: filesDir,
    kernelVersion: version,
    nodeBin,
    nodeBinDir: path.dirname(nodeBin),
    npmPath: process.execPath,
    apiPort: STATUS_PORT,
    extraEnv: { DSH_STATUS_PORT: String(STATUS_PORT) },
  });
  check('内核进程已 spawn', !!child && child.pid > 0);
  check('runtime.json 已写入（schema 2）',
    fs.existsSync(path.join(filesDir, 'supervisor', 'runtime.json')) &&
    JSON.parse(fs.readFileSync(path.join(filesDir, 'supervisor', 'runtime.json'), 'utf8')).schema === 2);

  // 5) 健康检查
  const healthy = await pollHealth({ host: '127.0.0.1', port: STATUS_PORT, healthPath: '/status', timeoutMs: 8000 });
  check('内核健康检查通过', healthy === true);

  // 6) 清理
  child.kill('SIGTERM');
  await new Promise((r) => child.on('exit', r));
  fs.rmSync(sandbox, { recursive: true, force: true });
  finish();
})().catch((e) => { console.error(e); process.exit(1); });
