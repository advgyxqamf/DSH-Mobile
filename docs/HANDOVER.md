# 交接文档 —— Android Node Container（DSH 容器底座）

> 生成时间：2026-09-16 19:41 GMT+8
> 仓库 HEAD：`c5bb718`
> 对应远端：`https://github.com/advgyxqamf/DSH-Mobile`（main 分支，已同步）

本文档面向**接手这个仓库的人**。目标是让你在 30 分钟内建立正确的心智模型，
知道哪些是已验证的事实、哪些是尚未处理的债、以及踩过的坑长什么样。

**建议阅读顺序**：本文 → `README.md`（快速上手）→ `docs/ARCHITECTURE.md`（硬约束原理）。

---

## 1. 一句话说清这是什么

把 **Node.js 运行时和一个可热更新的内核**装进 Android APK，
让 App 成为一个能跑 Agent、能通过 HostBridge 调用系统能力（无障碍、设备管理、
截图、文件、安装）的**容器底座**。设备上自带一台"能被远程指挥的手机"。

**React Native / Flutter 不做这件事** —— 它们把 JS 当业务逻辑跑，不提供
可执行任意二进制的运行时，也不提供系统级能力桥。本项目的差异点在这两处。

---

## 2. 三层架构（改代码前必须先理解这个）

```
┌─────────────────────────────────────────────────────────────┐
│ L0 容器（APK 内，冻结）                                       │
│   · Node 运行时（libnode.so，以 jniLibs 形态落 exec 目录）     │
│   · HostBridge（Kotlin，系统能力实现）                        │
│   · OTA 引擎（验签/解包/原子切指针）                          │
│   · 公钥锚点 assets/ota-public.pem（焊死）                   │
├─────────────────────────────────────────────────────────────┤
│ L1 内核（= 控制面板，签名 OTA 热更新）                        │
│   · 位于 files/kernel/<version>/，一个**数据目录**            │
│   · 容器有权改它 ⇒ 「DSH 改自己内核」不需要碰 APK             │
├─────────────────────────────────────────────────────────────┤
│ L2 Agent（npm 拉取，标准 integrity 校验）                     │
├─────────────────────────────────────────────────────────────┤
│ L3 HostBridge（Kotlin，随 APK 冻结，UDS 传输不走 TCP）        │
└─────────────────────────────────────────────────────────────┘
```

**核心洞察**：L1 是数据不是代码。所以「内核自举」不需要重新编译任何原生东西，
只需要**把新内核包喂给设备**。这就是 `LocalKernelFeed` 做的事。

---

## 3. 当前完成度（截至 `c5bb718`）

### 3.1 已验证可用

| 能力 | 状态 | 验证方式 |
|---|---|---|
| Node 运行时在真机以 `exec` 启动 | ✅ | 真机实测（Android 16/API 36） |
| W^X 四道关（SELinux/interp/架构/libc） | ✅ | 见 `ARCHITECTURE.md` §2 |
| HostBridge 8 组能力（34 个方法） | ✅ | `bridge-e2e` 14 条断言 |
| 内核↔容器桥**真实 UDS 互通** | ✅ | `bridge-interop` 14 条断言 |
| 内核 OTA：验签→解包→原子切指针 | ✅ | `ota-engine` 12 + `kernel-selfboot` 68 条 |
| **A'' 自举：设备从本地 feed 装内核** | ✅ | `kernel-feed` 23 条断言 |
| 稳定签名身份（APK 可覆盖升级） | ✅ | CI 实测 `[success]`，本地三路径实测 |
| 无网首启（内置基线内核） | ✅ | `kernel-baseline` 8 条断言 |
| 容器引擎测试 | ✅ | **228 passed / 0 failed（12 套件）** |
| CI 出包（fast 路径） | ✅ | run `35058735647` 全 21 步绿 |

### 3.2 已知未完成 / 遗留债

按优先级排列，**每一条都说明为什么没做**，避免你误以为是漏掉的。

