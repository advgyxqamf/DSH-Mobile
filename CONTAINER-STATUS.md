# 容器底座（L0）状态 — CONTAINER-STATUS

> 对齐用户指令：「容器底座最关键，不做完内核跑不起来」。本文档记录 L0 容器底座的完成度、验证方式与剩余缺口。
> 架构基线见 [`docs/BASE_SPEC.md`](docs/BASE_SPEC.md)（草案 v0.2）。

---

## 1. 结论

**L0 容器底座已完整落地并自测通过。** 内核（L1）现在能被真正拉起：容器写 `runtime.json`、注入 `DSH_ANDROID` 环境、spawn `node bin/dsh-supervisor daemon`、健康检查、退避重启；通道一（签名 OTA）端到端打通（签名→打包→验签→解包→原子指针切换→坏包拦截）；HostBridge 走 UDS 并实现 8 组方法 + 能力协商 + 审计；开机自启、Device Owner、无障碍三件套就位。

---

## 2. 已完成（M1–M5）

| 里程碑 | 内容 | 验证 |
|---|---|---|
| **M1 引擎核心** | `container-engine/src/`：zip / keys / sign / verify / kernel-bundle / ota-engine / runtime-json / boot | 35 passed |
| **M2 HostBridge 协议库** | `bridge/{protocol,methods,uds-transport,server}.js`：JSON-RPC 2.0 + UDS + 握手协商 + 错误码 + 审计 | 23 passed（bridge-protocol 14 / bridge-e2e 9） |
| **M2.5 内核↔容器桥互通** | 内核侧客户端（`dsh-android-kernel/src/platform/host-bridge/`）与容器参考桥**真实 UDS 互通**；`app.openUrl` 补齐（browser 承接方）；组级能力语义两侧收敛 | 14 passed（bridge-interop，跨仓） |
| **M3 Kotlin 安卓应用** | `MainActivity/NodeRuntimeService/HostBridgeService/KernelManager/BootReceiver/DeviceAdminReceiver/DshAccessibilityService` + Manifest 注册 + `host.html` 内核更新桥 | 代码评审（**沙箱无 Android SDK，未编译**） |
| **M4 脚本与 CI** | `scripts/build-kernel-bundle.sh` + `container-engine/bin/build-bundle.js` + `kernel-ota.yml` + `build-apk.yml` 公钥锚点校验 | 实测构建+验签闭环通过 |
| **M5 端到端 + 文档** | `e2e-mock-kernel-test.js`（真实 spawn 内核+健康检查）+ README/CONTAINER-STATUS 重写 | 8 passed |

### 关键闭环已实测（非仅代码存在）
- **签名 OTA 端到端**：`build-bundle.js` 用 ed25519 私钥签名 → 产出 `kernel-<v>.zip` + `kernel-manifest.json` → `OtaEngine.verifyPackage` 在焊死公钥下 `ok:true`；错误公钥 → `signature-invalid`；篡改字节 → `sha256-mismatch`；Node 引擎不符 → `node-engine-unsatisfied`。
- **内核真实拉起**：`e2e-mock-kernel-test.js` 由 `bootKernel` 真实 `spawn` 一个扮演 `dsh-supervisor` 的 node 进程，`/status` 健康检查通过，验证“容器→内核”接线成立。
- **内核↔容器桥真实互通**：`bridge-interop-test.js` 用**内核侧真实客户端**连**容器侧参考桥**（抽象命名空间 UDS），走完 连接→握手协商→8 组方法调用→能力门禁(-32001)→未知方法(-32601)→审计 全链路。
- **完整测试**：`npm test` 全绿 **80 passed, 0 failed**（8 套件）。

---

## 3. 已实现的能力（M3 Kotlin）

