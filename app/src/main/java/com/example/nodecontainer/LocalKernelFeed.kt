package com.example.nodecontainer

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * 本地内核 feed —— A'' 自举的落点。
 *
 * ============================================================================
 *  它解决什么问题
 * ============================================================================
 * 原诉求是「**离线、设备自举、不依赖外网/PC**」。
 *
 * 已证伪的路（见 /workspace/P3 内置构建链方案对比.md §5）：
 *   · A/C1「把 aapt2 打进 APK」—— Google Maven 上**根本没有 aarch64 版 aapt2**，
 *     实测 `linux-aarch64` / `linux-arm64` 均 404，解包出来是 x86_64 + glibc
 *     （e_machine=0x3e、PT_INTERP=/lib64/ld-linux-x86-64.so.2）。exec 四道关
 *     的后三关（interp / 架构 / libc）在装机后无法补救。
 *   · A'「自实现 APK 重打包」—— 要手写 APK v2 签名（CMS/PKCS#7 + ASN.1 DER）、
 *     重编二进制 AXML、重编 resources.arsc。而且当前 APK 用的是 AGP 自动生成的
 *     **debug 签名**，每次 CI 出的包签名都不同，改包后连 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
 *     都过不去。
 *
 * 而真正的高频诉求是「**DSH 改自己的内核**」—— 这件事**根本不需要碰 APK**：
 * 内核是 `files/kernel/<version>/` 下的一个数据目录，容器本来就有权改它。
 * 唯一缺的是「设备上从哪拿到新内核包」这一步。
 *
 * 本类补的就是这一步：**从设备自己的存储里发现内核包，就地安装**。
 *
 * ============================================================================
 *  为什么这比「内置构建链」更符合本项目的信任模型
 * ============================================================================
 * 内置构建链（A/C1）意味着设备上有一套能**生产**内核的工具。那套工具本身
 * 也需要被信任 —— 于是信任面从「一个数据目录」扩张成「一整条工具链」。
 *
 * 而本方案里，设备**不生产内核，只安装已签名内核**。生产者（CI / 内核仓）
 * 仍在外部，私钥仍在外部。设备侧只有公钥（焊进 APK），职责单一：
 * 验签通过就装，不通过就拒。
 *
 * 这恰好复用了已经打通、且已被实测验证的两条链：
 *   · ed25519 签名/验签（`crypto.verify`，Node 侧，与 API level 无关）
 *   · 原子落盘 + CURRENT 指针切换（失败绝不切指针）
 *
 * ============================================================================
 *  发现位置（按优先级）
 * ============================================================================
 * 1. `<externalFilesDir>/kernel-feed/`     —— 应用专属外部目录，无需任何权限
 * 2. `/sdcard/dsh/kernel-feed/`            —— 用户直接放置（需 MANAGE_EXTERNAL_STORAGE
 *                                             或 App 专属目录之外的可读路径）
 *
 * 选这两个位置的理由：位置 1 在任何设备上都能用（Android 4.4+ 起 app-specific
 * external dir 不需要权限），是**保底可用**的投递点；位置 2 是为了让用户/脚本
 * 用 `adb push` 或文件管理器直接投递，更符合"自举"的手感。
 *
 * 目录内约定：
 *   kernel-*.zip        候选内核包（必须带 ed25519 签名，否则 install 会拒）
 *   kernel-manifest.json 可选，提供 sha256/version 锚点（强烈建议提供）
 *
 * 只取**一个**候选（按文件名倒序，即版本号最大的那个）。不做批量安装：
 * 「一次升级一个版本」让失败面最小，也让 CURRENT 指针的语义保持清晰。
 * 装成功后调用方应清理该目录（见 [LocalKernelFeed.consume]）。
 */
object LocalKernelFeed {

    const val TAG = "LocalKernelFeed"

    /** 一次 feed 扫描的结果。 */
    data class Feed(
        val zip: File,
        val manifest: File?,
        val manifestJson: JSONObject?,
        /** manifest 里声明的 sha256（大小写已归一为小写）。null = 无锚点。 */
        val expectedSha256: String?,
        val expectedVersion: String?,
    )