| # | 事项 | 优先级 | 说明 |
|---|---|---|---|
| 1 | **12 项能力门禁/错误模型审计** | P0 | 含 3 个实质缺陷：① 内核 `call()` 不区分错误码；② `-32001` 承载 4 种语义（能力缺失/参数错/未实现/内部错），调用方无法据码分支；③ `shell.exec` 的兜底实现是死代码。**未做原因**：不属于 A'' 路线范围，且改动面涉及协议语义，需独立设计。 |
| 2 | **CI secret 未配置** | P0（运维） | `ANDROID_KEYSTORE_BASE64` 等 4 个 secret 未配 ⇒ 当前 CI 产物**不具备升级能力**（走退化路径，日志有 `::warning::`）。需人工在 GitHub 仓库设置里配置，代码侧无法代劳。 |
| 3 | **keystore 未备份** | P0（运维） | `keys/release.keystore` 是本轮**新生成**的，仅存在于本地沙箱。**丢失 = 永久失去给已装设备推升级的能力**。必须立刻取出妥善保存。 |
| 4 | 内核仓提交未推送 | P1 | `dsh-android-kernel` 的 `50031d3` 等提交未推送（**该仓没有配 remote**）。 |
| 5 | `build-apk.yml` 全量构建未跑通 | P2 | 3 小时全量路径（含 Node 交叉编译）本轮被多次取消，未拿到完整绿。fast 路径已绿，逻辑上应无差异，但**"应该"不等于"已验证"**。 |
| 6 | 仅 arm64-v8a | P2 | `abiFilters` 只列了 arm64-v8a。32 位设备需扩展 `build-node-android.sh`。 |
| 7 | 内核版本号未纳入 CI 自动递增 | P3 | `kernel-ota.yml` 需手动指定 version。 |

---

## 4. 两把独立的信任根（**最容易搞错的地方**）

这是接手者最常犯的错误来源，务必分清：

| | **APK 签名** | **内核签名** |
|---|---|---|
| 算法 | Java keystore（RSA 4096） | ed25519 |
| 管什么 | 「这个 APK 是不是同一发布者发的」 | 「这个内核包是不是官方签的」 |
| 信任锚点 | **设备上已装的那个包**的签名 | **焊死在 APK 里**的 `assets/ota-public.pem` |
| 谁校验 | Android 安装器（PackageManager） | 设备端 `kernel-verify.js`（Node） |
| 生成脚本 | `scripts/keygen-android-keystore.sh` | `scripts/keygen.sh` |
| 产物 | `keys/release.keystore`（gitignored） | `keys/ota-private.pem`（gitignored） |
| 丢了会怎样 | 无法覆盖安装/升级，只能让用户卸载重装 | 无法再签发新内核包 |

**两条容易搞反的推论**：

- 内核包签名正确 **≠** APK 能装到设备上（前者不经安装器）
- APK 签名稳定 **≠** 内核包可信（攻击者可重签 APK 并换掉锚点公钥）

详细论证见 `docs/ARCHITECTURE.md` §2.4 / §2.5。

---

## 5. 关键硬约束（改代码前必读，违反会静默失败）

### 5.1 W^X —— 可执行文件只能放在哪

Android 10+ SELinux **禁止 exec 应用可写目录**：

| 目录 | SELinux 类型 | 能否 execve |
|---|---|---|
| `filesDir` / `cacheDir` | `app_data_file` | ❌ `EACCES` |
| `nativeLibraryDir` | `exec_type` | ✅ |

⇒ node 必须以 `jniLibs/arm64-v8a/libnode.so` 形态打包。
⇒ **`File.canExecute()` 完全无感（恒假阳性），不要用它判断。**

### 5.2 Ed25519 在 Android 上要 API 33+，而本项目 `minSdk = 24`

⇒ Kotlin 侧在 API 24–32 上 `Signature.getInstance("Ed25519")` 抛 `NoSuchAlgorithmException`。
⇒ **验签必须由 Node 做**（`crypto.verify` 走自带 OpenSSL，与 API level 无关）。

