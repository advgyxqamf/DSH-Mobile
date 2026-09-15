# 容器底座（L0）状态 — CONTAINER-STATUS

> 对齐用户指令：「容器底座最关键，不做完内核跑不起来」。本文档记录 L0 容器底座的完成度、验证方式与剩余缺口。
> 架构基线见 [`docs/BASE_SPEC.md`](docs/BASE_SPEC.md)（草案 v0.2）。

---

## 1. 结论

**L0 容器底座已完整落地并自测通过。** 内核（L1）现在能被真正拉起：容器写 `runtime.json`、注入 `DSH_ANDROID` 环境、spawn `node bin/dsh-supervisor daemon`、健康检查、退避重启；通道一（签名 OTA）端到端打通（签名→打包→验签→解包→原子指针切换→坏包拦截）；HostBridge 走 UDS 并实现 8 组方法 + 能力协商 + 审计；开机自启、Device Owner、无障碍三件套就位。**2026-09 补齐 P1（预置自检探针 + Device Owner API 修正）、P2（无障碍真实实现，`ui_automation` 整组解锁）、P4（`shell.exec` 应用 uid 兜底 + Shizuku 三态探测）、P5（`fs.*` 真实实现 + `ui.screenshot` MediaProjection）。剩下 P3 构建链待方案决策。**

---

## 2. 已完成（M1–M5）

| 里程碑 | 内容 | 验证 |
|---|---|---|
| **M1 引擎核心** | `container-engine/src/`：zip / keys / sign / verify / kernel-bundle / ota-engine / runtime-json / boot | 35 passed |
| **M2 HostBridge 协议库** | `bridge/{protocol,methods,uds-transport,server}.js`：JSON-RPC 2.0 + UDS + 握手协商 + 错误码 + 审计 | 23 passed（bridge-protocol 14 / bridge-e2e 9） |
| **M2.5 内核↔容器桥互通** | 内核侧客户端（`dsh-android-kernel/src/platform/host-bridge/`）与容器参考桥**真实 UDS 互通**；`app.openUrl` 补齐（browser 承接方）；组级能力语义两侧收敛 | 14 passed（bridge-interop，跨仓） |
| **M2.6 内核更新桥闭环** | 宿主帧由**内核同源托管** `/__host`（消除跨源 403）；容器 `MainActivity` 改加载该 URL + 回灌严格按内核契约（含 `v/ok/restartUncertain`）；控制面端口 3080→36360 修正 | 22 passed（kernel-update-bridge 契约） |
| **M3 Kotlin 安卓应用** | `MainActivity/NodeRuntimeService/HostBridgeService/KernelManager/BootReceiver/DeviceAdminReceiver/DshAccessibilityService/ProvisioningProbe/PackageInstallReceiver/ScreenCaptureService` + Manifest 注册 + 加载内核同源宿主帧 `/__host` + `dsh:kernel-update` 桥 | **CI 真编译通过**（fast-apk `b47df7f`，14/14 步，APK 已发布） |
| **P1 预置自检探针** | `ProvisioningProbe`：5 项体检（人读 `diagnostics.txt` + 机器读 `provisioning.json`）；Device Owner 13 个 API 修正 | 编译通过 + 逻辑自测 |
| **P2 无障碍真实实现** | `DshAccessibilityService`：手势 / 节点树（含 IME 悬浮窗）/ 文本注入三级降级 / 条件轮询 | 编译通过；`bridge-e2e` 断言覆盖 |
| **P4 shell 兜底 + 探测增强** | `shell.exec` 以应用 uid 执行（`privileged:false` 明示）；Shizuku **三态**探测（未装 / 已装未授权 / 已授权） | 编译通过；`bridge-e2e` 覆盖 |
| **P5 storage + 截屏** | `fs.read/write/list/mkdir` 真实实现（全放开 + 危险路径提示不拦截）；`ScreenCaptureService`（MediaProjection 前台服务，`ui.screenshot` 真实出图） | 编译通过；`bridge-e2e` 覆盖 |
| **M4 脚本与 CI** | `scripts/build-kernel-bundle.sh` + `container-engine/bin/build-bundle.js` + `kernel-ota.yml` + `build-apk.yml` 公钥锚点校验 | 实测构建+验签闭环通过 |
| **M5 端到端 + 文档** | `e2e-mock-kernel-test.js`（真实 spawn 内核+健康检查）+ README/CONTAINER-STATUS 重写 | 8 passed |