- NodeRuntimeService：读 `kernel/CURRENT` → 写 `runtime.json`（schema 2）→ 注入环境 → `spawn node bin/dsh-supervisor daemon` → `/status` 健康检查 → 退避重启监督循环。
- HostBridgeService：UDS 监听（抽象命名空间 `dsh_hostbridge`）、JSON-RPC 2.0、握手协商 `capabilities/groups`、8 组方法分发、能力门禁（`-32001`/`-32601`）、审计日志。
- 落地能力方法（设备已有对应权限时真实执行）：`sys.info`、`notif.post`、`app.listInstalled`、`app.launch`、`app.openUrl`（**内核 browser.open 的承接方**，ACTION_VIEW）、`app.stop`、以及 **Device Owner 全组**（`policy.lockNow/setPassword/wipe/setKiosk/addUserRestriction`、`sys.setTime/sys.reboot`、`app.install/uninstall/grantPermission`）。
- 组级能力语义：`GROUP_REQUIRED`（每组**代表能力**）判定「组是否可用」；特权方法另由**方法级 caps** 单独门禁 —— 与内核侧 `methods.js` 已逐条对齐（2026-09 收敛）。
- BootReceiver（开机自启）、DeviceAdminReceiver（Device Owner 激活）、DshAccessibilityService（无障碍声明）、MainActivity（诊断面板 + 内核 UI iframe + `dsh:kernel-update-request/result` 桥）。

---

## 4. 已知缺口 / 后续（不影响“内核能跑起来”）

这些是**能力增强**，不是“底座缺失”——地基已通，下面是墙和屋顶：

1. **ui_automation / shell / storage / build 方法组为能力门禁占位**：当前在未预置对应能力时返回 `-32001`（符合 spec 降级语义）。真实手势执行、Shizuku shell、MANAGE_EXTERNAL_STORAGE 文件访问、内置 APK 构建链需在集成分支填充（依赖无障碍/Shizuku/特殊权限的实际开启）。
2. **OTA 下发编排**：`OtaEngine` 已具备验签/解包/原子指针能力；设备上“轮询 manifest→下载→apply→回滚”的调度器由内核侧 bootstrap（Node）承接，本仓未内置一个独立 Kotlin OTA 调度器（按 BASE_SPEC §5，OTA 引擎逻辑归于内核引导）。
3. **基线内核 `assets/kernel/baseline.zip`**：`KernelManager.ensureBaseline` 已支持首启离线落地，但本仓未内置基线内核包（由 `kernel-ota.yml` 构建产出后纳入）。
4. **Kotlin 未编译验证**：沙箱无 Android SDK/NDK，M3 代码经人工评审与 API 正确性核对，未经 Gradle 编译；真实出包见 `build-apk.yml`（需公网 CI）。

---

## 5. 如何复现验证

```bash
# 容器引擎单测（无需 Android SDK）
cd container-engine && npm test        # 80 passed, 0 failed（8 套件）

# 单独跑内核↔容器桥互通（跨仓，需 dsh-android-kernel 在同级 /workspace）
node test/bridge-interop-test.js       # 14 passed, 0 failed

# 构建并签名一个内核 OTA 包（开发期需先 ./scripts/keygen.sh）
./scripts/build-kernel-bundle.sh <内核源码目录> 1.4.0 node24-arm64-android35 https://cdn.example.com/ota
# 产物：release/kernel-1.4.0.zip + release/kernel-manifest.json

# 出 APK（需公网 CI）
git push → GitHub Actions → build-apk.yml（自动编 Node 24 +  assembleDebug）
# 出内核 OTA（需配置 OTA_PRIVATE_KEY_PEM secret）
Actions → kernel-ota.yml → 产出签名内核包 / Release
```

> **跨仓联调提示**：内核侧（`dsh-android-kernel`）在自己的仓内跑 `npm test`（含 `test/host-bridge-test.js`，20 passed）；
> 两侧协议须逐字段一致。抽象命名空间 UDS 名默认 `dsh_hostbridge`，容器 `boot.js` 经 `DSH_BRIDGE_SOCKET` 注入内核。
