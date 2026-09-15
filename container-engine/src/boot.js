'use strict';

// 内核启动接线（容器侧）。
// 读 CURRENT 指针 → 写 runtime.json → 注入 Android 环境 → spawn `node <kernel>/bin/dsh-supervisor daemon`
//   → 经内核 /status 健康检查。
// 这是把「冻结 APK 容器」与「可热更新内核」真正连起来的最后一环。

const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');
const http = require('http');
const { writeRuntimeJson } = require('./runtime-json');

/**
 * 拉起内核。返回 { child, kernelDir, entry }。
 * @param {object} o
 *  - kernelHome: DSH_SUPERVISOR_HOME（应用沙箱根）
 *  - kernelVersion: CURRENT 指向的版本
 *  - nodeBin: node 可执行文件绝对路径
 *  - nodeBinDir: node 所在目录（注入 PATH 首位）
 *  - npmPath: npm 可执行绝对路径
 *  - apiPort: 内核控制面端口（健康检查用）
 *  - uiDir?: 面板产物目录（默认 <kernel>/ui/dist）
 *  - bridgeSocket?: HostBridge 抽象命名空间 socket 名（默认 dsh_hostbridge）
 *  - extraEnv?: 额外环境变量
 */
function bootKernel(o) {
  const kernelDir = path.join(o.kernelHome, 'kernel', o.kernelVersion);
  const entry = path.join(kernelDir, 'bin', 'dsh-supervisor');

  writeRuntimeJson({
    home: o.kernelHome,
    nodePath: o.nodeBin,
    nodeBinDir: o.nodeBinDir,
    npmPath: o.npmPath,
    minNode: 'v24.12.0',
    writtenBy: 'android-node-container',
  });

  const env = Object.assign({}, process.env, {
    DSH_ANDROID: '1',
    DSH_PLATFORM: 'android',
    DSH_SUPERVISOR_HOME: o.kernelHome,
    DSH_UI_DIR: o.uiDir || path.join(kernelDir, 'ui', 'dist'),
    // HostBridge 抽象命名空间 socket 名（内核侧 client.js 用 '\0'+name 连接，与 HostBridgeService.SOCKET_NAME 一致）。
    DSH_BRIDGE_SOCKET: o.bridgeSocket || 'dsh_hostbridge',
    PATH: [o.nodeBinDir, process.env.PATH].filter(Boolean).join(path.delimiter),
    NODE_PATH: path.join(kernelDir, 'node_modules'),
    HOME: o.kernelHome,
    TMPDIR: os.tmpdir(),
  }, o.extraEnv || {});

  const child = spawn(o.nodeBin, [entry, 'daemon'], {
    env, cwd: kernelDir, stdio: ['ignore', 'pipe', 'pipe'],
  });
  return { child, kernelDir, entry };
}

/** 轮询内核健康检查端口，直到 200 或超时。 */
function pollHealth({ host, port, healthPath, timeoutMs }) {
  host = host || '127.0.0.1';
  healthPath = healthPath || '/status';
  timeoutMs = timeoutMs || 30000;
  return new Promise((resolve) => {
    const deadline = Date.now() + timeoutMs;
    const tick = () => {
      const req = http.get({ host, port, path: healthPath, timeout: 800 }, (res) => {
        res.resume();
        resolve(true);
      });
      req.on('error', () => { if (Date.now() < deadline) setTimeout(tick, 500); else resolve(false); });
      req.on('timeout', () => { req.destroy(); if (Date.now() < deadline) setTimeout(tick, 500); else resolve(false); });
    };
    tick();
  });
}

module.exports = { bootKernel, pollHealth };