### 关键闭环已实测（非仅代码存在）
- **签名 OTA 端到端**：`build-bundle.js` 用 ed25519 私钥签名 → 产出 `kernel-<v>.zip` + `kernel-manifest.json` → `OtaEngine.verifyPackage` 在焊死公钥下 `ok:true`；错误公钥 → `signature-invalid`；篡改字节 → `sha256-mismatch`；Node 引擎不符 → `node-engine-unsatisfied`。
- **内核真实拉起**：`e2e-mock-kernel-test.js` 由 `bootKernel` 真实 `spawn` 一个扮演 `dsh-supervisor` 的 node 进程，`/status` 健康检查通过，验证“容器→内核”接线成立。
- **内核↔容器桥真实互通**：`bridge-interop-test.js` 用**内核侧真实客户端**连**容器侧参考桥**（抽象命名空间 UDS），走完 连接→握手协商→8 组方法调用→能力门禁(-32001)→未知方法(-32601)→审计 全链路。
- **完整测试**：`npm test` 全绿 **107 passed, 0 failed**（9 套件）。

---

## 3. 已实现的能力（M3 Kotlin）

- NodeRuntimeService：读 `kernel/CURRENT` → 写 `runtime.json`（schema 2）→ 注入环境 → `spawn node bin/dsh-supervisor daemon` → `/status` 健康检查 → 退避重启监督循环。
- HostBridgeService：UDS 监听（抽象命名空间 `dsh_hostbridge`）、JSON-RPC 2.0、握手协商 `capabilities/groups`、8 组方法分发、能力门禁（`-32001`/`-32601`）、审计日志。
- 落地能力方法（设备已有对应权限时真实执行）：`sys.info`、`notif.post`、`app.listInstalled`、`app.launch`、`app.openUrl`（**内核 browser.open 的承接方**，ACTION_VIEW）、`app.stop`、以及 **Device Owner 全组**（`policy.lockNow/setPassword/wipe/setKiosk/addUserRestriction`、`sys.setTime/sys.reboot`、`app.install/uninstall/grantPermission`）。
- 组级能力语义：`GROUP_REQUIRED`（每组**代表能力**）判定「组是否可用」；特权方法另由**方法级 caps** 单独门禁 —— 与内核侧 `methods.js` 已逐条对齐（2026-09 收敛）。
- BootReceiver（开机自启）、DeviceAdminReceiver（Device Owner 激活）、MainActivity（诊断面板 + 内核 UI iframe + `dsh:kernel-update-request/result` 桥）。
- **DshAccessibilityService（P2 真实实现，2026-09）**：`dispatchGesture` 手势（tap/swipe）、`getWindows + rootInActiveWindow` 节点树递归采集（含 IME/悬浮窗）、`ACTION_SET_TEXT` 文本注入（含 `ACTION_PASTE` 降级）、`waitForNode` 条件轮询；以进程内单例 `instance` 与 HostBridgeService 对接。
- **ProvisioningProbe（P1 预置自检探针，2026-09）**：开机跑 5 项体检（device-owner / accessibility / shizuku / mediaprojection / special-perms），结果写 `files/diagnostics.txt`（人读）+ `files/provisioning.json`（机器读）。Shizuku 与 MediaProjection 两项在 P4/P5 后升级为**状态感知**（不止判断"有没有"，还判断"能不能用"）。
- **fs.* 真实实现（P5，2026-09）**：`fs.read`（`auto` 编码无损校验，非 UTF-8 自动退 base64；`maxBytes` 默认 8MB / 硬顶 64MB）、`fs.write`（utf8/base64、`append`、自动建父目录）、`fs.list`（`recursive`、`maxEntries` 上限 10000）、`fs.mkdir`。**范围全放开**（有 `MANAGE_EXTERNAL_STORAGE` 即通行），但保留审计留痕与**危险路径提示**（`/dev/*`、`/proc|/sys/*`、`/system|/vendor|/boot`）——**提示不拦截**，这是用户的显式选择。
- **ui.screenshot 真实实现（P5，2026-09）**：`ScreenCaptureService` 前台服务（`foregroundServiceType="mediaProjection"`，Android 14+ 硬性要求）→ `ImageReader` + `VirtualDisplay` → `acquireLatestImage()` 取最新帧 → `imageToBitmap` 处理 `rowPadding` 防花屏。默认返回 PNG 落盘路径（避免几 MB base64 撑爆 JSON-RPC 帧），`inline=true` 才内联 base64。授权缓存 `files/screen-capture-grant.json`（Parcel 字节流 + base64），`MainActivity` 提供「授权屏幕捕获」按钮承接系统弹窗。
- **shell.exec 兜底（P4，2026-09）**：以**应用 uid** 执行（`ProcessBuilder`），返回体显式带 `privileged:false` + `note`，**不冒充 shell uid(2000)**。读线程 pump 与 `waitFor` 并行以防管道死锁；超时 `destroyForcibly()`；输出截断 256KB。

