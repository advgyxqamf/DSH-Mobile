'use strict';

// HostBridge 参考服务端（Node 版，模拟 Kotlin HostBridgeService 侧）。
// 真实暴露 UDS：JSON-RPC 2.0 调度 + 握手/能力协商 + 8 组方法 + 特权操作审计。
// 真实安卓侧由 Kotlin 实现同构逻辑（见 HostBridgeService.kt），本实现供集成测试与引擎验证。
// 默认 handler 用 Node mock 落地各方法语义，使端到端链路可验证；真实部署替换为 Android API 调用。

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFile } = require('child_process');
const { createServer } = require('./uds-transport');
const proto = require('./protocol');
const methods = require('./methods');

class BridgeServer {
  constructor({ socketPath, deviceCapabilities, handlers, auditLogPath, storageRoot }) {
    this.socketPath = socketPath;
    this.deviceCapabilities = deviceCapabilities && deviceCapabilities.length ? deviceCapabilities : ['base'];
    this.handlers = handlers || {};
    this.auditLogPath = auditLogPath;
    this.storageRoot = storageRoot || path.join(os.tmpdir(), 'bridge-storage');
    this._server = null;
  }

  /** 设备已预置的 bridge:* 组（该组所有方法所需设备能力都具备）。 */
  availableGroups() {
    return methods.GROUPS
      .filter((g) => methods.groupCaps(g).every((c) => this.deviceCapabilities.includes(c)))
      .map((g) => 'bridge:' + g);
  }

  audit(entry) {
    if (!this.auditLogPath) return;
    try {
      fs.mkdirSync(path.dirname(this.auditLogPath), { recursive: true });
      fs.appendFileSync(this.auditLogPath, JSON.stringify(Object.assign({ ts: new Date().toISOString() }, entry)) + '\n');
    } catch (_e) {}
  }

  async dispatch(msg) {
    // 握手
    if (msg.method === 'bridge.handshake') {
      const groups = this.availableGroups();
      const reqCaps = (msg.params && msg.params.requires) || [];
      const missing = reqCaps.filter((g) => !groups.includes(g));
      this.audit({ type: 'handshake', agent: 'kernel', requires: reqCaps, grantedGroups: groups, missing });
      return proto.response(msg.id, {
        protocol: proto.PROTOCOL_VERSION,
        capabilities: this.deviceCapabilities,
        groups,
      });
    }
    const method = msg.method;
    if (!methods.METHODS[method]) {
      return proto.error(msg.id, proto.ERROR_CODES.METHOD_NOT_FOUND, 'unknown method: ' + method);
    }
    const miss = methods.missingCaps(method, this.deviceCapabilities);
    if (miss && miss.length) {
      return proto.error(msg.id, proto.ERROR_CODES.ERR_CAPABILITY_MISSING, 'missing capability: ' + miss.join(','), { missing: miss });
    }
    let result;
    try {
      result = await this.invoke(method, msg.params || {});
    } catch (e) {
      this.audit({ type: 'call', agent: 'kernel', method, ok: false, error: String(e && e.message) });
      return proto.error(msg.id, proto.ERROR_CODES.ERR_RUNTIME, String(e && e.message));
    }
    if (methods.isAudited(method)) this.audit({ type: 'call', agent: 'kernel', method, ok: true });
    return proto.response(msg.id, result);
  }

  async invoke(method, params) {
    const h = this.handlers[method];
    if (h) return await h(params, { deviceCapabilities: this.deviceCapabilities, storageRoot: this.storageRoot });
    return this._defaultHandler(method, params);
  }

  _defaultHandler(method, params) {
    switch (method) {
      case 'app.listInstalled': return { packages: ['com.example.a', 'com.example.b'] };
      case 'notif.post': return { posted: true, title: params.title, text: params.text };
      case 'notif.read': return { notifications: [] };
      case 'sys.info': return { device: 'mock-android', apiLevel: 35, arch: 'arm64' };
      // ui_automation：与 Kotlin 真实实现的返回结构对齐（P2，2026-09）。
      // Kotlin 侧 ui.* 返回 {ok:bool}（getUiTree 返回 {windows,windowCount,truncated}）
      case 'ui.getUiTree': return { windows: [], windowCount: 0, truncated: false };
      case 'ui.tap': return { ok: true };
      case 'ui.swipe': return { ok: true };
      case 'ui.inputText': return { ok: true };
      case 'ui.waitFor': return { found: false, elapsedMs: params.timeoutMs || 0 };
      case 'ui.screenshot': return { ok: false, note: 'MediaProjection 未落地（P5）' };
      case 'fs.list': return { entries: [] };
      case 'fs.read': return { content: '' };
      case 'fs.mkdir': return { created: params.path };
      case 'build.status': return { id: params.id || 'last', status: 'idle' };
      case 'build.apk': return { id: 'b' + Date.now(), status: 'queued' };
      case 'app.launch': return { launched: params.pkg };
      case 'app.openUrl': return { opened: true, url: params.url };
      case 'app.stop': return { stopped: params.pkg };
      case 'policy.lockNow': return { locked: true };
      case 'policy.setPassword': return { ok: true };
      case 'policy.wipe': return { ok: true };
      case 'policy.setKiosk': return { kiosk: params.pkg ? [params.pkg] : [] };
      case 'policy.addUserRestriction': return { ok: true };
      case 'app.install': return { installing: params.apkPath };
      case 'app.uninstall': return { uninstalling: params.pkg };
      case 'app.grantPermission': return { granted: true };
      case 'sys.setTime': return { ok: true };
      case 'sys.reboot': return { ok: true };
      case 'sys.setTimeZone': return { ok: true, timeZone: params.timeZone };
      case 'shell.exec': return new Promise((resolve, reject) => {
        execFile(params.cmd || 'echo', params.args || ['ok'], { timeout: 5000 }, (err, stdout) => {
          if (err) reject(err); else resolve({ exitCode: 0, stdout: String(stdout) });
        });
      });
      default: return { echo: method };
    }
  }

  start() {
    return new Promise((resolve) => {
      this._server = createServer(this.socketPath, async (sock, msg) => {
        const out = await this.dispatch(msg);
        if (out) sock.write(JSON.stringify(out) + '\n');
      }, { onListen: () => resolve() });
    });
  }

  stop() {
    return this._server ? this._server.close() : Promise.resolve();
  }
}

module.exports = { BridgeServer };
