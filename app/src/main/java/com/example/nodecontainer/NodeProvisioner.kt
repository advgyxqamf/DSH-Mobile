package com.example.nodecontainer

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 让 Node 可执行文件就位的唯一入口。
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
 *    判断"能否执行"的唯一可靠办法是真去执行一次 —— 见 NodeRuntimeService
 *    的 exec-probe 步骤。
 *
 * 完整的踩坑记录（linker 搜索路径、LD_LIBRARY_PATH、缓存漏存 libc++ 等）
 * 见 docs/ARCHITECTURE.md。这里只保留改动代码时必须知道的约束。
 * ============================================================================
 */
object NodeProvisioner {

    /** 本机首选 ABI，仅用于诊断展示。 */
    fun currentAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    /**
     * 内置 node 在设备上的真实路径：nativeLibraryDir 下的 libnode.so。
     *
     * 这个文件由系统在安装 APK 时解压生成。它位于 label 为 exec_type 的
     * 只读目录，是 Android 上唯一被允许 exec 的应用私有路径。
     *
     * 文件名必须以 lib 开头、.so 结尾 —— 否则 AGP 不会把它当 native lib
     * 处理，也就不会被解压到可执行目录里去。
     */
    fun bundledExecutable(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libnode.so")

    /**
     * 确保内置 node 可用。首启走这里。
     *
     * 刻意【不做任何复制】：node 已在安装 APK 时由系统放到 nativeLibraryDir，
     * 这里只确认它在。复制反而有害 —— 复制到 filesDir 的那份不可执行。
     *
     * @return 可执行的 node 文件
     * @throws IllegalStateException lib dir 里找不到 libnode.so 时。
     *         异常信息里带上目录实况，便于一眼判断是"没解压"还是"ABI 不匹配"。
     */
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
     * 每次启动都覆盖写 —— 这样换了 APK 里的 server.js 就能立即生效，
     * 不会因为残留旧文件而出现"改了没反应"。
     */
    fun ensureServerScript(context: Context): File {
        val script = File(context.filesDir, "server.js")
        context.assets.open("node/server.js").use { input ->
            script.outputStream().use { out -> input.copyTo(out) }
        }
        return script
    }
}
