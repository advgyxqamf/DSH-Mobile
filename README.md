# Android Node Container（DSH 容器底座 / L0）

> 把手机变成「工作台」的**地基**：一个冻结的安卓 APK，内含原生 Node 运行时 + HostBridge 能力桥 + 签名 OTA 引擎 + 生命周期/诊断。
> 真正的产品（控制面板内核 L1、Agent L2）由这套底座**热更新**承载——APK 只在 Node/构建链/桥能力变更时才重编。

分层架构详见 [`docs/BASE_SPEC.md`](docs/BASE_SPEC.md) 与 [`docs/BRIDGE_PROTOCOL.md`](docs/BRIDGE_PROTOCOL.md)。

---

## 1. 三层架构（BASE_SPEC §2）

| 层 | 名称 | 更新方式 | 冻结？ | 职责 |
|---|---|---|---|---|
| **L0** | 容器（本仓库 APK） | 仅 Node/构建链/桥能力变更才重编 | ✅ | Node 运行时 + npm 客户端 + 内置构建链 + **HostBridge（UDS 能力桥）** + **OTA 引擎** + 生命周期 + 诊断 |
| **L1** | 内核 = 控制面板 / Manager | 容器**签名 OTA** 热更新 | ❌ | 控制面板代码 + `kernel.json`；运行在 Node 运行时内；运行时经 npm 安装/管理 Agent |
| **L2** | Agent 产品 | 内核运行时 **npm（公共源）** | ❌ | Codex / Claude Code / DeepSeek Harness 等标准公共产品，由内核拉取 |

**两条热更新通道**（双信任根，互不替代）：
- 容器 → 内核：**签名 OTA**（容器私钥签内核包，公钥焊进 APK 验签）。
- 内核 → Agent：运行时 **npm 标准完整性**（sha512 integrity）。

---

## 2. 仓库结构

```
android-node-container/
├─ app/src/main/
│  ├─ jniLibs/arm64-v8a/libnode.so        # NDK 编出的 node（构建时注入，首启离线可用）
│  │                                       #   ⚠ 必须是 lib*.so 且放 jniLibs（见 §2.1，改动前必读）
│  ├─ assets/
│  │  ├─ node/server.js                   # 容器探针（无内核包时首启验证 Node 原生链路）
│  │  ├─ node-versions.json               # Node 运行时版本清单（驱动 Node OTA）
│  │  ├─ ota-public.pem                   # ★焊接的 OTA 验签公钥（设备端唯一信任源）
│  ├─ java/com/example/nodecontainer/
│  │  ├─ NodeContainerApp                 # 通知渠道
│  │  ├─ NodeRuntimeService(:node)        # 前台服务：预置体检 → 写 runtime.json → 拉起内核 → 退避重启
│  │  ├─ HostBridgeService                # ★UDS 能力桥（JSON-RPC 2.0，8 组方法 + 审计）
│  │  ├─ ProvisioningProbe                # ★预置自检探针（PROVISIONING §4，落 provisioning.json）
│  │  ├─ KernelManager                    # kernel/ CURRENT 指针 + 基线内核落地
│  │  ├─ NodeProvisioner / NodeVersionManager  # Node 运行时定位 / 版本管理
│  │  ├─ BootReceiver                     # ★开机自启容器
│  │  ├─ DeviceAdminReceiver              # ★Device Owner（静默装卸/锁屏/密码/Kiosk）
│  │  ├─ DshAccessibilityService          # ★无障碍：手势/节点树/文本注入（bridge:ui_automation）
│  │  ├─ ScreenCaptureService             # ★截屏：MediaProjection 前台服务（bridge:ui.screenshot）
│  │  ├─ PackageInstallReceiver           # ★PackageInstaller 会话结果回传（app.install/uninstall）
│  │  └─ MainActivity                     # 诊断面板 + 屏幕捕获授权 + 内核同源宿主帧 /__host
│  └─ res/xml/{device_admin, accessibility_service_config, network_security_config}.xml
├─ container-engine/                      # ★可测 OTA 引擎（Node，零依赖）
│  ├─ src/  zip / keys / sign / verify / kernel-bundle / ota-engine / runtime-json
│  │        / boot / bridge/{protocol,methods,uds-transport,server}.js
│  ├─ test/ 9 个测试套件（107 passed）
│  └─ bin/build-bundle.js                 # 内核包签名构建 CLI
├─ scripts/  keygen / build-node-android / build-apk-local / make-release / build-kernel-bundle
├─ keys/ota-private.pem                   # 开发期 ed25519 私钥（gitignored；CI 用 secret）
└─ .github/workflows/  build-apk.yml / fast-apk.yml / kernel-ota.yml
```