由此推出的分层：**Kotlin 编排（解包/原子 rename/切指针）+ Node 做密码学（一次性进程）**。
校验器 `kernel-verify.js` 随 APK 冻结，**不能由被校验的内核提供**（否则是自证循环）。

### 5.3 其他"注释说得好、代码写不对"的坑（均已修，但别改回去）

| 坑 | 症状 | 位置 |
|---|---|---|
| `keepDebugSymbols` 硬编码 | 新资产被 AGP 悄悄 strip，真机才报缺符号 | `app/build.gradle.kts` |
| `val x by lazy` 在 Gradle Kotlin DSL | `packaging{}` 先于赋值执行 → NPE | 同上，须用 `fun` |
| `storePassword` smart cast | 可变属性无法智能转换 → **脚本编译失败** | 同上 |
| `keytool` 指纹正则写 `"SHA256:"` | 实际输出是 `fingerprint (SHA-256)` → 永不匹配 | CI 签名步骤 |
| `grep -c` 后接管道 | 退出码被 `tail` 覆盖，假绿 | 所有 CI 断言步骤 |

---

## 6. 仓库地图

```
android-node-container/
├── app/                          Android 应用（Kotlin）
│   ├── build.gradle.kts          ⚠ 签名配置 + jniLibs 打包开关，改动风险高
│   └── src/main/
│       ├── assets/
│       │   ├── node/
│       │   │   ├── kernel-verify.js   ★ 设备端内核校验器（283 行，随 APK 冻结）
│       │   │   └── server.js          内核启动入口
│       │   ├── kernel/baseline.zip    ⚠ gitignored，构建产物（无网首启用）
│       │   └── ota-public.pem         ★ ed25519 公钥锚点（焊死）
│       └── java/com/example/nodecontainer/
│           ├── LocalKernelFeed.kt     ★ 从 feed 目录发现内核包（167 行）
│           ├── KernelInstaller.kt     ★ 编排安装（212 行，不做密码学）
│           ├── NodeKernelVerifier.kt  ★ 调 Node 校验器（151 行）
│           ├── NativeAssetRegistry.kt ★ 原生资产唯一事实来源
│           ├── HostBridgeService.kt   L3 能力桥服务端
│           └── native/                原生资产抽象层
│
├── container-engine/             纯 Node、零外部依赖的 OTA 引擎 + 测试
│   ├── src/
│   │   ├── ota-engine.js             验签/解包/原子切指针
│   │   ├── kernel-bundle.js          内核包打包（含排除表）
│   │   ├── zip.js                    自实现 zip（支持 Deflate）
│   │   ├── sign.js / verify.js        ed25519
│   │   ├── keys.js                   密钥加载（路径可用环境变量覆盖）
│   │   └── bridge/methods.js         ★ 能力令牌与方法门禁表
│   └── test/                     12 套件 / 228 条断言
│
├── scripts/
│   ├── keygen.sh                     生成内核 ed25519 密钥对
│   ├── keygen-android-keystore.sh    生成 APK 签名 keystore
│   ├── build-kernel-baseline.sh      产无网首启基线包
│   ├── build-kernel-feed.sh          ★ 产可投递 feed（A'' 第三段）
│   ├── build-kernel-bundle.sh        产单个签名内核包
│   ├── build-node-android.sh         NDK 交叉编译 Node（2~3 小时）
│   └── validate-workflow.py          CI 前置 YAML 校验
│
├── docs/
│   ├── HANDOVER.md               ← 本文
│   ├── ARCHITECTURE.md           硬约束原理（653 行，最值得读）
│   ├── BASE_SPEC.md              三层架构规格
│   ├── BRIDGE_PROTOCOL.md        桥协议契约
│   ├── PROVISIONING.md           设备 provisioning
│   └── CONTRIBUTING.md           贡献指南
│
├── keys/                         ⚠ 白名单 gitignore，默认全忽略
│   ├── ota-private.pem           内核 ed25519 私钥
│   ├── ota-public.pem            内核公钥（与 APK 内锚点配对）
│   ├── release.keystore          APK 签名 keystore
│   └── keystore.properties       上述密码
│
└── .github/
    ├── native-assets.txt         NativeAssetRegistry 的投影（CI 读它）
    └── workflows/                7 个（见 §7）
```

