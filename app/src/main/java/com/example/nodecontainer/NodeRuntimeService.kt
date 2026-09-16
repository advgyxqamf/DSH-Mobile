package com.example.nodecontainer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nodecontainer.native.AssetStatus
import com.example.nodecontainer.native.NativeAssetRegistry
import com.example.nodecontainer.native.NativePreparer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内核运行时前台服务（独立进程 :node）。容器（L0）把“可热更新的内核（L1）”真正拉起来的最后一环。
 *
 * 职责：把内置 Node 拉起来跑内核（内核控制面默认 127.0.0.1:36360），
 * 并把启动过程的每一步写进诊断文件，供 UI 进程逐行展示。
 *
 * "每一步都可观测"是本服务的硬性设计目标：真机环境千差万别（SELinux 策略、
 * ROM 定制、页大小），一旦启动失败，必须能从屏幕上直接看出失败在哪一环、
 * node 自己报了什么，而不是只能翻 logcat 猜。
 *
 * Node 是直接 exec 的应用私有二进制（bionic 链接）——这是真正的"原生安卓
 * 环境"，与 Termux 无关、不需要 root。可执行性的全部约束（W^X / linker / 架构 /
 * libc 四道关）与验证手段收敛在 `native/` 包，见 [NativePreparer]。
 *
 * 流程（对齐 container-engine/src/boot.js 与 docs/BASE_SPEC.md §9）：
 *   0. 预置体检（ProvisioningProbe，PROVISIONING.md §4）—— 控制面能力可见。
 *   1. 启动 HostBridge（UDS 能力桥，独立服务）。
 *   2. 原生资产统一准备：存在性 → 依赖前置 → exec-probe（`NativePreparer.prepare`）。
 *      全过程**任一必需项失败即中止**，且给出精确到修复动作的归因。
 *   3. server.js 探针就位。
 *   4. 写 runtime.json（schema 2，容器写内核读）。
 *   5. spawn 内核进程（注入 DSH_ANDROID 环境）。
 *   6. 轮询控制面端口；失败/进程退出 → 退避重启（START_STICKY 保活）。
 *
 * 关键点：一次包升级 = 重启 :node 进程（用户侧“热”的，无 APK 重编）。
 */
class NodeRuntimeService : Service() {

    private var nodeProcess: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var portUp = false
    private var healthUp = false
    private var keepRunning = true
    private var restartCount = 0

