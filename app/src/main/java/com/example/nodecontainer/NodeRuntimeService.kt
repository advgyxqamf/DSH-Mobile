package com.example.nodecontainer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Node 运行时前台服务（独立进程 :node）。
 *
 * 与之前版本的区别：每一步都用 RuntimeDiagnostics 写入带时间戳的诊断行，
 * 并在失败时把 node 进程自身的 stderr 完整落盘。这样 App 打开后，屏幕上的
 * “启动诊断”面板就能逐阶段反映真实状态——node 二进制是否就位、进程是否拉起、
 * 端口是否就绪、若失败则失败在哪一环、node 自己报了什么错。
 *
 * 关键点：node 是直接 exec 的应用私有二进制（bionic 链接），因此这是“原生安卓环境”，
 *        与 Termux 无关、不需要 root。
 */
class NodeRuntimeService : Service() {

    private var nodeProcess: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var portUp = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RuntimeDiagnostics.clear(this)
        scope.launch { startNode() }
        // START_STICKY：进程被杀后系统会尝试重启服务，尽量保活 Node
        return START_STICKY
    }

    private fun startNode() {
        try {
            RuntimeDiagnostics.append(
                this, "init", null, "NodeRuntimeService 启动 (进程 :node)",
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                    "ABI=${NodeProvisioner.currentAbi()}, " +
                    "filesDir=${filesDir.absolutePath}"
            )

            // 1) 版本清单
            val vm = NodeVersionManager(this)
            val manifest = vm.loadManifest()
            RuntimeDiagnostics.append(
                this, "manifest", true, "版本清单加载成功",
                "default=${manifest.default}, abi=${manifest.abi}, " +
                    "可用版本=${manifest.versions.joinToString { it.version }}"
            )

            // 2) 当前生效版本
            val version = vm.currentVersion()
            RuntimeDiagnostics.append(this, "version", true, "当前生效版本=$version", "指针=${version}")

            // 3) 确保 node 二进制就位
            //    注意：这里【不能】用 canExecute() 来判断可用性。
            //    canExecute() 只查 stat 的 x 权限位，对 SELinux W^X 完全无感 ——
            //    旧版本正是因此在诊断面板显示"可执行"，真去 exec 却被回
            //    error=13 (EACCES)。判断能否执行的唯一可靠方式是真执行一次，
            //    所以在下面 exec 成功/失败时再给结论。
            val nodeBin = NodeProvisioner.bundledExecutable(this)
            RuntimeDiagnostics.append(
                this, "provision", null, "定位内置 node 二进制",
                "nativeLibraryDir=${applicationInfo.nativeLibraryDir}\n" +
                    "目标路径=${nodeBin.absolutePath}"
            )
            try {
                NodeProvisioner.ensureBundledNode(this, version)
                RuntimeDiagnostics.append(
                    this, "provision", true, "内置 node 就位（已由系统解压到可执行目录）",
                    "${nodeBin.absolutePath}\n" +
                        "大小=${nodeBin.length()} 字节, " +
                        "可读=${nodeBin.canRead()}, " +
                        "x位=${
                            // 仅作信息展示。务必记住：这个值【不代表】真的能 exec，
                            // 只反映权限位；能否执行由 SELinux 策略决定，见下方 exec 结果。
                            nodeBin.canExecute()
                        }"
                )
            } catch (e: Exception) {
                RuntimeDiagnostics.append(this, "provision", false, "内置 node 不可用", err(e))
                return
            }

            // 4) server.js 探针
            val script = NodeProvisioner.ensureServerScript(this)
            RuntimeDiagnostics.append(this, "script", true, "server.js 探针就位", script.absolutePath)

            // 4.5) 先跑一次 `node -v`：这是对「能否 exec」的确定性验证。
            //      比 canExecute() 可靠得多 —— 它真去执行了。成功说明 W^X 这关过了，
            //      顺便把二进制的真实版本号显示出来（与清单里的 version 可能不同，
            //      因为 lib dir 里只有安装 APK 时打包的那一份）。
            //
            //      这里刻意【不用 runCatching】：它会吞掉所有 Throwable，包括
            //      InterruptedException / OutOfMemoryError 这类不该被当成
            //      "exec 失败"处理的异常，会把诊断引向错误方向。
            //      只精确捕获 IOException（即进程根本无法创建 —— 这正是
            //      error=13 Permission denied 的形态）。
            try {
                val probe = ProcessBuilder(nodeBin.absolutePath, "-v")
                    .redirectErrorStream(true).start()
                val out = probe.inputStream.bufferedReader().readText().trim()
                val exit = probe.waitFor()
                val ok = exit == 0
                RuntimeDiagnostics.append(
                    this, "exec-probe", ok,
                    if (ok) "node -v 执行成功（可执行性已验证）" else "node -v 退出码非 0",
                    "输出: ${out.ifBlank { "(空)" }}, exitCode=$exit"
                )
                if (!ok) return
            } catch (e: java.io.IOException) {
                // 这一步失败通常就是 exec 被拒。把错误码和排查方向一次说清楚。
                RuntimeDiagnostics.append(
                    this, "exec-probe", false,
                    "无法执行 node 二进制",
                    err(e) + "\n" +
                        "排查方向：\n" +
                        "  · error=13 Permission denied → 该路径被 SELinux 禁止 exec。\n" +
                        "    请确认执行的是 nativeLibraryDir 下的 libnode.so，而不是 files/ 里的副本。\n" +
                        "  · error=2 No such file → extractNativeLibs 未生效，.so 没被解压到 lib dir。\n" +
                        "  · error=8 Exec format error → ABI 不匹配或页对齐不满足。"
                )
                return
            }

            // 5) 启动 node
            val pb = ProcessBuilder(
                nodeBin.absolutePath, script.absolutePath, "--port", PORT.toString()
            ).directory(filesDir)
            pb.environment().apply {
                // Node 在安卓沙箱里需要 HOME / TMPDIR，否则部分模块(npm、crypto 临时文件)报错
                put("HOME", filesDir.absolutePath)
                put("TMPDIR", cacheDir.absolutePath)
                put("NODE_PATH", File(filesDir, "node_modules").absolutePath)
            }
            nodeProcess = pb.start()
            RuntimeDiagnostics.append(this, "exec", true, "node 进程已启动", "pid=${currentPid(nodeProcess)}, 监听 127.0.0.1:$PORT")

            // 6) 转发 stdout/stderr 到诊断
            forward(nodeProcess!!.inputStream, "stdout")
            forward(nodeProcess!!.errorStream, "stderr")

            // 7) 监听进程退出（独立于端口轮询，避免遗漏崩溃）
            watchExit()

            // 8) 轮询端口
            pollPort()
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "fatal", false, "启动流程异常", err(e))
            Log.e(TAG, "启动 Node 失败", e)
        }
    }

    /**
     * 取子进程的 PID，仅用于诊断展示（拿不到就返回 "n/a"，绝不影响主流程）。
     *
     * 为什么不用 [Process.pid]：**Android 上根本没有这个方法**。
     * [Process.pid] 是 Java 9 加入 java.lang.Process 的；而 Android 的
     * java.lang.Process 一直没跟进。已用 android.jar 逐版本核对（javap）：
     *   android-29 的 java.lang.Process 只有 getOutputStream/getInputStream/
     *   getErrorStream/waitFor/exitValue/destroy/destroyForcibly/isAlive；
     *   android-30 完全相同；compileSdk 35 亦无 pid()。
     * 所以原先那句
     *     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nodeProcess!!.pid()
     * 前提就是错的 —— 它会在编译期直接报
     *     error: Unresolved reference 'pid'
     * （CI 真实报错：NodeRuntimeService.kt:122:92 Unresolved reference 'pid'）。
     * 注意 SDK_INT 这个【运行时】判断救不了【编译期】的方法缺失。
     *
     * 也不能用 android.os.Process.myPid()：那是**本应用自己**的 pid，
     * 不是 node 子进程的 pid，两者含义完全不同，用了会误导诊断。
     *
     * 可行做法：Android 的 Process 实现类（java.lang.ProcessImpl）
     * 把 pid 编进了 toString()，格式形如
     *     "Process[pid=12345, exitValue=\"not exited\"]"
     * 这是社区长期沿用的解析方式（JDK 侧亦有同样惯例）。这里用正则提取，
     * 解析失败一律降级为 "n/a"，不抛异常。
     */
    private fun currentPid(p: Process?): String {
        if (p == null) return "n/a"
        return runCatching {
            Regex("""pid=(\d+)""").find(p.toString())?.groupValues?.get(1)
        }.getOrNull() ?: "n/a"
    }

    /** 把 node 子进程的 stdout/stderr 转发：stdout 记诊断、stderr 额外落 node-stderr.log。 */
    private fun forward(stream: java.io.InputStream, tag: String) {
        Thread {
            stream.bufferedReader().use { r ->
                r.forEachLine { line ->
                    Log.i("NodeRuntime:$tag", line)
                    if (tag == "stderr") RuntimeDiagnostics.recordNodeStderr(this, line + "\n")
                    else RuntimeDiagnostics.append(this, "node-$tag", null, line)
                }
            }
        }.start()
    }

    /** 等待 node 进程结束；若端口尚未就绪则说明失败并把 node 的 stderr 完整回写诊断。 */
    private fun watchExit() {
        val p = nodeProcess ?: return
        Thread {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (!portUp) {
                RuntimeDiagnostics.append(this, "process", false, "node 进程已退出", "exitCode=$code")
                val err = RuntimeDiagnostics.readNodeStderr(this)
                RuntimeDiagnostics.append(
                    this, "node-stderr", false, "node 标准错误(完整)",
                    if (err.isNotBlank()) err
                    else "(node 无 stderr 输出；用 adb logcat -s NodeRuntime:* 查看 stdout)"
                )
            }
        }.start()
    }

    private fun pollPort() {
        repeat(100) {
            if (isPortUp()) {
                portUp = true
                RuntimeDiagnostics.append(this, "port", true, "127.0.0.1:$PORT 已就绪", "Node 原生运行成功 ✓")
                return
            }
            Thread.sleep(300)
        }
        if (!portUp) {
            RuntimeDiagnostics.append(
                this, "port", false, "端口在 30s 内未就绪",
                "可能原因：node 崩溃 / 端口被占用 / 二进制不兼容当前 ROM(如非 16KB 页对齐)。\n" +
                    "查看上方 [FAIL] process 与 node-stderr，或 adb logcat -s NodeRuntime:*"
            )
        }
    }

    private fun isPortUp(): Boolean = try {
        val c = URL("http://127.0.0.1:$PORT/api/version").openConnection() as HttpURLConnection
        c.connectTimeout = 300
        c.readTimeout = 300
        c.requestMethod = "GET"
        c.responseCode == 200
    } catch (_: Throwable) {
        false
    }

    private fun err(e: Throwable): String =
        "${e::class.java.simpleName}: ${e.message}\n" +
            e.stackTraceToString().lines().take(10).joinToString("\n")

    override fun onDestroy() {
        nodeProcess?.destroy()
        nodeProcess = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NodeContainerApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "NodeRuntimeService"
        const val NOTIF_ID = 1001
        const val PORT = 3080
    }
}