---

## 7. CI 工作流

| workflow | 触发 | 用途 | 耗时 |
|---|---|---|---|
| `fast-apk.yml` | `main` push（8 条 paths）+ `fast-*` tag | **日常出包**（复用缓存的 Node） | ~5 分钟 |
| `build-apk.yml` | `main` push（7 条 paths-ignore） | 全量发布包（含 Node 交叉编译） | **2~3 小时** |
| `kernel-ota.yml` | `kernel-ota-*` tag | 签内核包 + 产 feed + 发 Release | 分钟级 |
| `pin-node.yml` | `pin-node-*` tag | 固化 Node 产物版本 | 分钟级 |
| `publish-apk.yml` | `publish-*` tag | 发布 APK | 分钟级 |
| `repack-apk.yml` | `repack-*` tag | 重打包（注入 libc++ 等） | 分钟级 |
| `admin.yml` | `admin-*` tag | 运维通道（见下） | 秒级 |

### 7.1 admin 回执通道（沙箱环境的关键工具）

维护环境**无法访问 GitHub 网页**，也无法用 `gh run view` 取日志
（blob 日志端点被网络限制阻断）。所有 CI 交互走 **tag + git 分支**：

| 推这个 tag | 效果 |
|---|---|
| `admin-<任意>` | 通用入口 |
| `admin-cancel-<run_id>` | 取消指定 run |
| `admin-status-<run_id>` | 查询状态（写入 `ci-admin` 分支） |
| `admin-logs-<run_id>` | 取失败步骤日志（写入 `ci-admin`） |
| `admin-cancelall` | 取消所有非本 run 的排队任务 ⚠️ **会误伤自己的验证 run** |
| `admin-release-<tag>` | 发布 Release |
| `admin-apk-<run_id>` | 校验 APK 的 `lib/` 内容与 ELF 形态 |

回执分支：`ci-admin`（管理员回执）/ `ci-ok`（出包成功）/ `ci-last`（最终报告）/ `ci-diag`（失败诊断）。

> ⚠️ **踩坑记录**：`admin-cancelall` 曾把我自己的两个验证 run 一起取消掉
> （tag 与 main 双触发各生成一个 run）。用它之前先想清楚有没有在跑的验证。

---

## 8. 快速上手

### 8.1 跑测试（不需要 Android SDK）

```bash
cd container-engine
npm run test:logic      # 228 条断言，秒级；不依赖任何产物
npm run test:baseline   # 8 条断言；验 app/src/main/assets/kernel/baseline.zip
```

若内核仓不在默认位置，跨仓测试会**显式 SKIP**（不是崩溃）：

```bash
DSH_KERNEL_REPO=/path/to/dsh-android-kernel npm run test:logic
```

### 8.2 出包

```bash
# 方式一：本地（需 Android SDK）
./scripts/build-apk-local.sh

# 方式二：走 CI（推荐，环境一致）
git push origin HEAD:refs/tags/fast-verify-$(git rev-parse --short HEAD)
```

### 8.3 生成内核包 / feed

```bash
# 首次：生成内核签名密钥对（会写 keys/ota-private.pem + 焊公钥进 APK）
./scripts/keygen.sh

# 产单个签名内核包
./scripts/build-kernel-bundle.sh <内核源码目录> <版本号>

# 产可投递 feed（A'' 自举用；含三重构建期校验）
./scripts/build-kernel-feed.sh <内核源码目录> 0.1.0-android.1

# 投递到设备（设备下次启动自动发现并安装）
adb push feed/kernel-*.zip feed/kernel-manifest.json /sdcard/dsh/kernel-feed/
```

### 8.4 生成 APK 签名密钥（首次发布前）

