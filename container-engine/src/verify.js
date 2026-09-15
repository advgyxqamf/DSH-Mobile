'use strict';

// 内核包验签 + 完整性校验（ed25519 + sha256）。
// 双信任根之一：容器私钥签内核，公钥焊进 APK。坏包永不生效。

const crypto = require('crypto');
const { canonical } = require('./sign');

/**
 * 验签单个 kernel.json。
 * @returns {boolean}
 */
function verifyManifest(publicKeyPem, kernelJson, signatureB64) {
  if (!signatureB64) return false;
  try {
    const data = Buffer.from(canonical(kernelJson), 'utf8');
    const sig = Buffer.from(signatureB64, 'base64');
    return crypto.verify(null, data, publicKeyPem, sig);
  } catch (_e) {
    return false;
  }
}

function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

module.exports = { verifyManifest, sha256 };
