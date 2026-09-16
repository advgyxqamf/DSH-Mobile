package com.example.nodecontainer

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 内核（L1）版本管理 —— 与容器引擎 OTA 引擎共用同一套指针约定。
 *
 * 布局（与 container-engine/src/ota-engine.js、kernel-bundle.js 对齐）：
 *   files/kernel/CURRENT                 -> 当前生效版本号（原子写）
 *   files/kernel/<version>/kernel.json   -> 内核包清单（含 entry/signature/requires）
 *   files/kernel/<version>/bin/dsh-supervisor -> 内核入口（**由 node 解释执行**）
 *
 * 与 Node 运行时版本（NodeVersionManager，files/node/CURRENT）是两套独立指针：
 *   - Node 运行时（L0）冻结；
 *   - 内核（L1）经签名 OTA 热更新。
 * 二者互不替代。
 *
 * ============================================================================
 *  ⚠️ dsh-supervisor 是【脚本】，不是可执行的二进制 —— 不要试图 exec 它
 * ============================================================================
 * 它落在 `filesDir`（label = `app_data_file`），**SELinux W^X 禁止 execve**。
 * 正确用法是把它当**参数**交给 node：
 *
 * ```kotlin
 * ProcessBuilder(nodeBin.absolutePath, entry.absolutePath, "daemon")
 * ```
 * 即「用 node 跑这个脚本」。见 NodeRuntimeService 的启动链。
 *
 * 这与 `libnode.so` 的处理方式**刻意不同** —— 后者是真正要被 exec 的 ELF，
 * 必须放在 `nativeLibraryDir`（label = `exec_type`），即 `jniLibs/<abi>/`。
 *
 * 历史上的类注释误写成「由 :node 进程 exec」，与实现矛盾。这是埋着的雷：
 * 照注释去 `ProcessBuilder(entry.absolutePath)` 必在真机上以
 * `error=13, Permission denied` 失败。本注释即为修正，并加了运行时断言守护。
 * ============================================================================
 */
class KernelManager(private val context: Context) {

    data class KernelVersion(val version: String, val dir: File)

    data class KernelManifest(
        val name: String,
        val version: String,
        val abi: String,
        val engines: JSONObject?,
        val entry: String,
        val requires: List<String>,
        val signature: String?
    )

    private val kernelRoot = File(context.filesDir, "kernel")
    private val currentPointer = File(kernelRoot, "CURRENT")

    fun currentVersion(): String? =
        if (currentPointer.exists()) currentPointer.readText().trim().ifBlank { null } else null

    /** 已安装（落盘）的内核版本目录名。 */
    fun installedVersions(): List<String> {
        if (!kernelRoot.exists()) return emptyList()
        return kernelRoot.list()?.filter {
            it != "CURRENT" && File(kernelRoot, it).isDirectory
        }?.sorted() ?: emptyList()
    }

    fun kernelDir(version: String): File = File(kernelRoot, version)

    /**
     * 内核入口脚本路径 —— **这是一个脚本，不是可执行二进制**。
     *
     * 它位于 `filesDir`（`app_data_file`），SELinux W^X 禁止 execve。
     * 必须交给 node 解释执行：`ProcessBuilder(nodeBin, entryPath(v), "daemon")`。
     *
     * 调用 [assertNotDirectlyExecutable] 可在开发期抓住误用。
     */
    fun entryPath(version: String): File = File(kernelDir(version), "bin/dsh-supervisor")

    /**
     * 断言 [entryPath] 不会被直接 exec —— 该路径在 `app_data_file` 下，W^X 会拒绝。
     *
     * 初衷：类注释曾与实现矛盾（写「由 :node 进程 exec」而实际落 filesDir），
     * 这是典型的「埋雷」型缺陷 —— 后人照注释写代码就必崩。把不变式写成可执行
     * 断言，比注释更难被忽略。
     *
     * 注意断言的是**目录归属**（唯一可靠的静态判据），不是文件权限位：
     * `File.canExecute()` 对 `app_data_file` 也返回 true，在此完全不可信。
     *
     * @throws IllegalStateException 该路径竟然不在 filesDir 子树内（布局被破坏）
     */
    fun assertNotDirectlyExecutable(version: String) {
        val entry = entryPath(version).canonicalFile
        val filesRoot = context.filesDir.canonicalFile
        check(entry.startsWith(filesRoot)) {
            "内核入口应位于 filesDir（app_data_file，W^X 禁 exec）内，但它跑到了 ${entry.parent}。" +
                "此断言失败意味着内核 OTA 的落盘布局被破坏 —— " +
                "若入口需要被 exec，它必须改走 jniLibs/nativeLibraryDir（exec_type）通道。"
        }
    }

    fun kernelJsonPath(version: String): File = File(kernelDir(version), "kernel.json")

    /** 读取内核 manifest；缺失/解析失败返回 null。 */
    fun readKernelJson(version: String): KernelManifest? = readKernelJson(version, kernelRoot)

