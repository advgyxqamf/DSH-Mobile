package com.example.nodecontainer

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 让 Node 可执行文件就位的入口。
 *
 * ============================================================================
 *  核心约束：Android 上的应用私有可执行文件只有一个合法去处
 * ============================================================================
 * SELinux 强制 W^X 策略（Android 10+）：
 *   /data/data/<pkg>/files/     label = app_data_file  →  **禁止 execve**
 *   /data/app/<pkg>/lib/<abi>/  label = exec_type      →  允许 exec
 *
 * 所以 node 不能解压到 filesDir 再执行（那会在真机上以
 * `error=13, Permission denied` 失败），唯一可行路径是：
 *   以 jniLibs/arm64-v8a/libnode.so 打包 → 安装时系统解压到
 *   nativeLibraryDir → 直接从那里 exec。
 *
 * 两个必须同时满足的打包开关（本仓库两处都写了，见 docs/ARCHITECTURE.md）：
 *   AndroidManifest 的 android:extractNativeLibs="true"
 *   gradle 的 packaging.jniLibs.useLegacyPackaging = true
 *
 * ⚠️ File.canExecute() 在上述约束下【完全不可信】：它只查 stat 的 x 权限位，
 *    对 noexec 挂载和 SELinux 策略无感，会在 filesDir 那份文件上返回 true。
 *    判断"能否执行"的唯一可靠办法是真去执行一次 —— 见
 *    `native/NativePreparer.kt` 的 `probe()`。
 *
 * 完整的踩坑记录（linker 搜索路径、LD_LIBRARY_PATH、缓存漏存 libc++ 等）
 * 见 docs/ARCHITECTURE.md。这里只保留改动代码时必须知道的约束。
 * ============================================================================
 *
 * ## 本文件在「原生资产抽象层」里的位置
 *
 * 「哪些二进制要能 exec、各自依赖什么、怎么验证」已收敛到 `native/` 包
 * （[com.example.nodecontainer.native.NativeAssetRegistry] 是唯一事实来源，
 * [com.example.nodecontainer.native.NativePreparer] 是统一引擎）。
 *
 * 本对象因此**只剩两件事**：
 *   1. [bundledExecutable] —— 一个薄转发，保留给存量调用方与诊断展示；
 *   2. [ensureServerScript] —— 纯数据文件的复制，与 exec 无关，**不受 W^X 影响**。
 *
 * 历史上的 [ensureBundledNode] 已废弃：它只检查 `libnode.so` 存在与否，
 * 完全不知道依赖库的存在，是「依赖缺失被误判为 SELinux 拒 exec」这一
 * 误导性故障的成因之一。需要校验请改用 `NativePreparer.prepare()`。
 */
object NodeProvisioner {

    /** 本机首选 ABI，仅用于诊断展示。 */
    fun currentAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    /**
     * 内置 node 在设备上的真实路径：`nativeLibraryDir` 下的 `libnode.so`。
     *
     * 这个文件由系统在安装 APK 时解压生成。它位于 label 为 `exec_type` 的
     * 只读目录，是 Android 上唯一被允许 exec 的应用私有路径。
     *
     * 文件名必须以 `lib` 开头、`.so` 结尾 —— 否则 PackageManager 不会把它当
     * native lib 处理，也就不会被解压到可执行目录里去。
     *
     * 实现已转发到 [com.example.nodecontainer.native.NativeAssetRegistry.resolve]，
     * **不再是硬编码字符串** —— 加新二进制只需改注册表一行。
     */
    fun bundledExecutable(context: Context): File =
        com.example.nodecontainer.native.NativeAssetRegistry
            .resolve(context, com.example.nodecontainer.native.NativeAssetRegistry.NODE)

