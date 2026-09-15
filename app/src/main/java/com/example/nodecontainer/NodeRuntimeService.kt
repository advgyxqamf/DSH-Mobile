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

            // 4.4) 列出 nativeLibraryDir 的实际内容。
            //
            //      为什么要专门列这个：真机报的是
            //          cannot locate symbol "_ZTVNSt6__ndk119basic_ostringstream..."
            //      这是【动态链接期】找不到提供该符号的 libc++_shared.so 导致的。
            //      而 libnode.so 既不依赖系统里的任何 C++ 库、自己也不导出这些符号，
            //      它唯一来源就是同目录的 libc++_shared.so。
            //
            //      所以「这个目录里到底有没有 libc++_shared.so」是所有猜测的分水岭：
            //        · 有 → 那就是 linker 搜索路径问题（需要 RUNPATH 或改加载方式）
            //        · 没有 → 是打包问题（APK 里就没带上它）
            //      一张截图即可定论，不必再来回猜。
            try {
                val libDir = File(applicationInfo.nativeLibraryDir)
                val files = libDir.listFiles()?.sortedBy { it.name } ?: emptyList()
                val listing = if (files.isEmpty()) {
                    "(目录为空或无法读取)"
                } else {
                    files.joinToString("\n") { f ->
                        "  ${f.name}  ${f.length()} 字节"
                    }
                }
                val hasCxx = files.any { it.name == "libc++_shared.so" }
                RuntimeDiagnostics.append(
                    this, "libdir", hasCxx,
                    if (hasCxx) "libc++_shared.so 就在 lib 目录里"
                    else "⚠ lib 目录里【没有】libc++_shared.so —— 这就是 cannot locate symbol 的直接原因",
                    "nativeLibraryDir=${libDir.absolutePath}\n" +
                        "文件数=${files.size}\n$listing"
                )
            } catch (e: Exception) {
                RuntimeDiagnostics.append(this, "libdir", false, "列举 nativeLibraryDir 失败", err(e))
            }

            // 4.45) 再看一眼【APK 内部】有没有这个文件。
            //
            //       4.4 只看了解压后的目录。但要定位问题究竟出在哪一环，必须把
            //       「打包」与「安装解压」分开看：
            //         · APK 里有、lib 目录里没有 → 是安装期没解压出来
            //           （多半是 extractNativeLibs 或 useLegacyPackaging 没生效）
            //         · APK 里就没有            → 是打包期就丢了
            //           （构建脚本没拷 / AGP 把文件 strip 掉或丢了）
            //       两者排查方向完全相反，所以必须分开确认。
            //
            //       读法：APK 本身就是个 zip，直接列 applicationInfo.sourceDir 的条目。
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
                val apkSize = File(apkPath).length()
                RuntimeDiagnostics.append(
                    this, "apk-libs", inApk,
                    if (inApk) "APK 内确实打包了 libc++_shared.so"
                    else "⚠ APK 内【没有】libc++_shared.so —— 问题出在打包阶段，不是安装解压",
                    "APK=$apkPath\n大小=$apkSize 字节\n" +
                        "lib/ 条目数=${entries.size}\n" +
                        entries.joinToString("\n") { "  $it" }
                )
            } catch (e: Exception) {
                RuntimeDiagnostics.append(this, "apk-libs", false, "读取 APK 条目失败", err(e))
            }

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
            //
            // -----------------------------------------------------------------
            // LD_LIBRARY_PATH 是这一轮修复的关键，别删。
            //
            // 真机报：
            //   CANNOT LINK EXECUTABLE ".../lib/arm64-v8a/libnode.so":
            //   cannot locate symbol "_ZTVNSt6__ndk119basic_ostringstream..."
            //
            // readelf 的结论：libnode.so 的 DT_NEEDED 里有 libc++_shared.so，
            // 而它自身既没有 DT_RPATH 也没有 DT_RUNPATH（动态段 29 个条目里
            // 只有 NEEDED/FLAGS/RELA/... 没有路径项）。
            //
            // 这意味着：这个子进程被 exec 起来后，Android 的 linker 在解析
            // libnode.so 的 NEEDED 时，【不会】自动去 nativeLibraryDir 找 ——
            // 那个目录只在 Java 层 dlopen / System.loadLibrary 时才进搜索路径，
            // 对「exec 一个可执行文件、由它自己拉起依赖」是完全另一套规则。
            // 于是它只能查系统默认路径（/system/lib64 等），那里没有
            // libc++_shared.so（它不是 bionic 的一部分），符号解析失败。
            //
            // 解法就是在进程环境里显式告诉 linker 去哪找：
            //   LD_LIBRARY_PATH = nativeLibraryDir
            // 两个 .so 都在这个目录里（libnode.so 自己也在），一举解决问题。
            //
            // 注意：ProcessBuilder 是直接 exec，不经过 shell，所以环境变量的
            // 值就是路径原文，不涉及任何 shell 展开或引号处理。
            // -----------------------------------------------------------------
            val libSearchPath = applicationInfo.nativeLibraryDir

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
                // 与上面的探针保持一致：必须让 linker 知道去哪找 libc++_shared.so。
                // 漏了这一行，node 会在动态链接期直接失败（cannot locate symbol）。
                put("LD_LIBRARY_PATH", libSearchPath)
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
