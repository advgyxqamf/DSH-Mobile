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

### 3.1 app_control（应用控制）
| 方法 | 参数 | 依赖 |
|---|---|---|
| `app.launch` | `pkg`, `activity?` | 基础 |
| `app.stop` | `pkg` | 基础 |
| `app.listInstalled` | — | 基础 |
| `app.install` | `apkPath`（包内/下载） | **Device Owner**（静默安装） |
| `app.uninstall` | `pkg` | **Device Owner**（静默卸载） |
| `app.grantPermission` | `pkg`, `perm` | **Device Owner** |

### 3.2 ui_automation（UI 自动化 / 无人值守操作）
| 方法 | 参数 | 依赖 |
|---|---|---|
| `ui.tap` | `x`, `y` | **Accessibility** / Shizuku `input` |
| `ui.swipe` | `x1,y1,x2,y2,dur` | 同上 |
| `ui.inputText` | `text` | 同上 |
| `ui.getUiTree` | — | **Accessibility**（节点树） |
| `ui.screenshot` | — | **MediaProjection** |
| `ui.waitFor` | `condition`, `timeout` | Accessibility |

### 3.3 shell（Shell 级命令）
| 方法 | 参数 | 依赖 |
|---|---|---|
| `shell.exec` | `cmd` | **Shizuku / 无线调试**（shell uid 2000） |

> 用于 `input` / `am` / `pm` / `settings` 等兜底操控。

### 3.4 device_policy（系统策略，Device Owner）
| 方法 | 参数 | 依赖 |
|---|---|---|
| `policy.setPassword` | `pwd`, `type` | **Device Owner** |
| `policy.lockNow` | — | **Device Owner** |
| `policy.wipe` | `flags?` | **Device Owner** |
| `policy.setKiosk` | `pkg` | **Device Owner** |
| `policy.addUserRestriction` | `key` | **Device Owner** |

### 3.5 storage（存储）
| 方法 | 参数 | 依赖 |
|---|---|---|
| `fs.read` / `fs.write` / `fs.list` / `fs.mkdir` | 路径相关 | `MANAGE_EXTERNAL_STORAGE`（全量文件访问） |

### 3.6 build（设备内编译 APK）
| 方法 | 参数 | 依赖 |
|---|---|---|
| `build.apk` | `projectDir`, `opts` | 内置构建链（JDK+aapt2/d8/apksigner） |
| `build.status` | `id` | 同上 |

> 由 HostBridge 或 build-agent 编排内置工具链完成编译；产物可经 `app.install` 静默安装（DO）。

### 3.7 notification（通知）
| 方法 | 参数 | 依赖 |
|---|---|---|
| `notif.read` | — | **Notification Access** |
| `notif.post` | `title`, `text` | 基础 |

### 3.8 system（系统信息）
| 方法 | 参数 | 依赖 |
|---|---|---|
| `sys.info` | — | 基础（设备/API level） |
| `sys.setTime` / `sys.reboot` | — | **Device Owner** |

---

## 4. 权限与降级

- Agent 的 `requires` 声明所需能力分组；桥按设备实际 `capabilities` 放行。
- 调用未授权方法 → 返回 `ERR_CAPABILITY_MISSING`，内核应优雅降级而非崩溃。
- 同一能力可能由多种预置机制满足（如 `ui.tap` 可由 Accessibility 或 Shizuku 提供）；桥内部择可用者执行。

## 5. 错误处理与审计

- 标准错误码：`ERR_CAPABILITY_MISSING` / `ERR_INVALID_PARAM` / `ERR_RUNTIME` / `ERR_TIMEOUT`。
- **所有特权操作（装卸应用、锁屏、shell、读屏、通知读取）必须写审计日志**：调用方 Agent、方法、参数摘要、结果、时间戳。
- 审计日志对内核包更新保持持久（不随内核包切换而丢）。
