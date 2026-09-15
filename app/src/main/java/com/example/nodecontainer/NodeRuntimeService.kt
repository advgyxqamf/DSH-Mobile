package com.example.nodecontainer

import android.app.Notification
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
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Node 运行时前台服务（独立进程 :node）。
 *
 * 职责：把内置 Node 拉起来当 HTTP 服务跑（默认 127.0.0.1:3080），
 * 并把启动过程的每一步写进诊断文件，供 UI 进程逐行展示。
 *
 * "每一步都可观测"是本服务的硬性设计目标：真机环境千差万别（SELinux 策略、
 * ROM 定制、页大小），一旦启动失败，必须能从屏幕上直接看出失败在哪一环、
 * node 自己报了什么，而不是只能翻 logcat 猜。
 *
 * Node 是直接 exec 的应用私有二进制（bionic 链接）——这是真正的"原生安卓
 * 环境"，与 Termux 无关、不需要 root。
 */
class NodeRuntimeService : Service() {

    private var nodeProcess: Process? = null
    private var portUp = false
    private val scope = CoroutineScope(Dispatchers.IO)

    /** 让 linker 找到随包的 libc++_shared.so。这个值的必要性见 runExecProbe 的说明。 */
    private val libSearchPath: String get() = applicationInfo.nativeLibraryDir

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

            // ---- 1) 确认内置 node 就位 ----
            // 这里刻意【不】用 canExecute() 判断可用性：它只查 stat 的 x 权限位，
            // 对 SELinux W^X 完全无感（假阳性）。真正确认能否执行的是第 4 步的
            // exec-probe —— 真去跑一次。详见 NodeProvisioner 顶部说明。
            val nodeBin = NodeProvisioner.bundledExecutable(this)
            val version = NodeVersionManager(this).currentVersion()
            RuntimeDiagnostics.append(
                this, "version", true, "内置 Node 版本=$version",
                "（以清单为准；实际二进制版本见下方 exec-probe 的输出）"
            )

            try {
                NodeProvisioner.ensureBundledNode(this)
                RuntimeDiagnostics.append(
                    this, "provision", true, "内置 node 就位",
                    "${nodeBin.absolutePath}\n" +
                        "大小=${nodeBin.length()} 字节, 可读=${nodeBin.canRead()}\n" +
                        "（x 权限位=${nodeBin.canExecute()} —— 仅供参考，" +
                        "能否真正 exec 由 SELinux 决定，见下方 exec-probe）"
                )
            } catch (e: Exception) {
                RuntimeDiagnostics.append(this, "provision", false, "内置 node 不可用", err(e))
                return
            }

            // ---- 2) server.js 探针就位 ----
            val script = NodeProvisioner.ensureServerScript(this)
            RuntimeDiagnostics.append(this, "script", true, "server.js 探针就位", script.absolutePath)

            // ---- 3) 两个 .so 是否都在 lib 目录 / APK 内 ----
            diagnoseNativeLibs()

            // ---- 4) exec-probe：真跑一次 node -v ----
            if (!runExecProbe(nodeBin)) return

            // ---- 5) 启动 node ----
            val pb = ProcessBuilder(
                nodeBin.absolutePath, script.absolutePath, "--port", PORT.toString()
            ).directory(filesDir)
            pb.environment().apply {
                // Node 在安卓沙箱里需要 HOME / TMPDIR，否则部分模块报错
                put("HOME", filesDir.absolutePath)
                put("TMPDIR", cacheDir.absolutePath)
                put("NODE_PATH", File(filesDir, "node_modules").absolutePath)
                // 必需项，理由见 runExecProbe。漏了 node 会在动态链接期直接失败。
                put("LD_LIBRARY_PATH", libSearchPath)
            }
            nodeProcess = pb.start()
            RuntimeDiagnostics.append(
                this, "exec", true, "node 进程已启动",
                "pid=${currentPid(nodeProcess)}, 监听 127.0.0.1:$PORT"
            )

