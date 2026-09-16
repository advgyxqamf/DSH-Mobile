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
      // 原生资产自检（2026-09）。返回形状与 Kotlin NativePreparer.PrepareReport.toJson 对齐：
      //   { allRequiredReady, nativeLibraryDir, libSearchPath, assets: [...] }
      // 与注册表 NativeAssetRegistry（libcxx + node）保持一致 —— 改动注册表时同步这里，
      // 否则 container-engine/test/native-assets-test.js 会失败。
      case 'sys.nativeAssets': return {
        allRequiredReady: true,
        nativeLibraryDir: '/data/app/~~mock/pkg-mock/lib/arm64-v8a',
        libSearchPath: '/data/app/~~mock/pkg-mock/lib/arm64-v8a',
        assets: [
          {
            id: 'libcxx', libName: 'libc++_shared.so', humanName: 'C++ 运行期',
            required: true, requiredDeps: [],
            note: '不是可执行文件，但必须在 nativeLibraryDir —— libnode.so 的 DT_NEEDED 依赖它',
            status: 'ready', path: '/data/app/~~mock/pkg-mock/lib/arm64-v8a/libc++_shared.so',
            probeOutput: '数据资产：mock（不做 exec-probe）',
          },
          {
            id: 'node', libName: 'libnode.so', humanName: 'Node 运行时',
            required: true, requiredDeps: ['libc++_shared.so'],
            note: '实为可执行文件，改名 lib*.so 借 jniLibs 通道落到 exec_type 目录',
            status: 'ready', path: '/data/app/~~mock/pkg-mock/lib/arm64-v8a/libnode.so',
            probeOutput: 'v24.21.0',
          },
        ],
      };
      // ui_automation：与 Kotlin 真实实现的返回结构对齐（P2，2026-09）。
      // Kotlin 侧 ui.* 返回 {ok:bool}（getUiTree 返回 {windows,windowCount,truncated}）
      case 'ui.getUiTree': return { windows: [], windowCount: 0, truncated: false };
      case 'ui.tap': return { ok: true };
      case 'ui.swipe': return { ok: true };
      case 'ui.inputText': return { ok: true };
      case 'ui.waitFor': return { found: false, elapsedMs: params.timeoutMs || 0 };
      case 'ui.screenshot': return {
        // P5：默认返回 PNG 文件路径（避免几 MB base64 撑爆 JSON-RPC 帧）；inline=true 才内联。
        ...(params && params.inline
          ? { encoding: 'base64', content: '' }
          : { path: '/data/.../screenshots/shot-<ts>.png', width: 1080, height: 2400, bytes: 0 }),
      };
      // storage：P5 真实实现（2026-09）。与 Kotlin 返回结构对齐。
      case 'fs.list': return { path: params.path || '/sdcard', isDirectory: true, entries: [], count: 0, truncated: false };
      case 'fs.read': return {
        path: params.path || '', bytes: 0, encoding: params.encoding === 'base64' ? 'base64' : 'utf8', content: '',
      };
      case 'fs.write': return { path: params.path || '', bytes: 0, appended: !!params.append };
      case 'fs.mkdir': return { path: params.path || '', existed: false };
      // build：A'' 内核安装（2026-09 语义修正）。与 Kotlin 返回结构对齐。
      //
      // 注意与旧 mock 的区别：旧的是"提交编译任务 → 返回 job id → 轮询"，
      // 新的**同步返回结果**。因为内核安装本身是秒级操作（验签 + 解包 + 改名），
      // 不像编译要几十分钟。为它引入任务队列只会增加状态而没有任何收益。
      case 'build.kernelInstall': return {
        ok: true,
        version: '0.1.0-android.1',
        source: params.feed ? 'APK 内置基线' : '本地文件 feed',
        reason: null,
        detail: '已落盘并切换指针',
        verifierOutput: 'DSH_VERIFY_RESULT {"ok":true,...}',
        restartRequired: true,
      };
      case 'build.kernelStatus': return {
        current: '0.1.0-android.1',
        installed: ['0.1.0-android.1'],
        integrity: [],
        feedPending: null,
      };
      // 旧名：返回**带解释的错误**而不是静默失败，便于存量调用方迁移。
      case 'build.status': return { current: '0.1.0-android.1', installed: ['0.1.0-android.1'] };
      case 'build.apk': throw new Error('build.apk 已废弃，请改用 build.kernelInstall');
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
        // P4 兜底语义：以**应用 uid** 执行（非 shell uid 2000）。
        // 真实部署需 Shizuku 才有特权；mock 保留可执行性验证。
        execFile(params.cmd || 'echo', params.args || ['ok'], { timeout: 5000 }, (err, stdout) => {
          if (err) reject(err);
          else resolve({ ok: true, exitCode: 0, stdout: String(stdout), uid: -1, privileged: false });
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