```bash
./scripts/keygen-android-keystore.sh
# 产物：keys/release.keystore + keys/keystore.properties
# ⚠ 立刻备份！丢了就再也无法给已装设备推升级
```

---

## 9. 本轮（A'' 路线）做了什么

用户选的路线是「**A'' OTA 级自举**」：*设备只安装已签名内核，不生产内核*。

**为什么不是「设备内置构建链」（A/C1）**：已实测证伪。
`aapt2` 在 Google Maven 上只有 `linux`/`osx`/`windows` 三个 classifier，
全是 x86_64；`linux-aarch64` / `linux-arm64` 均 **HTTP 404**。
W^X 四道关里的 2/3/4 关（interp/架构/libc）**装机后无法补救**。

### 提交清单

| commit | 内容 |
|---|---|
| `8f2fffb` | A'' 内核自举主体 —— 从本地 feed 安装已签名内核，修 4 个真缺陷 |
| `927e30f` | 修正测试与产物生成的**步骤顺序** —— 消除一次必然假红 |
| `222b2d1` | A'' 闭环补最后一段 —— 构建侧产出可投递 feed + 修 3 个跨仓/产物耦合缺陷 |
| `0197201` | 补齐**稳定签名身份** —— 设备自我升级 APK 的绝对前提 |
| `c5bb718` | 架构文档补「两把独立信任根」与「稳定签名身份」两节 |

### 三段闭环

```
① 构建侧产签名内核包    scripts/build-kernel-bundle.sh
        ↓
② 组织成设备能消费的 feed  scripts/build-kernel-feed.sh
        ↓   (kernel-*.zip + kernel-manifest.json，文件名倒序取第一个)
③ 设备侧从 feed 安装      LocalKernelFeed.kt + KernelInstaller.kt + kernel-verify.js
```

### feed 目录约定（bash ↔ Kotlin 的跨语言契约）

| 文件 | 必需性 | 作用 |
|---|---|---|
| `kernel-<version>.zip` | **必需** | 设备端按**文件名倒序**取第一个 |
| `kernel-manifest.json` | 可选但强建议 | `sha256` / `version` 锚点，挡重放 |
| `KERNEL-FEED-README.txt` | 可选 | 人读说明 |

> 没有编译器能校验这个契约，所以 `kernel-feed-test.js` 用 23 条断言把它钉死。

### 本轮修掉的真缺陷（都值得记住）

1. **`build-bundle.js` 产裸文件名 url** —— `urlBase` 为空时 `url="kernel-x.zip"`，
   无 scheme/host，设备端既不能解析也不能下载。改为空串。
2. **`build-bundle.js` 输出目录硬编码** —— 脚本拷到别处后产物落错位置。
   新增 `DSH_BUNDLE_OUT_DIR`。
3. **`build-kernel-feed.sh` 无法用自造密钥** —— 签名用生产私钥而验签用自造公钥
   → `signature-invalid`。新增 `DSH_OTA_PRIVATE_KEY_PATH` /
   `DSH_OTA_PUBLIC_KEY_PATH`（**刻意复用 `keys.js` 已有的变量名**，
   避免"设了 A 却没生效"这类最难查的配置问题）。
4. **CI 步骤顺序错误** —— 把"要求产物存在"的断言排在"生成产物"之前，
   制造了一次必然假红。修复方式是按**产物生命周期**把测试拆成
   `test:logic`（秒级快失败）/ `test:baseline`（在产物生成之后）。
5. **跨仓测试在单仓 CI 上崩** —— `require()` 写死绝对路径，
   单仓 CI 上在 require 那一刻就 `Cannot find module`，一条断言都打不出来。
   改为路径可配置 + **显式 SKIP**（并说明"未验证什么"）。
6. **`e2e-mock-kernel-test.js` 读生产私钥** —— `keys/ota-private.pem` 被
   `.gitignore` 排除（刻意），CI 上必然 `ENOENT`。改用测试内自造密钥对。
