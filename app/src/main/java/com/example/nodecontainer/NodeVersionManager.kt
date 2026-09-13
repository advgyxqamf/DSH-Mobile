package com.example.nodecontainer

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * Node 版本管理 —— “可升级容器”的核心。
 *
 * 职责：
 *  1. 读取 assets/node-versions.json 版本清单（哪些 Node 版本可用、OTA 地址、sha256）。
 *  2. 维护“当前生效版本”指针文件 files/node/CURRENT。
 *  3. 安装(升级)：从 OTA 下载 zip → sha256 校验 → 解压到 files/node/<version>/。
 *     校验失败直接抛异常、绝不切换指针 —— “坏包永不生效”。
 *  4. 列出已安装版本。
 *
 * 由此：未来升级 Node = 往清单追加一条记录 + 用 scripts/make-release.sh 生成发布包上传，
 *       App 端即可在不发新版 APK 的前提下完成 Node 升级。
 */
class NodeVersionManager(private val context: Context) {

    data class NodeVersion(
        val version: String,
        val channel: String,
        val minAndroidApi: Int,
        val bundled: Boolean,   // true = 已随 APK 打包在 assets，首启离线可用
        val url: String,
        val sha256: String?,
        val size: Long
    )

    data class Manifest(val default: String, val abi: String, val versions: List<NodeVersion>)

    private val currentPointer = File(context.filesDir, "node/CURRENT")

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
                    minAndroidApi = o.optInt("minAndroidApi", 24),
                    bundled = o.optBoolean("bundled", false),
                    url = o.getString("url"),
                    sha256 = o.optString("sha256", "").takeIf { it.isNotBlank() },
                    size = o.optLong("size", 0L)
                )
            }
        }
        return Manifest(
            default = json.getString("default"),
            abi = json.getString("abi"),
            versions = versions
        )
    }

    /** 当前生效版本：读指针文件，缺省回落到清单 default。 */
    fun currentVersion(): String =
        if (currentPointer.exists()) currentPointer.readText().trim() else loadManifest().default

    /** 切换当前版本（原子写）。调用前需保证目标版本已 install 成功。 */
    fun setCurrentVersion(version: String) {
        currentPointer.parentFile?.mkdirs()
        // 先写临时文件再 rename，保证指针写入的原子性
        val tmp = File(currentPointer.parentFile, "CURRENT.tmp")
        tmp.writeText(version)
        tmp.renameTo(currentPointer)
    }

    fun isInstalled(version: String): Boolean =
        NodeProvisioner.nodeExecutable(context, version).canExecute()

    /** 已安装到沙箱的版本目录名列表。 */
    fun installedVersions(): List<String> {
        val dir = File(context.filesDir, "node")
        return dir.list()?.filter {
            it != "CURRENT" && File(dir, it).isDirectory
        } ?: emptyList()
    }

    /**
     * 安装(升级)一个 OTA 版本。
     * 注：内置(bundled=true)版本用 NodeProvisioner.ensureBundledNode 解压，不走此下载路径。
     */
    suspend fun install(version: NodeVersion) {
        val zipFile = File(context.cacheDir, "node-${version.version}.zip")
        download(version.url, zipFile)
        version.sha256?.let { expected ->
            val actual = sha256(zipFile)
            require(actual.equals(expected, ignoreCase = true)) {
                "sha256 校验失败: 期望 $expected, 实际 $actual"
            }
        }
        val targetDir = File(context.filesDir, "node/${version.version}").apply { mkdirs() }
        unzip(zipFile, targetDir)
        val node = File(targetDir, "node")
        require(node.exists()) { "发布包内缺少 node 可执行文件" }
        node.setExecutable(true)
        zipFile.delete()
        Log.i(TAG, "已安装 Node ${version.version} -> ${targetDir.absolutePath}")
    }

    // ---- 下载 / 校验 / 解压 ----

    private fun download(url: String, dest: File) {
        URL(url).openStream().use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unzip(zip: File, dest: File) {
        val zis = java.util.zip.ZipInputStream(zip.inputStream())
        var entry = zis.nextEntry
        while (entry != null) {
            val outFile = File(dest, entry.name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { os -> zis.copyTo(os) }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
        zis.close()
    }

    companion object {
        const val TAG = "NodeVersionManager"
    }
}