    /**
     * 从指定根目录读取版本 manifest。
     *
     * 为什么要带 root 参数：安装流程会把包解到 `files/kernel/<v>.tmp-*` 而不是
     * 正式目录，然后**在落位前**核对"解包结果"与"校验阶段读到的"是否一致。
     * 用固定的 `kernelRoot` 读不到临时目录，那个核对就无从做起 —— 而它正是
     * 防住「验的是 A、装的是 B」的唯一手段（见 KernelInstaller 注释）。
     */
    fun readKernelJson(version: String, root: File): KernelManifest? {
        val p = File(root, "$version/kernel.json")
        if (!p.exists()) return null
        return try {
            val json = JSONObject(p.readText())
            KernelManifest(
                name = json.optString("name", "dsh-kernel"),
                version = json.optString("version", version),
                abi = json.optString("abi", ""),
                engines = json.optJSONObject("engines"),
                entry = json.optString("entry", "bin/dsh-supervisor"),
                requires = json.optJSONArray("requires")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList(),
                signature = json.optString("signature", "").ifBlank { null }
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 把 zip 解到指定目录（公开给 [KernelInstaller] 用）。
     *
     * 保留 private 的 [unzip] 作为实现 —— 这里只做"解包成功与否"的语义化封装，
     * 让调用方不必 catch Throwable 也能区分"包结构不合法"（可归因）与
     * "IO 出错"（需排查环境）。
     */
    fun unzipInto(zip: File, dest: File) {
        unzip(zip, dest)
    }

    /**
     * 设置当前版本（原子写：先写临时再 rename）。
     * 调用方需保证目标版本已落盘（由 OTA 引擎 apply 完成，或由 ensureBaseline 落地）。
     */
    fun setCurrentVersion(version: String) {
        kernelRoot.mkdirs()
        val tmp = File(kernelRoot, "CURRENT.tmp")
        tmp.writeText(version)
        tmp.renameTo(currentPointer)
    }

    /**
     * 基线内核的落地结果。
     *
     * 为什么不用 `String?`（历史实现）：「已经有内核」与「没有基线包」两种情况
     * 都返回 null，调用方无法区分 —— 于是真机上「无网首启起不来」这条故障
     * 永远只表现为一句 "尚无内核包"。把结果显式化，才能把「缺基线」这个
     * **构建期缺陷**和「等待 OTA」这个**正常状态**分开归因。
     */
    sealed class BaselineResult {
        /** 已有可用内核（CURRENT 指向的目录确实存在）。 */
        data class AlreadyPresent(val version: String) : BaselineResult()

        /** 本次从内置基线包落地成功。 */
        data class Installed(val version: String, val bytes: Long) : BaselineResult()

        /** APK 里没有基线包 —— 构建期没注入。无网时设备将无内核可用。 */
        data class NoBaselineAsset(val assetPath: String) : BaselineResult()

        /** 有基线包但不可用（zip 损坏 / 缺 kernel.json / 解压失败）。 */
        data class BrokenBaseline(val assetPath: String, val reason: String) : BaselineResult()

        val versionOrNull: String?
            get() = when (this) {
                is AlreadyPresent -> version
                is Installed -> version
                else -> null
            }

        /** 是否属于「需要人工/构建期修复」的异常，而非正常等待 OTA。 */
        val isDefect: Boolean
            get() = this is NoBaselineAsset || this is BrokenBaseline
    }

    /**
     * 首启兜底：若沙箱里没有任何内核（CURRENT 缺失），且 APK 内置了基线内核包
     * `assets/kernel/baseline.zip`，则经**完整校验**后落地并切指针。
     * 这样无网首启也能拉起一个已知良好内核；后续 OTA 覆盖升级。
     *
     * ============================================================================
     *  ⚠️ 基线包**同样必须验签** —— 不能因为"它是 APK 里带的"就跳过
     * ============================================================================
     * 直觉上「APK 已经验过签名了，里面的资产自然是可信的」。
     * 这个直觉在这里**不成立**，原因是信任根不同：
     *   · APK 签名锚定的是 **Play/发布者**（Android 平台信任）；
     *   · 内核签名锚定的是 **容器私钥**（`ota-public.pem`，本架构自己的信任根）。
     * 二者是两把独立的钥匙。若基线包跳过内核验签，那么：
     *   任何能重打 APK 的人（不必持有容器私钥）都能塞进一个任意内核，
     *   双信任根就退化成了单信任根。
     * 所以 [KernelInstaller.install] 对基线包与外部包一视同仁。
     *
     * 返回结构化的 [BaselineResult]，而不是历史上的 `String?` —— 后者让
     * 「缺基线包」与「已有内核」都返回 null，真机上无从区分。
     */
    fun ensureBaseline(): BaselineResult {
        val existing = currentVersion()
        if (existing != null && File(kernelRoot, existing).isDirectory) {
            return BaselineResult.AlreadyPresent(existing)
        }

        val baselineAsset = "kernel/baseline.zip"
        val assetNames = try {
            context.assets.list("kernel")?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }

        if (!assetNames.contains("baseline.zip")) {
            // 关键：把「assets/kernel/ 下有什么」也记下来。过去这里静默返回 null，
            // 结果真机上只能看到「没有内核」，无从判断是构建漏了还是 OTA 没下发。
            return BaselineResult.NoBaselineAsset(baselineAsset).also {
                RuntimeDiagnostics.append(
                    context, "kernel", false, "APK 未内置基线内核包",
                    "查找 assets/$baselineAsset 失败；assets/kernel/ 现有内容=${assetNames.ifEmpty { listOf("(空)") }}。" +
                        "无网首启将没有内核可跑，只能回落探针模式。"
                )
            }
        }

        // 先把资产落到文件（Node 校验器要按路径读它，且 assets 本身在 APK 内
        // 是压缩存储，必须经 AssetManager 才能访问 —— 无法直接给 Node 用）。
        val zip = File(context.cacheDir, "kernel-baseline.zip")
        try {
            context.assets.open(baselineAsset).use { input ->
                zip.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (e: Throwable) {
            return BaselineResult.BrokenBaseline(baselineAsset, "资产复制失败: ${errText(e)}")
                .also { RuntimeDiagnostics.append(context, "kernel", false, "基线包复制失败", errText(e)) }
        }
        val bytes = zip.length()

        // 走统一安装器：sha256（无外部锚点，只做包内自校验）+ ed25519 验签 + 结构检查。
        val result = KernelInstaller.install(
            context = context,
            zip = zip,
            manifest = null,          // 基线没有外部 manifest；签名仍照验
            source = KernelInstaller.Source.APK_ASSET,
        )
        zip.delete()

        return if (result.ok && result.version != null) {
            BaselineResult.Installed(result.version, bytes)
        } else {
            BaselineResult.BrokenBaseline(
                baselineAsset,
                "reason=${result.reason}；${result.detail}"
            ).also {
                RuntimeDiagnostics.append(
                    context, "kernel", false, "基线内核校验/落地未通过",
                    "reason=${result.reason}；${result.detail}\n" +
                        "校验器输出:\n${result.nodeVerifyOutput.take(1000)}"
                )
            }
        }
    }

    /** 结构性内核健康检查：CURRENT 指针与目录、entry 是否自洽。 */
    fun integrityChecks(): List<String> {
        val out = mutableListOf<String>()
        val cur = currentVersion()
        if (cur == null) {
            out += "CURRENT 指针缺失"
        } else {
            if (!File(kernelRoot, cur).isDirectory) out += "CURRENT=$cur 但目录不存在"
            if (!entryPath(cur).exists()) out += "CURRENT=$cur 但入口 bin/dsh-supervisor 缺失"
            if (readKernelJson(cur) == null) out += "CURRENT=$cur 但 kernel.json 缺失/不可解析"
        }
        return out
    }

    private fun errText(e: Throwable) = e::class.java.simpleName + ": " + (e.message ?: "(无消息)")

    /**
     * 从 zip 里取出 kernel.json 全文（不解整包）。
     *
     * 注意 `name.endsWith("kernel.json")` 是**刻意的宽松匹配**：包内路径恒为
     * `kernel/<version>/kernel.json`，而 version 事先未知 —— 这正是我们要读它的原因。
     * 但也因此可能命中 `foo-kernel.json` 这类条目，所以下面还要校验解析出的
     * version 非空，把它当作可信性门槛。
     *
     * 返回 null 表示"取不到或不可解析"，调用方据此归为 BrokenBaseline。
     */
    private fun readKernelJsonFromZip(zip: File): String? {
        return try {
            java.util.zip.ZipInputStream(zip.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith("kernel.json")) {
                        val text = zis.bufferedReader().readText()
                        if (text.isNotBlank()) return text
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readVersionFromZip(zip: File): String? {
        val text = readKernelJsonFromZip(zip) ?: return null
        return try {
            // 用 optString(name) 后判空，而不是 optString(name, null)：
            // 后者在 Kotlin 里会推断成 Nothing? 并触发 Java 类型不匹配警告，
            // 且语义上"缺失"与"空串"本就该一起归一为 null。
            JSONObject(text).optString("version", "").ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 解压 zip 到 dest（自动建目录）。
     *
     * 用 `java.util.zip.ZipInputStream`：它按**局部头**的 method 字段自动分派
     * Stored / Deflate，无需调用方关心压缩方式 —— 这正是我们要的（对照
     * container-engine/src/zip.js，那边因为是纯 JS 手写 zip，曾漏掉 Deflate 支持）。
     *
     * 安全：内核包可能来自本地 feed（用户放的 zip），属不可信输入，
     * 因此逐条做**目录穿越**检查。历史实现直接 `File(dest, entry.name)`，
     * 一个名为 `../../shared_prefs/x.xml` 的条目就能写出沙箱之外。
     */
    private fun unzip(zip: File, dest: File) {
        val destRoot = dest.canonicalFile
        java.util.zip.ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                val name = entry.name
                val out = File(dest, name).canonicalFile
                if (!out.path.startsWith(destRoot.path + File.separator) && out.path != destRoot.path) {
                    throw IllegalStateException("内核包条目路径越界（疑似目录穿越）: $name")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os -> zis.copyTo(os) }
                    count += 1
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            if (count == 0) throw IllegalStateException("内核包内没有任何文件条目")
        }
    }

    companion object {
        const val TAG = "KernelManager"
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
