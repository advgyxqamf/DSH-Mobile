package com.example.nodecontainer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 跨进程诊断日志（文件型）。
 *
 * NodeRuntimeService 跑在独立进程(:node)，MainActivity 跑在默认进程，二者无法共享
 * Kotlin 单例。因此所有诊断信息统一追加写入应用私有文件 files/diagnostics.txt，
 * 由 MainActivity 轮询读取并渲染到屏幕。
 *
 * 这是“完整报错、可观测”诉求的落地：App 打开后，无论成功还是失败，用户都能在
 * 屏幕上逐阶段看到状态与真实错误信息（含 node 自身的 stderr），而不必去翻 logcat。
 */
object RuntimeDiagnostics {

    private const val FILE = "diagnostics.txt"
    private const val NODE_ERR = "node-stderr.log"

    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun file(ctx: Context): File = File(ctx.filesDir, FILE)
    fun nodeErrFile(ctx: Context): File = File(ctx.filesDir, NODE_ERR)

    @Synchronized
    fun clear(ctx: Context) {
        // 仅清空诊断主文件；node-stderr.log 保留，便于失败时回看 node 自身报错
        file(ctx).writeText("")
    }

    /**
     * 追加一条诊断。
     * @param ok null=进行中/信息, true=成功, false=失败
     */
    @Synchronized
    fun append(ctx: Context, stage: String, ok: Boolean?, message: String, detail: String = "") {
        val ts = tsFmt.format(Date())
        val mark = when (ok) {
            true -> "[OK]"
            false -> "[FAIL]"
            null -> "[..]"
        }
        val line = buildString {
            append("${ts} ${mark} ${stage}: ${message}")
            if (detail.isNotBlank()) append("\n      ${detail.replace("\n", "\n      ")}")
        }
        file(ctx).appendText(line + "\n")
    }

    @Synchronized
    fun read(ctx: Context): String =
        if (file(ctx).exists()) file(ctx).readText() else ""

    @Synchronized
    fun recordNodeStderr(ctx: Context, text: String) {
        if (text.isNotBlank()) nodeErrFile(ctx).appendText(text)
    }

    fun readNodeStderr(ctx: Context): String =
        if (nodeErrFile(ctx).exists()) nodeErrFile(ctx).readText() else ""
}