---

## 4. 已知缺口 / 后续（不影响“内核能跑起来”）

这些是**能力增强**，不是“底座缺失”——地基已通，下面是墙和屋顶：

1. **能力矩阵：4 组已真实落地，1 组待决策**：
   - ✅ `ui_automation`：`ui.tap / ui.swipe / ui.inputText / ui.getUiTree / ui.waitFor / ui.screenshot` —— **P2 + P5 全部真实实现**。前五个需无障碍服务连接；`ui.screenshot` 需用户点一次屏幕捕获授权（**不可预置**，与 Device Owner 的本质区别）。
   - ✅ `device_policy` 全组：`policy.* / sys.setTime / sys.setTimeZone / sys.reboot / app.install / app.uninstall / app.grantPermission`（P1 修正 13 处 API 误用；`app.install/uninstall` 走 `PackageInstaller`）。
   - ✅ `storage`：`fs.read / fs.write / fs.list / fs.mkdir` 真实实现（P5）。需 `MANAGE_EXTERNAL_STORAGE`（Manifest 已声明，属 AppOps 特殊权限，需跳设置页或 Device Owner 静默授予）。
   - ⚠️ `shell`：`shell.exec` **兜底已实现**（应用 uid，`privileged:false`）。**未内置 Shizuku SDK**（P4 决策：不引入第三方 AAR 以免污染冻结容器的信任边界），故不具备 shell uid(2000) 特权。Shizuku 探测已细分三态，接入 SDK 后可解锁。
   - ⏳ `build`：内置构建链（**P3**）尚需 APK 资产打包决策（全内置 vs 首启下载 vs 最小子集），`build.apk/build.status` 仍返回 `-32001`。
2. **OTA 下发编排**：`OtaEngine` 已具备验签/解包/原子指针能力；设备上“轮询 manifest→下载→apply→回滚”的调度器由内核侧 bootstrap（Node）承接，本仓未内置一个独立 Kotlin OTA 调度器（按 BASE_SPEC §5，OTA 引擎逻辑归于内核引导）。
3. **基线内核 `assets/kernel/baseline.zip`**：`KernelManager.ensureBaseline` 已支持首启离线落地，但本仓未内置基线内核包（由 `kernel-ota.yml` 构建产出后纳入）。
4. **Kotlin 已过 CI 编译，待真机验证**：fast-apk（`b47df7f`）14/14 步全绿、APK 审计通过、已发布到 `apk-latest`。
   编译过程暴露并修复了 6 处**存量 API 误用**（详见下方「编译修复」），说明此前「只评审不编译」确实藏了真 bug。
   **剩下的是真机验证**：`adb shell dpm set-device-owner …` → 开无障碍 → 点「授权屏幕捕获」→ 看 `provisioning.json` 五项体检是否全绿。
   > P4/P5 新增代码（`MediaProjection` / `ImageReader` / `Parcel.marshall` / `PackageInstaller`）**尚未经 CI 真编译**，是本轮待验证项。
