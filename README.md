# Android Node Container

> 在**非 root、免 Termux** 的安卓原生环境（bionic libc）里跑通 Node.js 的容器骨架。
> 架构上把 Node 当作**可独立升级的运行时资源**，未来升级 Node 无需重新发布 APK。

---

## 1. 这个骨架解决什么

- **原生安卓跑 Node**：用 NDK 交叉编译出的 `node` 可执行文件（bionic 链接），由 App 前台服务直接 `exec`，与 Termux/root 无关。
- **最新 LTS + 现代 TLS**：默认 **Node 24 “Krypton” (Active LTS，支持到 2028-04-30)**，自带 **OpenSSL 3.5 / TLS 1.3、安全等级 2**。
- **可升级**：Node 二进制放在应用沙箱 `files/node/<version>/`，版本指针在 `files/node/CURRENT`。升级 = OTA 下载新版本 + sha256 校验 + 切换指针。
- **最小探针**：`assets/node/server.js` 在 `127.0.0.1:3080` 暴露 `/api/version`（验证 LTS/架构/OpenSSL），证明 Node 真跑起来了。后续把 DSH 等负载换成这个入口即可。

---

## 2. 架构

```
APK (com.example.nodecontainer)
├─ assets/node-bin/arm64-v8a/node      ← NDK 编出的 node（构建时注入，首启离线可用）
├─ assets/node/server.js               ← 容器探针（后续换成你的负载，如 DSH Web UI）
├─ assets/node-versions.json           ← 版本清单（驱动 OTA 升级）
└─ Kotlin 层
   ├─ NodeContainerApp       通知渠道
   ├─ NodeRuntimeService(:node 独立进程，前台服务)
   │     └─ ProcessBuilder → exec node server.js --port 3080
   ├─ NodeProvisioner        把 assets/node-bin 解压到 files/node/<v>/，chmod +x
   ├─ NodeVersionManager     读清单 / 当前版本指针 / OTA 下载+sha256 校验+原子切换
   └─ MainActivity           WebView 加载 http://127.0.0.1:3080
```

进程模型：`MainActivity`（UI 进程）→ 启动 `NodeRuntimeService`（独立 `:node` 进程，前台服务保活）→ 它 `exec` 出一个 `node` 子进程监听回环端口 → WebView 渲染本地 UI。

---

## 3. 快速开始

### 3.1 编译 Node 二进制（最难啃的一步，一次性）

> 没有现成的新版预编译安卓 Node：`node-on-mobile/node-on-android` 最后提交 2019、`nodejs-mobile` 停在 Node 12，都已不可用。必须从官方源码 + NDK 自编。

前置（主机侧）：`git python3 ninja cmake make zip` + **Android NDK r27+**。

```bash
# 默认编 Node 24 LTS
ANDROID_NDK=/path/to/ndk ./scripts/build-node-android.sh 24.21.0
# 产物 -> app/src/main/assets/node-bin/arm64-v8a/node
```

脚本内部：克隆 Node 官方源码 → `./android-configure $NDK arm64 24` → `make`。
NDK r27+ 链接器默认 16KB 页对齐，满足 Android 15+ (API 35) 的 `dlopen` 要求。

> 想要别的 LTS：把版本号换掉即可（如 `22.23.1`，Maintenance LTS，满足 DSH 的 `^22.19.0`）。
> 想要别的 ABI：扩展 `build-node-android.sh` 的 `ARCH` 与 `app/build.gradle.kts` 的 `abiFilters`。

### 3.2 出包（两条路径）

> ⚠️ 关于“谁来编”：本仓库的 Node 二进制与 APK 都需要在**有公网、能访问 dl.google.com** 的
> 环境里编译（要下载 Android SDK/NDK）。作者所在的沙箱环境外网被白名单限制，无法下载
> 这些工具，因此**出包交给你这边**。下面两条路径任选其一，都能产出可安装的 `app-debug.apk`。

**路径 A（推荐，零本地环境）：GitHub Actions 一键出包**

本仓库已带 `.github/workflows/build-apk.yml`。把它推到 GitHub → Actions → 手动
`Run workflow`（或 push 到 main/master 自动触发）→ 完成后在 Artifacts 下载
`android-node-container-apk.zip`，里面是 `app-debug.apk`。CI 运行器有完整公网，会自动：
装 SDK/NDK → `build-node-android.sh` 编 Node → `assembleDebug` → 上传 APK。

**路径 B（本地，需已装 SDK/NDK）：**

