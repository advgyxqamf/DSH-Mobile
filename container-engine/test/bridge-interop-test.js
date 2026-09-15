'use strict';

// 内核 HostBridge 客户端 ←→ 容器 HostBridge 服务端 **真实 UDS 互通**测试。
//
// 目的：证明「内核能连上容器桥」这一关键接线成立（此前内核侧无任何桥客户端，桥是孤儿）。
// 用容器侧参考服务端（BridgeServer，真实暴露抽象命名空间 UDS）+ 内核侧真实客户端
// （dsh-android-kernel/src/platform/host-bridge/client.js），走完整链路：
//   连接 → bridge.handshake 能力协商 → 调用 8 组方法 → 能力门禁(-32001) / 未知方法(-32601)。
//
// 抽象命名空间 socket 名随机化，避免与真实设备 / 并行测试冲突。

const makeRunner = require('./harness');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { check, finish } = makeRunner('bridge-interop');

// 内核侧客户端（跨仓引用）
const clientMod = require('/workspace/dsh-android-kernel/src/platform/host-bridge/client');
const { BridgeServer } = require('../src/bridge/server');

const SOCK = 'dsh_test_' + process.pid + '_' + Math.random().toString(36).slice(2, 8);
const auditLog = path.join(os.tmpdir(), 'bridge-interop-audit-' + process.pid + '.log');

async function main() {
  // 容器侧：只有 base 能力（模拟未预置 device_owner/accessibility 的设备）
  const server = new BridgeServer({
    socketPath: '\0' + SOCK, // 抽象命名空间（与 Kotlin LocalServerSocket(name) 等价）
    deviceCapabilities: ['base'],
    auditLogPath: auditLog,
    storageRoot: path.join(os.tmpdir(), 'bridge-interop-store'),
  });
  await server.start();

  const c = new clientMod.HostBridgeClient({ socketName: SOCK, requires: ['bridge:app_control', 'bridge:device_policy'] });

  // 1) 连接 + 握手
  const hs = await c.handshake();
  check('内核客户端连上容器桥并完成握手', !!hs && hs.protocol === 1);
  check('握手返回设备能力（base）', Array.isArray(hs && hs.capabilities) && hs.capabilities.includes('base'));
  check('requires 含 device_policy 但设备无 device_owner → 未授予', Array.isArray(hs && hs.groups) && !hs.groups.includes('bridge:device_policy'));
  check('requires 含 app_control（代表能力 base）→ 已授予', hs && hs.groups.includes('bridge:app_control'));

  // 2) 调用 base 能力方法（应成功）
  const info = await c.call('sys.info', {});
  check('call sys.info 成功返回结果', !!info && info.ok === true && info.result && info.result.device === 'mock-android');

  const launch = await c.call('app.launch', { pkg: 'com.example.a' });
  check('call app.launch 成功', !!launch && launch.ok && launch.result.launched === 'com.example.a');

  const openUrl = await c.call('app.openUrl', { url: 'https://example.com' });
  check('call app.openUrl（browser 承接方）成功', !!openUrl && openUrl.ok && openUrl.result.opened === true);

  const post = await c.call('notif.post', { title: 't', text: 'b' });
  check('call notif.post（notify 承接方）成功', !!post && post.ok && post.result.posted === true);

  // app.stop 需 base 能力（非特权，不可审计）
  const stop = await c.call('app.stop', { pkg: 'com.example.a' });
  check('call app.stop 成功', !!stop && stop.ok && stop.result.stopped === 'com.example.a');

  // 3) 能力门禁：设备无 device_owner → policy.lockNow 返回 -32001
  const denied = await c.call('policy.lockNow', {});
  check('越能力调用 policy.lockNow → ERR_CAPABILITY_MISSING', !!denied && denied.ok === false && denied.error.code === -32001);

  // 4) 未知方法 → -32601
  const unknown = await c.call('nope.nothing', {});
  check('未知方法 → METHOD_NOT_FOUND', !!unknown && unknown.ok === false && unknown.error.code === -32601);

  // 5) 单例与 inContainer 判定
  check('inContainer 由 DSH_ANDROID 判定', clientMod.inContainer() === (process.env.DSH_ANDROID === '1'));

  // 6) 审计日志：握手必落盘；且非特权（base）调用不应污染审计（符合 spec §5「特权操作才审计」）
  let auditText = '';
  try { auditText = fs.readFileSync(auditLog, 'utf8'); } catch {}
  check('容器侧审计日志含 handshake', auditText.includes('handshake'));
  check('非特权调用（app.launch/notif.post）未写入审计', !auditText.includes('"method":"app.launch"') && !auditText.includes('"method":"notif.post"'));

  c.close();
  await server.stop();
  try { fs.unlinkSync(auditLog); } catch {}

  finish();
}

main().catch((e) => { console.error('interop 异常:', e && e.stack || e); finish(); });
