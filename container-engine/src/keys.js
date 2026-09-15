'use strict';

// 密钥锚点解析。
//  - 公钥：焊进 APK 的只读公钥（app/src/main/assets/ota-public.pem），设备端验签唯一信任源。
//  - 私钥：仅本地/CI 用于签名内核包（keys/ota-private.pem，gitignored）。
// 可用环境变量 DSH_OTA_PUBLIC_KEY_PATH / DSH_OTA_PRIVATE_KEY_PATH 覆盖（测试隔离用）。

const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const DEFAULT_PUBLIC_KEY_PATH = path.join(REPO_ROOT, 'app', 'src', 'main', 'assets', 'ota-public.pem');
const DEFAULT_PRIVATE_KEY_PATH = path.join(REPO_ROOT, 'keys', 'ota-private.pem');

function loadPublicKey(p) {
  const fp = p || process.env.DSH_OTA_PUBLIC_KEY_PATH || DEFAULT_PUBLIC_KEY_PATH;
  return fs.readFileSync(fp, 'utf8');
}

function loadPrivateKey(p) {
  const fp = p || process.env.DSH_OTA_PRIVATE_KEY_PATH || DEFAULT_PRIVATE_KEY_PATH;
  return fs.readFileSync(fp, 'utf8');
}

module.exports = { REPO_ROOT, DEFAULT_PUBLIC_KEY_PATH, DEFAULT_PRIVATE_KEY_PATH, loadPublicKey, loadPrivateKey };
