# 容器底座规范（BASE_SPEC）

> 状态：草案 v0.2（修正内核模型：内核=控制面板/Manager，Agent 由运行时 npm 从公共源拉取）
> 定位：定义**冻结的容器**与**可热更新的内核**之间的契约。容器是地基，内核是产品。

---

## 1. 核心原则

1. **容器冻结**：APK 仅在 **Node 运行时版本变更** 或 **内置构建工具链版本变更** 时才重新编译。其余一切经热更新。
2. **约定优于实现**：容器不认识具体 Agent（Codex / Claude Code / DeepSeek Harness），只认**内核包契约**并提供**运行时 npm 环境**。
3. **两条热更新通道**：
   - 容器 → 内核（Manager）：**签名 OTA**（容器签内核）。
   - 内核 → Agent：**运行时 npm（公共源）**（npm 标准完整性校验 Agent）。
   - 两个信任根，互不替代。
4. **特权收口**：所有安卓危险操作必须经 HostBridge（Kotlin）执行。

---

## 2. 分层架构

| 层 | 名称 | 更新方式 | 冻结？ | 职责 |
|---|---|---|---|---|
| **L0** | 容器（APK） | 仅 Node/构建链版本变更才重编 | ✅ | Node 运行时 + **npm 客户端** + 内置 JDK/SDK build-tools + libc++_shared.so + HostBridge + OTA 引擎 + 生命周期 + 诊断 |
| **L1** | 内核 = 控制面板 / Manager | 容器签名 OTA 热更新 | ❌ | 控制面板代码 + `kernel.json`；运行在 Node 运行时内；**运行时经 npm 安装/升级/启停 Agent** |
| **L2** | Agent 产品 | 内核运行时 npm（公共源） | ❌ | Codex / Claude Code / DeepSeek Harness 等标准公共产品，由内核拉取管理 |
| **L3** | 能力桥 HostBridge（Kotlin） | 随 APK | ✅ | 版本化 RPC 方法表，把安卓能力交给内核 |

> "把手机变成工作台" = **Manager（控制面板，含工作台 UI）+ Agents（工作者）+ HostBridge（控制面）** 三者之和。Agent 是内核在运行时经 npm 拉取的，不随内核打包。

---

## 3. 运行时契约（容器侧，冻结）

| 项 | 值 |
|---|---|
| Node 版本 | `24.21.0`（arm64-v8a） |
| 平台 | android-35 |
| C++ 运行时 | 容器内 `libc++_shared.so`（native 模块必须链接它） |
| **npm 客户端** | **运行时可用**（供内核在设备内管理 Agent 产品） |
| 内置构建链 | JDK + Android build-tools（aapt2/d8/apksigner/zipalign）+ platform jar |
| 进程模型 | `NodeRuntimeService`（前台 `START_STICKY`）spawn 独立 `:node` 进程加载内核入口 |

容器在 `kernel.json` 中声明上述契约，内核可据此声明兼容性。

---

## 4. 内核包契约（Kernel Bundle Contract）

整个内核是一个目录，**只含控制面板（Manager）+ 清单**：

```
kernel/<version>/
  kernel.json          # 内核包清单（见下）
  manager/             # 控制面板代码（Node）
  node_modules/        # Manager 依赖（含已按固定 ABI 预编译的 .node）
```

> **不含 `agents/`**：Agent 产品由内核运行时从公共 npm 拉取，不打包进内核。

`kernel.json` schema：

```json
{
  "name": "dsh-kernel",
  "version": "1.4.0",
  "abi": "node24-arm64-android35",
  "engines": { "node": ">=24 <25" },
  "entry": "manager/index.js",
  "requires": [
    "bridge:app_control",
    "bridge:ui_automation",
    "bridge:device_policy",
    "bridge:build",
    "bridge:shell"
  ],
  "managedAgents": [
    { "id": "codex",    "pkg": "@openai/codex",              "version": "1.x", "requires": ["bridge:ui_automation","bridge:shell"] },
    { "id": "claude",   "pkg": "@anthropic-ai/claude-code",  "version": "1.x", "requires": ["bridge:ui_automation"] },
    { "id": "deepseek", "pkg": "<deepseek-harness-pkg>",     "version": "1.x", "requires": ["bridge:ui_automation"] }
  ],
  "signature": "<ed25519 over the manifest hash>"
}
```

- `managedAgents` 只声明"管理哪些、锁哪个版本、需要哪些能力"，**不打包产物**。
- `requires` 是控制面板本身所需能力；各 Agent 的能力在运行时由内核按 `managedAgents[].requires` 校验。

容器启动校验：1) 验签（内置公钥）→ 2) `engines.node` 比对固定运行时 → 3) `requires` 比对设备已预置能力 → 任一不符则**拒绝加载并回滚到上一个良好版本**。

---

## 5. 两条更新通道

