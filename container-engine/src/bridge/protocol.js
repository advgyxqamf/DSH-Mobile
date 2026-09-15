'use strict';

// HostBridge 协议编解码（JSON-RPC 2.0 + 握手/能力协商）。对齐 docs/BRIDGE_PROTOCOL.md。
// 传输无关的纯函数：传输层负责「按行分割 + 写 JSON」的帧封装（见 uds-transport.js）。

const PROTOCOL_VERSION = 1;

// 错误码（BRIDGE_PROTOCOL §5）：标准 JSON-RPC 区间 + 桥自定义区间（-32000 起）。
const ERROR_CODES = {
  PARSE_ERROR: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL_ERROR: -32603,
  ERR_RUNTIME: -32000,
  ERR_CAPABILITY_MISSING: -32001,
  ERR_TIMEOUT: -32002,
};

function request(id, method, params) {
  return { jsonrpc: '2.0', id, method, params: params || {} };
}
function response(id, result) {
  return { jsonrpc: '2.0', id, result };
}
function error(id, code, message, data) {
  const e = { jsonrpc: '2.0', id, error: { code, message } };
  if (data !== undefined) e.error.data = data;
  return e;
}
function notification(method, params) {
  return { jsonrpc: '2.0', method, params: params || {} };
}
function parse(str) {
  return JSON.parse(str);
}

// 握手：内核连接后主动发 bridge.handshake{ protocol, requires }
function handshakeRequest(id, requires) {
  return request(id, 'bridge.handshake', { protocol: PROTOCOL_VERSION, requires: requires || [] });
}

/**
 * 协商能力：requires（内核声明的 bridge:* 组令牌）与设备实际可用能力的交集/差集。
 * @returns {{granted: string[], missing: string[]}}
 */
function negotiateGroups(requires, availableGroups) {
  const granted = (requires || []).filter((r) => availableGroups.includes(r));
  const missing = (requires || []).filter((r) => !availableGroups.includes(r));
  return { granted, missing };
}

module.exports = {
  PROTOCOL_VERSION,
  ERROR_CODES,
  request,
  response,
  error,
  notification,
  parse,
  handshakeRequest,
  negotiateGroups,
};
