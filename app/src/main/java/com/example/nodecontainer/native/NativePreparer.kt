package com.example.nodecontainer.native

import android.content.Context
import android.util.Log
import com.example.nodecontainer.RuntimeDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * 单个资产的验证结果。
 *
 * 这是**结构化**的 —— 每个失败分支都携带精确归因所需的事实，
 * 而不是一段让人自己猜的日志文本。
 */
sealed class AssetStatus {

    /**
     * 资产就位且（若可执行）exec-probe 通过。
     *
     * @param probeOutput 探针 stdout；不可执行资产为描述性文本。
     */
    data class Ready(
        val exe: NativeExecutable,
        val path: String,
        val probeOutput: String,
    ) : AssetStatus()

    /**
     * 资产**不在** `nativeLibraryDir` 里。
     *
     * @param inApk `true` = APK 内有这个条目，但安装期没解压出来
     *              （`extractNativeLibs` 未生效 / `useLegacyPackaging` 未开）
     *              `false` = APK 内就没有 —— 打包期就丢了
     *
     * 这两者排查方向完全相反，所以必须分开携带，不能合成一句话。
     */
    data class MissingFromLib(
        val exe: NativeExecutable,
        val path: String,
        val inApk: Boolean,
        val libListing: String,
    ) : AssetStatus()

    /**
     * 资产本身在，但它依赖的 `.so` 不在。
     *
     * ★ **这是历史上完全缺失的一层。** 过去依赖缺失从不作为前置条件阻断，
     * 结果是 exec-probe 以 linker 错误失败（`error=13`），而错误归因走到
     * 「该路径被 SELinux 禁止 exec」分支 —— **把排查方向彻底带偏**。
     *
     * 现在依赖检查发生在 exec-probe **之前**，且归因独立成一类。
     */
    data class MissingDependency(
        val exe: NativeExecutable,
        val dep: String,
        val libListing: String,
    ) : AssetStatus()

    /**
     * 文件在、依赖齐，但 exec 被拒（`IOException`）。
     *
     * @param errnoHint 从异常消息里解析出的 errno；拿不到为 `null`
     *
     * 走到这一步，依赖已确认完好，所以 `error=13` 可以**确定**归因到
     * SELinux W^X 拒 exec（而非依赖缺失）—— 这正是与历史行为的关键差别。
     */
    data class NotExecutable(
        val exe: NativeExecutable,
        val path: String,
        val errnoHint: Int?,
        val raw: String,
    ) : AssetStatus()

    /** 进程起来了但结果不对：退出码非 0，或 stdout 缺了期望片段。 */
    data class ProbeFailed(
        val exe: NativeExecutable,
        val path: String,
        val exit: Int,
        val output: String,
    ) : AssetStatus()
}

/** [NativePreparer.prepare] 的完整结果。 */
data class PrepareReport(val entries: List<Pair<NativeExecutable, AssetStatus>>) {

    /** 所有 **必需** 资产是否都 Ready。可选资产失败不影响此值。 */
    val allRequiredReady: Boolean
        get() = entries.filter { it.first.required }.all { it.second is AssetStatus.Ready }

    /** 失败的必需资产。 */
    val failedRequired: List<Pair<NativeExecutable, AssetStatus>>
        get() = entries.filter { it.first.required && it.second !is AssetStatus.Ready }

