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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内核运行时前台服务（独立进程 :node）。容器（L0）把“可热更新的内核（L1）”真正拉起来的最后一环。
 *
 * 流程（对齐 container-engine/src/boot.js 与 docs/BASE_SPEC.md §9）：
 *   1. 启动 HostBridge（UDS 能力桥，本进程外独立监听）。
 *   2. 读 files/kernel/CURRENT 指针 → 若缺则落地基线内核（assets/kernel/baseline.zip）。
 *   3. 取冻结的 Node 运行时（NodeVersionManager，files/node/CURRENT）。
 *   4. 写 runtime.json（schema 2，容器写内核读）到 files/supervisor/runtime.json。
 *   5. 注入安卓环境（DSH_ANDROID=1 / DSH_SUPERVISOR_HOME / PATH / HOME / TMPDIR …）。
 *   6. spawn `node bin/dsh-supervisor daemon`（内核入口）。
 *   7. HTTP /status 健康检查；失败/进程退出 → 退避重启（START_STICKY 保活）。
 *
 * 关键点：node 是直接 exec 的应用私有二进制（bionic 链接），与 Termux 无关、不需要 root。
 *        一次包升级 = 重启 :node 进程（用户侧“热”的，无 APK 重编）。
 */
class NodeRuntimeService : Service() {

    private var nodeProcess: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var healthUp = false
    private var keepRunning = true
    private var restartCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RuntimeDiagnostics.clear(this)
        keepRunning = true
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
                while (keepRunning && nodeProcess?.isAlive == true && healthUp) {
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

    /** 单次拉起内核；成功返回 true（进程已起 + 健康），失败返回 false。 */
    private fun bootKernelOnce(): Boolean {
        try {
            RuntimeDiagnostics.append(
                this, "init", null, "NodeRuntimeService 启动内核 (进程 :node)",
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), filesDir=${filesDir.absolutePath}"
            )

            // 1) 内核版本指针
            val km = KernelManager(this)
            val version = km.ensureBaseline() ?: km.currentVersion()
            if (version.isNullOrBlank() || !km.entryPath(version).exists()) {
                RuntimeDiagnostics.append(
                    this, "kernel", false, "无可用内核",
                    "files/kernel/CURRENT 缺失且无 assets/kernel/baseline.zip，" +
                        "请确认 OTA 已下发或通过 CI 内置基线内核。"
                )
                return false
            }
            RuntimeDiagnostics.append(this, "kernel", true, "内核版本=$version", "入口=${km.entryPath(version).absolutePath}")

            // 2) 冻结的 Node 运行时
            val vm = NodeVersionManager(this)
            val manifest = vm.loadManifest()
            val nodeVersion = vm.currentVersion()
            val nodeBin = NodeProvisioner.nodeExecutable(this, nodeVersion)
            if (!nodeBin.canExecute()) {
                val bundled = manifest.versions.firstOrNull { it.version == nodeVersion }?.bundled == true
                if (bundled) {
                    NodeProvisioner.ensureBundledNode(this, nodeVersion)
                } else {
                    RuntimeDiagnostics.append(this, "provision", false, "Node 二进制缺失且非内置", nodeBin.absolutePath)
                    return false
                }
            }
            if (!nodeBin.canExecute()) {
                RuntimeDiagnostics.append(this, "provision", false, "node 仍不可执行", nodeBin.absolutePath)
                return false
            }
            RuntimeDiagnostics.append(this, "provision", true, "Node 运行时就位", nodeBin.absolutePath)

            val kernelDir = km.kernelDir(version)
            val entry = km.entryPath(version)
            val uiDir = File(kernelDir, "manager/dist").absolutePath

            // 3) 写 runtime.json（schema 2，容器写内核读）
            writeRuntimeJson(
                home = filesDir.absolutePath,
                nodePath = nodeBin.absolutePath,
                nodeBinDir = nodeBin.parentFile!!.absolutePath,
                npmPath = nodeBin.absolutePath,
                minNode = "v24.12.0"
            )
            RuntimeDiagnostics.append(this, "runtime", true, "runtime.json 已写入（schema 2）", "home=${filesDir.absolutePath}")

            // 4) 注入安卓环境并拉起内核
            val pb = ProcessBuilder(nodeBin.absolutePath, entry.absolutePath, "daemon")
                .directory(kernelDir)
            pb.environment().apply {
                put("DSH_ANDROID", "1")
                put("DSH_PLATFORM", "android")
                put("DSH_SUPERVISOR_HOME", filesDir.absolutePath)
                put("DSH_UI_DIR", uiDir)
                put("HOME", filesDir.absolutePath)
                put("TMPDIR", cacheDir.absolutePath)
                put("NODE_PATH", File(kernelDir, "node_modules").absolutePath)
                put("PATH", (nodeBin.parentFile!!.absolutePath) + File.pathSeparator + (getenv("PATH") ?: ""))
            }
            nodeProcess = pb.start()
            healthUp = false
            val pidStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nodeProcess!!.pid().toString() else "n/a"
            RuntimeDiagnostics.append(this, "exec", true, "内核进程已启动", "pid=$pidStr, 监听 127.0.0.1:$KERNEL_CONTROL_PORT")

            forward(nodeProcess!!.inputStream, "stdout")
            forward(nodeProcess!!.errorStream, "stderr")
            watchExit()
            return pollHealth()
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "fatal", false, "启动内核异常", err(e))
            Log.e(TAG, "启动内核失败", e)
            return false
        }
    }

    /** 把内核子进程的 stdout/stderr 转发：stderr 额外落 node-stderr.log。 */
    private fun forward(stream: java.io.InputStream, tag: String) {
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

    private fun watchExit() {
        val p = nodeProcess ?: return
        Thread {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (!healthUp) {
                RuntimeDiagnostics.append(this, "process", false, "内核进程退出", "exitCode=$code")
                val err = RuntimeDiagnostics.readNodeStderr(this)
                RuntimeDiagnostics.append(
                    this, "node-stderr", false, "内核标准错误(完整)",
                    if (err.isNotBlank()) err else "(无 stderr；adb logcat -s Kernel:*)"
                )
            }
        }.start()
    }

    /** 轮询内核 /status 健康检查，直到 200 或超时；成功置 healthUp。 */
    private fun pollHealth(): Boolean {
        repeat(100) {
            if (isStatusUp()) {
                healthUp = true
                RuntimeDiagnostics.append(this, "health", true, "内核健康检查通过 (127.0.0.1:$KERNEL_CONTROL_PORT/status)", "内核原生运行成功 ✓")
                return true
            }
            Thread.sleep(300)
        }
        if (!healthUp) {
            RuntimeDiagnostics.append(
                this, "health", false, "健康检查 30s 内未通过",
                "可能原因：内核崩溃 / 端口不符 / 二进制不兼容。查看上方 [FAIL] process 与 node-stderr。"
            )
        }
        return healthUp
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
        const val BACKOFF_BASE_MS = 1000L
        const val BACKOFF_MAX_MS = 30000L
    }
}
