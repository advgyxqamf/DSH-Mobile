package com.example.nodecontainer

import android.content.Context
import org.json.JSONObject

/**
 * 内置 Node 版本的元信息读取。
 *
 * ============================================================================
 *  为什么这里只剩「读」这一件事
 * ============================================================================
 * 早期版本叫 NodeVersionManager，带一整套 OTA 链路：从远端下载 zip →
 * sha256 校验 → 解压到 filesDir/node/<version>/ → 切换 CURRENT 指针。
 *
 * 那套设计在 Android 10+ 上【从根上不成立】，已整体删除：
 *   · filesDir 的 SELinux label 是 app_data_file，**禁止 execve**。
 *     所以解压出来的 node 永远无法被 ProcessBuilder 启动，"升级到新版本"
 *     这个目标根本无法达成（详见 NodeProvisioner 顶部的 W^X 说明）。
 *   · 清单里的 OTA 地址一直是占位符（REPLACE_WITH_YOUR_OTA_HOST），
 *     这段代码从未真正跑通过一次。
 *   · 更糟的是 isInstalled() 会在 filesDir 里看到文件就返回 true，
 *     而那个文件不可执行 —— 一个"看起来成功、实际必然失败"的接口。
 *
 * 现在的运行时来源只有一个、且是确定的：
 *   APK 里的 jniLibs/arm64-v8a/libnode.so，安装时由系统解压到
 *   nativeLibraryDir，从那里直接执行。它随 APK 版本走 —— 升级 Node
 *   等价于重新出一次 APK（这也正是 fast-apk.yml 存在的意义：
 *   换 Node 二进制不需要重编 Node，只要下载新的预编译产物）。
 *
 * 保留本类的原因：启动诊断需要显示"当前是哪个 Node 版本"，
 * 这仍然要从 assets 里的清单读。仅此而已。
 * ============================================================================
 */
class NodeVersionManager(private val context: Context) {

    /** 清单里的一条版本记录。bundled 恒为 true —— 见类注释。 */
    data class NodeVersion(
        val version: String,
        val channel: String,
        val minAndroidApi: Int
    )

    data class Manifest(val default: String, val abi: String, val versions: List<NodeVersion>)

    /**
     * 读取 assets/node-versions.json。
     *
     * 失败时抛异常（由调用方展示到诊断面板）—— 不要在这里静默兜底成某个
     * 默认版本号，那会掩盖"清单被改坏了"这个事实，反而更难排查。
     */
    fun loadManifest(): Manifest {
        val text = context.assets.open("node-versions.json")
            .bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val versions = json.getJSONArray("versions").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NodeVersion(
                    version = o.getString("version"),
                    channel = o.optString("channel", "unknown"),
                    minAndroidApi = o.optInt("minAndroidApi", 24)
                )
            }
        }
        return Manifest(
            default = json.getString("default"),
            abi = json.getString("abi"),
            versions = versions
        )
    }

    /**
     * 当前生效的 Node 版本号。
     *
     * 语义很直接：就是清单里的 default。不存在"运行期切换版本"这回事，
     * 因为可执行的运行时只能来自 APK 的 nativeLibraryDir（见类注释）。
     *
     * 注意这个值【仅用于展示】。真正跑的二进制版本以实际 `node -v` 输出为准，
     * 两者理论上应该一致（构建时会同步清单，见 build-node.yml 的 sync 步骤），
     * 但诊断面板上以 `node -v` 的实测输出为最终依据。
     */
    fun currentVersion(): String = loadManifest().default
}
