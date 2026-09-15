# 能力契约：HostBridge 协议（BRIDGE_PROTOCOL）

> 状态：草案 v0.1 — 方法表为**第一版建议**，待产品确认增删。
> 这是真正的"能力补全清单"：Agent 经此协议控制安卓系统。

---

## 1. 传输

- **Unix 域套接字（UDS）**，路径位于 `Context.getFilesDir()` 下（如 `files/bridge.sock`），文件权限绑定本 App UID，**仅本应用进程可连**。
- 协议：**JSON-RPC 2.0**（请求/响应/通知）。
- 连接由 `:node` 进程（内核）主动发起；HostBridge（Kotlin Service）监听。
- 严禁经 TCP（`127.0.0.1:*`）暴露控制面。

## 2. 版本协商

- 连接建立后，内核发送 `handshake`，携 `protocol` 版本与 `requires` 能力清单。
- HostBridge 回 `capabilities`：设备实际已预置的能力集合（取决于 Device Owner / 无障碍 / Shizuku / 特殊权限的开启状态）。
- 内核 `requires` 超出 `capabilities` → 桥拒绝对应方法调用，其余正常。

## 3. 能力分组与方法表（第一版）

> 每个方法标注其依赖的**设备预置能力**（见 PROVISIONING.md）。缺失则调用返回 `ERR_CAPABILITY_MISSING`。
> 「落地」列标出实现状态：✅ 真实实现 / ⚠️ 兜底实现 / ⏳ 未实现（返回 `-32001`）。

### 3.1 app_control（应用控制）
| 方法 | 参数 | 依赖 | 落地 |
|---|---|---|---|
| `app.launch` | `pkg`, `activity?` | 基础 | ✅ |
| `app.stop` | `pkg` | 基础 | ✅（`audit=false`，按 §5） |
| `app.listInstalled` | — | 基础 | ✅ |
| `app.install` | `apkPath`（包内/下载） | **Device Owner**（静默安装） | ✅ `PackageInstaller` |
| `app.uninstall` | `pkg` | **Device Owner**（静默卸载） | ✅ `PackageInstaller.uninstall` |
| `app.grantPermission` | `pkg`, `perm` | **Device Owner** | ✅ |

> ⚠ 装箱注意：`DevicePolicyManager` **没有** `installPackage` / `uninstallPackage` 方法 ——
> 静默装卸只能走 `PackageInstaller`（`createSession` → `openWrite` → `commit`），
> 异步结果经 `PendingIntent` 回 `PackageInstallReceiver`。

### 3.2 ui_automation（UI 自动化 / 无人值守操作）
| 方法 | 参数 | 依赖 | 落地 |
|---|---|---|---|
| `ui.tap` | `x`, `y`, `durationMs?` | **Accessibility** | ✅ `dispatchGesture` |
| `ui.swipe` | `x1,y1,x2,y2,durationMs?` | 同上 | ✅ 同上 |
| `ui.inputText` | `text`, `selector?` | 同上 | ✅ 三级降级（SET_TEXT → FOCUS+PASTE） |
| `ui.getUiTree` | `maxNodes?`, `maxDepth?` | **Accessibility**（节点树） | ✅ `getWindows` + `rootInActiveWindow` 合并 |
| `ui.screenshot` | `width?`, `height?`, `inline?` | **MediaProjection** | ✅ 默认落盘 PNG；`inline=true` 内联 base64 |
| `ui.waitFor` | `selector`, `timeoutMs?`, `intervalMs?` | Accessibility | ✅ 条件轮询 |

> **`ui.screenshot` 的授权语义与 Device Owner 本质不同**：MediaProjection 授权是**每次会话**的，
> 必须由用户在系统弹窗点一次「开始录制」，**无法预置**。授权结果缓存于
> `files/screen-capture-grant.json`（Intent 的 Parcel 字节流 + base64），进程重启后自动复用。
> 未授权时返回 `-32001` 并附「需先在 App 内授权」的指引。
>
> **`ui.getUiTree` 取的是全窗口**（`getWindows()` 优先），因此 IME / 悬浮窗的节点也能拿到 ——
> 仅靠 `getRootInActiveWindow()` 会漏掉这两类。

### 3.3 shell（Shell 级命令）
| 方法 | 参数 | 依赖 | 落地 |
|---|---|---|---|
| `shell.exec` | `cmd`, `args?`, `timeoutMs?` | **Shizuku / 无线调试**（shizuku） | ⚠️ 应用 uid 兜底 |

> **兜底实现语义（P4 决策）**：容器**未内置 Shizuku SDK**（不引入第三方 AAR，以免污染
> 冻结容器的信任边界）。当前 `shell.exec` 以**应用 uid** 通过 `ProcessBuilder` 执行，
> 返回体显式带 `privileged:false` + `note` —— **不冒充 shell uid(2000)**。
> 应用 uid 下 `getprop` / `pm list` / `am`（部分）等只读命令可用；`input` / `settings put` 等需特权。
>
> `shizuku` 仍是**方法级 caps**：设备未装 Shizuku 时调用返回 `-32001`（组级门禁）。
> 若希望"无 Shizuku 也能用兜底"，需把 caps 放宽到组代表能力 —— 待产品决策。
>
> 实现细节：读线程 pump 与 `waitFor` 并行（防管道写满死锁）；超时 `destroyForcibly()` + 抛 `ERR_TIMEOUT`；
> 输出截断 256 KB。

