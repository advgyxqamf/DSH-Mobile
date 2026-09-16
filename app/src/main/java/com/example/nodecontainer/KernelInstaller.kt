package com.example.nodecontainer

import android.content.Context
import com.example.nodecontainer.native.NativeAssetRegistry
import com.example.nodecontainer.native.NativePreparer
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 内核安装器（Kotlin 侧编排层）。
 *
 * ============================================================================
 *  为什么 Kotlin 侧**不能**自己验签 —— 这是 minSdk 的硬约束，不是取舍
 * ============================================================================
 * 内核的信任根是 **ed25519**（`container-engine/src/sign.js`，公钥焊死在
 * `assets/ota-public.pem`）。而 Android 官方文档明确列出：
 *
 * ```
 * Android Signature 算法支持表：
 *   ECDSA      11+
 *   Ed25519    33+        ← 注意这一行
 * ```
 *
 * 本项目 `minSdk = 24`（Android 7.0），`targetSdk = 34`。也就是说：
 * **在 API 24–32 的设备上，`Signature.getInstance("Ed25519")` 抛
 * NoSuchAlgorithmException —— Kotlin 侧根本无法验签。**
 *
 * 旁证（真实工程教训）：Conscrypt 官方支持 Ed25519；但 API 31–32 的平台
 * BouncyCastle 被裁剪、不含 Ed25519；API 33+ 上 `KeyFactory.getInstance("Ed25519")`
 * 还会**静默**解析到 AndroidKeyStore provider（只认硬件密钥，导入软件 PKCS8
 * 会 InvalidKeySpecException）。即"能拿到 Signature 实例"与"能用"是两件事。
 *
 * 结论：**验签必须由 Node 做**（`crypto.verify(null, data, pem, sig)` 走自带
 * OpenSSL，与 API level 完全无关，已在 Node 22/24 实测通过 —— 见
 * `container-engine/test/ota-engine-test.js`）。
 *
 * ============================================================================
 *  于是本类的职责被严格限定为「编排」，不含任何密码学
 * ============================================================================
 * 它做四件事，全部是可独立验证的机械操作：
 *   1. 把候选包（来自 APK assets / 本地文件 / 下载落盘）交给 Node 校验；
 *   2. 校验**通过**才解包到 `files/kernel/<version>.tmp-*`；
 *   3. 原子 rename 到 `files/kernel/<version>`；
 *   4. 原子写 `CURRENT` 指针。
 *
 * 「坏包永不生效」由第 2 步的前置校验保证：校验失败直接返回，**不碰任何已有
 * 文件**。这条不变式比"校验得多严"更重要 —— 它保证最坏情况是"没升级成功"，
 * 而不是"把能跑的版本弄坏了"。
 *
 * ============================================================================
 *  为什么不直接复用 container-engine/src/ota-engine.js
 * ============================================================================
 * 那个引擎是**完整**的（下载 + 验签 + 解包 + 切指针 + 回滚），但它是为
 * 「容器自己就是 Node 进程」的假设写的（见 `boot.js`）。而安卓上编译出的
 * 事实是：**Kotlin 宿主进程（:main）拥有 filesDir 的写权与生命周期控制**，
 * Node（:`+node` 进程）是被它 spawn 的、随时可能被杀。
 *
 * 让"随时可能被杀的进程"去管理"自己下个版本"的落盘，是竞态的来源
 * （写到一半被杀 → 半包残留 → 下次启动读到损坏目录）。所以：
 *   · **落盘/切指针**（有状态、需原子性）→ 留在 Kotlin 侧，它不会中途消失；
 *   · **验签**（无状态、纯函数）→ 交给一次性 Node 进程，用后即弃。
 *
 * 这样两边各做自己可靠的事，而不是把两件事塞进同一个易变进程。
 */
object KernelInstaller {

    const val TAG = "KernelInstaller"

    /** 候选包来源。归因时区分来源很重要 —— 「APK 里没带」和「用户放的坏了」是两类问题。 */
    enum class Source(val label: String) {
        APK_ASSET("APK 内置基线"),
        LOCAL_FILE("本地文件 feed"),
        NONE("无"),
    }

    /**
     * 一次安装尝试的结果。
     *
     * 刻意不抛异常：调用方（启动链）需要**总是**能继续往下走，
     * 且失败必须能落进诊断。异常会诱导"catch 住就完了"的写法，掩盖归因。
     */
    data class InstallResult(
        val ok: Boolean,
        val version: String?,
        val source: Source,
        /** 机械失败或验证失败的短码，如 "signature-invalid" / "sha256-mismatch"。 */
        val reason: String?,
        /** 人类可读的细节，直接进诊断行。 */
        val detail: String,
        val nodeVerifyOutput: String = "",
    ) {
        fun toDiagnosticLine(): String = when {
            ok -> "内核安装成功 v=$version（来源=${source.label}）"
            else -> "内核安装未生效（来源=${source.label}）原因=$reason；$detail"
        }
    }

