'use strict';

// ============================================================================
//  安卓原生 Node 容器 —— 最小探针服务
//  作用：证明 Node.js 已在安卓 bionic 环境原生跑通，并暴露运行时元信息
//        （Node 版本 / LTS 栈 / OpenSSL-TLS 版本 / 架构）。
//  后续塞 DSH 等负载时，把这份 server.js 换成你的入口即可；
//        端口、监听地址(127.0.0.1)、进程模型都保持不变。
// ============================================================================

const http = require('http');
const os = require('os');
const url = require('url');

const PORT = parseInt(process.argv[2] || '3080', 10);
const HOST = '127.0.0.1'; // 只监听回环，避免暴露到局域网

const server = http.createServer((req, res) => {
  const parsed = url.parse(req.url, true);

  // 健康检查 + 运行时元信息（验证“最新 LTS / 现代 TLS 栈”是否真的跑起来了）
  if (parsed.pathname === '/api/version') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({
      container: 'android-node-container',
      node: process.version,                 // 例如 v24.21.0
      lts: process.release.lts || null,       // LTS 代号或 null
      platform: process.platform,             // android
      arch: process.arch,                     // arm64
      v8: process.versions.v8,
      openssl: process.versions.openssl,      // Node 24 自带 OpenSSL 3.5 → 现代 TLS 1.3
      uptime: process.uptime(),
      cpus: os.cpus().length,
    }, null, 2));
    return;
  }

  // 简单 echo，验证请求/响应链路
  if (parsed.pathname === '/api/echo' && req.method === 'POST') {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ echo: body, receivedAt: new Date().toISOString() }));
    });
    return;
  }

  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(
    `<h1>Node.js 已在 Android 上原生运行</h1>` +
    `<p>Node ${process.version} • ${process.platform}/${process.arch} • OpenSSL ${process.versions.openssl}</p>` +
    `<p><a href="/api/version">/api/version</a> · POST <code>/api/echo</code></p>`
  );
});

server.listen(PORT, HOST, () => {
  console.log(`[node-container] listening on http://${HOST}:${PORT}`);
});
