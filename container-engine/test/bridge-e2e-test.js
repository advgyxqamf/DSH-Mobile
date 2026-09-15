'use strict';

// HostBridge 端到端：真起 UDS 服务端（模拟 Kotlin 侧），内核侧客户端握手 + 调方法 + 审计。
const fs = require('fs');
const os = require('os');
const path = require('path');
const { BridgeServer } = require('../src/bridge/server');
const { connect } = require('../src/bridge/uds-transport');
const proto = require('../src/bridge/protocol');
const makeRunner = require('./harness');

const { check, finish } = makeRunner('bridge-e2e');

const sock = path.join(os.tmpdir(), 'bridge-e2e-' + process.pid + '.sock');
const audit = path.join(os.tmpdir(), 'bridge-e2e-audit-' + process.pid + '.log');

function call(cli, id, method, params) {
  return new Promise((resolve) => {
    cli.onMessage((m) => { if (m.id === id) resolve(m); });
    cli.send(proto.request(id, method, params));
  });
}

(async () => {
  const srv = new BridgeServer({
    socketPath: sock,
    // 设备已预置：base / device_owner / accessibility / shizuku / build_chain
    // 未预置：manage_external_storage（故 storage 组不可用）、mediaprojection、notification_access
    deviceCapabilities: ['base', 'device_owner', 'accessibility', 'shizuku', 'build_chain'],
    auditLogPath: audit,
  });
  await srv.start();

  const cli = connect(sock);
  await cli.ready();

  const hs = await new Promise((resolve) => {
    cli.onMessage((m) => { if (m.id === 1) resolve(m); });
    cli.send(proto.handshakeRequest(1, ['bridge:app_control', 'bridge:notification', 'bridge:device_policy', 'bridge:storage']));
  });
  check('握手返回 capabilities 含 device_owner', Array.isArray(hs.result.capabilities) && hs.result.capabilities.includes('device_owner'));
  check('握手 groups 含 bridge:app_control', hs.result.groups.includes('bridge:app_control'));
  check('握手 groups 不含缺能力的 storage', !hs.result.groups.includes('bridge:storage'));

  const np = await call(cli, 2, 'notif.post', { title: 'hi', text: 'there' });
  check('notif.post（base）成功', np.result && np.result.posted === true);

  const ln = await call(cli, 3, 'policy.lockNow', {});
  check('policy.lockNow（device_owner）成功', ln.result && ln.result.locked === true);

  const fw = await call(cli, 4, 'fs.write', { path: '/x', content: 'y' });
  check('fs.write 缺 manage_external_storage 被拒(-32001)', fw.error && fw.error.code === -32001);

  const un = await call(cli, 5, 'nope.x', {});
  check('未知方法 METHOD_NOT_FOUND(-32601)', un.error && un.error.code === -32601);

  // P2（2026-09）：ui_automation 真实实现的**返回结构契约**（与 Kotlin 侧对齐）
  const tap = await call(cli, 6, 'ui.tap', { x: 10, y: 20 });
  check('ui.tap（accessibility）成功且返回 {ok:true}', tap.result && tap.result.ok === true);

  const tree = await call(cli, 7, 'ui.getUiTree', {});
  check('ui.getUiTree 返回 windows 结构',
    tree.result && Array.isArray(tree.result.windows) && typeof tree.result.windowCount === 'number');

  const wf = await call(cli, 8, 'ui.waitFor', { selector: { text: 'x' }, timeoutMs: 100 });
  check('ui.waitFor 返回 found/elapsedMs', wf.result && wf.result.found === false && typeof wf.result.elapsedMs === 'number');

  // ui.screenshot 需 mediaprojection（本用例未预置）→ 方法级门禁拦截
  const ss = await call(cli, 9, 'ui.screenshot', {});
  check('ui.screenshot 缺 mediaprojection 被拒(-32001)', ss.error && ss.error.code === -32001);

  // sys.setTimeZone 与 sys.setTime 同属 system 组、同需 device_owner
  const tz = await call(cli, 10, 'sys.setTimeZone', { timeZone: 'Asia/Shanghai' });
  check('sys.setTimeZone（device_owner）成功', tz.result && tz.result.ok === true);

  const log = fs.existsSync(audit) ? fs.readFileSync(audit, 'utf8') : '';
  check('审计日志含 policy.lockNow（特权）', log.includes('policy.lockNow'));
  check('审计日志含握手记录', log.includes('handshake'));

  cli.close();
  await srv.stop();
  finish();
})().catch((e) => { console.error(e); process.exit(1); });