### 通道一：容器 → 内核（签名 OTA）
**构建期（CI）**：
1. `npm ci` 解析 Manager 依赖；
2. 对含原生模块者，按固定 ABI（Node 24 / N-API / libc++_shared.so / 16KB 对齐）交叉预编译 `.node`；
3. 打包 `kernel/<version>/` 目录 → 用私钥签名 → 产出 `kernel-manifest.json`（版本/URL/sha256/签名）；
4. 发布到 CDN / Release。**npm 在此仅作构建期工具，用于产出内核包。**

**设备端 OTA 流程**：
```
轮询 kernel-manifest → 下载内核包 → 验签 + sha256
  → 原子解包到 files/kernel/<new-version>/ → 切换 CURRENT 指针（临时文件 rename）
  → 杀旧 :node 进程、spawn 新进程（Manager 引导）
```
- 原子性：先写新目录再切指针，失败不影响旧版本。
- 回滚：保留上一版本；新包启动健康检查失败 → 指针回退 + 重启。
- **坏包永不生效**：验签不过直接丢弃，绝不切换指针。

### 通道二：内核 → Agent（运行时 npm，公共源）
Manager 在运行时按 `managedAgents` 的 `pkg` + `version`，从**公共 npm registry** 执行 `npm install` 安装/升级 Agent；安装到内核可写目录，按 `bin`/`entry` 拉起。
- **不设私有源**；Agent 为标准规范公共产品，无特殊处理。
- 完整性依赖 **npm 内置 sha512 integrity**（安装即自动校验）。
- 版本由内核升级逻辑**锁定已知良好版本**，不盲目追 latest。
- 这是 "Agent 运行时"：内核读包、按升级逻辑更新、启停、编排。

---

## 6. Agent 信任模型（合理置信逻辑）

- **来源**：公共 npm registry。
- **身份**：确切包名 + 官方发布者（Codex / Claude Code / DeepSeek Harness 均为官方标准产品）。
- **完整性**：npm 内置 integrity（sha512），安装即验——即"完全可验证"，无需自建签名/镜像/白名单。
- **版本**：内核锁版本，升级逻辑决定何时升。
- **特殊处理**：无。

> 信任链：**容器签名内核（Manager）** → **内核按公共 npm 标准完整性拉 Agent**。不需要第三套签名体系。

---

## 7. 原生模块 ABI 契约

内核包内 `.node` 及 Agent 若带原生模块，必须满足（加载期校验）：
- ELF arch == `arm64`；
- 使用 **N-API**（保证 ABI 稳定）；
- `DT_NEEDED` 闭环，解析到容器提供的 `libnode.so` / `libc++_shared.so` / `libc.so` / `libdl.so`；
- **16KB 页对齐**（用 `readelf -W` 校验）；
- 签名校验（与内核包同签名体系；Agent 侧则由 npm integrity 保证）。

> 注：Codex / Claude Code / DeepSeek harness 基本为纯 JS CLI，原生编译通常非问题；个别 Agent 若带 `.node`，须按固定 ABI 预编译或由容器 node 构建链编译。

---

## 8. 安全模型

- **双信任根**：容器公钥签内核；npm 标准完整性验 Agent。
- **传输私有**：Agent↔HostBridge 走 **Unix 域套接字（UDS）**，文件权限绑定本 App UID，**不走 TCP**（当前 `127.0.0.1:3080` 在安卓上其他 App 可连，控制权面必须改 UDS）。
- **能力作用域**：Agent 仅能使用其 `requires` 声明且在设备已预置的能力；缺失能力 → 桥拒绝/降级。
- **审计**：所有经桥执行的特权操作（装卸应用、锁屏、shell、读屏）须落审计日志。

---

## 9. 生命周期（运行时）

```
App 启动 → NodeRuntimeService(START_STICKY) → 读 CURRENT 指针
  → 加载 kernel/<version>/manager/index.js（spawn :node）
  → Manager 读 kernel.json → 按 requires 连接 HostBridge(UDS)
  → Manager 按 managedAgents 在运行时经 npm 安装/拉起各 Agent
  → 健康检查（端口/探针/桥握手）
  → 进程退出或健康检查失败 → 退避重启（沿用 watchExit/pollPort）
```

`START_STICKY` 保活；内核包更新 = 重启 `:node` 进程（对用户是"热"的，无 APK 重编）。

---

## 10. 可观测性

复用 `RuntimeDiagnostics`，扩展：
- 内核包加载阶段（验签/解包/指针切换）逐阶段诊断；
- Agent 安装/升级日志（npm 输出）、Agent 运行日志桥接进诊断面板；
- 崩溃上报（exitCode + 桥审计摘要）。

---

## 11. 何时重编 APK（冻结边界）

仅以下情况重编并发布新 APK：
- Node 运行时版本升级（如 24.21.0 → 25.x）；
- 内置构建工具链（JDK / build-tools）版本升级；
- HostBridge 新增/修改能力方法（桥是冻结层）；
- npm 客户端自身需升级。

其余所有产品演进（控制面板逻辑、Agent 增改、能力参数调整）→ 走两条热更新通道。
