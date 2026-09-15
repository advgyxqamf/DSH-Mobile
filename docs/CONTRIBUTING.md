# 贡献规范

本文件规定**唯一的做事方式**。目的是不再重复踩已经踩过的坑。

---

## 1. 最重要的一条：改什么，走哪条路

**直接查表，不要凭感觉推。**

| 你改了什么 | 走哪条 | 耗时 | 说明 |
|---|---|---|---|
| `app/src/main/assets/**`（含 `server.js`） | `fast-apk.yml` | **分钟级** | 推 `main` 自动触发 |
| `app/src/main/java/**`（Kotlin） | `fast-apk.yml` | **分钟级** | 推 `main` 自动触发 |
| `app/src/main/res/**`（布局、字符串） | `fast-apk.yml` | **分钟级** | 推 `main` 自动触发 |
| `app/build.gradle.kts`、`gradle.properties` | `fast-apk.yml` | **分钟级** | 推 `main` 自动触发 |
| `scripts/build-node-android.sh` | `build-node.yml` | **2~3 小时** | 真的需要重编 Node |
| 升级 Node 版本 | `build-node.yml` + `pin-node.yml` | **2~3 小时** | 编完必须固化，见第 3 节 |
| `docs/**`、`*.md` | 不触发构建 | — | 纯文档 |
| `.github/workflows/**` | 不触发构建 | — | 但会跑校验 |

> **判断准则**：这个改动会不会改变 `libnode.so` 这一个字节？
> 不会 → 走 fast-apk。会 → 走 build-node。

---

## 2. 日常开发流程

```bash
# 改代码
vim app/src/main/assets/node/server.js

# 提交推送 —— fast-apk 自动跑，分钟级出包
git add -A && git commit -m "..." && git push origin main

# 取包
# https://github.com/advgyxqamf/DSH-Mobile/releases/download/apk-latest/app-debug.apk
```

**不需要做的事**：

- ❌ 不需要手动触发构建
- ❌ 不需要等待"缓存命中"
- ❌ 不需要碰任何 Node 相关的编译参数

---

## 3. 升级 Node 版本（低频，需要主动操作）

```bash
# ① 改清单里的默认版本
vim app/src/main/assets/node-versions.json   # 改 default 字段

# ② 触发重编（会改清单并编 Node，约 2~3 小时）
#    Actions → "Build Node runtime" → Run workflow

# ③ 编完必须【固化】，否则 fast-apk 找不到对应的运行时
#    Actions → "Pin Node runtime" → Run workflow（run_id 留空=自动取最近成功的一次）
#    产物落在 Release: node-runtime-<新版号>-arm64

# ④ 之后 fast-apk 会自动用上新版本（它从清单读版本号）
```

**第 ③ 步不能省。** `fast-apk` 按 `node-runtime-<version>-<abi>` 这个名字去找
Release，不固化就会报"找不到 Release"并给出指引。

---

## 4. 触发 CI 的方式（维护环境专用）

本项目维护环境（沙箱）**无法访问 GitHub API**（`api.github.com` 被屏蔽，
PAT 已失效），也**无法操作网页**。所有 CI 操作都通过 **git push tag** 完成。

```bash
# ---- 出包 ----
git push origin refs/tags/fast-verify-1        # 手动跑一次 fast-apk

# ---- 固化运行时 ----
git push origin refs/tags/pin-node-latest      # 固化最近一次成功的 build-node 产物
git push origin refs/tags/pin-node-<run_id>    # 固化指定 run 的产物

# ---- 管理（admin.yml）----
git push origin refs/tags/admin-status-<run_id>   # 查 run 状态 + artifact
git push origin refs/tags/admin-logs-<run_id>     # ★ 拉失败日志
git push origin refs/tags/admin-release           # 查 Release 附件指纹
git push origin refs/tags/admin-cancel-<run_id>   # 取消 run
git push origin refs/tags/admin-cancelall         # 取消所有在跑的
```

结果写到这几个分支，用 `git fetch` + `git show` 读：

| 分支 | 内容 |
|---|---|
| `ci-admin` | admin 命令的结果 |
| `ci-hb` | 构建期心跳（stage / 内存 / OOM 计数） |
| `ci-last` | 构建终态报告（含失败步骤与日志尾部） |
| `ci-ok` | 成功发布信息（含 sha256） |

> **`admin-logs` 是排查 CI 失败的唯一手段。** 没有它就只能猜着改再等一轮 ——
> 而一轮可能是几小时。CI 失败时**第一件事**就是拉日志。