    /**
     * 让 linker 找到随包 `.so` 的搜索路径。
     *
     * 唯一正确取值 = `nativeLibraryDir`，由 [NativePreparer.libSearchPath] 从
     * [NativeAssetRegistry] 派生 —— 不要再各写一份。完整论证（为什么这个变量必需、
     * 为什么不能省、为什么不用 `$ORIGIN` rpath）见 `NativePreparer.probe()` 的注释。
     */
    private val libSearchPath: String get() = NativePreparer.libSearchPath(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RuntimeDiagnostics.clear(this)
        keepRunning = true
        // 先把预置体检结果写进诊断（PROVISIONING §4），再拉起 HostBridge 与内核 ——
        // 这样即使内核起不来，屏幕上也能看到「设备到底具备哪些控制面能力」。
        try {
            ProvisioningProbe.run(this)
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "probe", false, "预置体检异常", "${e::class.java.simpleName}: ${e.message}")
        }
        // 先拉起 HostBridge（UDS 能力桥），再启动内核
        startHostBridge()
        scope.launch { supervisorLoop() }
        return START_STICKY
    }

    private fun startHostBridge() {
        val svc = Intent(this, HostBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
    }

    /** 监督循环：持续拉起内核，进程退出/健康失败则退避重启，避免无限紧循环。 */
    private suspend fun supervisorLoop() {
        while (keepRunning) {
            val backoff = minOf(BACKOFF_BASE_MS shl restartCount.coerceAtMost(5), BACKOFF_MAX_MS)
            val ok = bootKernelOnce()
            if (ok) {
                restartCount = 0
                // 内核在跑；等待其退出或被外部停止
                while (keepRunning && nodeProcess?.isAlive == true && (healthUp || portUp)) {
                    delay(1000)
                }
            } else {
                restartCount += 1
            }
            if (!keepRunning) break
            RuntimeDiagnostics.append(this, "supervisor", null, "退避 ${backoff}ms 后重启", "attempt=$restartCount")
            delay(backoff)
        }
    }

    /**
     * 单次拉起内核；成功返回 true（进程已起 + 控制面就绪），失败返回 false。
     *
     * 可执行性验证由 [NativePreparer.prepare] 完成 —— 它**真跑一次**进程。
     * `canExecute()` 只查 stat 权限位，对 SELinux W^X 无感（假阳性），
     * 这是上游真机排查得出的结论，务必保留。
     */
    private fun bootKernelOnce(): Boolean {
        try {
            RuntimeDiagnostics.append(
                this, "init", null, "NodeRuntimeService 启动内核 (进程 :node)",
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), filesDir=${filesDir.absolutePath}"
            )

            // ---- 0) 内核版本指针 ----
            val km = KernelManager(this)
            val kVersion = km.ensureBaseline() ?: km.currentVersion()
            val kernelDir = if (!kVersion.isNullOrBlank()) km.kernelDir(kVersion) else null
            val entry = if (!kVersion.isNullOrBlank()) km.entryPath(kVersion) else null
            val hasKernel = entry != null && entry.exists()
            // 不变式守护：内核入口是【脚本】，必须交给 node 解释执行。
            // 它落在 filesDir（app_data_file），W^X 禁止 execve —— 直接 ProcessBuilder
            // 它在真机上必然 error=13。这个断言把「注释与实现矛盾」的雷变成可执行检查。
            if (hasKernel && kVersion != null) {
                try {
                    km.assertNotDirectlyExecutable(kVersion)
                } catch (e: IllegalStateException) {
                    RuntimeDiagnostics.append(this, "kernel", false, "内核入口布局异常", err(e))
                    return false
                }
            }
            RuntimeDiagnostics.append(
                this, "kernel", hasKernel,
                if (hasKernel) "内核版本=$kVersion" else "尚无内核包（等待 OTA 下发，先跑内置探针）",
                if (hasKernel) "入口=${entry!!.absolutePath}"
                else "files/kernel/CURRENT 缺失且无 assets/kernel/baseline.zip；本次将回落到 assets/node/server.js 探针模式"
            )

            // ---- 1) 原生资产统一准备（存在性 → 依赖前置 → exec-probe） ----
            //
            // 这一步取代了历史上的三处分散逻辑：
            //   · NodeProvisioner.ensureBundledNode   （只知道 libnode.so 存在与否）
            //   · diagnoseNativeLibs()                （只打日志，从不阻断 → 缺陷 1）
            //   · runExecProbe(nodeBin)               （归因只按 errno 罗列可能 → 缺陷 2）
            //
            // 三者叠加出的真实故障：libc++_shared.so 缺失 → exec-probe 以 linker 错误失败
            // → errno=13 → 归因到「SELinux 禁止 exec」→ 真因（依赖缺失）永远浮不出来。
            // 现在依赖检查前置且独立归因，见 native/NativePreparer.kt 顶部说明。
            val version = NodeVersionManager(this).currentVersion()
            RuntimeDiagnostics.append(
                this, "version", true, "内置 Node 版本=$version",
                "（以清单为准；实际二进制版本见下方 exec-probe 的输出）"
            )

            val assets = NativePreparer.prepare(this)
            if (!assets.allRequiredReady) {
                val what = assets.failedRequired.joinToString("; ") { (e, st) ->
                    "${e.libName}（${describeStatus(st)}）"
                }
                RuntimeDiagnostics.append(
                    this, "provision", false, "原生资产校验未通过，中止启动", what
                )
                return false
            }
            val nodeAsset = NativeAssetRegistry.NODE
            val nodeBin = NativeAssetRegistry.resolve(this, nodeAsset)

            // ---- 2) server.js 探针就位 ----
            val script = NodeProvisioner.ensureServerScript(this)
            RuntimeDiagnostics.append(this, "script", true, "server.js 探针就位", script.absolutePath)

            // ---- 5) 写 runtime.json（schema 2，容器写内核读） ----
            writeRuntimeJson(
                home = filesDir.absolutePath,
                nodePath = nodeBin.absolutePath,
                nodeBinDir = nodeBin.parentFile!!.absolutePath,
                npmPath = nodeBin.absolutePath,
                minNode = "v24.12.0"
            )
            RuntimeDiagnostics.append(this, "runtime", true, "runtime.json 已写入（schema 2）", "home=${filesDir.absolutePath}")

            // ---- 6) 启动内核 ----
            // 有内核包：跑内核入口（控制面 36360）；无内核包：回落内置探针 server.js（便于首启验证 Node 原生链路）。
            //
            // ⚠️ 注意第一个参数是 nodeBin（nativeLibraryDir 下的 libnode.so，唯一可 exec 的东西），
            //    entry 是**脚本参数**、不是被 exec 的目标 —— 它落在 filesDir（app_data_file），
            //    W^X 禁止 execve。把两者顺序写反必在真机上 error=13。
            //    不变式由 km.assertNotDirectlyExecutable() 守护。
            val pb = if (hasKernel && kernelDir != null && entry != null) {
                val uiDir = File(kernelDir, "manager/dist").absolutePath
                ProcessBuilder(nodeBin.absolutePath, entry.absolutePath, "daemon")
                    .directory(kernelDir)
                    .apply {
                        environment().apply {
                            put("DSH_ANDROID", "1")
                            put("DSH_PLATFORM", "android")
                            put("DSH_SUPERVISOR_HOME", filesDir.absolutePath)
                            put("DSH_UI_DIR", uiDir)
                            put("HOME", filesDir.absolutePath)
                            put("TMPDIR", cacheDir.absolutePath)
                            put("NODE_PATH", File(kernelDir, "node_modules").absolutePath)
                            put("PATH", nodeBin.parentFile!!.absolutePath + File.pathSeparator + (getenv("PATH") ?: ""))
                            put("LD_LIBRARY_PATH", libSearchPath)
                        }
                    }
            } else {
                ProcessBuilder(nodeBin.absolutePath, script.absolutePath, "--port", PORT.toString())
                    .directory(filesDir)
            }
            pb.environment().apply {
                // Node 在安卓沙箱里需要 HOME / TMPDIR，否则部分模块报错
                put("HOME", filesDir.absolutePath)
                put("TMPDIR", cacheDir.absolutePath)
                put("NODE_PATH", File(filesDir, "node_modules").absolutePath)
                // 必需项，理由见 NativePreparer.probe() 的注释。漏了 node 会在动态链接期直接失败。
                put("LD_LIBRARY_PATH", libSearchPath)
            }
            nodeProcess = pb.start()
            portUp = false
            healthUp = false
            RuntimeDiagnostics.append(
                this, "exec", true, "内核进程已启动",
                "pid=${currentPid(nodeProcess)}, 控制面 127.0.0.1:$KERNEL_CONTROL_PORT（探针端口 $PORT）"
            )

            forward(nodeProcess!!.inputStream, "stdout")
            forward(nodeProcess!!.errorStream, "stderr")
            watchExit()
            pollControlPlane()
            return healthUp || portUp
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "fatal", false, "启动流程异常", err(e))
            Log.e(TAG, "启动 Node/内核失败", e)
            return false
        }
    }

    /**
     * 把资产失败状态压成一句可读结论（供启动中止时的诊断行）。
     *
     * 每种状态对应**互不相同**的修复动作 —— 这正是把归因结构化的意义：
     * 不再是「可能原因有 1/2/3」，而是「就是这一条」。
     */
    private fun describeStatus(st: AssetStatus): String = when (st) {
        is AssetStatus.Ready -> "就位"
        is AssetStatus.MissingFromLib ->
            if (st.inApk) "APK 内有但未解压到 nativeLibraryDir（查 extractNativeLibs / useLegacyPackaging）"
            else "APK 内就没有（打包期丢失：查构建脚本产物与 keepDebugSymbols）"
        is AssetStatus.MissingDependency ->
            "缺少依赖 ${st.dep}（linker 不查 nativeLibraryDir，须随包放同目录）"
        is AssetStatus.NotExecutable ->
            "无法 exec（依赖已确认完好 → SELinux W^X 拒 exec，查该文件是否真在 nativeLibraryDir）"
        is AssetStatus.ProbeFailed ->
            "探针失败 exit=${st.exit}，输出=${st.output.ifBlank { "(空)" }}"
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
                    Log.i("Kernel:$tag", line)
                    if (tag == "stderr") RuntimeDiagnostics.recordNodeStderr(this, line + "\n")
                    else RuntimeDiagnostics.append(this, "kernel-$tag", null, line)
                }
            }
        }.start()
    }

    /**
     * 等待 node/内核进程结束；若控制面始终没起来，说明启动失败，把 stderr 完整回写诊断。
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
            if (portUp || healthUp) return@Thread

            RuntimeDiagnostics.append(this, "process", false, "内核/node 进程已退出", "exitCode=$code")

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

    /**
     * 轮询控制面是否就绪（最多 30s）。
     *
     * 双判定：内核真跑起来时看 /status（36360）；无内核包、仅跑内置探针 server.js 时
     * 看探针端口（3080）。任一就绪即认定启动成功。
     */
    private fun pollControlPlane() {
        repeat(100) {
            if (isStatusUp()) {
                healthUp = true
                RuntimeDiagnostics.append(
                    this, "health", true,
                    "内核控制面就绪 (127.0.0.1:$KERNEL_CONTROL_PORT/status)",
                    "内核原生运行成功 ✓"
                )
                return
            }
            if (isPortUp()) {
                portUp = true
                RuntimeDiagnostics.append(
                    this, "port", true, "127.0.0.1:$PORT 已就绪（探针模式）",
                    "Node 原生运行成功 ✓（尚未下发内核包，当前为内置 server.js 探针）"
                )
                return
            }
            Thread.sleep(300)
        }
        RuntimeDiagnostics.append(
            this, "health", false, "控制面在 30s 内未就绪",
            "可能原因：node/内核崩溃 / 端口被占用 / 二进制不兼容当前 ROM（如非 16KB 页对齐）。\n" +
                "查看上方 [FAIL] process 与 node-stderr。"
        )
    }

    private fun isStatusUp(): Boolean = try {
        val c = URL("http://127.0.0.1:$KERNEL_CONTROL_PORT/status").openConnection() as HttpURLConnection
        c.connectTimeout = 300
        c.readTimeout = 300
        c.requestMethod = "GET"
        c.responseCode == 200
    } catch (_: Throwable) {
        false
    }

    /** 内置探针 server.js 的端口（首启验证 Node 原生链路用；内核就绪后走 36360）。 */
    private fun isPortUp(): Boolean = try {
        val c = URL("http://127.0.0.1:$PORT/").openConnection() as HttpURLConnection
        c.connectTimeout = 300
        c.readTimeout = 300
        c.requestMethod = "GET"
        c.responseCode in 200..499
    } catch (_: Throwable) {
        false
    }

    private fun getenv(k: String): String? = System.getenv(k)

    private fun writeRuntimeJson(home: String, nodePath: String, nodeBinDir: String, npmPath: String, minNode: String) {
        val dir = File(filesDir, "supervisor")
        dir.mkdirs()
        val obj = JSONObject().apply {
            put("schema", 2)
            put("nodePath", nodePath)
            put("nodeBinDir", nodeBinDir)
            put("npmPath", npmPath)
            put("minNode", minNode)
            put("writtenBy", "android-node-container")
        }
        File(dir, "runtime.json").writeText(obj.toString(2))
    }

    private fun err(e: Throwable): String =
        "${e::class.java.simpleName}: ${e.message}\n" +
            e.stackTraceToString().lines().take(10).joinToString("\n")

    override fun onDestroy() {
        keepRunning = false
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
        // 内核**控制面**（supervisor API）端口：与内核 src/platform/config.js 的 apiPort 默认值(36360)一致。
        // ⚠ 不是 3080 —— 3080 是内核 healthUrl（被管控的 DSH 应用端口），不是 supervisor 控制面。
        const val KERNEL_CONTROL_PORT = 36360
        /** 内置探针 server.js 端口（无内核包时的首启验证）。 */
        const val PORT = 3080
        const val BACKOFF_BASE_MS = 1000L
        const val BACKOFF_MAX_MS = 30000L
    }
}
