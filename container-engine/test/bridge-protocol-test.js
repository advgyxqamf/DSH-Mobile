'use strict';

// HostBridge 协议层单测：编解码、握手协商、错误码、方法能力表。
const proto = require('../src/bridge/protocol');
const methods = require('../src/bridge/methods');
const makeRunner = require('./harness');

const { check, finish } = makeRunner('bridge-protocol');

const hs = proto.handshakeRequest(1, ['bridge:app_control']);
check('handshake 请求形态', hs.method === 'bridge.handshake' && hs.params.protocol === 1 && Array.isArray(hs.params.requires));
check('negotiateGroups 交集/差集',
  JSON.stringify(proto.negotiateGroups(['bridge:app_control', 'bridge:shell'], ['bridge:app_control'])) ===
  JSON.stringify({ granted: ['bridge:app_control'], missing: ['bridge:shell'] }));
check('error 结构含 code/message', proto.error(5, proto.ERROR_CODES.ERR_CAPABILITY_MISSING, 'x').error.code === -32001);
check('ERR_CAPABILITY_MISSING = -32001', proto.ERROR_CODES.ERR_CAPABILITY_MISSING === -32001);
check('ERR_TIMEOUT = -32002', proto.ERROR_CODES.ERR_TIMEOUT === -32002);

check('methodCaps app.install = [device_owner]', JSON.stringify(methods.methodCaps('app.install')) === JSON.stringify(['device_owner']));
check('missingCaps app.install 缺 device_owner', JSON.stringify(methods.missingCaps('app.install', ['base'])) === JSON.stringify(['device_owner']));
const npMiss = methods.missingCaps('notif.post', ['base']);
check('notif.post 仅需 base → 满足', npMiss !== null && npMiss.length === 0);
check('未知方法 methodCaps = null', methods.methodCaps('nope.nope') === null);
check('isAudited app.install = true', methods.isAudited('app.install') === true);
check('notif.post 不审计', methods.isAudited('notif.post') === false);
check('BRIDGE_TOKENS 含 8 组', methods.BRIDGE_TOKENS.length === 8);
check('DEVICE_CAPS 含 device_owner/accessibility/shizuku', ['device_owner', 'accessibility', 'shizuku'].every((c) => methods.DEVICE_CAPS.includes(c)));

const round = JSON.parse(JSON.stringify(proto.response(2, { ok: true })));
check('response 往返 id 保持', round.id === 2 && round.result.ok === true);

finish();
