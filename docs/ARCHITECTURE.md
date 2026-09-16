# DSH Mobile · 架构与约束

本文件是**唯一的事实来源**。代码注释只写"改这里必须知道什么"，历史排查过程、
外部依据、失败现象全部收在这里 —— 避免同一件事在多个文件里各写一份、
改一处忘两处。

---

## 1. 这个项目是什么

在**安卓原生环境**（免 Termux、免 root）里跑 Node.js，并把它当成一个可被
WebView 访问的本地 HTTP 服务。

核心难点不在 Node 本身，而在**安卓的进程与安全模型**。下面每一条都是真机
实测踩出来的硬约束，不是理论推演。

```
┌─────────────────────────────────────────────────────────────┐
│  APK                                                        │
│  ├── jniLibs/arm64-v8a/libnode.so         ← Node 可执行文件  │
│  ├── jniLibs/arm64-v8a/libc++_shared.so   ← 它的 C++ 运行时  │
│  └── assets/node/server.js                ← 探针服务         │
└─────────────────────────────────────────────────────────────┘
                          │ 安装时系统解压
                          ▼
   /data/app/~~xxx/<pkg>-yyy/lib/arm64-v8a/    ← 唯一可 exec 的目录
                          │ exec
                          ▼
   Node 进程（前台服务 :node）→ 监听 127.0.0.1:3080
                          │ HTTP
                          ▼
   MainActivity 的 WebView
```

---

## 2. 硬约束一：SELinux W^X —— 可执行文件只能放在哪

**结论：应用私有文件里，只有 `nativeLibraryDir` 允许 `execve()`。**

| 路径 | SELinux label | 能否 exec |
|---|---|---|
| `/data/data/<pkg>/files/` | `app_data_file` | ❌ 禁止（`error=13`） |
| `/data/app/<pkg>/lib/<abi>/` | `exec_type` | ✅ 允许 |

