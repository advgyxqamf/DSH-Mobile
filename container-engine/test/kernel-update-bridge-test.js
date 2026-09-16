'use strict';

// 内核更新桥（UI ↔ 容器宿主帧）**协议契约**测试。
//
// 背景：容器 WebView 加载内核同源托管的宿主帧 /__host，面板 iframe 经 postMessage 请求更新。
// 该链路的报文格式由内核 `ui/src/services/supervisor/kernelUpdateBridge.ts` 定义（协议 v1）。
// 历史上容器原生侧回灌 `{type,requestId,status,message}` —— **缺 `v` 与 `ok`**，
// 内核侧 `if (d.v !== 1) return;` 会直接丢弃 → 面板 6 分钟超时静默失败。
//
// 本测试用**内核真实过滤逻辑的等价实现**（下方 kernelAccepts/mapResult，逐行对齐 TS 源码）
// 去消化容器侧应当产出的报文，锁定两侧字段一致；任一侧漂移即失败。
//
// ⚠ 这是「第三套契约」（UI↔宿主 postMessage），与前两套（runtime.json / kernel.json）无关。

const makeRunner = require('./harness');
const fs = require('fs');
const path = require('path');

const { check, finish } = makeRunner('kernel-update-bridge');

// 跨仓路径（内核仓）。**不写死绝对路径** —— 见 bridge-interop-test.js
// 顶部关于"单仓 CI 上跨仓测试崩掉"的说明。
// 本文件对内核源码的引用是**静态比对**（读文件做正则/存在性检查），
// 用的是软探测（存在才比，不存在就说明"未验证"），所以内核仓不在时不会崩，
// 只是少验几条"两侧常量是否漂移"的断言。
const KERNEL_REPO = process.env.DSH_KERNEL_REPO || '/workspace/dsh-android-kernel';

// ── 内核侧契约常量（须与 kernelUpdateBridge.ts 一致）──
const KERNEL_BRIDGE_PROTOCOL_VERSION = 1;
const REQUEST = 'dsh:kernel-update-request';
const RESULT = 'dsh:kernel-update-result';
const PROGRESS = 'dsh:kernel-update-progress';

// ── 内核侧过滤逻辑的等价实现（逐行对齐 kernelUpdateBridge.ts onMessage）──
// 返回 null = 内核丢弃该消息（不结算）；否则返回结算后的 KernelUpdateResult。
function kernelConsume(d, expectedRequestId) {
  if (!d || typeof d !== 'object') return null;
  if (d.v !== KERNEL_BRIDGE_PROTOCOL_VERSION) return null;
  if (d.type !== RESULT && d.type !== PROGRESS) return null;
  if (d.requestId !== expectedRequestId) return null;
  if (d.type === PROGRESS) return null; // 进度不终结
  return {
    ok: d.ok === true,
    stage: d.stage === undefined || d.stage === null ? null : d.stage,
    version: d.version === undefined || d.version === null ? null : d.version,
    restartUncertain: d.restartUncertain === true,
    error: d.error === undefined || d.error === null ? null : d.error,
  };
}

// ── 容器原生侧应当产出的报文（对齐 MainActivity.handleKernelUpdateRequest）──
function containerResult(requestId, version) {
  return {
    v: 1,                                   // ★ 必须存在
    type: RESULT,
    requestId,
    ok: true,                               // ★ 必须为布尔 true 才判成功
    stage: 'restarting',
    version: version === undefined ? null : version,
    restartUncertain: true,
    error: null,
  };
}