    /** 供 `sys.nativeAssets` 桥方法返回给内核的结构化快照。 */
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for ((exe, st) in entries) {
            val o = JSONObject()
            o.put("id", exe.id)
            o.put("libName", exe.libName)
            o.put("humanName", exe.humanName)
            o.put("required", exe.required)
            o.put("note", exe.note)
            o.put("requiredDeps", JSONArray(exe.requiredDeps))
            when (st) {
                is AssetStatus.Ready -> {
                    o.put("status", "ready")
                    o.put("path", st.path)
                    o.put("probeOutput", st.probeOutput)
                }
                is AssetStatus.MissingFromLib -> {
                    o.put("status", "missing_from_lib")
                    o.put("path", st.path)
                    o.put("inApk", st.inApk)
                    o.put("libListing", st.libListing)
                    // 内核可据此区分「改打包配置重出 APK」vs「安装期问题」两条修复路径
                    o.put("hint", if (st.inApk) "安装期未解压（extractNativeLibs 未生效）"
                    else "打包期就丢了（构建脚本未拷入 / 被 strip 掉）")
                }
                is AssetStatus.MissingDependency -> {
                    o.put("status", "missing_dependency")
                    o.put("missingDep", st.dep)
                    o.put("libListing", st.libListing)
                    o.put("hint", "依赖放在同一 nativeLibraryDir 内才找得到；" +
                        "linker 不查 nativeLibraryDir，须显式设 LD_LIBRARY_PATH")
                }
                is AssetStatus.NotExecutable -> {
                    o.put("status", "not_executable")
                    o.put("path", st.path)
                    st.errnoHint?.let { o.put("errno", it) }
                    o.put("raw", st.raw)
                    o.put("hint", "依赖已确认完好，errno=13 可确定归因到 SELinux W^X 拒 exec")
                }
                is AssetStatus.ProbeFailed -> {
                    o.put("status", "probe_failed")
                    o.put("path", st.path)
                    o.put("exit", st.exit)
                    o.put("output", st.output)
                }
            }
            arr.put(o)
        }
        return JSONObject().apply {
            put("allRequiredReady", allRequiredReady)
            put("assets", arr)
        }
    }

    /** 人类可读的诊断行，供 `RuntimeDiagnostics` 落盘。 */
    fun toDiagnosticLines(): List<String> = entries.map { (exe, st) ->
        val tag = if (exe.required) "[必需]" else "[可选]"
        when (st) {
            is AssetStatus.Ready ->
                "$tag ${exe.libName} —— 就位（${exe.humanName}）" +
                    if (exe.probeArgs.isNotEmpty()) "，探针输出: ${st.probeOutput.ifBlank { "(空)" }}" else ""
            is AssetStatus.MissingFromLib ->
                "$tag ${exe.libName} —— ✗ 不在 nativeLibraryDir。" +
                    if (st.inApk) "APK 内有该条目 → 安装期未解压（查 extractNativeLibs / useLegacyPackaging）"
                    else "APK 内也没有该条目 → 打包期就丢了（查构建脚本与 keepDebugSymbols）"
            is AssetStatus.MissingDependency ->
                "$tag ${exe.libName} —— ✗ 缺少依赖 ${st.dep}（它必须先于 exec-probe 补齐，否则会被误判为 SELinux 拒 exec）"
            is AssetStatus.NotExecutable ->
                "$tag ${exe.libName} —— ✗ 无法 exec（依赖已确认完好，errno=${st.errnoHint ?: "?"}）"
            is AssetStatus.ProbeFailed ->
                "$tag ${exe.libName} —— ✗ 探针失败 exit=${st.exit}，输出: ${st.output.ifBlank { "(空)" }}"
        }
    }
}

/**
 * 随包原生资产的统一准备 / 验证引擎。
 *
 * ## 它取代了什么
 *
 * 历史上这是**三处分散逻辑**，各自只做了一半，组合起来有漏洞：
 *
 * | 旧位置 | 旧行为 | 漏洞 |
 * |---|---|---|
 * | `NodeProvisioner.ensureBundledNode` | 只确认 `libnode.so` 存在就抛异常 | 完全不知道 libc++ 的存在 |
 * | `NodeRuntimeService.diagnoseNativeLibs` | 检查 `libc++_shared.so` 在不在，**只打日志** | **从不阻断启动** → 缺陷 1 |
 * | `NodeRuntimeService.runExecProbe` | 真跑 `node -v` | 归因只按 errno 罗列三种可能 → 缺陷 2 |
 *
 * **缺陷 1 + 缺陷 2 叠加出的真实故障场景**：`libc++_shared.so` 缺失 →
 * `exec-probe` 以 linker 错误失败 → errno 是 `13` → 归因走到「该路径被 SELinux
 * 禁止 exec」→ 排查者去查 SELinux 与解压路径，**真因（依赖库缺失）永远浮不出来**。
 *
 * ## 修复后的顺序（顺序本身就是修复）
 *
 * 对每个资产严格按此序，**任一步失败立即停止**，不再往下走到误导性结论：
 *
 * ```
 * ① 存在性    nativeLibraryDir 里有没有这个文件
 *              ↳ 没有 → 查 APK 内是否有该条目 → MissingFromLib(inApk)
 * ② 依赖前置  遍历 requiredDeps，每个都必须在同目录
 *              ↳ 缺 → MissingDependency    ★ 这一步是历史上缺失的
 * ③ exec 探针 【仅对 probeArgs 非空的资产】真跑一次
 *              ↳ IOException → NotExecutable（此时可确定归因 SELinux）
 *              ↳ exit != 0 或 stdout 缺片段 → ProbeFailed
 * ```
 *
 * ## 为什么不能省掉第 ③ 步
 *
 * `File.canExecute()` 对 `app_data_file`（`filesDir`）也返回 `true` ，
 * 对 SELinux 的 W^X 政策**完全无感（假阳性）**。唯一可靠的手段是真去 exec 一次。
 */