### 2.1 ⚠️ 为什么 node 必须放在 jniLibs 而不是 assets

这是本项目踩过的最大一个坑，也是**真机 `error=13, Permission denied` 的根因**，改动前务必先读。

Android 10 (API 29) 起 SELinux 强制 **W^X** 策略：

| 路径 | SELinux label | 能否 `execve` |
|---|---|---|
| `/data/data/<pkg>/files/`（`getFilesDir()`） | `app_data_file` | ❌ 禁止 |
| `/data/data/<pkg>/cache/`（`getCacheDir()`） | `app_data_file` | ❌ 禁止 |
| `/data/app/<pkg>/lib/<abi>/`（`nativeLibraryDir`） | `exec_type` | ✅ 允许 |

把 node 解压到 `filesDir` 再 `ProcessBuilder` 启动，会得到：

```
IOException: Cannot run program ".../files/node/24.21.0/node": error=13, Permission denied
```

官方认定这是**设计如此**（Google issuetracker 128554619）：

> Calling exec() on writable application files is a W^X violation... While exec() no longer works on files within the application home directory, it continues to be supported for files within the read-only /data/app directory. In particular, it should be possible to package the binaries into your application's native libs directory and enable android:extractNativeLibs=true, and then call exec() on the /data/app artifacts.

因此方案是：**把 node 命名为 `libnode.so` 放进 `jniLibs/<abi>/`，开启 `extractNativeLibs`，运行时从 `applicationInfo.nativeLibraryDir` 执行。** 三个配套条件缺一不可：

1. 文件名必须是 `lib*.so` 形式，否则 AGP 不会当 native lib 处理；
2. `android:extractNativeLibs="true"`（本项目在 Manifest 与 gradle 两处都写了）—— 否则 AGP 3.6+ 默认把 `.so` 压缩在 APK 内不落盘，文件系统上根本没有可执行路径；
3. 二进制解释器必须是 Android 的 `/system/bin/linker64`（交叉编译产物天然满足）。

> **还有一个必设的环境变量**：`LD_LIBRARY_PATH = nativeLibraryDir`。Android linker 查找依赖库的目录只有 `$LD_LIBRARY_PATH` / DT_RUNPATH / 系统默认路径三者，`nativeLibraryDir` **不在其中**（它只在 Java 层 `dlopen` 时进搜索路径）。而 `libnode.so` 自身既无 DT_RPATH 也无 DT_RUNPATH，若不设这个变量，`libc++_shared.so` 的符号解析会直接失败：
> ```
> CANNOT LINK EXECUTABLE ".../libnode.so": cannot locate symbol "_ZTVNSt6__ndk119basic_ostringstream..."
> ```

> **一个隐蔽的陷阱**：`File.canExecute()` 对上述限制**完全无感** —— 它只查 stat 的 x 权限位，不知道 noexec 挂载、更不知道 SELinux 策略。所以它在不可 exec 的文件上照样返回 `true`，造成"诊断显示可执行、真 exec 却失败"的假阳性。**判断能否执行，唯一可靠的办法是真去执行一次**（本项目在启动前跑一次 `node -v` 来验证，见 `runExecProbe`）。

> **对 OTA 的影响**：`nativeLibraryDir` 是安装时固定、运行期只读的，且每次 APK 更新路径中的随机串都会变。这意味着"在沙箱放多个 Node 版本目录、切指针"的 Node OTA 方案在该路径上**不成立**。当前策略是以内置版本保证首启可用。**本文的 OTA 通道（签名验签/解包/原子指针）服务于内核包（L1）**——内核是 `filesDir` 下的 JS 代码，由 `node` 解释执行，不涉及 `execve`，因此不受 W^X 限制。

---


## 3. 内核启动流程（BASE_SPEC §9）

`NodeRuntimeService`（独立 `:node` 进程，`START_STICKY` 前台保活）在 App 启动或 `BootReceiver` 收到开机广播时：