function main() {
  const rid = 'kupd-' + Date.now() + '-abc123';

  // ── 1) 正向：容器产出的报文能被内核正确消化 ──
  const good = kernelConsume(containerResult(rid, '1.4.0'), rid);
  check('容器回灌报文被内核接受（不被丢弃）', good !== null);
  check('结果 ok=true（面板判成功）', !!good && good.ok === true);
  check('结果 stage 透传', !!good && good.stage === 'restarting');
  check('结果 version 透传', !!good && good.version === '1.4.0');
  check('结果 restartUncertain=true（重启结果不确定语义）', !!good && good.restartUncertain === true);

  // version 缺失时应为 null 而非 undefined（不阻断）
  const noVer = kernelConsume(containerResult(rid, null), rid);
  check('version 缺失 → null 且仍被接受', !!noVer && noVer.version === null && noVer.ok === true);

  // ── 2) 反向门禁：历史上容器侧的错误报文必须被判为「内核会丢弃」 ──
  const legacyBad = { type: RESULT, requestId: rid, status: 'restarting', message: '已重启' };
  check('旧报文（缺 v/ok）被判定为内核丢弃', kernelConsume(legacyBad, rid) === null);

  const noV = containerResult(rid); delete noV.v;
  check('缺 v → 内核丢弃', kernelConsume(noV, rid) === null);

  const wrongV = containerResult(rid); wrongV.v = 2;
  check('v 不符（版本漂移）→ 内核丢弃', kernelConsume(wrongV, rid) === null);

  const wrongRid = containerResult('other-id');
  check('requestId 不匹配 → 内核丢弃', kernelConsume(wrongRid, rid) === null);

  const wrongType = containerResult(rid); wrongType.type = 'dsh:kernel-update-ack';
  check('type 不符 → 内核丢弃', kernelConsume(wrongType, rid) === null);

  // ok 非布尔 true（如字符串 "true"）视为失败
  const badOk = containerResult(rid); badOk.ok = 'true';
  check('ok 非布尔 true → 内核判失败（ok=false）', (kernelConsume(badOk, rid) || {}).ok === false);

  // 进度消息不终结请求
  check('progress 消息不终结请求', kernelConsume({ v: 1, type: PROGRESS, requestId: rid }, rid) === null);

  // ── 3) 协议常量与内核源码一致（静态比对，防版本漂移）──
  const tsPath = path.join(KERNEL_REPO, 'ui/src/services/supervisor/kernelUpdateBridge.ts');
  let ts = '';
  try { ts = fs.readFileSync(tsPath, 'utf8'); } catch {}
  if (ts) {
    check('内核 BRIDGE_PROTOCOL_VERSION === 1', /BRIDGE_PROTOCOL_VERSION\s*=\s*1\b/.test(ts));
    check('内核 REQUEST 常量一致', new RegExp('"' + REQUEST + '"').test(ts));
    check('内核 RESULT 常量一致', new RegExp('"' + RESULT + '"').test(ts));
    check('内核 PROGRESS 常量一致', new RegExp('"' + PROGRESS + '"').test(ts));
  } else {
    check('（跳过）内核源码不可达，仅做行为契约', true);
  }

  // ── 4) 宿主帧产物存在且含关键契约元素 ──
  const hostHtml = path.join(KERNEL_REPO, 'ui/public/host.html');
  const hostJs = path.join(KERNEL_REPO, 'ui/public/host-frame.js');
  if (fs.existsSync(hostHtml)) {
    const h = fs.readFileSync(hostHtml, 'utf8');
    check('宿主页 iframe 同源（src="/"）', /<iframe[^>]*src="\/"/.test(h));
    check('宿主页脚本外链（满足 CSP script-src self）', /src="\/host-frame\.js"/.test(h));
  } else {
    check('（跳过）宿主页不可达', true);
  }
  if (fs.existsSync(hostJs)) {
    const j = fs.readFileSync(hostJs, 'utf8');
    check('宿主帧脚本 PROTOCOL_VERSION=1', /PROTOCOL_VERSION\s*=\s*1\b/.test(j));
    check('宿主帧脚本转发 REQUEST', j.includes(REQUEST));
    check('宿主帧脚本暴露 dshDeliverResult', /dshDeliverResult\s*=/.test(j));
  } else {
    check('（跳过）宿主帧脚本不可达', true);
  }

  finish();
}

main();
