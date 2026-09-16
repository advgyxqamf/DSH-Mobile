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
    fun readKernelJson(version: String): KernelManifest? {
        val p = kernelJsonPath(version)
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
     * 首启兜底：若沙箱里没有任何内核（CURRENT 缺失），且 APK 内置了基线内核包
     * assets/kernel/<version>.zip，则解压到 files/kernel/<version>/ 并切指针。
     * 这样无网首启也能拉起一个已知良好内核；后续 OTA 覆盖升级。
     * 返回落地后的版本号，或 null（无基线包、需联网 OTA）。
     */
    fun ensureBaseline(): String? {
        val existing = currentVersion()
        if (existing != null && File(kernelRoot, existing).isDirectory) return existing
        val baselineAsset = "kernel/baseline.zip"
        return try {
            context.assets.open(baselineAsset).use { input ->
                val zip = File(context.cacheDir, "kernel-baseline.zip")
                zip.outputStream().use { out -> input.copyTo(out) }
                val version = readVersionFromZip(zip)
                if (version == null) {
                    zip.delete()
                    return@use null
                }
                val dest = File(kernelRoot, version).apply { mkdirs() }
                unzip(zip, dest)
                zip.delete()
                setCurrentVersion(version)
                RuntimeDiagnostics.append(context, "kernel", true, "基线内核已落地", "version=$version")
                version
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readVersionFromZip(zip: File): String? {
        // 仅取 kernel.json 头部的 version 字段，不整包解压
        return try {
            val zis = java.util.zip.ZipInputStream(zip.inputStream())
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.endsWith("kernel.json")) {
                    val text = zis.bufferedReader().readText()
                    zis.close()
                    return JSONObject(text).optString("version", null).ifBlank { null }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            zis.close()
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** 解压 zip 到 dest（自动建目录）。内核包是 .zip，走 java.util.zip 即可，无需外部依赖。 */
    private fun unzip(zip: File, dest: File) {
        java.util.zip.ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os -> zis.copyTo(os) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
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