object NativePreparer {

    private const val TAG = "NativePreparer"

    /**
     * 逐项验证 [NativeAssetRegistry.ALL]，返回完整报告。
     *
     * 副作用：向 `RuntimeDiagnostics` 写入一行逐资产结论 + 一次 lib 目录快照。
     * **不抛异常** —— 失败通过 [AssetStatus] 表达，调用方按
     * [PrepareReport.allRequiredReady] 决定是否继续启动。
     */
    fun prepare(ctx: Context): PrepareReport {
        val libDir = File(ctx.applicationInfo.nativeLibraryDir)
        val listing = listLibDir(libDir)

        val apkLibNames: Set<String> = try {
            readApkLibEntries(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "读取 APK lib 条目失败", e)
            emptySet()
        }

        val entries = NativeAssetRegistry.ALL.map { exe ->
            exe to verifyInternal(ctx, exe, libDir, listing, apkLibNames)
        }
        val report = PrepareReport(entries)

        // 一次落盘完整快照，省得逐项刷屏
        RuntimeDiagnostics.append(
            ctx, "native-assets", report.allRequiredReady,
            if (report.allRequiredReady) "原生资产全部就位（${entries.size} 项）"
            else "原生资产校验失败：${report.failedRequired.joinToString(", ") { it.first.libName }}",
            "nativeLibraryDir=${libDir.absolutePath}\n" +
                "LD_LIBRARY_PATH 必需值=${libDir.absolutePath}\n" +
                "lib 目录内容（${listing.lines().size - 3} 项）:\n" +
                listing.lineSequence().drop(2).joinToString("\n") { "  $it" } + "\n" +
                report.toDiagnosticLines().joinToString("\n")
        )
        return report
    }

    /** 单项验证，供 `sys.nativeAssets` 按需调用（与 [prepare] 走同一实现，不会漂移）。 */
    fun verify(ctx: Context, exe: NativeExecutable): AssetStatus {
        val libDir = File(ctx.applicationInfo.nativeLibraryDir)
        val apkNames = try {
            readApkLibEntries(ctx)
        } catch (e: Exception) {
            emptySet()
        }
        return verifyInternal(ctx, exe, libDir, listLibDir(libDir), apkNames)
    }