```bash
export ANDROID_HOME=/path/to/sdk ANDROID_NDK=/path/to/ndk   # NDK 需 r27+
./scripts/build-apk-local.sh
# 产出 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 注：本仓库未附带 `gradle-wrapper.jar`（二进制无法手生成）。两条路径都会自动
> `gradle wrapper` 生成它，无需你手动处理。

### 3.3 运行 & 验证（你在手机上会看到什么）

打开 App → 通知栏出现“Node.js 运行时”常驻通知 → 屏幕先显示**“启动诊断”面板**：

- 逐阶段、带时间戳地打印：`init → manifest → version → provision → script → exec → port`。
- **成功**：端口就绪后自动切换到 Node 探针 Web UI。
- **失败**：对应阶段标 `[FAIL]`，并附真实错误。最常见两类：
  - `provision [FAIL]`：`assets/node-bin/arm64-v8a/node` 缺失 → 说明没跑 `build-node-android.sh`（CI/本地脚本已处理）。
  - `node-stderr [FAIL]`：node 进程自己报的错（如二进制非 16KB 页对齐、ROM 不兼容）→ 看完整 stderr 即可定位。

点探针页 `/api/version` 应返回类似：

```json
{
  "container": "android-node-container",
  "node": "v24.21.0",
  "lts": "Krypton",
  "platform": "android",
  "arch": "arm64",
  "openssl": "3.5.8"
}
```

`adb logcat -s NodeRuntime:*` 可看 Node 进程日志。

---

## 4. 升级 Node（不发新版 APK）

1. 用新版本号重跑 `build-node-android.sh` 生成 node。
2. `./scripts/make-release.sh 24.x.x` → 产出 `release/node-24.x.x-android-arm64-v8a.zip` 并打印 sha256。
3. 把 zip 上传到你的 OTA 服务器（GitHub Release / 对象存储均可）。
4. 在 `assets/node-versions.json` 的 `versions` 追加一条（把脚本打印的 sha256 填进去，`url` 换成真实地址）。
5. App 内 `NodeVersionManager.install(...)` 下载 → sha256 校验 → 解压到 `files/node/<新版本>/` → `setCurrentVersion(...)` 原子切换 → 重启 `NodeRuntimeService`。

**坏包永不生效**：sha256 不匹配直接抛异常，不切指针。

---

## 5. 关键坑（已为你在工程里规避/标注）

| 坑 | 处理 |
|---|---|
| **16KB 页对齐** | NDK r27+ 编译；老 NDK 需 `LDFLAGS=-Wl,-z,max-page-size=16384`，否则安卓 15+ `dlopen` 失败 |
| **bionic 链接** | 走官方 `android-configure`，node 链接系统 libc，不依赖 Termux |
| **前台保活** | `NodeRuntimeService` 前台服务 + `START_STICKY` + 常驻通知 |
| **exec 限制** | node 放在应用私有 `files/` 目录直接 `exec`（Termux 同款做法），无需系统分区 |
| **明文 HTTP** | `network_security_config.xml` 仅对 `127.0.0.1/localhost` 放行，不全局 cleartext |
| **HOME/TMPDIR** | 启动 node 前注入 `HOME`、`TMPDIR`，否则 npm/crypto 临时文件报错 |

---

## 6. 下一步（接你之前的路线）

- **塞 DeepSeek Harness**：把 `server.js` 换成 `dsh web` 的入口（`npm pack @deepseek-ai/dsh` 也塞进 `files/node_modules`），端口/监听地址不变。注意 DSH 的 `native/system` host-addon 仍需为安卓交叉编译。
- **加 Python / Java**：Python 用 Chaquopy、Java 用 FCL-Team/Android-OpenJDK-Build，以同样“沙箱运行时资源”思路并入，由 `NodeVersionManager` 同款模式管理版本。
- **让容器内的 Agent 控手机**：叠加无障碍服务或 Shizuku（见对话前文），把 droidrun / mobile-use 当容器内 Agent 的执行器。

---

## 7. 已核实事实

- **最新 LTS = Node 24 Krypton**（24.21.0，2026-09-08；Active LTS 到 2028-04-30）；Node 22 Jod 为 Maintenance LTS。满足 DSH `^22.19.0 || >=24`。
- **Node 24 自带 OpenSSL 3.5**，默认安全等级 2（拒绝 <2048bit RSA / <224bit ECC）。
- **`node-on-mobile/node-on-android` 真实但已死**（最后提交 2019-01，libnode.so 是 Node 8/10 时代），架构模式（libnode + JNI + WebView）可参考，二进制不可用。