### 3.4 device_policy（系统策略，Device Owner）
| 方法 | 参数 | 依赖 | 落地 |
|---|---|---|---|
| `policy.setPassword` | `pwd`, `type` | **Device Owner** | ⚠️ API 30 起废弃，多数设备不生效 |
| `policy.lockNow` | — | **Device Owner** | ✅ |
| `policy.wipe` | `flags?`, `reason?` | **Device Owner** | ✅ API 29+ 走 `wipeData(flags, reason)` |
| `policy.setKiosk` | `pkg` / `packages[]`, `enable` | **Device Owner** | ✅ API 34+ 补 `setLockTaskFeatures` |
| `policy.addUserRestriction` | `key` | **Device Owner** | ✅ |
| `sys.setTime` | `epochMs` | **Device Owner** | ✅ 需 `AUTO_TIME=0`（API 28+） |
| `sys.setTimeZone` | `timeZone`（Olson ID） | **Device Owner** | ✅ 需 `AUTO_TIME_ZONE=0`（API 28+） |
| `sys.reboot` | — | **Device Owner** | ✅ 单参 `reboot(ComponentName)` |

> ⚠ 装箱注意：`dpm.reboot` 在 android.jar 里**只有单参版本**（两参版是桌面 Java 的）。
> `setTime` / `setTimeZone` 是 **API 28+**，且必须先关自动时间/时区，否则静默无效。

### 3.5 storage（存储）
| 方法 | 参数 | 依赖 | 落地 |
|---|---|---|---|
| `fs.read` | `path`, `encoding?`(auto/base64), `maxBytes?` | `MANAGE_EXTERNAL_STORAGE` | ✅ |
| `fs.write` | `path`, `content`, `encoding?`(utf8/base64), `append?` | 同上 | ✅ |
| `fs.list` | `path?`, `recursive?`, `maxEntries?` | 同上 | ✅ |
| `fs.mkdir` | `path` | 同上 | ✅ |

> **访问范围：全放开**（有 `MANAGE_EXTERNAL_STORAGE` 即通行），不做白名单限制 —— 这是显式选择。
> 但保留**审计留痕**与**危险路径提示**（`/dev/*`、`/proc|/sys/*`、`/system|/vendor|/boot`、`/`）：
> 提示经返回体的 `hint` 字段回传，**不拦截**。
>
> `fs.read` 的 `encoding: "auto"` 会做 **UTF-8 无损性校验**（`text.toByteArray(UTF_8).contentEquals(bytes)`），
> 不无损则自动退 base64 并加 `note` —— 避免二进制文件被静默损坏。
> `maxBytes` 默认 8 MB，硬顶 64 MB（与 JSON-RPC 帧模型匹配）。

### 3.6 build（设备内编译 APK）
| 方法 | 参数 | 依赖 | 落地 |
|---|---|---|---|
| `build.apk` | `projectDir`, `opts` | 内置构建链（JDK+aapt2/d8/apksigner） | ⏳ P3 待决策 |
| `build.status` | `id` | 同上 | ⏳ 同上 |

> 由 HostBridge 或 build-agent 编排内置工具链完成编译；产物可经 `app.install` 静默安装（DO）。
> **待决策**：全内置（APK 体积暴涨）vs 首启下载（需网络，破坏离线可用性）vs 最小 build-tools 子集。

### 3.7 notification（通知）
| 方法 | 参数 | 依赖 | 落地 |
|---|---|---|---|
| `notif.read` | — | **Notification Access** | ⏳ |
| `notif.post` | `title`, `text` | 基础 | ✅ |

### 3.8 system（系统信息）
| 方法 | 参数 | 依赖 | 落地 |
|---|---|---|---|
| `sys.info` | — | 基础（设备/API level） | ✅ |
| `sys.setTime` / `sys.setTimeZone` / `sys.reboot` | 见 §3.4 | **Device Owner** | ✅ |

---

## 4. 权限与降级

- Agent 的 `requires` 声明所需能力分组；桥按设备实际 `capabilities` 放行。
- 调用未授权方法 → 返回 `ERR_CAPABILITY_MISSING`，内核应优雅降级而非崩溃。
- 同一能力可能由多种预置机制满足（如 `ui.tap` 可由 Accessibility 或 Shizuku 提供）；桥内部择可用者执行。

## 5. 错误处理与审计

- 标准错误码：`ERR_CAPABILITY_MISSING` / `ERR_INVALID_PARAM` / `ERR_RUNTIME` / `ERR_TIMEOUT`。
- **所有特权操作（装卸应用、锁屏、shell、读屏、通知读取）必须写审计日志**：调用方 Agent、方法、参数摘要、结果、时间戳。
- 审计日志对内核包更新保持持久（不随内核包切换而丢）。
