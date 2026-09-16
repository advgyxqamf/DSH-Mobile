package com.example.nodecontainer

import android.content.Context
import com.example.nodecontainer.native.NativeAssetRegistry
import com.example.nodecontainer.native.NativePreparer
import org.json.JSONObject
import java.io.File

/**
 * 用一次性 Node 进程校验内核包（sha256 + ed25519 + 结构）。
 *
 * ============================================================================
 *  为什么要有这个类
 * ============================================================================
 * 内核信任根是 ed25519，而 **Android 到 API 33 才有 Ed25519**（官方 Signature
 * 算法支持表），本项目 minSdk=24 —— Kotlin 侧在大量设备上根本无法验签。
 * 完整论证见 [KernelInstaller] 类注释。
 *
 * 所以把校验下沉到 Node：`crypto.verify(null, data, pem, sig)` 走 OpenSSL，
 * 与 API level 无关。脚本是 `assets/node/kernel-verify.js`（随 APK 冻结，
 * 与内核包解耦 —— 它是**校验器**，不该由被校验的对象提供）。
 *
 * ============================================================================
 *  「一次性进程」而非「让跑着的内核顺便验一下」
 * ============================================================================
 * 好处有三，每一条都是真问题：
 *  1. **不依赖内核在跑**。首启时还没有内核，但基线包同样要验签。
 *  2. **不给内核提权机会**。内核是"被校验对象"，不能兼任"校验者"——
 *     否则一个签名无效的内核只要能启动，就能宣布自己有效。
 *  3. **失败可观测**。子进程退出码 + stdout/stderr 原样带回，落进诊断。
 *
 * 代价是每次校验要 spawn 一个 Node（~100ms）。内核升级是低频操作（启动时一次），
 * 这个代价完全可接受，而上面三条收益是架构性的。
 */
object NodeKernelVerifier {

    const val TAG = "NodeKernelVerifier"

    /** 与 container-engine 侧的失败短码保持一致，便于两仓对照排查。 */
    data class VerifyOutcome(
        val ok: Boolean,
        val version: String?,
        val reason: String?,
        val detail: String,
        /** 子进程原始输出，直接进诊断 —— 校验失败时这是唯一的一手证据。 */
        val raw: String,
        /** 包内是否含 manifest 声明的入口（结构完整性的一环）。 */
        val entryOk: Boolean?,
    )

    /**
     * 校验一个内核包。
     *
     * @param manifest 期望值（sha256 / version）。允许为 null —— 表示"只做包内
     *                 自校验"（验签仍照做，因签名是包自带的；但缺少 sha256 锚点
     *                 意味着挡不住"用一个合法旧包替换新包"，故调用方应尽量提供）
     */
    fun verify(
        context: Context,
        zip: File,
        manifest: JSONObject?,
        nodeBin: File = NativeAssetRegistry.resolve(context, NativeAssetRegistry.NODE),
    ): VerifyOutcome {
        val script = try {
            NodeProvisioner.ensureKernelVerifyScript(context)
        } catch (e: Throwable) {
            return VerifyOutcome(
                false, null, "verifier-script-missing",
                "内置校验脚本不可用: ${e.message}", "", null
            )
        }

        val pubKeyPath = try {
            NodeProvisioner.ensureOtaPublicKey(context).absolutePath
        } catch (e: Throwable) {
            return VerifyOutcome(
                false, null, "public-key-missing",
                "公钥锚点不可用: ${e.message}", "", null
            )
        }

        // 参数用 argv 传，不走 shell —— 路径里的空格/特殊字符不会变成注入。
        val args = mutableListOf(
            nodeBin.absolutePath,
            script.absolutePath,
            "--zip", zip.absolutePath,
            "--pubkey", pubKeyPath,
        )
        manifest?.optString("sha256", "")?.ifBlank { null }?.let { args += listOf("--sha256", it) }
        manifest?.optString("version", "")?.ifBlank { null }?.let { args += listOf("--version", it) }

        return try {
            val pb = ProcessBuilder(args)
                .directory(context.filesDir)
                .redirectErrorStream(true)
            pb.environment().apply {
                put("HOME", context.filesDir.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
                // 与启动链同款：linker 只查 LD_LIBRARY_PATH，见 NativePreparer.probe()
                put("LD_LIBRARY_PATH", NativePreparer.libSearchPath(context))
            }
            val proc = pb.start()
            val out = StringBuilder()
            // 读线程与 waitFor 并行 —— 单线程 waitFor 后读管道会死锁（管道写满）。
            val pump = Thread {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (out.length < MAX_OUTPUT) out.append(line).append('\n')
                }
            }
            pump.start()
            val finished = proc.waitFor(VERIFY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return VerifyOutcome(false, null, "verifier-timeout",
                    "校验进程超时 ${VERIFY_TIMEOUT_MS}ms", out.toString(), null)
            }
            pump.join(1000)
            val raw = out.toString()

            // 校验脚本约定：stdout 最后一行是 `DSH_VERIFY_RESULT <json>`。
            // 用"约定行"而非"整个 stdout 是 JSON"：这样脚本可以自由 printf 调试信息，
            // 不会因为多打一行日志就让解析失败（历史上这种事在真机排查时很烦人）。
            val line = raw.lineSequence().lastOrNull { it.startsWith(RESULT_PREFIX) }
                ?: return VerifyOutcome(false, null, "verifier-no-result",
                    "校验进程未输出结果行（exit=${proc.exitValue()}）", raw, null)

            val json = try {
                JSONObject(line.substring(RESULT_PREFIX.length).trim())
            } catch (e: Throwable) {
                return VerifyOutcome(false, null, "verifier-bad-json",
                    "结果行不是合法 JSON: ${line.take(200)}", raw, null)
            }

            VerifyOutcome(
                ok = json.optBoolean("ok", false),
                version = json.optString("version", "").ifBlank { null },
                reason = json.optString("reason", "").ifBlank { null },
                detail = json.optString("detail", ""),
                raw = raw,
                entryOk = if (json.has("entryOk")) json.optBoolean("entryOk") else null,
            )
        } catch (e: Throwable) {
            VerifyOutcome(false, null, "verifier-spawn-failed",
                "${e::class.java.simpleName}: ${e.message ?: ""}", "", null)
        }
    }

    private const val RESULT_PREFIX = "DSH_VERIFY_RESULT "
    private const val VERIFY_TIMEOUT_MS = 60_000L
    private const val MAX_OUTPUT = 32 * 1024
}
