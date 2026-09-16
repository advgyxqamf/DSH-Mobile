package com.example.nodecontainer.native

import android.content.Context
import java.io.File

/**
 * 随包原生资产的**唯一事实来源**。
 *
 * 所有需要知道「包里有哪些 .so、谁依赖谁、谁要能 exec」的地方（运行时启动链、
 * 构建脚本、CI 流水线、Gradle 打包配置、一致性测试）都必须从这里派生，
 * **不得再出现硬编码文件名**。
 *
 * ## 同步契约
 *
 * 注册表是源头，以下三处是它的**投影**，由 `container-engine/test/native-assets-test.js`
 * 做双向一致性守护（任一方向漏项都会让测试失败）：
 *
 * | 投影 | 位置 | 内容 |
 * |---|---|---|
 * | 打包保留符号 | `app/build.gradle.kts` → `keepDebugSymbols` | [keepDebugSymbolsGlobs] |
 * | 构建期 NEEDED 白名单 | `scripts/native-deps.txt` | [bundledDeps] + 系统库白名单 |
 * | CI 资产清单 | `.github/native-assets.txt` | [libNames] + `manifest.json` |
 *
 * ## 新增一个二进制资产要做什么
 *
 * 1. 在 [ALL] 里加一行；
 * 2. 跑 `node container-engine/test/native-assets-test.js`，按报错补齐三处投影；
 * 3. 改 `scripts/build-node-android.sh` 让它把产物拷进 `jniLibs/<abi>/`。
 *
 * 就这些 —— 运行时启动链、诊断、exec-probe、`sys.nativeAssets` 全部自动跟上。
 */
object NativeAssetRegistry {

    /**
     * libc++ 运行期。
     *
     * ⚠️ 它**不是**可执行文件（`probeArgs` 为空 + `probeExpect` 为 null 时，
     * [NativePreparer] 只校验存在性与可读性，不做 exec-probe）。
     * 但它**必须**在 `nativeLibraryDir` —— `libnode.so` 的 `DT_NEEDED` 里有它，
     * 而它不在 Android 系统镜像里，只能随包提供。
     */
    val LIBCXX = NativeExecutable(
        id = "libcxx",
        libName = "libc++_shared.so",
        humanName = "C++ 运行期",
        probeArgs = emptyList(),
        probeExpect = null,
        requiredDeps = emptyList(),
        required = true,
        note = "不是可执行文件，但必须在 nativeLibraryDir —— libnode.so 的 DT_NEEDED 依赖它",
    )

    /**
     * Node 运行时。
     *
     * 它**实际是一个可执行文件**（有 `PT_INTERP = /system/bin/linker64`），
     * 只是被改名为 `lib*.so` 以借道 `jniLibs` 打包通道，从而落进 `exec_type` 目录。
     *
     * 这层「改名把戏」是 W^X 约束下的唯一出路：Android 10+ 不允许从 `filesDir`
     * execve，`jniLibs` 通道是系统唯一愿意解压到可执行目录的入口。
     */
    val NODE = NativeExecutable(
        id = "node",
        libName = "libnode.so",
        humanName = "Node 运行时",
        probeArgs = listOf("-v"),
        probeExpect = "v",
        requiredDeps = listOf("libc++_shared.so"),
        required = true,
        note = "实为可执行文件，改名 lib*.so 借 jniLibs 通道落到 exec_type 目录",
    )

    // ← 未来加资产在这里加一行即可，例如：
    //   val APKREPACK = NativeExecutable(
    //       id = "apkrepack", libName = "libapkrepack.so", humanName = "APK 重打包器",
    //       probeArgs = listOf("--version"), probeExpect = null,
    //       requiredDeps = listOf("libc++_shared.so"), required = false, ...)

    /** 全部资产。顺序即诊断输出顺序（必需项放前面，便于人眼先看关键项）。 */
    val ALL: List<NativeExecutable> get() = listOf(LIBCXX, NODE)

    /** 仅必需资产。 */
    val REQUIRED: List<NativeExecutable> get() = ALL.filter { it.required }

    /** 全部资产的文件名（CI 资产清单的来源）。 */
    val libNames: List<String> get() = ALL.map { it.libName }

    /**
     * 解析资产在设备上的绝对路径。
     *
     * 永远指向 `nativeLibraryDir` —— **不要**复制到 `filesDir`：那里 `app_data_file`
     * label 禁止 execve，复制过去只会在真机上炸，而 `canExecute()` 还会骗你说没问题。
     */
    fun resolve(ctx: Context, e: NativeExecutable): File =
        File(ctx.applicationInfo.nativeLibraryDir, e.libName)

    /**
     * Gradle `keepDebugSymbols` 的 glob 列表。
     *
     * 这些文件不能被 strip：`libnode.so` 是真正要被 exec 的 ELF（strip 会破坏它），
     * `libc++_shared.so` 要提供符号（strip 后 `cannot locate symbol`）。
     */
    val keepDebugSymbolsGlobs: List<String> get() = ALL.map { "**/${it.libName}" }

    /** 全部随包依赖库名（去重）。CI 资产清单与构建期 NEEDED 白名单均以此为源。 */
    val bundledDeps: List<String> get() = ALL.flatMap { it.requiredDeps }.distinct()
}
