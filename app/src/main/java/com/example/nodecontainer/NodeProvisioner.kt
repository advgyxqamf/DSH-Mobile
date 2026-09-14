package com.example.nodecontainer

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 负责让 node 可执行文件「就位到能被 exec 的位置」。
 *
 * ============================================================================
 *  【重要设计变更】为什么不再把 node 解压到 filesDir
 * ============================================================================
 * 早期实现是把 assets 里的 node 解压到
 *     /data/data/<pkg>/files/node/<version>/node
 * 然后 setExecutable(true) 再 ProcessBuilder 启动。在 Android 10+ 的真机上，
 * 这会以
 *     IOException: Cannot run program ".../files/node/24.21.0/node":
 *     error=13, Permission denied
 * 失败（真机 Android 16 / API 36 实测）。
 *
 * 根因是 SELinux 强制 W^X 策略，不是权限位问题：
 *   - /data/data/<pkg>/files/    label = app_data_file → **禁止** execve
 *   - /data/app/<pkg>/lib/<abi>/ label = exec_type    → 允许 exec
 * 官方立场（Google issuetracker 128554619，明确回复"设计如此"）：
 *   "Calling exec() on writable application files is a W^X violation...
 *    While exec() no longer works on files within the application home directory,
 *    it continues to be supported for files within the read-only /data/app
 *    directory. In particular, it should be possible to package the binaries into
 *    your application's native libs directory and enable
 *    android:extractNativeLibs=true, and then call exec() on the /data/app
 *    artifacts."
 *
 * 所以现在走官方推荐路径：node 以 jniLibs/arm64-v8a/libnode.so 打包，
 * 安装时由系统解压到 nativeLibraryDir（= /data/app/.../lib/arm64-v8a/），
 * 直接从那里执行。这条路径不需要任何权限设置，也不能被权限设置破坏。
 *
 * 一个容易踩的坑：File.canExecute() 对上述限制【完全无感】。它只查 stat 的
 * x 权限位，既不知道目录是不是 noexec 挂载，也不知道 SELinux 策略。所以它在
 * filesDir 那份文件上会返回 true —— 这正是旧版本诊断面板显示"可执行"却在 exec
 * 时报 EACCES 的原因（假阳性）。判断能否执行，唯一可靠的办法是真去执行一次。
 * ============================================================================
 *
 * 【关于 OTA 升级的影响，必须说明】
 * nativeLibraryDir 是【安装时固定、运行期只读】的，路径形如
 *     /data/app/~~<随机>==/<pkg>-<随机>==/lib/arm64-v8a/
 * 每次 APK 更新这个随机串都会变。这意味着：
 *   - 原先「在沙箱放多个版本目录、切换 CURRENT 指针」的 OTA 方案，
 *     **在 lib dir 路径上不成立**（那里只能有一份、且由 APK 安装决定）。
 *   - 当前策略：内置版本(bundled)从 nativeLibraryDir 执行，这是可靠路径，
 *     保证首启离线可跑；OTA 通道保留在 filesDir（见 installedVersions /
 *     resolveExecutable 中的说明），将来若要真正启用可执行型 OTA，
 *     需要另行解决 filesDir 的 exec 限制（例如以 app_process/zygote 拉起，
 *     或让 OTA 直接走 APK 更新），这不属于本轮修复范围。
 */
object NodeProvisioner {
    private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /** 选本机 ABI（当前只编了 arm64-v8a；其他架构回退并提示）。 */
    fun currentAbi(): String {
        for (abi in Build.SUPPORTED_ABIS) {
            if (abi in SUPPORTED_ABIS) return abi
        }
        return "arm64-v8a"
    }

    /**
     * 内置 node 在设备上的真实路径：nativeLibraryDir 下的 libnode.so。
     *
     * 这个文件由系统在安装 APK 时解压生成（前提：Manifest 的 extractNativeLibs
     * 为 true，或 gradle 的 packaging.jniLibs.useLegacyPackaging 为 true，
     * 两者等价，本项目两处都写了）。它位于 label 为 exec_type 的只读目录，
     * 是 Android 上唯一被允许 exec 的应用私有路径。
     */
    fun bundledExecutable(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libnode.so")

    /**
     * 兼容旧调用点的路径查询。
     *
     * 语义变化：不再返回 filesDir 下那份（那份无法执行），而是返回真正会被
     * 执行的路径。保留 version 参数是为了未来接入 OTA 时不改签名。
     * 对 bundled 版本一律返回 nativeLibraryDir 里的 libnode.so。
     */
    fun nodeExecutable(context: Context, version: String): File =
        bundledExecutable(context)

    /**
     * OTA 版本预期的落地路径（filesDir 下）。
     *
     * ⚠️ 注意：当前它【不可执行】—— Android 10+ 禁止 exec 应用可写目录中的文件。
     * 保留这个函数是为了让 OTA 的下载/校验/解压链路完整（数据文件仍可放在这里），
     * 但把它当作"可直接 ProcessBuilder 启动的路径"是错的。
     * 真正可执行的入口只有 bundledExecutable()。
     */
    fun otaExecutable(context: Context, version: String): File =
        File(context.filesDir, "node/$version/node")

    /**
     * 确保内置 node 可用。首启走这里。
     *
     * 与旧实现的区别：**不再复制任何文件**。node 已经在安装 APK 时由系统放到
     * nativeLibraryDir 了，我们只需要确认它在，并返回它。
     * 复制反而有害 —— 复制到 filesDir 的那份是不可执行的。
     *
     * @return 可执行的 node 文件
     * @throws IllegalStateException 当 lib dir 里找不到 libnode.so 时
     *         （通常意味着 extractNativeLibs 没生效，或设备 ABI 不匹配）
     */
    fun ensureBundledNode(context: Context, version: String): File {
        val target = bundledExecutable(context)
        if (target.exists()) return target

        // 兜底排查：把 nativeLibraryDir 的实际内容报出来，便于一眼看出是
        // "没解压" 还是 "名字不对" 还是 "ABI 不匹配"。
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

    /** 把 server.js 探针复制到 filesDir（纯数据文件，不受 W^X 影响，可保留）。 */
    fun ensureServerScript(context: Context): File {
        val script = File(context.filesDir, "server.js")
        if (!script.exists()) {
            context.assets.open("node/server.js").use { input ->
                script.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return script
    }
}