    /**
     * linker 搜索路径。
     *
     * **必须是 `nativeLibraryDir`**，理由见 [probe] 的注释。所有启动 native 子进程的
     * 地方都应从这里取值，不要再各写一份。
     */
    fun libSearchPath(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun verifyInternal(
        ctx: Context,
        exe: NativeExecutable,
        libDir: File,
        listing: String,
        apkLibNames: Set<String>,
    ): AssetStatus {
        val f = File(libDir, exe.libName)

        // ---- ① 存在性 ----
        if (!f.exists()) {
            val inApk = apkLibNames.contains(exe.libName)
            return AssetStatus.MissingFromLib(exe, f.absolutePath, inApk, listing)
        }

        // ---- ② 依赖前置（历史上缺失的一层）----
        for (dep in exe.requiredDeps) {
            if (!File(libDir, dep).exists()) {
                return AssetStatus.MissingDependency(exe, dep, listing)
            }
        }

        // ---- ③ exec 探针 ----
        // probeArgs 为空 = 纯数据资产（如 libc++_shared.so），只校验存在与可读。
        if (exe.probeArgs.isEmpty() && exe.probeExpect == null) {
            return if (f.canRead() || f.length() > 0) {
                AssetStatus.Ready(exe, f.absolutePath, "数据资产：${f.length()} 字节（不做 exec-probe）")
            } else {
                AssetStatus.NotExecutable(exe, f.absolutePath, null, "文件存在但不可读且长度为 0")
            }
        }

        return probe(exe, f, libDir)
    }

    /**
     * 真跑一次探针进程，验证「这个二进制到底能不能被 exec」。
     *
     * 刻意【不用 `runCatching`】：它会吞掉所有 `Throwable`（含 `InterruptedException`
     * / `OutOfMemoryError`），把不该归为「exec 失败」的情况也引向这个结论。
     * 只精确捕获 `IOException` —— 那正是「进程无法创建」的形态。
     *
     * ------------------------------------------------------------------
     * LD_LIBRARY_PATH 为什么是必需的（改这里之前务必读完）
     * ------------------------------------------------------------------
     * 现象（真机实测）：
     * ```
     * CANNOT LINK EXECUTABLE ".../lib/arm64-v8a/libnode.so":
     * cannot locate symbol "_ZTVNSt6__ndk119basic_ostringstream..."
     * ```
     *
     * 根因：Android linker 查找依赖库的目录**只有三个**：
     *   ① `$LD_LIBRARY_PATH` 里的目录
     *   ② 二进制 `DT_RUNPATH` 动态段列出的目录
     *   ③ 系统默认路径 `/system/lib64`、`/system/lib`
     * （`DT_RPATH` 在 Android 上被忽略，只有 `DT_RUNPATH` 有效。）
     *
     * `nativeLibraryDir` **不在这三者中的任何一个** —— 它只在 Java 层
     * `dlopen` / `System.loadLibrary` 时才进搜索路径。而我们是 exec 一个可执行
     * 文件、由它自己拉起依赖，完全是另一套规则。`libnode.so` 自身既无
     * `DT_RPATH` 也无 `DT_RUNPATH`（readelf 逐个核对过动态段 29 个条目），
     * 于是它只能查系统默认路径，那里没有 `libc++_shared.so`（它不是 bionic
     * 的一部分），符号解析失败。
     *
     * 解法：显式设 `LD_LIBRARY_PATH = nativeLibraryDir`。两个 `.so` 都在该目录，
     * 一举解决。注意 `ProcessBuilder` 是直接 exec、不经过 shell，所以值就是
     * 路径原文，不涉及任何展开或引号处理。
     *
     * 为什么不改用 `$ORIGIN` rpath：那需要重编并改链接参数，且有资料指出它
     * 只在部分设备上有效。`LD_LIBRARY_PATH` 是跨设备可靠的那一个。
     */
    private fun probe(exe: NativeExecutable, f: File, libDir: File): AssetStatus {
        try {
            val cmd = mutableListOf(f.absolutePath).apply { addAll(exe.probeArgs) }
            val p = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .apply { environment()["LD_LIBRARY_PATH"] = libDir.absolutePath }
                .start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val exit = p.waitFor()

            if (exit != 0) return AssetStatus.ProbeFailed(exe, f.absolutePath, exit, out)
            val expect = exe.probeExpect
            if (expect != null && !out.contains(expect)) {
                // 退出码对但输出不对：多半是拿错了二进制（例如被其它 .so 覆盖）
                return AssetStatus.ProbeFailed(exe, f.absolutePath, exit, out)
            }
            return AssetStatus.Ready(exe, f.absolutePath, out)
        } catch (e: IOException) {
            // 走到这里：① 与 ② 均通过，所以 errno=13 可确定归因到 SELinux W^X 拒 exec，
            // 不再罗列「也可能是依赖缺失」那种把人带偏的可能性。
            return AssetStatus.NotExecutable(exe, f.absolutePath, parseErrno(e.message), err(e))
        }
    }

    /** 从异常消息里抠 `errno=N`；抠不到返回 null（诊断降级，绝不影响主流程）。 */
    private fun parseErrno(msg: String?): Int? {
        if (msg == null) return null
        return Regex("""errno=(\d+)""").find(msg)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** 列出 `nativeLibraryDir` 内容（诊断用，含文件大小）。 */
    private fun listLibDir(libDir: File): String {
        val files = try {
            libDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        } catch (e: Exception) {
            return "nativeLibraryDir=${libDir.absolutePath}\n（列举失败: ${e.message}）\n"
        }
        return buildString {
            appendLine("nativeLibraryDir=${libDir.absolutePath}")
            appendLine("文件数=${files.size}")
            files.forEach { appendLine("${it.name}  ${it.length()} 字节") }
        }
    }

    /**
     * 读 APK 内的 `lib/<abi>/` 条目名（只要文件名部分）。
     *
     * 用于区分「安装期没解压」和「打包期就丢了」—— 这两者排查方向完全相反，
     * 必须分开判断，合在一起看会分不清问题出在哪一环。
     */
    private fun readApkLibEntries(ctx: Context): Set<String> {
        val apkPath = ctx.applicationInfo.sourceDir
        return ZipFile(apkPath).use { zf ->
            zf.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.substringAfterLast('/') }
                .toSet()
        }
    }

    private fun err(e: Throwable): String =
        "${e.javaClass.simpleName}: ${e.message}"
}
