'use strict';

// HostBridge 传输层：Unix 域套接字（UDS）+ 换行分隔 JSON 帧。
// 权限绑定本 App UID（chmod 0600），仅本应用进程可连（对齐 BRIDGE_PROTOCOL §1）。
// 真实安卓侧由 Kotlin HostBridgeService 提供同构 UDS 服务端；此处为可测参考实现 + 测试传输。

const net = require('net');
const fs = require('fs');
const os = require('os');
const path = require('path');

function tmpSocketName(prefix) {
  return path.join(os.tmpdir(), (prefix || 'bridge') + '-' + process.pid + '-' + Math.random().toString(36).slice(2) + '.sock');
}

/** 创建 UDS 服务端。onMessage(sock, msg) 收到每帧解析后的对象。返回 { server, path, close() }。 */
function createServer(socketPath, onMessage, opts) {
  opts = opts || {};
  const server = net.createServer((sock) => {
    let buf = '';
    sock.setEncoding('utf8');
    sock.on('data', (chunk) => {
      buf += chunk;
      let idx;
      while ((idx = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, idx);
        buf = buf.slice(idx + 1);
        if (!line.trim()) continue;
        let msg;
        try { msg = JSON.parse(line); } catch (_e) { continue; }
        onMessage(sock, msg);
      }
    });
  });
  server.listen(socketPath, () => {
    try { fs.chmodSync(socketPath, 0o600); } catch (_e) {}
    if (opts.onListen) opts.onListen();
  });
  return {
    server,
    path: socketPath,
    close: () => new Promise((res) => server.close(() => res())),
  };
}

/** 连接 UDS 服务端。send(msg) 写 JSON+换行；onMessage(cb) 注册帧回调；ready() 等待连接建立。 */
function connect(socketPath) {
  const sock = net.connect(socketPath);
  let buf = '';
  const handlers = [];
  sock.setEncoding('utf8');
  sock.on('data', (chunk) => {
    buf += chunk;
    let idx;
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx);
      buf = buf.slice(idx + 1);
      if (!line.trim()) continue;
      try {
        const m = JSON.parse(line);
        handlers.forEach((h) => h(m));
      } catch (_e) {}
    }
  });
  const api = {
    socket: sock,
    send: (msg) => sock.write(JSON.stringify(msg) + '\n'),
    onMessage: (cb) => handlers.push(cb),
    close: () => sock.end(),
    ready: () => new Promise((resolve, reject) => {
      if (sock.connecting || sock.pending) { sock.once('connect', resolve); sock.once('error', reject); }
      else resolve();
    }),
  };
  return api;
}

module.exports = { createServer, connect, tmpSocketName };