安卓 10+ 强制执行 W^X（不可写且可执行）。Google 官方在
[issuetracker 128554619](https://issuetracker.google.com/128554619) 的回复明确说是设计如此：

> Calling exec() on writable application files is a W^X violation...
> While exec() no longer works on files within the application home directory,
> it continues to be supported for files within the read-only /data/app
> directory. In particular, it should be possible to package the binaries into
> your application's native libs directory and enable
> `android:extractNativeLibs=true`, and then call exec() on the /data/app
> artifacts.

**真机失败现象：**
```
IOException: Cannot run program ".../files/node/24.21.0/node":
error=13, Permission denied
```

### 由此推出的三条硬规则

1. **二进制必须以 `jniLibs/<abi>/lib*.so` 的形式打包。**
   文件名必须以 `lib` 开头、`.so` 结尾 —— 否则 AGP 不会把它当 native lib
   处理，也就不会解压到那个可执行目录。

2. **必须解压落盘，不能留在 APK 里 mmap。**
   两个等价开关，本项目两处都写了：
   - `AndroidManifest.xml` 的 `android:extractNativeLibs="true"`
   - `app/build.gradle.kts` 的 `packaging.jniLibs.useLegacyPackaging = true`

   AGP 3.6+ 默认 `false`（压缩留在 APK 内、运行时 mmap 加载）。那种模式下
   `File.exists()` 都是 `false`，更不可能被 exec。
   代价是 APK 变大，对本地运行时是必要且可接受的。

3. **`File.canExecute()` 完全不可信。**
   它只查 `stat` 的 x 权限位，对 noexec 挂载和 SELinux 策略无感，
   会在 `filesDir` 那份文件上返回 `true`（假阳性）。
   **判断"能否执行"的唯一可靠办法是真去执行一次** —— 即启动流程里的
   `exec-probe` 步骤。

### 连带影响：OTA 升级方案不成立

`nativeLibraryDir` 的路径形如 `/data/app/~~<随机>/<pkg>-<随机>/lib/arm64-v8a/`，
**每次 APK 更新随机串都会变**，且那里只能有一份、由安装决定。

所以"在沙箱放多个版本、切换指针"的 OTA 设计在安卓 10+ 上无法实现。
`NodeVersionManager` 原先的下载/校验/解压/切指针整条链路已删除
（它是纯死代码，且 `isInstalled()` 会返回"看起来成功、实际必然失败"的结果）。

**现在的升级路径**：换 Node 版本 = 用新的预编译二进制重新出一次 APK。
这正是 `fast-apk.yml` 存在的原因 —— 几分钟而不是几小时。

### 2.1 从「一个二进制」到「一组原生资产」

上面三条规则对**每一个**可执行资产都成立。一旦有第二个（例如未来的 APK
重打包工具），"哪些文件要能 exec、各自依赖什么、怎么验证"就必须是**数据**
而不是散落的特判。

**唯一事实来源**：`app/src/main/java/com/example/nodecontainer/native/NativeAssetRegistry.kt`

```kotlin
val LIBCXX = NativeExecutable(
    id = "libcxx", libName = "libc++_shared.so", humanName = "C++ 运行期",
    probeArgs = emptyList(), probeExpect = null,      // 数据资产，不做 exec-probe
    requiredDeps = emptyList(), required = true,
    note = "不是可执行文件，但必须在 nativeLibraryDir —— libnode.so 的 DT_NEEDED 依赖它",
)
val NODE = NativeExecutable(
    id = "node", libName = "libnode.so", humanName = "Node 运行时",
    probeArgs = listOf("-v"), probeExpect = "v",
    requiredDeps = listOf("libc++_shared.so"), required = true,
    note = "实为可执行文件，改名 lib*.so 借 jniLibs 通道落到 exec_type 目录",
)
```

**统一引擎**：`native/NativePreparer.kt`。启动链与桥方法都走它，不存在两套实现。

#### exec 的四道关

一个 ELF 想在 Android aarch64 上跑起来，必须**同时**越过：

| 关 | 要求 | 装机后能否补救 |
|---|---|---|
| 1. W^X | 落在 `nativeLibraryDir`（`exec_type`） | 能（改打包） |
| 2. interp | `PT_INTERP` = `/system/bin/linker64` | **不能**（只能换二进制） |
| 3. 架构 | `e_machine` = `0x00b7`（aarch64） | **不能** |
| 4. libc | `DT_NEEDED` 只能是 bionic / 随包库 | **不能** |

第 2/3/4 关在装机后无法补救，所以必须在**打包期**用 readelf 校验。
`pin-node.yml` 已经做了这三项校验；这也是「A 全内置工具链」方案
（aapt2 等 x86-64 + glibc 的 Google 官方产物）被判定不可行的直接原因 ——
它在第 2/3/4 关全部失败，只剩 QEMU user-mode + 随包 amd64 glibc 一条路，
而那在 SELinux 受限的 `untrusted_app` 域里没有公开成功先例。

#### 验证顺序即修复（不要打乱）

`NativePreparer.verify()` 严格按此序，**任一步失败立即返回、不再往下走**：

```
① 存在性    nativeLibraryDir 里有没有这个文件
             ↳ 没有 → 查 APK 内是否有该条目 → MissingFromLib(inApk)
② 依赖前置  遍历 requiredDeps，每个都必须在同目录
             ↳ 缺 → MissingDependency
③ exec 探针 【仅对 probeArgs 非空的资产】真跑一次
             ↳ IOException → NotExecutable（此时可确定归因 SELinux）
             ↳ exit≠0 或 stdout 缺片段 → ProbeFailed
```

**顺序本身就是修复。** 历史实现里这三件事分散在三个地方，且依赖检查
**只打日志、从不阻断**。叠加出的真实故障是：

> `libc++_shared.so` 缺失 → exec-probe 以 linker 错误失败 → errno 是 `13`
> → 归因走到「该路径被 SELinux 禁止 exec」→ 排查者去查 SELinux 与解压路径
> → **真因（依赖库缺失）永远浮不出来**。

把依赖检查提到 exec-probe **之前**，`error=13` 才能被唯一地归因到 W^X。

#### 五处清单，一个源头

「哪些 `.so` 随包」这件事在仓库里以 5 种形态存在。① 是权威源，②–⑤ 是投影：

| # | 位置 | 作用 |
|---|---|---|
| ① | `native/NativeAssetRegistry.kt` | **权威源** |
| ② | `app/build.gradle.kts` `keepDebugSymbols` | 防 strip 破坏 |
| ③ | `.github/native-assets.txt` | CI 下载校验 + APK 审计 |
| ④ | `scripts/native-deps.txt` | 构建期 NEEDED 闭环自检（系统库白名单） |
| ⑤ | `scripts/inject-libcxx-into-apk.py` | 注入锚点 |

② 直接读 ③（`nativeAssetNames`），所以实际只需同步 ③④⑤。
**一致性由 `container-engine/test/native-assets-test.js` 双向守护**
（正向：注册表每项下游都有；反向：下游没有注册表未声明的项）。
已用变异测试验证：改注册表名、删清单项、加幽灵项、在别处重新硬编码 ——
四种漂移全部被捕获。

> 为什么值得单独写个测试：这类问题的暴露路径是
> 「跑 3 小时 CI → 装到真机 → 失败」，而测试只需几秒。
> 更麻烦的是缓存相关的那种间歇形态 —— 命中缓存才炸、首次完整编译却正常。

---

### 2.2 硬约束四：ED25519 在 Android 上要 API 33+，而 minSdk 是 24

这条约束**决定了内核升级链的分层**，且它在项目早期被漏掉了。

**事实（Android 官方 `Signature` 算法支持表）：**

| 算法 | 起始 API |
|---|---|
| ECDSA | 11+ |
| **Ed25519** | **33+** |
| RSA 系列 | 1+ |

而本项目 `minSdk = 24`（Android 7.0）。所以：

> **在 API 24–32 的设备上，Kotlin 侧 `Signature.getInstance("Ed25519")`
> 直接抛 `NoSuchAlgorithmException`。Kotlin 无法验签。**

旁证（真实工程教训，见 openclaw#5475）：Conscrypt 官方支持 Ed25519，但
API 31–32 的平台 BouncyCastle 被裁剪、不含 Ed25519；API 33+ 上
`KeyFactory.getInstance("Ed25519")` 还会**静默**解析到 AndroidKeyStore
provider（只认硬件密钥，导入软件 PKCS8 会 `InvalidKeySpecException`）。
即「能拿到 Signature 实例」与「能用」是两件事 —— 所以不能靠 try/catch 探测。

**由此推出的分层（`kernel_update` 链）：**

```
┌─ Kotlin（宿主进程 :main，生命周期长）────────────────────┐
│  KernelInstaller   编排：解包 / 原子 rename / 切 CURRENT  │
│  LocalKernelFeed   发现：扫本地 feed 目录                 │
│  ── 不含任何密码学 ──                                     │
└──────────────────┬──────────────────────────────────────┘
                   │ spawn 一次性进程，传 zip + 公钥路径
                   ▼
┌─ Node（一次性进程，用后即弃）─────────────────────────────┐
│  kernel-verify.js  sha256 + ed25519 验签 + zip 结构校验    │
│  ── crypto.verify 走自带 OpenSSL，与 API level 无关 ──     │
└──────────────────────────────────────────────────────────┘
```

**为什么这样切分（每一条都是具体问题的解）：**

| 决策 | 原因 |
|---|---|
| 验签放 Node | `minSdk=24` + `Ed25519 33+` 的硬约束，Kotlin 做不到 |
| 落盘/切指针放 Kotlin | Node 进程随时可能被杀；让"随时消失的进程"管"自己下个版本"的落盘是竞态来源（写到一半被杀 → 半包残留） |
| 校验器随 APK 而非内核 | 否则「签名无效的内核只要能启动就能宣布自己有效」—— 自证循环 |
| 用一次性进程而非常驻内核 | ① 首启还没有内核，基线包也要验；② 不给内核提权机会；③ 失败可观测（退出码 + 原始输出进诊断） |

**`kernel_update` vs `build_chain`** —— 两个能力令牌，别混用：

| 令牌 | 含义 | 设备是否具备 |
|---|---|---|
| `kernel_update` | 从本地 feed **安装已签名内核**（读文件 + 验签 + 写 filesDir） | **是**，任意设备 |
| `build_chain` | 设备上有**编译工具链**（aapt2/d8/JDK） | **否，永不置位** |

后者已被实测证伪（见下节）。原先 `bridge:build` 整组绑在 `build_chain` 上，
于是因为一个永不具备的能力而**永远返回 `-32001`** —— 一个沉默且代价极高的失败。
拆开后，"安装内核"这半条链立刻可用。

### 2.3 为什么「内置构建链」不可行（A/C1 已证伪）

原始诉求是「**离线、设备自举、不依赖外网/PC**」。最初的方案是
把 `aapt2` + d8 + JDK 打进 APK（方案 A：全内置 / C1：最小子集）。
**实测推翻了这条路 —— 它的地基不存在。**

```
GET maven.aliyun.com/repository/google/com/android/tools/build/aapt2/<V>/
  classifier=linux          -> 200 (2,385,035 B)   ← 解包实为 x86-64
  classifier=linux-aarch64  -> 404
  classifier=linux-arm64    -> 404
  classifier=osx            -> 200
  classifier=windows        -> 200
```

解包 `aapt2` 实况：`e_machine=0x3e`（x86-64）、
`PT_INTERP=/lib64/ld-linux-x86-64.so.2`、NEEDED 含 6 个 glibc 库。
（能力本身没问题 —— `strings` 能检出 compile/link/dump/diff/optimize 全部子命令。
纯粹是「在 aarch64 上跑不起来」。）

**对照 exec 四道关（§2.1）：**

| 关 | 要求 | aapt2 现状 | A/C1 |
|---|---|---|---|
| 1. W^X | 落 `nativeLibraryDir` | — | 可解 |
| 2. interp | `/system/bin/linker64` | `/lib64/ld-linux-x86-64.so.2` | ❌ |
| 3. 架构 | aarch64 | x86-64 | ❌ |
| 4. libc | bionic | 6 个 glibc 库 | ❌ |

第 2/3/4 关**装机后无法补救，只能换二进制** —— 而 aarch64 版 aapt2 不存在。

**转向 A''：设备只安装已签名内核，不生产内核。**

| | A/C1 内置构建链 | **A'' 本地 feed 安装** |
|---|---|---|
| APK 体积 | +300~500 MB | **+1.2 MB**（基线包） |
| 需要新原生二进制 | 整套工具链 | **无** |
| 触碰 W^X 链 | 是 | **否** |
| 私钥在设备上 | 需要（要签名） | **不需要**（只验签） |
| 信任面 | 扩张到整条工具链 | **不扩张** |
| 覆盖「DSH 改自己内核」 | ✅ | ✅ |

关键洞察：**「DSH 改自己的内核」根本不需要碰 APK**。内核是
`files/kernel/<version>/` 下的一个数据目录，容器本来就有权改它。
唯一缺的是「设备上从哪拿到新内核包」—— 这就是 `LocalKernelFeed` 补的那一步。

### 2.4 两把独立的信任根（极易混淆，必须辨析）

本项目有**两套密码学身份**，它们没有任何关系、不可互相替代。混用会导致
"验签通过却装不上"这类极难定位的问题：

| | APK 签名 | 内核签名 |
|---|---|---|
| 算法 | Java keystore（RSA 4096 / EC） | ed25519 |
| 管什么 | 「这个 APK 是不是同一发布者发的」 | 「这个内核包是不是官方签的」 |
| 信任锚点 | **设备上已装的那个包**的签名 | **焊死在 APK 里**的 `assets/ota-public.pem` |
| 谁校验 | Android 安装器（PackageManager） | 设备端 `kernel-verify.js`（Node，随 APK 冻结） |
| 生成 | `scripts/keygen-android-keystore.sh` | `scripts/keygen.sh` |
| 产物 | `keys/release.keystore`（gitignored） | `keys/ota-private.pem`（gitignored） |

两点容易搞反的推论：

* 内核包签名正确 **≠** APK 能装到设备上。前者不影响安装器决策。
* APK 签名稳定 **≠** 内核包可信。攻击者用自己的 keystore 重签一个 APK，
  签名一样"稳定"，但里面的 `ota-public.pem` 换了 → 设备会拒装外来内核。

### 2.5 为什么「稳定签名身份」是自我升级的前提

原项目**没有任何 `signingConfig`** ⇒ 每次 CI 出包用 AGP 现场生成的
debug keystore（runner 每次重建，指纹每次不同）⇒ 新包覆盖安装旧包时
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

这条链断掉的不只是一个便利功能，而是整条「设备自我升级」能力：

* A'（重打包路线）依赖它 —— 新 APK 必须能装到旧 APK 上；
* `app.install` 静默升级能力依赖它；
* 增量升级、灰度推送等一切「应用身份稳定」机制都依赖它。

现在 `app/build.gradle.kts` 从 `keys/release.keystore` 注入签名，CI 从
secret 解码。三条路径的语义是刻意设计的：

| 情形 | 行为 | 理由 |
|---|---|---|
| 有 keystore + 有密码 | 用稳定签名 | 正常发布路径 |
| **无** keystore | WARNING 后退化 debug，**不阻断构建** | 本地开发/PR 只需能装的包；强制要求密钥会卡住所有这类场景 |
| 有 keystore 但密码空 | **前置报错** | 否则要等整个构建（release 含 native 交叉编译）跑完才在 apksigner 那步炸 |

⚠ **`keys/release.keystore` 一旦用于真实发布必须永久保存**：丢掉它 = 再也
无法给已装该应用的设备推升级，只能让用户卸载重装（会清掉 `files/` 下全部
内核与 Agent 数据）。Android 生态**没有"换回旧签名"的机制**。

---

## 3. 硬约束二：linker 找不到 `libc++_shared.so`

**结论：必须显式设置 `LD_LIBRARY_PATH`。**

**真机失败现象：**
```
CANNOT LINK EXECUTABLE ".../lib/arm64-v8a/libnode.so":
cannot locate symbol "_ZTVNSt6__ndk119basic_ostringstream..."
```

### 根因

Android linker 查找依赖库的目录**只有三个**：

1. `$LD_LIBRARY_PATH` 里的目录
2. 二进制 `DT_RUNPATH` 动态段列出的目录
3. 系统默认路径 `/system/lib64`、`/system/lib`

> Termux 官方 wiki《Termux execution environment》特别指出：
> *"The DT_RPATH dynamic section attribute of the binary and the ld cache file
> (/etc/ld.so.cache) ... is not used."*
> 即 **`DT_RPATH` 在 Android 上被忽略，只有 `DT_RUNPATH` 有效**。

**`nativeLibraryDir` 不在这三者中的任何一个。** 它只在 Java 层
`dlopen` / `System.loadLibrary` 时才进搜索路径；而我们是 **exec 一个可执行
文件、由它自己拉起依赖**，完全是另一套规则。

`readelf` 逐条核对 `libnode.so` 的动态段（29 个条目）确认：

```
NEEDED: libm.so / libdl.so / liblog.so / libc++_shared.so / libc.so
无 DT_RUNPATH、无 DT_RPATH、无 DT_SONAME
```

于是它只能查系统默认路径，那里没有 `libc++_shared.so`
（**它不是 bionic 的一部分**，安卓系统不提供）。

### 解法

```kotlin
pb.environment().put("LD_LIBRARY_PATH", applicationInfo.nativeLibraryDir)
```

探针与正式启动**两处都必须设**，漏一处就挂。

> 外部印证：[viliussutkus89.com — Distributing Android CLI programs in APKs](https://viliussutkus89.com/posts/distributing-android-cli-programs-in-apks)
> 场景与本项目完全一致，连报错形状都一样。作者结论：
> *"nativeLibraryDir is not among the directories which are searched for,
> when loading libraries."*
> *"Could be solved by linking executables with rpath=$ORIGIN flag, but
> strangely it does not work on all devices."*
> *"Use LD_LIBRARY_PATH environment variable, it just works."*

**不要改链接参数加 `$ORIGIN` rpath** —— 那要重编，且只在部分设备有效。
`LD_LIBRARY_PATH` 是跨设备可靠的那个。

> 注：`ProcessBuilder` 是直接 exec、不经过 shell，所以环境变量的值就是
> 路径原文，不涉及任何 shell 展开或引号处理。

---

## 4. 硬约束三：`libc++_shared.so` 必须随包提供

`libnode.so` 的 `DT_NEEDED` 里有 `libc++_shared.so`，符号由它提供
（`std::__ndk1::basic_ostringstream` 等）。它**不在 Android 系统里**，
必须从 NDK 拷一份随 APK 打包：

```
$ANDROID_NDK/toolchains/llvm/prebuilt/<host>/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so
```

未压缩约 9.29 MB。

### 曾经的隐患：缓存只存了 node 本体

有一轮缓存保存逻辑只存了 `out/Release/node`，漏了 `libc++_shared.so`。
后果是**命中缓存时**打出的 APK 缺库，未命中（完整编译）时反而正常 ——
表现为"第一次能跑、之后反而不行"的间歇性故障，极难排查。

现已修复：缓存保存/恢复两侧都连带处理该文件；旧缓存则从 NDK 补齐，
补不上就**直接失败**（宁可不出包，也不出一个真机跑不起来的包）。

---

## 5. 打包细节：两个 `.so` 不能被 strip

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = true
        // 直接从 .github/native-assets.txt 读（NativeAssetRegistry 的投影）——
        // 不再硬编码文件名，加资产时 gradle 配置自动跟上。
        keepDebugSymbols += nativeAssetNames.map { "**/$it" }
    }
}
```

**注意现在读的是清单文件，不是字面量列表**（见 §2.1 的五处清单）。
清单缺失/为空时直接抛 `GradleException` —— 刻意不静默降级，
否则会产出「编译成功但真机跑不起来」的 APK，那种问题排查成本远高于一次构建失败。

`keepDebugSymbols` 是旧 API `doNotStrip` 的替代
（AGP 文档原话：*"Use jniLibs.keepDebugSymbols.add() instead."*）。
**命名有误导性** —— 它不只是"保留调试信息"，实际语义是
"这些 `.so` 不要交给 strip 处理"。

为什么必须豁免：

- `libnode.so` —— 其实是一个可执行文件，只是改名为 `lib*.so` 借 `jniLibs`
  通道落到可执行目录。strip 会破坏它被 exec 所需的信息。
- `libc++_shared.so` —— node 的运行期动态依赖，strip 掉符号表只会让
  动态链接更无解。

这些文件由 CI 用与 node 相同的 NDK 亲自挑选/产出，不需要 AGP 再加工。

---

## 6. 应用侧：端口约定与参数解析

App 侧启动命令：

```kotlin
ProcessBuilder(nodeBin, script, "--port", "3080")
// → process.argv = [nodeBin, script, "--port", "3080"]
// → argv[2] = "--port"
```

**`server.js` 必须按标志位解析，不能直接取 `argv[2]`。**

曾经写成 `parseInt(process.argv[2] || '3080', 10)`，于是
`parseInt("--port")` → **`NaN`**（安静返回，不抛错），接着
`server.listen(NaN, ...)` 抛：

```
RangeError [ERR_SOCKET_BAD_PORT]: options.port should be >= 0 and < 65536.
Received type number (NaN).
```

进程随即 `exitCode=1` 退出。**现象是"Node 启动瞬间就死"，看起来像二进制
有问题，实际纯粹是参数解析 bug。** 真机上这个进程只活了约 0.17 秒
（12:36:26.959 → 12:36:27.126），比端口轮询周期还短。

现在的 `server.js`：
- 优先找 `--port <n>` 标志
- 兼容位置参数（`argv[2]` 是纯数字时）
- 都不给则用默认 3080
- 解析结果做范围校验，非法则打印可读原因并 `process.exit(2)`
- 启动时打印 `argv`，下次出问题一眼可见

---

## 7. 另一类坑：诊断信息本身不可信

排查时最怕的不是没有报错，而是**报错是错的**。

### `node-stderr` 显示为空，但 node 明明打印了错误

`forward()` 在**独立线程**里逐行读 stderr 并写文件，`watchExit()` 在
`waitFor()` 返回后立刻读同一个文件 —— 两者无任何同步。进程死得快时
（就是上面那个 0.17 秒的例子），读取线程还没被调度到，文件自然是空的。

诊断于是显示 `(node 无 stderr 输出)`，把排查引向"是不是二进制有问题"
的错误方向。

**修法**：读之前轮询等待文件出现内容（最多 1.5 秒，正常第一轮即命中），
并在文案里带上实际等待时长 —— 便于区分"真没输出"和"读取太慢没赶上"。

> 顺带记一个 Kotlin 细节：这里必须用 `while` 而非 `repeat {}`。
> `repeat` 是内联 lambda，里面的 `return@repeat` 语义只相当于 `continue`，
> **跳不出整个循环**，会被误解成"读到了就收工"。

### `File.canExecute()` 的假阳性

见第 2 节。它会在 `filesDir` 的那份文件上返回 `true`，而那份根本不能 exec。
所以诊断里把它标为"仅供参考"，真正的结论由 `exec-probe` 给出。

---

## 8. 构建体系：什么改动需要重编 Node

**这是本项目的效率核心。** Node 交叉编译（两份 V8：host x64 + target
aarch64）在 GitHub 免费 runner 上要 **2~3 小时**。

### 决策表

| 改动内容 | 走哪条 workflow | 耗时 |
|---|---|---|
| `assets/**`（server.js）、`**/*.kt`、布局、`build.gradle.kts` | **`fast-apk.yml`** | 分钟级 |
| `scripts/build-node-android.sh`、Node 版本变更 | `build-node.yml` | 2~3 小时 |
| 只想把已有产物发到 Release | `publish-apk.yml` | 几分钟 |

`fast-apk.yml` 不编译 Node —— 它从固定的 Release
（`node-runtime-<version>-<abi>`）下载预编译的 `libnode.so` +
`libc++_shared.so`，校验 sha256，放进 `jniLibs/`，然后直接跑 gradle。

### 预编译产物是怎么来的

`pin-node.yml` 负责**固化**：从某次成功的 `build-node` 运行里取出两个 `.so`，
逐项校验后发布为不可变 Release。

校验项（任一不过即失败，绝不放行坏产物）：

1. ELF 架构必须是 `ARM aarch64`
2. 解释器必须指向 `/system/bin/linker64`（否则是 glibc 链接，真机无法 exec）
3. 全部 `LOAD` 段必须 `0x4000` 对齐（Android 15+ 要求，16KB 页）
4. `DT_NEEDED` 依赖闭环：非 bionic 库必须随包提供

产物含 `manifest.json`（sha256 / 大小 / 来源 run），
让"这个 APK 用的是哪份运行时"可追溯。

### 为什么用 Release 而不是 Actions cache

| | Release | Actions cache |
|---|---|---|
| 过期 | 永久 | 7 天不用即清理 |
| 可外部下载 | ✅ | ❌（走 api.github.com） |
| 可校验 | ✅ sha256 | 不透明 |
| 命中确定性 | 100% | 受 key/容量/淘汰影响 |
| 版本可追溯 | ✅ 发布历史 | ❌ |

本项目实测遇到过"cache key 没变却没命中"的情况，不可控。

---

## 9. 排障手册

### 真机启动失败，看诊断面板

诊断面板按阶段输出，每一步都带 `[OK]` / `[FAIL]` / `[..]` 标记：

| 阶段 | 含义 | 失败时看什么 |
|---|---|---|
| `init` | 服务启动，打印设备信息 | — |
| `kernel` | 内核版本指针 + 入口布局断言 | 入口被误放到 filesDir 外会在此失败 |
| `version` | 清单里的版本号 | 与实际 `node -v` 对比 |
| `native-assets` | **逐资产校验：存在性 → 依赖 → exec-probe** | 见下方「归因速查」，结论是唯一的 |
| `script` | server.js 就位 | — |
| `runtime` | runtime.json 已写入 | — |
| `exec` | node 进程已启动 | pid |
| `process` | node 退出了 | `exitCode` |
| `node-stderr` | node 自己报的错 | **最关键的一项** |
| `port` | 端口是否就绪 | 成功标志 |

> 历史阶段名 `provision` / `libdir` / `apk-libs` / `exec-probe` 已合并为
> `native-assets` 一项 —— 它们本就是同一个问题的四个侧面，分开写只会
> 制造「四处都说了但拼不出结论」的困境。

### 归因速查（`native-assets` 结构化结论）

`PrepareReport` 给出的是**唯一结论**，不是「可能原因 1/2/3」：

| `status` | 含义 | 下一步 |
|---|---|---|
| `ready` | 全部通过 | — |
| `missing_from_lib` + `inApk=true` | APK 里有但没解压落盘 | 查 `extractNativeLibs` / `useLegacyPackaging` |
| `missing_from_lib` + `inApk=false` | 打包期就丢了 | 查构建脚本产物 + `.github/native-assets.txt` |
| `missing_dependency` | **某个依赖 `.so` 不在同目录** | 补齐依赖；linker 不查 `nativeLibraryDir`，必须同目录 + 设 `LD_LIBRARY_PATH` |
| `not_executable` | 存在、依赖齐，但 exec 被拒 | **依赖已确认完好 → 可确定归因 SELinux W^X**：确认该文件真在 `nativeLibraryDir` |
| `probe_failed` | 进程起来了但退出码/输出不对 | 看 `output`；多半是拿错了二进制 |

**关键差别**：`not_executable` 出现时，依赖检查已经通过 —— 所以
`error=13` 不再有第二种解释。历史实现因为把依赖检查只当日志，
同一段错误提示同时覆盖「SELinux 拒 exec」和「依赖缺失」两种完全不同的
排查路径，浪费了大量真机调试时间。

内核可经桥方法 `sys.nativeAssets` 拿到同一份结构化报告（传
`{"walkProbes": false}` 可跳过 spawn 进程，只做静态检查）。

### 错误码速查

| 错误 | 含义 | 方向 |
|---|---|---|
| `error=13` Permission denied | SELinux 禁止 exec **或** 依赖库缺失 | 先看 `sys.nativeAssets` 的结论再定方向 |
| `error=2` No such file | `.so` 没被解压落盘 | 检查 `extractNativeLibs` / `useLegacyPackaging` |
| `error=8` Exec format error | ABI 不匹配或页对齐不满足 | 检查 `abiFilters` 与 16KB 对齐 |
| `cannot locate symbol` | linker 找不到 `libc++_shared.so` | 检查 `LD_LIBRARY_PATH` 是否设置 |
| `exitCode=1` 且瞬间退出 | 多半是 JS 层参数/逻辑错误 | 看 `node-stderr`（现在能拿到了） |

### 查看 CI 状态与日志

维护环境（沙箱）无法访问 `api.github.com`，所有 API 操作通过 **tag 触发
`admin.yml`** 完成，结果写到 `ci-admin` 分支：

```bash
# 查 run 状态 + artifact
git push origin refs/tags/admin-status-<run_id>

# 拉失败日志（--log-failed）
git push origin refs/tags/admin-logs-<run_id>

# 查 Release 附件指纹
git push origin refs/tags/admin-release

# 取消 run / 取消全部
git push origin refs/tags/admin-cancel-<run_id>
git push origin refs/tags/admin-cancelall
```

构建期间进度写到 `ci-hb` 分支（心跳，含 stage / 内存 / OOM 计数），
终态写到 `ci-last`，成功发布信息写到 `ci-ok`。

---

## 10. 冷启动清单（换机器/重新开始时）

1. **确认有可用的预编译运行时**：
   ```
   Actions → Pin Node runtime → Run workflow（run_id 留空 = 自动取最近成功的一次）
   ```
   产物落在 Release `node-runtime-<version>-arm64`。

2. **日常出包**：推 `app/**` 的改动即可，`fast-apk.yml` 自动跑。

3. **只有当 `build-node-android.sh` 或 Node 版本变了**，才需要跑
   `build-node.yml`（2~3 小时），跑完记得再 `pin-node` 一次把新产物固化。

4. **首次在任何新设备上验证时**，先看诊断面板的 `exec-probe` 与 `port`。