---

## 5. 提交前必须自查（避免浪费一整轮 CI）

**凡是要跑 CI 的改动，先本地跑这两条：**

```bash
# ① workflow 静态校验（几毫秒）
python3 scripts/validate-workflow.py

# ② JS 语法检查（改过 server.js 时）
node --check app/src/main/assets/node/server.js
```

`validate-workflow.py` 能抓到的问题，都是**曾经真实浪费过一整轮 CI 的**：

| 检查 | 为什么重要 |
|---|---|
| 重复 key | GitHub 拒绝加载，页面只显示"红色 ✗ + 0 秒"，**没有任何日志** |
| `on.push` 出现两次 | 后者静默覆盖前者，触发条件变得完全不可预期 |
| `paths` 与 `paths-ignore` 同时写 | 语义冲突 |
| 缺少 `name` | run 列表里显示文件路径，找人极不方便 |
| `readelf` 未加 `-W` | 输出折行导致字段解析拿到错值，校验误判 |

---

## 6. 改 workflow 时的硬规矩

**一条 `on:` 块里只能有一个 `push:`。**

```yaml
# ❌ 错：两个 push，后者覆盖前者
on:
  push:
    branches: [main]
    paths: ['app/**']
  push:
    tags: ['fast-*']

# ✅ 对：合并成一个
on:
  push:
    branches: [main]
    paths: ['app/**']
    tags: ['fast-*']
```

**触发条件的选择**：

- **高频路径**（日常出包）用 `paths` **白名单**。
  语义是"不匹配就不跑"。宁可漏跑（可 tag 手动触发），
  也不要因为新增了某个目录就莫名跑起来。
- **低频路径**（Node 编译）用 `paths-ignore` **黑名单**。
  语义是"改了这些就不跑"。这个方向更安全：万一漏配了某个路径，
  最多是白跑一次，而不是该跑的没跑。

---

## 7. 代码与注释规范

### 注释只写"改这里必须知道什么"

历史排查过程、外部依据、失败现象 —— **全部归 `docs/ARCHITECTURE.md`**，
代码里只在必要处给一行指引。

```kotlin
// ❌ 不要在代码里写长篇叙事
// 2026-09-15 那天真机报 cannot locate symbol，我查了 termux wiki
// 和 viliussutkus89 的文章，发现 nativeLibraryDir 不在搜索路径里……
// （以下省略 40 行）

// ✅ 只写约束和指引
// LD_LIBRARY_PATH 必需，原因见 docs/ARCHITECTURE.md 第 3 节。删了真机必挂。
put("LD_LIBRARY_PATH", libSearchPath)
```

**为什么**：同一件事写在三处，改一处忘两处就是事故。
本项目已经吃过这个亏（`keepDebugSymbols` 的说明一度在
`build.gradle.kts`、`AndroidManifest.xml`、`build-apk.yml` 里各有一份）。

### 删除死代码，不留"以后可能用得上"

判据很简单：**一个 API 如果在仓库里零调用，就删掉。**

本项目删掉的例子：

- `NodeVersionManager` 的整套 OTA 链路（下载/校验/解压/切指针）
  —— 因为 `filesDir` 禁止 exec，这条链路**从设计上就不可能工作**
- `NodeProvisioner.nodeExecutable()` —— 零调用，且 `version` 参数被忽略
- `NodeVersionManager.isInstalled()` —— 注释自认"返回 true 不代表能跑"，
  这种 API 留着只会让人误用

**一个自己声明不可信的 API 就不该存在。** 需要用的时候再从 git 历史里翻，
比留一个会误导人的空壳好得多。

---

## 8. 出问题时的排查顺序

1. **看真机诊断面板**（App 打开即是）。逐阶段 `[OK]`/`[FAIL]`，
   哪一环挂了、node 报了什么，都在上面。对照表见
   `docs/ARCHITECTURE.md` 第 9 节。
2. **CI 失败** → `admin-logs-<run_id>` 拉日志。
   不要猜，猜一轮就是几小时。
3. **真机报错看不懂** → 查 `docs/ARCHITECTURE.md` 的"错误码速查"。
4. **改了没生效** → 先确认是不是运行了错误的那条 workflow；
   再确认 APK 是不是从 `apk-latest` 拿的（这个 tag 每轮覆盖）。

---

## 9. 一句话总结

> **改 App 代码走 fast-apk（分钟级）；只有动 Node 编译脚本才走 build-node（小时级）。
> 拿不准就问自己：这会改变 libnode.so 吗？**
