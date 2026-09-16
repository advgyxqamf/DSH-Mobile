package com.example.nodecontainer.native

/**
 * 一个随包分发的原生资产，以及它在 W^X 约束下的可执行性声明。
 *
 * ## 为什么需要这个抽象
 *
 * 历史上，「哪个二进制要能 exec、它依赖什么、怎么验证」这件事被硬编码在 **8 处**：
 *
 * | # | 假设 | 位置 |
 * |---|---|---|
 * | A | 只有 `libnode.so` 一个产物 | `NodeProvisioner.bundledExecutable`、`build-node-android.sh` |
 * | B | `nativeLibraryDir` 里只有 2 个 .so | `NodeRuntimeService.diagnoseNativeLibs` |
 * | C | exec-probe 只探一个文件 | `NodeRuntimeService.runExecProbe(nodeBin)` |
 * | D | 依赖只有 `libc++_shared.so` | `build-node-android.sh` 的 NEEDED 白名单 |
 * | E | CI 只下载校验 2 个资产 | `fast-apk.yml` / `build-apk.yml` |
 * | F | `keepDebugSymbols` 只列 2 项 | `app/build.gradle.kts` |
 * | G | inject 脚本 anchor 固定 | `scripts/inject-libcxx-into-apk.py` |
 * | H | 单版本单指针 | `ARCHITECTURE.md` |
 *
 * 加第三个二进制（例如未来的 APK 重打包工具）要同时改这 8 处 —— 漏一处就 在真机上炸。
 * 本类把这些事实收敛成**一条声明**，其余全部由 [NativeAssetRegistry] 派生。
 *
 * ## 约束（真机实测，不可违背）
 *
 * Android 10+ SELinux 强制 **W^X**：
 * - `filesDir` / `cacheDir`（label `app_data_file`）→ **禁止 execve**
 * - `/data/app/<pkg>/lib/<abi>/`（label `exec_type`）→ **允许**，即 `ApplicationInfo.nativeLibraryDir`
 *
 * 所以任何要 exec 的东西都必须以 `lib*.so` 的形式落进 `jniLibs/<abi>/`。
 *
 * ⚠️ **`File.canExecute()` 在此完全无感（假阳性）** —— 它对 `app_data_file` 也返回 true。
 * 唯一可靠的验证是**真跑一次**（exec-probe），见 [NativePreparer]。
 *
 * ## exec 的四道关
 *
 * 一个 ELF 想在 Android aarch64 上跑起来，必须同时过：
 * 1. **W^X** —— 落在 `nativeLibraryDir`（`exec_type`）
 * 2. **interp** —— `PT_INTERP` 必须是 `/system/bin/linker64`
 * 3. **架构** —— `e_machine` 必须是 `0x00b7`（aarch64）
 * 4. **libc** —— `DT_NEEDED` 只能是 bionic / 随包提供的库，不能是 glibc
 *
 * 第 2/3/4 关在**装机后无法补救**（只能换二进制），所以必须在打包期用 readelf 校验。
 */
data class NativeExecutable(
    /** 稳定标识，用于诊断、审计、一致性测试。仅 ASCII 小写/下划线。 */
    val id: String,

    /**
     * `nativeLibraryDir` 内文件名。
     *
     * ⚠️ **必须以 `lib` 开头、`.so` 结尾** —— PackageManager 只把符合该模式的文件
     * 解压到 `nativeLibraryDir`；否则它会留在 APK 内被 mmap，`nativeLibraryDir` 里根本没有。
     */
    val libName: String,

    /** 给人看的名字（诊断输出用）。 */
    val humanName: String,

    /**
     * exec-probe 参数。空列表 = 只验证「能不能启动」。
     *
     * 注意：探针是**真跑进程**，不是读文件头。这是唯一能同时覆盖四道关的验证手段。
     */
    val probeArgs: List<String>,

    /**
     * 期望 stdout 包含的片段；`null` = 只看退出码。
     *
     * 只判退出码不够：有些 ELF 会在缺依赖时退 0 后立刻挂。
     */
    val probeExpect: String?,

    /**
     * 必须**同目录**存在的依赖 `.so`。
     *
     * 这些是 linker 在运行期要找的库。`nativeLibraryDir` 不在 linker 的搜索路径里
     * （它只查 `$LD_LIBRARY_PATH` / `DT_RUNPATH` / 系统默认路径；`DT_RPATH` 在 Android 被忽略），
     * 所以启动时必须显式把 `nativeLibraryDir` 塞进 `LD_LIBRARY_PATH`，且这些库必须真在那儿。
     *
     * 依赖缺失时 linker 报 `error=13` —— 与「SELinux 拒绝 exec」的 errno 完全相同，
     * 极易误导排查。本字段的存在就是为了让 [NativePreparer] 能**先查依赖再归因**。
     */
    val requiredDeps: List<String>,

    /**
     * `false` = 可选资产，缺失不阻断启动。
     *
     * 用于「有了更好、没有也能降级」的东西（例如未来的 shell 工具）。
     */
    val required: Boolean,

    /** 该资产的已知约束（给人看的自由文本）。 */
    val note: String = "",
)
