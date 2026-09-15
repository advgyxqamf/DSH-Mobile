'use strict';

// 签名/验签：ed25519 对 kernel.json 规范化摘要签名，正确公钥过、篡改/缺签/错钥不过。
const crypto = require('crypto');
const { signManifest, canonical } = require('../src/sign');
const { verifyManifest } = require('../src/verify');
const makeRunner = require('./harness');

const { check, finish } = makeRunner('sign-verify');

const kp = crypto.generateKeyPairSync('ed25519', {
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  publicKeyEncoding: { type: 'spki', format: 'pem' },
});

const kj = {
  name: 'dsh-kernel',
  version: '1.4.0',
  abi: 'node24-arm64-android35',
  engines: { node: '>=24 <25' },
  entry: 'bin/dsh-supervisor',
  requires: ['bridge:app_control'],
  managedAgents: [],
};
kj.signature = signManifest(kp.privateKey, kj);

check('签名生成非空', typeof kj.signature === 'string' && kj.signature.length > 0);
check('正确公钥验签通过', verifyManifest(kp.publicKey, kj, kj.signature) === true);
check('篡改 version 后验签失败', verifyManifest(kp.publicKey, { ...kj, version: '9.9.9' }, kj.signature) === false);
check('缺签名验签失败', verifyManifest(kp.publicKey, kj, '') === false);
check('错误公钥验签失败', (() => {
  const bad = crypto.generateKeyPairSync('ed25519', {
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    publicKeyEncoding: { type: 'spki', format: 'pem' },
  });
  return verifyManifest(bad.publicKey, kj, kj.signature) === false;
})());
check('规范化稳定：键序无关', canonical({ a: 1, b: 2 }) === canonical({ b: 2, a: 1 }));

finish();
