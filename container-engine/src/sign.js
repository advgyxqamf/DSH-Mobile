'use strict';

// 内核包签名（ed25519）。
// 签名对象 = kernel.json 去掉 signature 字段后的「规范化 JSON」摘要。
// 规范化保证：① 去掉 signature 自身；② key 排序；③ 稳定字符串 —— 验签侧必须复用同一算法。

const crypto = require('crypto');

/** 规范化：剔除 signature 字段、按 key 排序的 JSON 字符串。 */
function canonical(obj) {
  const { signature, ...rest } = obj;
  return JSON.stringify(rest, Object.keys(rest).sort());
}

/**
 * 对 kernel.json 签名。
 * @param {string} privateKeyPem PKCS8 PEM（ed25519）
 * @param {object} kernelJson 含全部字段（signature 会被忽略后再签）
 * @returns {string} base64 签名
 */
function signManifest(privateKeyPem, kernelJson) {
  const data = Buffer.from(canonical(kernelJson), 'utf8');
  // ed25519：algorithm 传 null，直接对原始数据签名（不额外预哈希）
  const sig = crypto.sign(null, data, privateKeyPem);
  return sig.toString('base64');
}

module.exports = { canonical, signManifest };
