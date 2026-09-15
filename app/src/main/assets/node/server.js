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

// ---------------------------------------------------------------------------
// 端口解析 —— 这里踩过一个很隐蔽的坑，注释留着防止有人改回去。
//
// 最初的写法是一行：
//     const PORT = parseInt(process.argv[2] || '3080', 10);
//
// 而 Android 侧（NodeRuntimeService.kt）是这么拉起来的：
//     ProcessBuilder(nodeBin, script, "--port", "3080")
// 于是进程里实际的 argv 是：
//     [0]=libnode.so  [1]=server.js  [2]="--port"  [3]="3080"
// 也就是说 argv[2] 拿到的是字符串 "--port"，而不是端口号。
//
// parseInt('--port', 10) 的结果是 NaN（不是抛错，是安静地返回 NaN）。
// 接着 server.listen(NaN, ...) 才抛：
//     RangeError [ERR_SOCKET_BAD_PORT]: options.port should be >= 0 and < 65536.
//     Received type number (NaN).
// 进程随即以 exitCode=1 退出 —— 表现是「Node 启动瞬间就死」，看起来
// 像是二进制有问题，实际纯粹是参数解析 bug，跟 Node 本身毫无关系。
//
// 所以这里按标志位解析，并同时兼容三种传法：
//     node server.js --port 3080     ← App 现在用的
//     node server.js 3080            ← 位置参数（老写法）
//     node server.js                 ← 什么都不传，用默认值
// 解析不出来就明确报错退出，绝不让 NaN 流到 listen() 里去。
// ---------------------------------------------------------------------------
const DEFAULT_PORT = 3080;

function resolvePort(argv) {
  // ① 优先找 --port <n> 标志（App 实际使用的形式）
  const i = argv.indexOf('--port');
  if (i > -1 && argv[i + 1] !== undefined) return argv[i + 1];

  // ② 退而求其次，接受第一个纯数字的位置参数
  for (let k = 2; k < argv.length; k++) {
    if (/^\d+$/.test(argv[k])) return argv[k];
  }

  // ③ 都没有就用默认值
  return String(DEFAULT_PORT);
}

const PORT = parseInt(resolvePort(process.argv), 10);
const HOST = '127.0.0.1'; // 只监听回环，避免暴露到局域网

// 参数校验：宁可在这里显式报错并给出可读原因，也不要让 NaN 一路
// 流到 server.listen() 去触发一个难以定位的 RangeError。
if (!Number.isInteger(PORT) || PORT <= 0 || PORT >= 65536) {
  console.error(
    `[node-container] 端口无效: ${JSON.stringify(process.argv)}\n` +
    `  期望形式: --port <1-65535>  或  直接给数字\n` +
    `  解析结果: ${PORT}`
  );
  process.exit(2);
}

// 把 argv 打出来（走 stdout 而非 stderr）—— 万一将来又出参数问题，
// 这一行能让人一眼看到进程到底收到了什么，不用再靠猜。
console.log('[node-container] argv =', JSON.stringify(process.argv));
console.log(`[node-container] node ${process.version} / ${process.platform}-${process.arch}`);

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
