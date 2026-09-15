'use strict';

// HostBridge 8 组方法表与能力声明（对齐 BRIDGE_PROTOCOL.md §3 + PROVISIONING.md）。
//
// 两层能力：
//  1) bridgeGroup：内核 kernel.json 的 requires 使用的「组令牌」bridge:<group>。
//  2) deviceCaps：方法实际依赖的「设备预置能力」（device_owner / accessibility / shizuku / …），
//     由 PROVISIONING.md 决定设备是否具备。缺失 → 桥返回 ERR_CAPABILITY_MISSING。
// audit=true 的方法属 BRIDGE_PROTOCOL §5 强制审计的特权操作。

const GROUPS = ['app_control', 'ui_automation', 'shell', 'device_policy', 'storage', 'build', 'notification', 'system'];
const BRIDGE_TOKENS = GROUPS.map((g) => 'bridge:' + g);

// 设备预置能力（PROVISIONING.md 权限栈）
const DEVICE_CAPS = [
  'base',                     // 容器 App 基础能力（始终可用）
  'device_owner',             // Device Owner (DPC)
  'accessibility',            // AccessibilityService
  'shizuku',                  // Shizuku / 无线调试
  'mediaprojection',          // MediaProjection
  'manage_external_storage',  // MANAGE_EXTERNAL_STORAGE
  'notification_access',      // 通知访问
  'build_chain',              // 内置 JDK/build-tools
];

// method → { group, caps:[deviceCap...], audit?:bool }
const METHODS = {
  'app.launch':            { group: 'app_control', caps: ['base'] },
  'app.openUrl':           { group: 'app_control', caps: ['base'] },
  'app.stop':              { group: 'app_control', caps: ['base'] },
  'app.listInstalled':     { group: 'app_control', caps: ['base'] },
  'app.install':           { group: 'app_control', caps: ['device_owner'], audit: true },
  'app.uninstall':         { group: 'app_control', caps: ['device_owner'], audit: true },
  'app.grantPermission':   { group: 'app_control', caps: ['device_owner'], audit: true },

  'ui.tap':               { group: 'ui_automation', caps: ['accessibility'] },
  'ui.swipe':             { group: 'ui_automation', caps: ['accessibility'] },
  'ui.inputText':         { group: 'ui_automation', caps: ['accessibility'] },
  'ui.getUiTree':         { group: 'ui_automation', caps: ['accessibility'] },
  'ui.screenshot':        { group: 'ui_automation', caps: ['mediaprojection'], audit: true },
  'ui.waitFor':           { group: 'ui_automation', caps: ['accessibility'] },

  'shell.exec':           { group: 'shell', caps: ['shizuku'], audit: true },

  'policy.setPassword':    { group: 'device_policy', caps: ['device_owner'], audit: true },
  'policy.lockNow':       { group: 'device_policy', caps: ['device_owner'], audit: true },
  'policy.wipe':          { group: 'device_policy', caps: ['device_owner'], audit: true },
  'policy.setKiosk':      { group: 'device_policy', caps: ['device_owner'], audit: true },
  'policy.addUserRestriction': { group: 'device_policy', caps: ['device_owner'], audit: true },

  'fs.read':              { group: 'storage', caps: ['manage_external_storage'] },
  'fs.write':             { group: 'storage', caps: ['manage_external_storage'], audit: true },
  'fs.list':              { group: 'storage', caps: ['manage_external_storage'] },
  'fs.mkdir':             { group: 'storage', caps: ['manage_external_storage'], audit: true },

  'build.apk':            { group: 'build', caps: ['build_chain'], audit: true },
  'build.status':         { group: 'build', caps: ['build_chain'] },

  'notif.read':           { group: 'notification', caps: ['notification_access'], audit: true },
  'notif.post':           { group: 'notification', caps: ['base'] },

  'sys.info':             { group: 'system', caps: ['base'] },
  'sys.setTime':          { group: 'system', caps: ['device_owner'], audit: true },
  'sys.setTimeZone':      { group: 'system', caps: ['device_owner'], audit: true },
  'sys.reboot':           { group: 'system', caps: ['device_owner'], audit: true },
};

/** 方法所需设备能力。未知方法返回 null（调用方据此报 METHOD_NOT_FOUND）。 */
function methodCaps(method) {
  return METHODS[method] ? METHODS[method].caps : null;
}

/** 方法是否强制审计。 */
function isAudited(method) {
  return !!(METHODS[method] && METHODS[method].audit);
}

/** 设备能力是否满足方法要求；返回缺失列表（空=满足）。 */
function missingCaps(method, availableCaps) {
  const caps = methodCaps(method);
  if (!caps) return null; // 未知方法
  return caps.filter((c) => !availableCaps.includes(c));
}

// 每组的**代表能力**：握手时判定「该组是否可用」。
//
// ⚠ 语义（与 Kotlin HostBridgeService.GROUP_REQUIRED 逐条对齐，2026-09 收敛）：
//   组可用 = 该组的**代表性基础能力**具备，而非「组内每个方法的能力都具备」。
//   例：bridge:app_control 的代表能力是 base —— 否则「启动已装应用」会被 Device Owner 门槛误挡，
//   而 app.install/uninstall 这类特权方法本就由**方法级 caps**单独门禁（调用时再报 -32001）。
//   两层门禁：组级（握手协商，粗粒度可用性）+ 方法级（每次调用，精确拦截）。
const GROUP_REQUIRED = {
  'app_control': 'base',
  'notification': 'base',
  'system': 'base',
  'device_policy': 'device_owner',
  'ui_automation': 'accessibility',
  'shell': 'shizuku',
  'storage': 'manage_external_storage',
  'build': 'build_chain',
};

/** bridge:* 组令牌 → 该组的代表能力（用于握手协商「组是否可用」）。 */
function groupCaps(group) {
  const rep = GROUP_REQUIRED[group];
  return rep ? [rep] : [];
}

/** 该组全部方法依赖的设备能力并集（供文档/诊断呈现；不用于握手判定）。 */
function groupAllCaps(group) {
  const out = new Set();
  for (const [m, def] of Object.entries(METHODS)) {
    if (def.group === group) def.caps.forEach((c) => out.add(c));
  }
  return [...out];
}

module.exports = {
  GROUPS, BRIDGE_TOKENS, DEVICE_CAPS, METHODS, GROUP_REQUIRED,
  methodCaps, isAudited, missingCaps, groupCaps, groupAllCaps,
};
