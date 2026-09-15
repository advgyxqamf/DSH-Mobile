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
        keepDebugSymbols += setOf("**/libnode.so", "**/libc++_shared.so")
    }
}
```

`keepDebugSymbols` 是旧 API `doNotStrip` 的替代
（AGP 文档原话：*"Use jniLibs.keepDebugSymbols.add() instead."*）。
**命名有误导性** —— 它不只是"保留调试信息"，实际语义是
"这些 `.so` 不要交给 strip 处理"。

为什么必须豁免：

- `libnode.so` —— 其实是一个可执行文件，只是改名为 `lib*.so` 借 `jniLibs`
  通道落到可执行目录。strip 会破坏它被 exec 所需的信息。
- `libc++_shared.so` —— node 的运行期动态依赖，strip 掉符号表只会让
  动态链接更无解。

这两个文件由 CI 用与 node 相同的 NDK 亲自挑选/产出，不需要 AGP 再加工。

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
| `version` | 清单里的版本号 | 与实际 `node -v` 对比 |
| `provision` | 内置 node 就位 | 目录实况会一并打印 |
| `script` | server.js 就位 | — |
| `libdir` | lib 目录里有啥 | 有没有 `libc++_shared.so` |
| `apk-libs` | APK 里有啥 | 与 `libdir` 对比可定位是打包还是解压问题 |
| `exec-probe` | **真跑一次 `node -v`** | 可执行性的确定性结论 |
| `exec` | node 进程已启动 | pid |
| `process` | node 退出了 | `exitCode` |
| `node-stderr` | node 自己报的错 | **最关键的一项** |
| `port` | 端口是否就绪 | 成功标志 |

### 错误码速查

| 错误 | 含义 | 方向 |
|---|---|---|
| `error=13` Permission denied | SELinux 禁止 exec | 确认执行的是 `nativeLibraryDir` 下的 `libnode.so`，不是 `files/` 里的副本 |
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