1. **预置体检**（`ProvisioningProbe`，PROVISIONING §4）：device-owner / accessibility / shizuku / mediaprojection / special-perms 五项落 `diagnostics.txt` + `provisioning.json`，保证内核起不来时也能看清设备能力。
2. 启动 **HostBridgeService**（UDS 监听，内核侧主动 connect）。
3. 读 `files/kernel/CURRENT` 指针；若无内核则落地 `assets/kernel/baseline.zip`（首启离线可用）。
4. 确认内置 node 就位（`nativeLibraryDir/libnode.so`）→ 自检两个 `.so` → **exec-probe 真跑一次 `node -v`**。
5. 取冻结的 Node 运行时（`files/node/CURRENT`）。
6. 写 **runtime.json（schema 2，容器写内核读）** 到 `files/supervisor/runtime.json`。
7. 注入安卓环境：`DSH_ANDROID=1` / `DSH_PLATFORM=android` / `DSH_SUPERVISOR_HOME` / `DSH_UI_DIR` / `PATH` / `HOME` / `TMPDIR` / `LD_LIBRARY_PATH`。
8. `spawn node bin/dsh-supervisor daemon`（内核入口）；无内核包时回落到 `assets/node/server.js` 探针（:3080）。
9. 轮询控制面（有内核看 `36360/status`；探针模式看 `3080`）；失败/进程退出 → **退避重启**（1s→…→30s 上限）。

> 一次内核升级 = 重启 `:node` 进程（用户侧“热”的，无 APK 重编）。

---

## 4. HostBridge（能力桥，L3）

- **传输**：Unix 域套接字（抽象命名空间 `dsh_hostbridge`）；内核（Node）经 `net.connect('\0dsh_hostbridge')` 主动连接（**前导 NUL 字节**，Node 22 原生支持）。**严禁 TCP 暴露控制面**（BASE_SPEC §8）。
- **协议**：JSON-RPC 2.0，换行分隔 JSON 帧；握手协商 `capabilities` / `groups`。
- **8 组方法**：`app_control / ui_automation / shell / device_policy / storage / build / notification / system`。
- **鉴权**：**两层门禁** —— 组级（握手按每组**代表能力**协商 `bridge:*`）+ 方法级（每次调用按 `caps` 精确拦截）。未授权 → `ERR_CAPABILITY_MISSING (-32001)`；未知方法 → `METHOD_NOT_FOUND (-32601)`。内核应优雅降级。
- **审计**：所有特权操作（装卸应用/锁屏/shell/读屏/通知读取，见 BRIDGE_PROTOCOL §5）落 `files/bridge-audit.log`（持久，不随内核包切换丢失）。
- **内核侧客户端**：内核仓 `src/platform/host-bridge/`（本次已互通）；`notify.post`/`app.openUrl` 分别承接内核的通知与「打开浏览器」。

**落地进度（2026-09）**：

| 方法组 | 状态 | 说明 |
|---|---|---|
| `app_control` / `notification` / `system` | ✅ | 真实实现 |
| `device_policy` | ✅ | 13 个 `dpm.*` 方法真实调用（需 Device Owner）；**P1 修正 6 处存量 API 误用** |
| `ui_automation` | ✅ | **P2 落地**：手势/节点树/文本注入；**P5 补 `ui.screenshot`**（MediaProjection） |
| `storage` | ✅ | **P5 落地**：`fs.read/write/list/mkdir` 真实实现（全放开 + 危险路径提示不拦截） |
| `shell` | ⚠️ | **P4 兜底**：以应用 uid 执行（`privileged:false`）；特权 shell 需 Shizuku SDK（未内置） |
| `build` | ⏳ | 需内置构建链（P3，待方案决策） |

> **`ui.screenshot` 的前置**：MediaProjection 授权**不可预置**（与 Device Owner 本质区别）——
> 需在 App 诊断面板点一次「授权屏幕捕获」，之后缓存于 `files/screen-capture-grant.json` 长期复用。
>
> 协议细节、方法表、错误码见 [`docs/BRIDGE_PROTOCOL.md`](docs/BRIDGE_PROTOCOL.md)；实现与 [`container-engine/src/bridge/*`](container-engine/src/bridge) 对齐。
> 跨仓互通由 `container-engine/test/bridge-interop-test.js` 实测（内核真实客户端 ←→ 容器参考桥，真实 UDS）。
> 权限预置与自检见 [`docs/PROVISIONING.md`](docs/PROVISIONING.md)；`Device Owner` 激活：`adb shell dpm set-device-owner com.example.nodecontainer/.DeviceAdminReceiver`。

---

## 5. 快速开始

### 5.1 编译 Node 二进制（一次性）

前置（主机侧）：`git python3 ninja cmake make zip` + **Android NDK r27+**。

```bash
ANDROID_NDK=/path/to/ndk ./scripts/build-node-android.sh 24.21.0
# 产物 -> app/src/main/assets/node-bin/arm64-v8a/node
```