            // ---- 6) 转发输出，7) 监听退出，8) 轮询端口 ----
            forward(nodeProcess!!.inputStream, "stdout")
            forward(nodeProcess!!.errorStream, "stderr")
            watchExit()
            pollPort()
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "fatal", false, "启动流程异常", err(e))
            Log.e(TAG, "启动 Node 失败", e)
        }
    }

    /**
     * 自检两个 .so 的存在性：既看安装解压后的 lib 目录，也看 APK 内部。
     *
     * 两者要分开看，因为排查方向完全相反：
     *   APK 里有、lib 目录没有 → 安装期没解压出来（extractNativeLibs 未生效）
     *   APK 里就没有            → 打包期就丢了（构建脚本没拷 / AGP strip 掉）
     * 合在一起看会分不清问题出在哪一环。
     */
    private fun diagnoseNativeLibs() {
        // (a) 安装解压后的 nativeLibraryDir
        try {
            val libDir = File(applicationInfo.nativeLibraryDir)
            val files = libDir.listFiles()?.sortedBy { it.name } ?: emptyList()
            val hasCxx = files.any { it.name == "libc++_shared.so" }
            RuntimeDiagnostics.append(
                this, "libdir", hasCxx,
                if (hasCxx) "libc++_shared.so 就在 lib 目录里"
                else "⚠ lib 目录里【没有】libc++_shared.so —— 动态链接必然失败",
                "nativeLibraryDir=${libDir.absolutePath}\n" +
                    "文件数=${files.size}\n" +
                    files.joinToString("\n") { "  ${it.name}  ${it.length()} 字节" }
            )
        } catch (e: Exception) {
            RuntimeDiagnostics.append(this, "libdir", false, "列举 nativeLibraryDir 失败", err(e))
        }

        // (b) APK 内部（APK 本身就是 zip）
        try {
            val apkPath = applicationInfo.sourceDir
            val entries = java.util.zip.ZipFile(apkPath).use { zf ->
                zf.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("lib/") }
                    .sorted()
                    .toList()
            }
            val inApk = entries.any { it.endsWith("libc++_shared.so") }
            RuntimeDiagnostics.append(
                this, "apk-libs", inApk,
                if (inApk) "APK 内确实打包了 libc++_shared.so"
                else "⚠ APK 内【没有】libc++_shared.so —— 问题出在打包阶段",
                "APK=$apkPath\n大小=${File(apkPath).length()} 字节\n" +
                    "lib/ 条目数=${entries.size}\n" +
                    entries.joinToString("\n") { "  $it" }
            )
        } catch (e: Exception) {
            RuntimeDiagnostics.append(this, "apk-libs", false, "读取 APK 条目失败", err(e))
        }
    }

    /**
     * 真跑一次 `node -v`，验证"这个二进制能不能被 exec"。
     *
     * 这是对可执行性的确定性验证，比 canExecute() 可靠得多 —— 它真去执行了。
     * 刻意【不用 runCatching】：它会吞掉所有 Throwable（含 InterruptedException
     * / OutOfMemoryError），把不该归为"exec 失败"的情况也引向这个错误结论。
     * 只精确捕获 IOException —— 那正是"进程无法创建"（error=13）的形态。
     *
     * ----------------------------------------------------------------------
     * LD_LIBRARY_PATH 为什么是必需的（改这里之前务必读完）
     * ----------------------------------------------------------------------
     * 现象：真机报
     *   CANNOT LINK EXECUTABLE ".../lib/arm64-v8a/libnode.so":
     *   cannot locate symbol "_ZTVNSt6__ndk119basic_ostringstream..."
     *
     * 根因：Android linker 查找依赖库的目录【只有三个】：
     *   ① $LD_LIBRARY_PATH 里的目录
     *   ② 二进制 DT_RUNPATH 动态段列出的目录
     *   ③ 系统默认路径 /system/lib64、/system/lib
     * （DT_RPATH 在 Android 上被忽略，只有 DT_RUNPATH 有效。）
     *
     * nativeLibraryDir **不在这三者中的任何一个** —— 它只在 Java 层
     * dlopen / System.loadLibrary 时才进搜索路径。而我们是 exec 一个可执行
     * 文件、由它自己拉起依赖，完全是另一套规则。libnode.so 自身既无
     * DT_RPATH 也无 DT_RUNPATH（readelf 逐个核对过动态段 29 个条目），
     * 于是它只能查系统默认路径，那里没有 libc++_shared.so（它不是 bionic
     * 的一部分），符号解析失败。
     *
     * 解法：显式设 LD_LIBRARY_PATH = nativeLibraryDir。两个 .so 都在该目录，
     * 一举解决。注意 ProcessBuilder 是直接 exec、不经过 shell，所以值就是
     * 路径原文，不涉及任何展开或引号处理。
     *
     * 为什么不改用 $ORIGIN rpath：那需要重编并改链接参数，且有资料指出它
     * 只在部分设备上有效。LD_LIBRARY_PATH 是跨设备可靠的那一个。
     *
     * @return true = 可执行；false = 已写入失败诊断，调用方应中止后续步骤
     */
    private fun runExecProbe(nodeBin: File): Boolean {
        try {
            val probe = ProcessBuilder(nodeBin.absolutePath, "-v")
                .redirectErrorStream(true)
                .apply { environment()["LD_LIBRARY_PATH"] = libSearchPath }
                .start()
            val out = probe.inputStream.bufferedReader().readText().trim()
            val exit = probe.waitFor()
            val ok = exit == 0
            RuntimeDiagnostics.append(
                this, "exec-probe", ok,
                if (ok) "node -v 执行成功（可执行性已验证）" else "node -v 退出码非 0",
                "输出: ${out.ifBlank { "(空)" }}, exitCode=$exit\n" +
                    "LD_LIBRARY_PATH=$libSearchPath"
            )
            return ok
        } catch (e: IOException) {
            // 走到这里通常是 exec 被拒。把错误码含义一次说清，省得再来回猜。
            RuntimeDiagnostics.append(
                this, "exec-probe", false, "无法执行 node 二进制",
                err(e) + "\n" +
                    "排查方向：\n" +
                    "  · error=13 Permission denied → 该路径被 SELinux 禁止 exec。\n" +
                    "    请确认执行的是 nativeLibraryDir 下的 libnode.so，而不是 files/ 里的副本。\n" +
                    "  · error=2 No such file → extractNativeLibs 未生效，.so 没被解压到 lib dir。\n" +
                    "  · error=8 Exec format error → ABI 不匹配或页对齐不满足。"
            )
            return false
        }
    }

    /**
     * 取子进程的 PID，仅用于诊断展示（拿不到返回 "n/a"，绝不影响主流程）。
     *
     * 为什么不用 Process.pid()：**Android 上根本没这个方法**。它是 Java 9
     * 加入 java.lang.Process 的，Android 的 java.lang.Process 一直没跟进
     * （android-29/30/35 的 android.jar 均无此方法），写了会在编译期报
     * "Unresolved reference 'pid'"。注意 SDK_INT 这种【运行时】判断救不了
     * 【编译期】的方法缺失。
     *
     * 也不能用 android.os.Process.myPid()：那是本应用自己的 pid，
     * 不是 node 子进程的 pid，含义完全不同，用了会误导诊断。
     *
     * 可行做法：Android 的 Process 实现把 pid 编进了 toString()，形如
     *   "Process[pid=12345, exitValue=\"not exited\"]"
     * 这里用正则提取，解析失败一律降级为 "n/a"。
     */
    private fun currentPid(p: Process?): String {
        if (p == null) return "n/a"
        return runCatching {
            Regex("""pid=(\d+)""").find(p.toString())?.groupValues?.get(1)
        }.getOrNull() ?: "n/a"
    }

    /** 转发子进程 stdout/stderr：都进 logcat，stderr 额外落盘供失败时回看。 */
    private fun forward(stream: InputStream, tag: String) {
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

    /**
     * 等待 node 进程结束；若端口始终没起来，说明启动失败，把 stderr 完整回写诊断。
     *
     * ----------------------------------------------------------------------
     * 读 stderr 前为什么必须轮询等待
     * ----------------------------------------------------------------------
     * forward() 在【另一个线程】里逐行读并写文件，而本方法在 waitFor() 返回后
     * 立刻去读同一个文件 —— 两者之间没有任何同步。当 node 死得很快时（例如
     * 参数错误导致 listen() 抛 RangeError 后瞬间退出），读取线程很可能还没被
     * 调度到，文件自然是空的，诊断就会显示"node 无 stderr 输出"，而实际上
     * node 明明打印了错误。这曾把排查引向"是不是二进制有问题"的错误方向。
     *
     * 所以这里轮询等文件出现内容（最多 1.5 秒）。正常情况下第一轮就命中。
     * 用 while 而非 repeat{}：repeat 是内联 lambda，return@repeat 只相当于
     * continue，跳不出整个循环。
     * ----------------------------------------------------------------------
     */
    private fun watchExit() {
        val p = nodeProcess ?: return
        Thread {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (portUp) return@Thread

            RuntimeDiagnostics.append(this, "process", false, "node 进程已退出", "exitCode=$code")

            var err = RuntimeDiagnostics.readNodeStderr(this)
            var waited = 0
            while (err.isBlank() && waited < 1500) {
                Thread.sleep(100)
                waited += 100
                err = RuntimeDiagnostics.readNodeStderr(this)
            }

            RuntimeDiagnostics.append(
                this, "node-stderr", err.isNotBlank(), "node 标准错误(完整)",
                if (err.isNotBlank()) err
                else "(node 确实没有 stderr 输出；已等待 ${waited}ms 让转发线程收敛。\n" +
                    " stdout 已逐行写入 logcat，可用 adb logcat -s NodeRuntime:*)"
            )
        }.start()
    }

    /** 轮询端口直到就绪（最多 30s）。就绪即认定启动成功。 */
    private fun pollPort() {
        repeat(100) {
            if (isPortUp()) {
                portUp = true
                RuntimeDiagnostics.append(
                    this, "port", true, "127.0.0.1:$PORT 已就绪", "Node 原生运行成功 ✓"
                )
                return
            }
            Thread.sleep(300)
        }
        RuntimeDiagnostics.append(
            this, "port", false, "端口在 30s 内未就绪",
            "可能原因：node 崩溃 / 端口被占用 / 二进制不兼容当前 ROM（如非 16KB 页对齐）。\n" +
                "查看上方 [FAIL] process 与 node-stderr。"
        )
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