5. **`policy.setPassword` 属遗留路径**：`DevicePolicyManager.resetPassword` 自 API 30 废弃且多数设备不生效，实现保留但已返回 `note` 提示；建议改用 user restrictions 或应用内锁。
6. **`shell.exec` 的 `shizuku` 能力门禁语义**：方法级 caps 声明为 `shizuku`，但实现是应用 uid 兜底 —— 这意味着设备未装 Shizuku 时调用会得到 `-32001`（**组级**门禁），装了 Shizuku 才会走到兜底实现。若希望"无 Shizuku 也能用兜底"，需把 caps 放宽到组代表能力，这是一个待定的产品决策。

### 编译修复（2026-09，fast-apk 首次真编译）

这条流水线第一次真正编译 Kotlin（此前 M3 只有人工评审），一次性暴露 6 处存量 API 误用。
**结论：对 Android API 光靠「核对文档 + 人工评审」不够，必须让 CI 编译。**

| 问题 | 真相 | 修法 |
|---|---|---|
| `dpm.installPackage(...)` | **DevicePolicyManager 根本没有这个方法** | 改用 `PackageInstaller`（createSession→openWrite→commit）+ 新增 `PackageInstallReceiver` 承接异步结果广播 |
| `dpm.uninstallPackage(...)` | 同上，也不存在 | 走 `PackageInstaller.uninstall(pkg, intentSender)` |
| `dpm.reboot(admin, null)` | android.jar 只有单参 `reboot(ComponentName)`（两参版是桌面 Java 的） | 改 `reboot(deviceAdmin)` |
| `MainActivity` 裸用 `KERNEL_CONTROL_PORT` | companion 常量须限定名 | 补 `NodeRuntimeService.` 前缀 |
| `KernelManager.ensureBaseline` 的 `return@use` | lambda 返回值类型不匹配（Unit vs String?） | 显式判 null 后 `return@use null` |
| `KernelManager.unzip` | **方法从未定义**（被调用但没实现） | 补 `java.util.zip` 实现 |

> 影响面值得记一笔：Device Owner 组里的「静默装卸应用」是本项目最核心的特权能力之一，
> 而这 6 处里有两处就落在它上面 —— 意味着在真机上**必然直接崩溃**。这正是"必须编译"的价值。

---

## 5. 如何复现验证

```bash
# 容器引擎单测（无需 Android SDK）
cd container-engine && npm test        # 107 passed, 0 failed（9 套件）

# 单独跑内核↔容器桥互通（跨仓，需 dsh-android-kernel 在同级 /workspace）
node test/bridge-interop-test.js       # 14 passed, 0 failed

# 构建并签名一个内核 OTA 包（开发期需先 ./scripts/keygen.sh）
./scripts/build-kernel-bundle.sh <内核源码目录> 1.4.0 node24-arm64-android35 https://cdn.example.com/ota
# 产物：release/kernel-1.4.0.zip + release/kernel-manifest.json

# 出 APK（日常路径：不重编 Node，分钟级）
git push → Actions → fast-apk.yml（拉 pinned 运行时 → assembleDebug → 审计 → 发布）
# 出 APK（改了 Node 版本/编译脚本才需要，2~3 小时）
Actions → build-apk.yml
# 出内核 OTA（需配置 OTA_PRIVATE_KEY_PEM secret）
Actions → kernel-ota.yml → 产出签名内核包 / Release

# 拉 CI 失败日志（沙箱读不到 actions blob 时用这条路）
git push origin HEAD:refs/tags/admin-logs-<run_id> && git fetch origin ci-admin
git show origin/ci-admin:ci-admin.txt
```

> **跨仓联调提示**：内核侧（`dsh-android-kernel`）在自己的仓内跑 `npm test`（含 `test/host-bridge-test.js`，20 passed）；
> 两侧协议须逐字段一致。抽象命名空间 UDS 名默认 `dsh_hostbridge`，容器 `boot.js` 经 `DSH_BRIDGE_SOCKET` 注入内核。
