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

### 3.6 build（内核安装 —— **不是**编译）
| 方法 | 参数 | 依赖 | 落地 |
|---|---|---|---|
| `build.kernelInstall` | `feed?` 或 `zipPath`/`sha256`/`version` | `kernel_update` | ✅ |
| `build.kernelStatus` | — | `kernel_update` | ✅ |
| `build.apk` | — | — | ⚠️ 废弃，返回带迁移指引的 `-32602` |
| `build.status` | — | `kernel_update` | ✅（旧名，保留兼容） |

> **语义已修正**：本组不再是「内置编译工具链」。那个方案**已实测证伪**——
> Google Maven 上没有 aarch64 版 aapt2（`linux-aarch64`/`linux-arm64` 均 404），
> 解包实为 x86-64 + glibc，exec 四道关的后三关装机后无法补救。
> 完整论证见 [ARCHITECTURE.md §2.2–2.3](ARCHITECTURE.md)。
>
> 现在的语义是「**从本地 feed 安装已签名内核**」：设备不生产内核，只安装。
> 全程离线，不需要网络、不需要 PC、不需要新原生二进制。
> 验签由 Node 侧完成（Android 要 API 33+ 才有 Ed25519，而 minSdk=24）。

**`build.kernelInstall` 请求 / 响应：**

```jsonc
// 方式一：扫本地 feed 目录并安装
{ "method": "build.kernelInstall", "params": { "feed": true } }

// 方式二：指定包路径（可附锚点）
{ "method": "build.kernelInstall",
  "params": { "zipPath": "/sdcard/dsh/downloads/k1.zip",
              "sha256": "e8586375...", "version": "0.1.0-android.1" } }
```

```jsonc
// 成功
{ "result": {
    "ok": true,
    "version": "0.1.0-android.1",
    "source": "本地文件 feed",
    "reason": null,
    "detail": "已落盘并切换指针: /data/.../files/kernel/0.1.0-android.1",
    "verifierOutput": "[verify] ed25519 验签通过\nDSH_VERIFY_RESULT {...}",
    "restartRequired": true
} }

// 失败（**不抛错，返回结构化结果** —— 便于调用方区分"可重试"与"包有问题"）
{ "result": {
    "ok": false,
    "version": null,
    "reason": "signature-invalid",
    "detail": "ed25519 验签未通过（公钥 /data/.../files/ota-public.pem）",
    "restartRequired": false
} }
```

> **失败绝不破坏现状**：校验在解包**之前**发生，失败时不碰任何已有文件。
> 最坏情况是"没升级成功"，而不是"把能跑的版本弄坏了"。
>
> **`restartRequired` 刻意由调用方处理**：本方法**不**自己重启进程 ——
> 重启会让调用方（内核自己）在半途消失，无法收到回执。

**feed 目录约定**（`LocalKernelFeed` 扫描顺序）：

| 优先级 | 路径 | 说明 |
|---|---|---|
| 1 | `<externalFilesDir>/kernel-feed/` | 应用专属外部目录，**无需任何权限**，保底可用 |
| 2 | `/sdcard/dsh/kernel-feed/` | 可直接 `adb push` / 文件管理器投递 |

目录内：`kernel-*.zip`（候选包，必须带 ed25519 签名）+ 可选 `kernel-manifest.json`（提供 sha256/version 锚点）。多个候选时取**文件名倒序**第一个，装成功后自动清理。

### 3.7 notification（通知）
| 方法 | 参数 | 依赖 | 落地 |
|---|---|---|---|
| `notif.read` | — | **Notification Access** | ⏳ |
| `notif.post` | `title`, `text` | 基础 | ✅ |

### 3.8 system（系统信息）
| 方法 | 参数 | 依赖 | 落地 |
|---|---|---|---|
| `sys.info` | — | 基础（设备/API level） | ✅ |
| `sys.nativeAssets` | `walkProbes?`（默认 `true`） | 基础（只读探测） | ✅ |
| `sys.setTime` / `sys.setTimeZone` / `sys.reboot` | 见 §3.4 | **Device Owner** | ✅ |

#### `sys.nativeAssets` —— 原生资产自检

W^X/exec 这条链的失败几乎全部发生在真机，而容器侧的诊断要么用户手动去翻
`diagnostics.txt`，要么只能看一句「启动失败」。本方法把
`NativePreparer.prepare()` 的**结构化结论**经桥暴露出来。

同一份实现被容器启动链复用，所以桥上的结论和诊断文件里的**永远不会漂移**。

**请求**
```json
{ "method": "sys.nativeAssets", "params": { "walkProbes": true } }
```
- `walkProbes: true`（默认）—— 真跑一次 exec-probe（会 spawn 进程）
- `walkProbes: false` —— 只做存在性 + 依赖检查，不 spawn

**响应**
```json
{
  "allRequiredReady": true,
  "nativeLibraryDir": "/data/app/~~x/pkg-y/lib/arm64-v8a",
  "libSearchPath": "/data/app/~~x/pkg-y/lib/arm64-v8a",
  "assets": [
    {
      "id": "node", "libName": "libnode.so", "humanName": "Node 运行时",
      "required": true, "requiredDeps": ["libc++_shared.so"],
      "note": "实为可执行文件，改名 lib*.so 借 jniLibs 通道落到 exec_type 目录",
      "status": "ready",
      "path": "/data/app/~~x/pkg-y/lib/arm64-v8a/libnode.so",
      "probeOutput": "v24.21.0"
    }
  ]
}
```

`status` 取值与对应的 `hint`（失败时携带）：

| `status` | 附加字段 | 含义 |
|---|---|---|
| `ready` | `path`, `probeOutput` | 就位且探针通过 |
| `missing_from_lib` | `inApk`, `libListing`, `hint` | 不在 `nativeLibraryDir`；`inApk` 区分「安装期未解压」vs「打包期就丢了」 |
| `missing_dependency` | `missingDep`, `libListing`, `hint` | 依赖 `.so` 不在同目录（**历史实现完全缺失这一层**） |
| `not_executable` | `errno`, `raw`, `hint` | 存在+依赖齐但 exec 被拒 → 可确定归因 SELinux W^X |
| `probe_failed` | `exit`, `output` | 进程起来了但退出码/输出不对 |

> 客户端应优先读 `allRequiredReady` 做快速判定，再按 `status` 决定给用户
> 什么引导 —— 这五种状态的修复动作完全不同，不应该被压成同一句「失败」。

---

## 4. 权限与降级

- Agent 的 `requires` 声明所需能力分组；桥按设备实际 `capabilities` 放行。
- 调用未授权方法 → 返回 `ERR_CAPABILITY_MISSING`，内核应优雅降级而非崩溃。
- 同一能力可能由多种预置机制满足（如 `ui.tap` 可由 Accessibility 或 Shizuku 提供）；桥内部择可用者执行。

## 5. 错误处理与审计

- 标准错误码：`ERR_CAPABILITY_MISSING` / `ERR_INVALID_PARAM` / `ERR_RUNTIME` / `ERR_TIMEOUT`。
- **所有特权操作（装卸应用、锁屏、shell、读屏、通知读取）必须写审计日志**：调用方 Agent、方法、参数摘要、结果、时间戳。
- 审计日志对内核包更新保持持久（不随内核包切换而丢）。