    /**
     * @deprecated 只检查文件存在与否，**不知道依赖库的存在**。
     *
     * 保留它会造成一个具体且已被真机验证过的误导：
     * `libc++_shared.so` 缺失时，本方法通过 → exec-probe 以 linker 错误失败
     * → errno=13 → 归因到「SELinux 禁止 exec」→ **真因永远浮不出来**。
     *
     * 请改用 `NativePreparer.prepare(context)`（返回结构化的 `PrepareReport`，
     * 依赖检查前置、归因精确到修复动作）。
     */
    @Deprecated(
        "只查存在性、不知依赖，会把「依赖缺失」误判为「SELinux 拒 exec」。改用 NativePreparer.prepare()",
        ReplaceWith("NativePreparer.prepare(context)")
    )
    fun ensureBundledNode(context: Context): File {
        val target = bundledExecutable(context)
        if (target.exists()) return target

        val dir = File(context.applicationInfo.nativeLibraryDir)
        val listing = dir.list()?.joinToString(", ") ?: "(无法列出)"
        throw IllegalStateException(
            "内置 node 不存在: ${target.absolutePath}\n" +
                "nativeLibraryDir = ${dir.absolutePath}\n" +
                "该目录实际内容   = [$listing]\n" +
                "可能原因：\n" +
                "  1) APK 打包时 extractNativeLibs 未生效（未被解压落盘）—— " +
                "检查 Manifest/打包配置；\n" +
                "  2) 设备 ABI 与 APK 内的 ABI 不匹配（当前仅打包 arm64-v8a，设备是 " +
                "${Build.SUPPORTED_ABIS.joinToString()}）；\n" +
                "  3) jniLibs 里缺少 arm64-v8a/libnode.so（构建产物未拷贝）。"
        )
    }

    /**
     * 把 server.js 探针复制到 filesDir。
     *
     * 纯数据文件（不是可执行文件），不受 W^X 影响，放 filesDir 完全没问题。
     * 每个启动都覆盖写 —— 这样换了 APK 里的 server.js 就能立即生效，
     * 不会因为残留旧文件而出现"改了没反应"。
     */
    fun ensureServerScript(context: Context): File {
        val script = File(context.filesDir, "server.js")
        context.assets.open("node/server.js").use { input ->
            script.outputStream().use { out -> input.copyTo(out) }
        }
        return script
    }

    /**
     * 让内核包校验器（`assets/node/kernel-verify.js`）就位。
     *
     * 为什么由 Kotlin 复制而不是直接从 assets 读：Node **读不了 APK 内的
     * assets** —— `process.env` 里没有任何指向它的路径，而且 assets 在 APK
     * 里是压缩存储的，需要 AssetManager 才能访问。所以必须落到普通文件。
     *
     * 与 [ensureServerScript] 的区别：这里用**内容比对**决定是否重写。
     * 原因：校验器会在每次内核安装时被调用，而"每次都写盘"会让它自己成为
     * 一个潜在的失败点（磁盘满/IO 抖动）。内容一致就跳过，减少无谓写。
     *
     * ⚠️ 它是**校验器**，与被校验的内核包解耦。绝不能改从内核目录加载 ——
     *    那会让"签名无效的内核"有机会提供自己的校验器（自证循环）。
     */
    fun ensureKernelVerifyScript(context: Context): File {
        return ensureAssetCopied(context, "node/kernel-verify.js", File(context.filesDir, "kernel-verify.js"))
    }

    /**
     * 让 ed25519 公钥锚点就位（`assets/ota-public.pem` → `files/ota-public.pem`）。
     *
     * 这把公钥是**设备端唯一信任源**：内核包只有用它验签通过才能生效。
     * 它随 APK 冻结、只能随 APK 升级而变 —— 所以任何"运行时可替换公钥"的
     * 设计都会让双信任根失效。本方法只做 APK assets → filesDir 的复制，
     * 不提供任何写入/覆盖该文件的桥方法。
     */
    fun ensureOtaPublicKey(context: Context): File {
        return ensureAssetCopied(context, "ota-public.pem", File(context.filesDir, "ota-public.pem"))
    }

    /**
     * 把 assets 里的文件复制到 filesDir，**内容一致则跳过**。
     *
     * 用字节比对而非 mtime/size：mtime 在 APK 更新后会变（即使内容没变），
     * 而 size 相同不代表内容相同。字节比对是这个场景下唯一可靠的判据，
     * 且这些文件都很小（KB 级），开销可忽略。
     */
    private fun ensureAssetCopied(context: Context, assetPath: String, dest: File): File {
        val assetBytes = context.assets.open(assetPath).use { it.readBytes() }
        if (dest.exists() && dest.length() == assetBytes.size.toLong()) {
            val same = try {
                dest.readBytes().contentEquals(assetBytes)
            } catch (_: Throwable) {
                false
            }
            if (same) return dest
        }
        dest.parentFile?.mkdirs()
        // 先写临时再 rename：避免"写到一半被杀"导致下次读到半截文件。
        // 这个文件是安全关键件（公钥/校验器），半截内容比不存在更危险 ——
        // 它会被当成"存在但无效"，故障表现会非常难查。
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(assetBytes)
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IllegalStateException("无法把 $assetPath 落地到 ${dest.absolutePath}")
        }
        return dest
    }
}