    /**
     * 扫描本地 feed。没有候选包返回 null。
     *
     * 注意：这里**不**做任何校验 —— 校验是 [KernelInstaller] 的职责。
     * 本方法只回答"有没有东西要装"，保持单一职责，也让"扫描"这一步不至于
     * 因为包里内容有问题就整个失效（否则一个坏包会挡住后面所有包）。
     */
    fun scan(context: Context): Feed? {
        for (dir in candidateDirs(context)) {
            if (!dir.isDirectory) continue
            val zip = newestZip(dir) ?: continue

            val manifestFile = File(dir, "kernel-manifest.json")
            var manifestJson: JSONObject? = null
            if (manifestFile.isFile) {
                manifestJson = try {
                    JSONObject(manifestFile.readText())
                } catch (_: Throwable) {
                    // manifest 存在但解析失败：**不因此放弃**装包。
                    // 理由：manifest 是可选的"锚点增强"，不是必需品；而一个损坏的
                    // manifest 不该让一个签名完好的内核包变得无法安装 —— 那等于
                    // 让非关键文件拥有了否决权。install 会照常做验签。
                    RuntimeDiagnostics.append(
                        context, "kernel-feed", false, "feed 内 manifest 不可解析",
                        "已忽略该 manifest，改以包内签名为准: ${manifestFile.absolutePath}"
                    )
                    null
                }
            }

            val sha = manifestJson?.optString("sha256", "")?.ifBlank { null }?.lowercase()
            val ver = manifestJson?.optString("version", "")?.ifBlank { null }
            return Feed(
                zip = zip,
                manifest = if (manifestJson != null) manifestFile else null,
                manifestJson = manifestJson,
                expectedSha256 = sha,
                expectedVersion = ver,
            )
        }
        return null
    }

    /** 安装成功后清掉候选包，避免下次启动重复安装。 */
    fun consume(feed: Feed) {
        try {
            feed.zip.delete()
            feed.manifest?.delete()
        } catch (_: Throwable) {
            // 删不掉不影响正确性：install 是幂等的（版本目录已存在则直接切指针），
            // 下次扫描会再次走一遍校验并快速返回 already-installed。
        }
    }

    /**
     * 候选目录，按优先级排序。
     *
     * `externalFilesDir` 可能返回 null（外部存储未挂载 / 被弹出）—— 必须
     * 容忍，否则在"存储卡拔了"的设备上会直接崩。
     */
    private fun candidateDirs(context: Context): List<File> {
        val out = mutableListOf<File>()
        try {
            context.getExternalFilesDir(null)?.let { out += File(it, "kernel-feed") }
        } catch (_: Throwable) { /* 外部存储不可用，跳过 */ }
        // getExternalStorageDirectory() 已 deprecated（作用域存储时代的建议是别用）。
        // 这里**刻意**继续用，理由是本应用的定位：受管设备上的容器，
        // 已声明 MANAGE_EXTERNAL_STORAGE 且需通过 AppOps 授权。
        // 用它的好处是 feed 目录可被 `adb push` / 文件管理器直接投递 —— 这正是
        // "自举"要的手感。若只是内部升级通道，用上面的 app-specific 目录就够了。
        @Suppress("DEPRECATION")
        out += File(Environment.getExternalStorageDirectory(), "dsh/kernel-feed")
        return out
    }

    /**
     * 找候选包：`kernel-*.zip`，按文件名**倒序**取第一个。
     *
     * 为什么按名字倒序而不是 mtime：内核包名含版本号（`kernel-1.4.2.zip`），
     * 字典序在版本号同为多位数字时与语义序一致。而 mtime 会被"拷贝/解压"
     * 之类的操作整体改写（甚至全部变成同一时刻），反而不可靠。
     */
    private fun newestZip(dir: File): File? {
        val candidates = dir.listFiles { f ->
            f.isFile && f.name.startsWith("kernel-") && f.name.endsWith(".zip")
        } ?: return null
        return candidates.sortedByDescending { it.name }.firstOrNull()
    }
}