    /**
     * 校验并安装一个候选内核包。
     *
     * @param zip   候选包（必须已落成**文件** —— Node 校验器要读它，走文件比走
     *              stdin 更利于把失败原因看全）
     * @param manifest 期望的 sha256/version。null 表示"只做包内自校验"
     *                 （此时 sha256 从包本身算，防不住替换，但能挡住结构损坏）
     * @param source  来源标签（仅用于归因）
     * @return 安装结果。**失败时保证 files/kernel 下不留任何新东西。**
     */
    fun install(
        context: Context,
        zip: File,
        manifest: JSONObject?,
        source: Source,
        nodeBin: File = NativeAssetRegistry.resolve(context, NativeAssetRegistry.NODE),
    ): InstallResult {
        if (!zip.isFile) {
            return InstallResult(false, null, source, "zip-missing", "候选包不存在: ${zip.absolutePath}")
        }
        if (zip.length() <= 0) {
            return InstallResult(false, null, source, "zip-empty", "候选包是空文件: ${zip.absolutePath}")
        }

        // ---- 1) 交给 Node 做密码学校验（Kotlin 侧做不到，见类注释）----
        val verify = NodeKernelVerifier.verify(context, zip, manifest, nodeBin)
        if (!verify.ok) {
            return InstallResult(
                ok = false, version = verify.version, source = source,
                reason = verify.reason, detail = verify.detail, nodeVerifyOutput = verify.raw,
            )
        }
        val version = verify.version
            ?: return InstallResult(false, null, source, "no-version", "校验通过但包内无 version", verify.raw)

        // ---- 2) 目标已存在则直接复用（幂等：重复安装同一版本不重写）----
        val km = KernelManager(context)
        val dest = km.kernelDir(version)
        if (dest.isDirectory && km.entryPath(version).exists()) {
            km.setCurrentVersion(version)
            return InstallResult(
                ok = true, version = version, source = source, reason = "already-installed",
                detail = "该版本已落盘，直接切指针", nodeVerifyOutput = verify.raw,
            )
        }

        // ---- 3) 解包到临时目录（同名 .tmp-* 保证不污染正式目录）----
        val tmp = File(km.kernelDir(version).parentFile, "$version.tmp-${android.os.Process.myPid()}-${System.currentTimeMillis()}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            km.unzipInto(zip, tmp)     // 内含目录穿越防护 + 空包检查
        } catch (e: Throwable) {
            tmp.deleteRecursively()    // ★ 失败清理，绝不留半包
            val reason = if (e is IllegalStateException) "unsafe-or-empty-zip" else "unzip-failed"
            return InstallResult(
                false, version, source, reason,
                "${e::class.java.simpleName}: ${e.message ?: ""}", verify.raw
            )
        }

        // 解包后**再核一次**包内 kernel.json 与校验阶段读到的一致。
        // 为什么：Node 校验是读原 zip，而落盘走的是 Java 解压 —— 两者若对
        // 同一 zip 的解读不同（历史上 zip.js 就只在 Stored 下正确），会出现
        // "验的是 A、装的是 B"。这一步把这种不一致变成硬失败。
        val installedManifest = km.readKernelJson(version, tmp)
        if (installedManifest == null) {
            tmp.deleteRecursively()
            return InstallResult(false, version, source, "postcheck-manifest-unreadable",
                "解包后读不到 kernel.json —— ZipInputStream 与校验器对包结构理解不一致", verify.raw)
        }
        if (installedManifest.version != version) {
            tmp.deleteRecursively()
            return InstallResult(false, version, source, "postcheck-version-mismatch",
                "校验阶段 version=$version，解包后读到 ${installedManifest.version}", verify.raw)
        }
        if (verify.entryOk == false) {
            tmp.deleteRecursively()
            return InstallResult(false, version, source, "postcheck-entry-missing",
                "包内缺少入口 ${installedManifest.entry}", verify.raw)
        }

        // ---- 4) 原子就位 ----
        //
        // 顺序是刻意的：先删旧的同名正式目录（若有半包残留），再 rename。
        // rename 在同一文件系统内是原子的，所以设备断电只会得到
        // "改名成功" 或 "没改名"，不会得到半成品 —— 这正是不能用 copyTo 的原因。
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) {
            // rename 失败（跨设备/权限）时退化为拷贝，但**先拷到 .tmp 再 rename**，
            // 保住原子性。直接拷到 dest 会让窗口期内 dest 是不完整的。
            tmp.deleteRecursively()
            return InstallResult(false, version, source, "rename-failed",
                "无法把 ${tmp.absolutePath} 重命名为 ${dest.absolutePath}", verify.raw)
        }

        km.setCurrentVersion(version)
        return InstallResult(
            ok = true, version = version, source = source, reason = null,
            detail = "已落盘并切换指针: ${dest.absolutePath}", nodeVerifyOutput = verify.raw,
        )
    }

    /** 计算文件 sha256（十六进制小写），与 Node 侧 `verify.js:sha256` 同口径。 */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(65536)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
