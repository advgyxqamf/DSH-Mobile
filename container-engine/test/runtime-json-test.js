'use strict';

// runtime.json：容器写入、内核读取；schema 必须为 2 且与内核契约字段一致。
const fs = require('fs');
const os = require('os');
const path = require('path');
const { writeRuntimeJson, readRuntimeJson, SCHEMA } = require('../src/runtime-json');
const makeRunner = require('./harness');

const { check, finish } = makeRunner('runtime-json');

const home = fs.mkdtempSync(path.join(os.tmpdir(), 'rt-'));
const obj = writeRuntimeJson({
  home,
  nodePath: '/data/app/node/24.21.0/node',
  nodeBinDir: '/data/app/node/24.21.0',
  npmPath: '/data/app/node/24.21.0/npm',
  minNode: 'v24.12.0',
});

check('schema = 2（对齐内核 SUPPORTED_SCHEMA）', obj.schema === 2 && SCHEMA === 2);
check('含 nodePath', obj.nodePath === '/data/app/node/24.21.0/node');
check('含 nodeBinDir', obj.nodeBinDir === '/data/app/node/24.21.0');
check('含 npmPath', obj.npmPath === '/data/app/node/24.21.0/npm');
check('含 minNode', obj.minNode === 'v24.12.0');
check('含 writtenBy', obj.writtenBy === 'android-node-container');

const read = readRuntimeJson(home);
check('读回 object 与写入一致', read && read.schema === 2 && read.nodePath === obj.nodePath);

// schema 不符抛错
const badPath = path.join(home, 'supervisor', 'runtime.json');
fs.writeFileSync(badPath, JSON.stringify({ schema: 99, nodePath: 'x' }));
let threw = false;
try { readRuntimeJson(home); } catch (_e) { threw = true; }
check('schema 不符时抛出', threw === true);

finish();