### 5.2 生成 OTA 密钥对（开发期）

```bash
./scripts/keygen.sh
# 生成 keys/ota-private.pem（gitignored）+ 把公钥焊进 app/src/main/assets/ota-public.pem
```

> 私钥仅用于**签名内核包**；公钥焊死在 APK。私钥轮换 = 发新版 APK。

### 5.3 出包（APK）

**路径 A（推荐，零本地环境）：GitHub Actions 一键出包** — `.github/workflows/build-apk.yml` 自动解析最新 Node 24.x tag、NDK 交叉编译、`./gradlew assembleDebug` 上传 APK。推到你有写权限的仓库 → Actions → Run workflow。

**路径 B（本地，需 SDK/NDK）：**

```bash
export ANDROID_HOME=/path/to/sdk ANDROID_NDK=/path/to/ndk   # NDK r27+
./scripts/build-apk-local.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

打开 App → 常驻通知 → 先显示“启动诊断”面板（逐阶段带时间戳），内核就绪后 WebView 加载
**内核同源托管的宿主帧** `http://127.0.0.1:36360/__host`（页内 iframe 嵌内核面板）。

### 5.4 构建并签名内核 OTA 包

```bash
# 先确保私钥就位（见 5.2）
./scripts/build-kernel-bundle.sh <内核源码目录> 1.4.0 node24-arm64-android35 https://cdn.example.com/ota
# 产物（release/，gitignored）：
#   kernel-1.4.0.zip        OTA 下发的内核包（已 ed25519 签名）
#   kernel-manifest.json    版本/url/sha256/签名
```

CI 等价流程见 `.github/workflows/kernel-ota.yml`（用 `OTA_PRIVATE_KEY_PEM` secret 签名，绝不进 APK）。

### 5.5 内核更新桥（dsh:kernel-update）

内核 WebView 加载**内核同源托管的宿主帧** `GET /__host`（`ui/public/host.html`），其内 iframe 嵌面板（`src="/"`）。

- **为什么同源**：内核 `originAllowed` 闸② 要求驱动页面 Origin = `<本机/局域网>:<apiPort>`；
  容器 `assets/` 的 `file://` 宿主页 Origin 为 null → 面板写操作**一律 403**。故宿主帧搬到内核侧同源托管。
- **链路**：面板 iframe `postMessage({v:1,type:'dsh:kernel-update-request',requestId})`
  → 宿主帧 `window.DshNative.onRequest(json)`（`MainActivity` 经 `JavascriptInterface` 收到）
  → 重启 `:node` 重读 `CURRENT` / 承接 OTA
  → 回灌 `{v:1,type:'dsh:kernel-update-result',requestId,ok,stage,version,restartUncertain,error}`
  → 宿主帧 `dshDeliverResult(json)` → 面板 iframe。
- ⚠ 回灌**必须含 `v` 与 `ok`**（内核 `kernelUpdateBridge.ts` 依此过滤，缺则丢弃 → 面板超时）。
  契约由 `container-engine/test/kernel-update-bridge-test.js` 锁定。

---

## 6. container-engine（可测 OTA 引擎）

纯 Node、零外部依赖，可在本机 `node` 直接跑测试（无需 Android SDK）：

```bash
cd container-engine && npm test
# sign-verify 6 / kernel-bundle 9 / ota-engine 12 / runtime-json 8 /
# bridge-protocol 14 / bridge-e2e 14 / bridge-interop 14 / kernel-update-bridge 22 / e2e-mock-kernel 8
# ⇒ 107 passed, 0 failed（9 套件）
```

覆盖：ed25519 签名/验签、内核包打包、OTA 验签+解包+原子指针切换+坏包拦截、runtime.json 契约、HostBridge 协议编解码/握手/方法能力/审计、**内核↔容器桥真实 UDS 互通**、**更新桥协议契约**，以及**真实 spawn 内核 + 健康检查**的端到端。

---

## 7. 已核实事实

- **最新 LTS = Node 24 Krypton**（24.21.0；Active LTS 到 2028-04-30）；Node 24 自带 OpenSSL 3.5，默认安全等级 2。
- **16KB 页对齐**：NDK r27+ 编译满足安卓 15+ (API 35) `dlopen` 要求。
- **双信任根**：容器 ed25519 私钥签内核、公钥焊进 APK；npm 标准 integrity 验 Agent。
- **传输私有**：Agent↔HostBridge 走 UDS，不走 TCP（BASE_SPEC §8）。
