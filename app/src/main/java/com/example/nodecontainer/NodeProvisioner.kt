package com.example.nodecontainer

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 负责把“打包在 assets 里的 node 二进制”与“server.js 探针”解压到应用沙箱
 * （/data/data/<pkg>/files），并赋予可执行权限。幂等、可重复调用。
 *
 * 设计要点：node 二进制不写死在 jniLibs，而是作为【运行时资源】放在 files/node/<version>/。
 * 这让“升级 Node”只需在沙箱里多放一份新版本目录、切换指针即可，无需重新发 APK。
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

    /** 某版本 node 可执行文件的绝对路径（沙箱内）。 */
    fun nodeExecutable(context: Context, version: String): File =
        File(context.filesDir, "node/$version/node")

    /**
     * 确保“内置(assets)版本”已解压到沙箱且可执行。首启走这里。
     * 若已存在且可执行则跳过（幂等）。
     */
    fun ensureBundledNode(context: Context, version: String) {
        val target = nodeExecutable(context, version)
        if (target.exists() && target.canExecute()) return
        val abi = currentAbi()
        val assetPath = "node-bin/$abi/node"
        context.assets.open(assetPath).use { input ->
            target.parentFile?.mkdirs()
            target.outputStream().use { out -> input.copyTo(out) }
        }
        target.setExecutable(true)
    }

    /** 把 server.js 探针复制到 filesDir（所有版本共享），供 node 启动时加载。 */
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