7. **🔴 私钥泄漏路径** —— `keys/` 原先按后缀拉黑（`keys/*.pem`），
   新增的 `release.keystore` 不在规则内，`git add -A` 直接把它和密码文件
   暂存了。改为**白名单制**：默认全忽略，只放行 `keys/README.md`。
8. **`keytool` 指纹正则永不匹配** —— 写的是 `"SHA256:"`，
   实际输出是 `Certificate fingerprint (SHA-256):`。健康路径也会误报失败。

---

## 10. 方法论：这个项目验证问题的方式

比具体代码更容易被忽略、但更值钱的部分。**建议沿用**。

### 10.1 双环境验证

不只跑本地，还建**干净单仓副本**（无内核仓、无 `keys/`、无基线包）模拟 CI：

```
本地（有内核仓+密钥+基线包）：  228 passed / 0 failed / rc=0
干净单仓（CI 等价）：           208 passed / 0 failed / rc=0，bridge-interop 显式 SKIP
```

**这一步才暴露出 `e2e-mock-kernel-test.js` 读生产私钥的问题** —— 本地永远测不出来。

### 10.2 用真实代码路径测试，不手抄逻辑

验 CI 签名步骤时，我**从 workflow 文件里抽出真实 `run` 脚本**驱动三种情形执行，
而不是手写一份等价逻辑。手抄的测试永远发现不了"正则写错了"这类问题。

### 10.3 反向用例是必需的，不是加分项

每个正向断言都该有对应的反向断言。例：`kernel-feed-test.js` 的 23 条里
有 3 条是反向的（私钥缺失 → 必须 rc≠0 且**不产未签名包**且**不留输出目录**；
公私钥不配对 → 必须 rc≠0）。

### 10.4 「注释说得好、代码编译不过」是本项目的存量病

历史上有 6 处这类缺陷。**凡改 Gradle Kotlin DSL，必须真实编译一次**
（`:app:tasks --dry-run` 就够，会走完整配置阶段求值）：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
/opt/gradle/gradle-8.9/bin/gradle --offline --no-daemon :app:tasks --dry-run
```

### 10.5 退出码不要被管道吃掉

`harness.finish()` 确实 `process.exit(1)`。但 `cmd | tail` 的退出码是 `tail` 的。
**取真实退出码必须用 `PIPESTATUS[0]` 或重定向到文件**。

---

## 11. 接手后建议的第一步

1. **先跑一遍测试**，确认基线：`cd container-engine && npm run test:logic`
2. **确认密钥安全**：`keys/release.keystore` 是否已妥善备份？（见 §3.2 第 3 条）
3. **配置 CI secret**（见 §3.2 第 2 条），否则产物不可升级
4. **读 `docs/ARCHITECTURE.md`** —— 653 行，但它是所有硬约束的唯一权威说明
5. **处理 §3.2 第 1 条**（12 项审计里的 3 个 P0）—— 这是当前最大的技术债

---

## 12. 术语表

| 术语 | 含义 |
|---|---|
| **L0 / L1 / L2 / L3** | 容器 / 内核 / Agent / HostBridge 四层 |
| **A''** | 已选路线：设备只安装已签名内核，不生产内核 |
| **feed** | 内核包的投递目录（`kernel-feed/`），设备从中发现并安装 |
| **基线包** | `baseline.zip`，内置在 APK 里，供**无网首启**使用 |
| **能力令牌** | `BRIDGE_TOKENS`，8 组；方法调用前鉴权 |
| **`kernel_update`** | 「能安装已签名内核」——**任意设备具备** |
| **`build_chain`** | 「有编译工具链」——**已证伪，永不置位** |
| **W^X** | 内存页要么可写要么可执行，SELinux 据此禁 exec 可写目录 |
| **原子切指针** | `CURRENT` 指针指向当前版本目录，切换是 rename（不会半包残留） |
| **自证循环** | 被校验的内核自己提供校验器 → 签名无效的内核也能宣布自己有效 |

---

*本文档记录的是截至 `c5bb718` 的**实测**状态。文中所有"已验证"字样
均对应可复现的测试或 CI 记录，未经验证的推测已显式标注。*
