package com.example.nodecontainer

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * HostBridge —— 安卓能力桥（L3，随 APK 冻结）。
 *
 * 传输：Unix 域套接字（抽象命名空间，`LocalServerSocket(SOCKET_NAME)`）。内核（:node 进程）
 *       主动 connect；本服务监听。严禁 TCP 暴露控制面（见 BASE_SPEC §8）。
 * 协议：JSON-RPC 2.0，换行分隔的 JSON 帧（与 container-engine/src/bridge/uds-transport.js 对齐）。
 * 握手：内核先发 `bridge.handshake`（protocol + requires 能力分组），本服务回 `capabilities`
 *       + `groups`（设备实际已预置能力的分组交集）。
 * 鉴权：每方法声明所需能力（caps）；调用方 requires 超出设备 capabilities → 返回
 *       ERR_CAPABILITY_MISSING(-32001)；未知方法 → METHOD_NOT_FOUND(-32601)。
 * 审计：所有特权操作落 files/bridge-audit.log（持久，不随内核包切换丢失）。
 *
 * 注：抽象命名空间套接字在 Android 上等价于 `LocalServerSocket(name)`；内核侧 Node 客户端
 *     用 `net.connect('\0' + SOCKET_NAME)`（**前导 NUL 字节** = Linux 抽象命名空间；Node 22 原生支持）连接。
 *     ⚠ 不是空格前缀——那会连到文件系统里名为 " name" 的路径，永远连不通。
 */
class HostBridgeService : Service() {

    private var server: LocalServerSocket? = null
    private var running = false
    private val executor = Executors.newCachedThreadPool()
    private lateinit var dpm: DevicePolicyManager
    private lateinit var deviceAdmin: ComponentName
    private lateinit var notifManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        deviceAdmin = ComponentName(this, DeviceAdminReceiver::class.java)
        notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        startBridge()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) startBridge()
        return START_STICKY
    }

    private fun startBridge() {
        if (running) return
        running = true
        executor.execute {
            try {
                server = LocalServerSocket(SOCKET_NAME)
                RuntimeDiagnostics.append(this, "bridge", true, "HostBridge 监听 UDS", "name=$SOCKET_NAME")
                while (running) {
                    val sock = try { server?.accept() } catch (_: Throwable) { null } ?: break
                    executor.execute { handleConnection(sock) }
                }
            } catch (e: Throwable) {
                RuntimeDiagnostics.append(this, "bridge", false, "HostBridge 启服失败", "${e::class.java.simpleName}: ${e.message}")
                Log.e(TAG, "HostBridge 启动失败", e)
            }
        }
    }

    private fun handleConnection(sock: LocalSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(sock.inputStream))
            val out = sock.outputStream
            while (running) {
                val text = reader.readLine() ?: break
                if (text.isBlank()) continue
                try {
                    val msg = JSONObject(text)
                    val resp = dispatch(msg)
                    if (resp != null) writeFrame(out, resp)
                } catch (e: Throwable) {
                    Log.w(TAG, "帧解析失败: $text", e)
                }
            }
        } catch (_: Throwable) {
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun writeFrame(out: OutputStream, obj: JSONObject) {
        out.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 分发一帧；通知类（无 id）返回 null（不回包）。 */
    private fun dispatch(msg: JSONObject): JSONObject? {
        val id = msg.opt("id")
        val method = msg.optString("method", null)
        if (method == null) return null // 通知：不回包

        if (method == "bridge.handshake") {
            return handshake(id, msg.optJSONObject("params") ?: JSONObject())
        }

        val params = msg.optJSONObject("params") ?: JSONObject()
        val def = METHODS[method]
        if (def == null) {
            return error(id, CODE_METHOD_NOT_FOUND, "未知方法: $method")
        }
        if (!capsSatisfied(def.caps)) {
            return error(id, CODE_CAPABILITY_MISSING, "缺少能力: ${def.caps.joinToString()}")
        }
        try {
            val result = def.handle(params)
            if (def.audit) audit(method, params, true, null)
            return ok(id, result)
        } catch (e: BridgeError) {
            if (def.audit) audit(method, params, false, e.message)
            return error(id, e.code, e.message ?: "error")
        } catch (e: Throwable) {
            if (def.audit) audit(method, params, false, e.message)
            Log.e(TAG, "方法执行异常: $method", e)
            return error(id, CODE_INTERNAL, "${e::class.java.simpleName}: ${e.message}")
        }
    }

    private fun handshake(id: Any?, params: JSONObject): JSONObject {
        val protocol = params.optInt("protocol", 1)
        val requires = params.optJSONArray("requires")?.toList() ?: emptyList()
        val granted = requires.filter { groupCapsSatisfied(it) }
        val caps = deviceCapabilities()
        audit("bridge.handshake", params, false, null)
        return ok(id, JSONObject().apply {
            put("protocol", protocol)
            put("capabilities", JSONArray(caps))
            put("groups", JSONArray(granted))
        })
    }

    // ---- 设备能力推断 ----

    private fun deviceCapabilities(): Set<String> {
        val caps = mutableSetOf("base")
        try {
            if (dpm.isDeviceOwnerApp(packageName)) caps.add("device_owner")
        } catch (_: Throwable) {}
        // accessibility 以**服务实例已连接**为准，而非仅看 Settings 字符串：
        // 字符串可能在服务被系统回收后仍残留，会导致 ui.* 方法通过门禁却在执行时 NullPointer。
        if (DshAccessibilityService.isReady()) caps.add("accessibility")
        // mediaprojection：授权是每次会话的，以「截屏服务已连且 projection 非空」为准。
        if (ScreenCaptureService.isReady()) caps.add("mediaprojection")
        // 仍未内置：Shizuku（P4，需第三方 SDK）。
        // 置位后 bridge:shell 会自动解锁，无需改动此处以外的代码。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
            caps.add("manage_external_storage")
        if (notificationListenerEnabled()) caps.add("notification_access")
        return caps
    }

    /**
     * 无障碍服务是否已在系统设置中启用（字符串层面）。
     * ⚠ 只用于**诊断探针**展示配置状态；能力门禁请用 [DshAccessibilityService.isReady]。
     */
    private fun accessibilityEnabledInSettings(): Boolean {
        val enabled = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        } catch (_: Throwable) { "" }
        return enabled.split(":").any { it.contains("DshAccessibilityService") }
    }

    private fun notificationListenerEnabled(): Boolean {
        val enabled = try {
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        } catch (_: Throwable) { "" }
        return enabled.split(":").any { it.contains(packageName) }
    }

    /** 一个能力分组是否“满足”：分组映射到其代表能力，设备具备该能力即满足。 */
    private fun groupCapsSatisfied(group: String): Boolean {
        val rep = GROUP_REQUIRED[group] ?: return false
        return deviceCapabilities().contains(rep)
    }

    private fun capsSatisfied(required: List<String>): Boolean {
        val caps = deviceCapabilities()
        return required.all { caps.contains(it) }
    }

    // ---- 审计 ----

    private fun audit(method: String, params: JSONObject, ok: Boolean, err: String?) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val summary = params.toString().let { if (it.length > 200) it.take(200) + "…" else it }
            val line = "$ts method=$method ok=$ok params=$summary${if (err != null) " err=$err" else ""}\n"
            File(filesDir, "bridge-audit.log").appendText(line)
        } catch (_: Throwable) {}
    }

    // ---- 响应构造 ----

    private fun ok(id: Any?, result: JSONObject): JSONObject =
        JSONObject().apply { put("jsonrpc", "2.0"); put("id", id); put("result", result) }

    private fun error(id: Any?, code: Int, message: String): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", id)
            put("error", JSONObject().apply { put("code", code); put("message", message) })
        }

    // ---- 方法实现 ----

    private fun requireDpm(): DevicePolicyManager {
        if (!dpm.isDeviceOwnerApp(packageName)) throw BridgeError(CODE_CAPABILITY_MISSING, "需要 Device Owner")
        return dpm
    }

    /**
     * 取无障碍服务实例；未连接则抛 -32001。
     * 能力门禁已在 dispatch 前置（caps 含 "accessibility"），此处是**二次确认**，
     * 覆盖「门禁通过后服务恰好被系统回收」的竞态窗口。
     */
    private fun requireA11y(): DshAccessibilityService =
        DshAccessibilityService.instance
            ?: throw BridgeError(CODE_CAPABILITY_MISSING, "无障碍服务未连接（请在系统设置中开启 DSH 无障碍服务）")

    private val METHODS: Map<String, MethodDef> = mapOf(
        // 3.8 system
        "sys.info" to MethodDef(listOf("base"), false) { _ ->
            JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("androidApi", Build.VERSION.SDK_INT)
                put("platform", "android")
                put("bridge", SOCKET_NAME)
            }
        },
        "sys.setTime" to MethodDef(listOf("device_owner"), true) { p ->
            // DevicePolicyManager.setTime 为 API 28+，且**仅当系统自动对时关闭时生效**：
            // Settings.Global.AUTO_TIME == 0 才可写，否则 setTime 静默返回 false（无异常、无效果）。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                throw BridgeError(CODE_CAPABILITY_MISSING, "setTime 需 Android 9 (API 28)+，当前 API ${Build.VERSION.SDK_INT}")
            }
            val autoTime = Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME, 1)
            if (autoTime != 0) {
                throw BridgeError(
                    CODE_CAPABILITY_MISSING,
                    "系统自动对时开启（AUTO_TIME=1），setTime 无效；请先关闭自动对时"
                )
            }
            val m = requireDpm()
            val ok = m.setTime(deviceAdmin, p.optLong("time", System.currentTimeMillis()))
            if (!ok) throw BridgeError(CODE_INTERNAL, "setTime 被系统拒绝（返回 false）")
            JSONObject().apply { put("ok", true) }
        },
        "sys.setTimeZone" to MethodDef(listOf("device_owner"), true) { p ->
            // 同样 API 28+，且需 AUTO_TIME_ZONE == 0。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                throw BridgeError(CODE_CAPABILITY_MISSING, "setTimeZone 需 Android 9 (API 28)+")
            }
            val autoZone = Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME_ZONE, 1)
            if (autoZone != 0) {
                throw BridgeError(
                    CODE_CAPABILITY_MISSING,
                    "自动设置时区开启（AUTO_TIME_ZONE=1），setTimeZone 无效"
                )
            }
            val tz = p.optString("timeZone", "")
            if (tz.isEmpty()) throw BridgeError(CODE_INVALID_PARAM, "timeZone 为空（需 Olson ID，如 Asia/Shanghai）")
            val ok = requireDpm().setTimeZone(deviceAdmin, tz)
            if (!ok) throw BridgeError(CODE_INTERNAL, "setTimeZone 被系统拒绝（返回 false）")
            JSONObject().apply { put("ok", true); put("timeZone", tz) }
        },
        "sys.reboot" to MethodDef(listOf("device_owner"), true) { _ ->
            // ⚠ Android 的 DevicePolicyManager.reboot 只接受 ComponentName 一个参数
            //   （桌面 Java 的 reboot(ComponentName, String) 在 android.jar 中不存在）。
            requireDpm().reboot(deviceAdmin)
            JSONObject().apply { put("ok", true) }
        },
        // 3.7 notification
        "notif.post" to MethodDef(listOf("base"), true) { p ->
            val ch = "hostbridge_notif"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notifManager.createNotificationChannel(
                    NotificationChannel(ch, "HostBridge", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                android.app.Notification.Builder(this, ch)
            else android.app.Notification.Builder(this)
            n.setContentTitle(p.optString("title", "DSH")).setContentText(p.optString("text", ""))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
            notifManager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), n.build())
            JSONObject().apply { put("posted", true) }
        },
        "notif.read" to MethodDef(listOf("notification_access"), true) { _ ->
            // 需要通知监听权限；真实实现经 NotificationListenerService 收集。此处返回能力占位。
            JSONObject().apply { put("supported", true); put("note", "经 NotificationListenerService 收集") }
        },
        // 3.1 app_control
        "app.listInstalled" to MethodDef(listOf("base"), false) { _ ->
            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val arr = JSONArray()
            for (ai in apps) arr.put(ai.packageName)
            JSONObject().apply { put("packages", arr); put("count", arr.length()) }
        },
        "app.launch" to MethodDef(listOf("base"), false) { p ->
            val pkg = p.optString("pkg", "")
            val ai = packageManager.getLaunchIntentForPackage(pkg)
            if (ai == null) throw BridgeError(CODE_INVALID_PARAM, "无启动入口: $pkg")
            ai.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(ai)
            JSONObject().apply { put("launched", pkg) }
        },
        "app.openUrl" to MethodDef(listOf("base"), false) { p ->
            // 内核 browser.open 的承接方：安卓上「打开外部浏览器」= ACTION_VIEW Intent。
            // 无浏览器/无 Activity 可处理 → 抛 INVALID_PARAM（调用方走「无可用浏览器」分支）。
            val url = p.optString("url", "")
            if (url.isEmpty()) throw BridgeError(CODE_INVALID_PARAM, "url 为空")
            val ai = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            ai.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (ai.resolveActivity(packageManager) == null) throw BridgeError(CODE_INVALID_PARAM, "无可用浏览器: $url")
            startActivity(ai)
            JSONObject().apply { put("opened", true); put("url", url) }
        },
        "app.stop" to MethodDef(listOf("base"), false) { p ->
            val pkg = p.optString("pkg", "")
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
            JSONObject().apply { put("stopped", pkg) }
        },
        "app.install" to MethodDef(listOf("device_owner"), true) { p ->
            // ⚠ DevicePolicyManager 没有 installPackage —— 静默安装的 API 是
            //   PackageInstaller（须为 Device Owner + Manifest 声明 REQUEST_INSTALL_PACKAGES）。
            //   这里走 PackageInstaller 的 createSession/write/commit 流程。
            requireDpm() // 仅做 Device Owner 前置校验（静默安装的实际 API 走 PackageInstaller）
            val apk = p.optString("apkPath", "")
            val f = File(apk)
            if (!f.exists()) throw BridgeError(CODE_INVALID_PARAM, "APK 不存在: $apk")
            val installer = packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            // Device Owner 可申请 INSTALL_REPLACE_EXISTING 等；此处保持最小权限集。
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                f.inputStream().use { input ->
                    session.openWrite("dsh", 0, f.length()).use { input.copyTo(it) }
                }
                val intent = Intent(this, PackageInstallReceiver::class.java)
                    .putExtra(EXTRA_PKG, apk)
                val pi = android.app.PendingIntent.getBroadcast(
                    this, sessionId, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                session.commit(pi.intentSender)
            }
            JSONObject().apply {
                put("installing", apk)
                put("sessionId", sessionId)
                put("note", "已提交 PackageInstaller 会话；结果经广播回传，可轮询 app.listInstalled 确认")
            }
        },
        "app.uninstall" to MethodDef(listOf("device_owner"), true) { p ->
            // 同样：静默卸载走 PackageInstaller.uninstall（Device Owner 特权）。
            val pkg = p.optString("pkg", "")
            if (pkg.isEmpty()) throw BridgeError(CODE_INVALID_PARAM, "pkg 为空")
            val intent = Intent(this, PackageInstallReceiver::class.java)
                .setAction(PackageInstallReceiver.ACTION_UNINSTALLED)
                .putExtra(EXTRA_PKG, pkg)
            val pi = android.app.PendingIntent.getBroadcast(
                this, pkg.hashCode(), intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            packageManager.packageInstaller.uninstall(pkg, pi.intentSender)
            JSONObject().apply { put("uninstalling", pkg) }
        },
        "app.grantPermission" to MethodDef(listOf("device_owner"), true) { p ->
            val m = requireDpm()
            m.setPermissionGrantState(
                deviceAdmin, p.optString("pkg", ""),
                p.optString("perm", ""), DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            JSONObject().apply { put("granted", true) }
        },
        // 3.4 device_policy
        "policy.lockNow" to MethodDef(listOf("device_owner"), true) { _ ->
            requireDpm().lockNow()
            JSONObject().apply { put("locked", true) }
        },
        "policy.setPassword" to MethodDef(listOf("device_owner"), true) { p ->
            // ⚠ DevicePolicyManager.resetPassword(String, int) 自 **API 30 起废弃** 且仅对
            // 「已配置密码强度但尚未设密码」的设备生效；现代设备上基本无效。
            // 该能力属遗留路径，保留实现但显式提示调用方改用 user restrictions / 应用内锁。
            val m = requireDpm()
            m.setPasswordQuality(deviceAdmin, p.optInt("type", DevicePolicyManager.PASSWORD_QUALITY_NUMERIC))
            @Suppress("DEPRECATION")
            val ok = m.resetPassword(p.optString("pwd", ""), 0)
            JSONObject().apply {
                put("ok", ok)
                if (!ok) put("note", "resetPassword 已废弃(API 30)且多数设备不生效；建议改用 user restriction")
            }
        },
        "policy.wipe" to MethodDef(listOf("device_owner"), true) { p ->
            // ⚠ wipeData(int) 自 API 29 起废弃，替代品 wipeData(int, CharSequence)。
            // flags 语义未变；reason 作为审计留痕（Android 10+ 要求非空）。
            val m = requireDpm()
            val flags = p.optInt("flags", 0)
            val reason = p.optString("reason", "DSH remote wipe")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                m.wipeData(flags, reason)
            } else {
                @Suppress("DEPRECATION")
                m.wipeData(flags)
            }
            JSONObject().apply { put("ok", true) }
        },
        "policy.setKiosk" to MethodDef(listOf("device_owner"), true) { p ->
            // Device Owner 专用：把目标包加入 lock task 白名单。
            // ⚠ API 34 (UPSIDE_DOWN_CAKE) 起 lock task features 与 packages 捆绑为同一策略，
            //   必须显式调用 setLockTaskFeatures，否则部分机型上报 SecurityException。
            val m = requireDpm()
            val pkgs = p.optJSONArray("packages")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
            } ?: listOf(p.optString("pkg", packageName))
            val target = if (pkgs.isEmpty()) listOf(packageName) else pkgs
            m.setLockTaskPackages(deviceAdmin, target.toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // 若调用方要求进入 kiosk 模式，一并开启 features（含 LOCK_TASK_FEATURE_NONE=0 表示仅白名单）
                val features = p.optInt("features", DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                m.setLockTaskFeatures(deviceAdmin, features)
            }
            JSONObject().apply {
                put("kiosk", JSONArray(target))
                put("note", "已加入 lock task 白名单；调用方需再 startLockTask() 进入锁定态")
            }
        },
        "policy.addUserRestriction" to MethodDef(listOf("device_owner"), true) { p ->
            val key = p.optString("key", "")
            val r = try { getUserRestriction(key) } catch (_: Throwable) { null }
            if (r != null) requireDpm().addUserRestriction(deviceAdmin, r)
            JSONObject().apply { put("ok", r != null) }
        },
        // 3.2 ui_automation —— 真实实现（DshAccessibilityService，P2 落地）。
        // 服务未连接时 requireA11y() 抛 -32001；方法级 caps 已由 dispatch 前置门禁。
        "ui.tap" to MethodDef(listOf("accessibility"), true) { p ->
            val a11y = requireA11y()
            val ok = a11y.performTap(
                p.optDouble("x", 0.0).toFloat(),
                p.optDouble("y", 0.0).toFloat(),
                p.optLong("durationMs", 60L)
            )
            JSONObject().apply { put("ok", ok) }
        },
        "ui.swipe" to MethodDef(listOf("accessibility"), true) { p ->
            val a11y = requireA11y()
            val ok = a11y.performSwipe(
                p.optDouble("x1", 0.0).toFloat(), p.optDouble("y1", 0.0).toFloat(),
                p.optDouble("x2", 0.0).toFloat(), p.optDouble("y2", 0.0).toFloat(),
                p.optLong("durationMs", 300L)
            )
            JSONObject().apply { put("ok", ok) }
        },
        "ui.inputText" to MethodDef(listOf("accessibility"), true) { p ->
            val a11y = requireA11y()
            val text = p.optString("text", "")
            if (p.has("selector") && p.optJSONObject("selector")?.length() == 0) {
                throw BridgeError(CODE_INVALID_PARAM, "selector 不能为空对象")
            }
            val ok = a11y.inputText(text, p.optJSONObject("selector"))
            JSONObject().apply { put("ok", ok) }
        },
        "ui.getUiTree" to MethodDef(listOf("accessibility"), false) { p ->
            val a11y = requireA11y()
            // 读屏属 BRIDGE_PROTOCOL §5 审计项，但 getUiTree 是只读观测；此处保留 audit=true
            // （与 methods.js 对齐：getUiTree 未标 audit，故此处也不落审计）
            a11y.dumpUiTree(
                maxNodes = p.optInt("maxNodes", 3000),
                maxDepth = p.optInt("maxDepth", 40)
            )
        },
        "ui.screenshot" to MethodDef(listOf("mediaprojection"), true) { p ->
            // P5：真实实现。授权是**每次会话**的（用户须点系统弹窗），故首次调用前
            // 必须先经 MainActivity 授权；授权结果缓存在 files/screen-capture-grant.json。
            val svc = ScreenCaptureService.instance
                ?: throw BridgeError(
                    CODE_CAPABILITY_MISSING,
                    "截屏服务未启动。需先在 App 内完成一次截屏授权（系统弹窗），此后可后台复用"
                )
            val metrics = resources.displayMetrics
            val w = p.optInt("width", metrics.widthPixels)
            val h = p.optInt("height", metrics.heightPixels)
            val bmp = svc.capture(w, h, metrics.densityDpi)
                ?: throw BridgeError(CODE_INTERNAL, "截屏失败（8s 内未取到帧；可能屏幕处于锁屏/息屏）")

            val dir = File(filesDir, "screenshots").apply { mkdirs() }
            val file = File(dir, "shot-${System.currentTimeMillis()}.png")
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val outW = bmp.width
            val outH = bmp.height
            bmp.recycle()

            if (p.optBoolean("inline", false)) {
                // 内联 base64：方便小图/低分辨率直取，但大图会显著撑大 JSON-RPC 帧。
                val bytes = file.readBytes()
                JSONObject().apply {
                    put("encoding", "base64")
                    put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    put("width", outW); put("height", outH); put("bytes", bytes.size)
                    put("path", file.absolutePath)
                }
            } else {
                JSONObject().apply {
                    put("path", file.absolutePath)
                    put("width", outW)
                    put("height", outH)
                    put("bytes", file.length())
                }
            }
        },
        "ui.waitFor" to MethodDef(listOf("accessibility"), false) { p ->
            val a11y = requireA11y()
            val selector = p.optJSONObject("selector")
                ?: throw BridgeError(CODE_INVALID_PARAM, "waitFor 需要 selector")
            a11y.waitForNode(
                selector,
                timeoutMs = p.optLong("timeoutMs", 10_000L),
                intervalMs = p.optLong("intervalMs", 250L)
            )
        },
        // 3.3 shell / 3.6 build：仍为能力门禁占位（P3/P4 待落地）。
        // 能力缺失时返回 -32001（与 spec 降级语义一致）。
        "shell.exec" to MethodDef(listOf("shizuku"), true) { p ->
            // 「兜底」实现：无 Shizuku 时，本应用 uid 仍能跑一部分命令（getprop / pm list /
            // am 查询等只读或本应用权限内的操作）。明确不冒充 shell uid(2000)。
            // 设计取舍见类注释「shell.exec 的兜底语义」。
            val cmd = p.optString("cmd", "")
            if (cmd.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "cmd 为空")
            val uid = android.os.Process.myUid()
            if (uid == 2000 || uid == 0) {
                throw BridgeError(CODE_CAPABILITY_MISSING, "意外的 uid=$uid（不该出现在应用进程中）")
            }
            val arr = p.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: emptyList()
            val timeoutMs = p.optLong("timeoutMs", 10_000L).coerceIn(1L, 60_000L)
            try {
                val proc = ProcessBuilder(listOf(cmd) + arr)
                    .redirectErrorStream(true)
                    .start()
                val out = StringBuilder()
                val reader = proc.inputStream.bufferedReader()
                // 读线程与 waitFor 并行，避免管道写满导致子进程阻塞（经典死锁）。
                val pump = Thread {
                    reader.forEachLine { line ->
                        if (out.length < MAX_SHELL_OUTPUT) out.append(line).append('\n')
                    }
                }
                pump.start()
                val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    throw BridgeError(CODE_TIMEOUT, "命令超时 ${timeoutMs}ms: $cmd")
                }
                pump.join(1000)
                val text = out.toString()
                JSONObject().apply {
                    put("ok", proc.exitValue() == 0)
                    put("exitCode", proc.exitValue())
                    put("stdout", if (text.length > MAX_SHELL_OUTPUT) text.take(MAX_SHELL_OUTPUT) + "\n…(截断)" else text)
                    put("uid", uid)
                    put("privileged", false)
                    put("note", "以应用 uid($uid) 执行，非 shell uid(2000)。需特权请接入 Shizuku（P4）。")
                }
            } catch (e: java.io.IOException) {
                throw BridgeError(
                    CODE_INVALID_PARAM,
                    "无法执行 $cmd：${e.message}。注意：PATH 受限，且多数系统命令需 shell uid（Shizuku）。"
                )
            }
        },
        // ---- 3.5 storage：fs.* 真实实现（P5，2026-09）----
        // 访问范围：全放开（有 MANAGE_EXTERNAL_STORAGE 即通行），不做白名单限制。
        // 但保留**审计留痕**与**危险路径提示**（不拦截）——见 auditPathHint()。
        "fs.read" to MethodDef(listOf("manage_external_storage"), false) { p ->
            val f = requireReadableFile(p.optString("path", ""))
            val maxBytes = p.optLong("maxBytes", DEFAULT_FS_MAX_BYTES).coerceIn(1L, MAX_FS_BYTES)
            if (f.length() > maxBytes) {
                throw BridgeError(
                    CODE_INVALID_PARAM,
                    "文件 ${f.length()} 字节超过上限 $maxBytes；用 maxBytes 显式放大（硬顶 ${MAX_FS_BYTES}）"
                )
            }
            val bytes = f.readBytes()
            val encoding = p.optString("encoding", "auto")
            if (encoding == "base64") {
                JSONObject().apply {
                    put("path", f.absolutePath)
                    put("bytes", bytes.size)
                    put("encoding", "base64")
                    put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                }
            } else {
                // auto：能按 UTF-8 无损还原就当文本，否则退 base64（避免二进制被静默损坏）。
                val text = String(bytes, Charsets.UTF_8)
                val lossless = text.toByteArray(Charsets.UTF_8).contentEquals(bytes)
                JSONObject().apply {
                    put("path", f.absolutePath)
                    put("bytes", bytes.size)
                    if (lossless) {
                        put("encoding", "utf8")
                        put("content", text)
                    } else {
                        put("encoding", "base64")
                        put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                        put("note", "内容非合法 UTF-8，已自动以 base64 返回（避免损坏二进制）")
                    }
                }
            }
        },
        "fs.write" to MethodDef(listOf("manage_external_storage"), true) { p ->
            val f = requireWritableFile(p.optString("path", ""))
            val encoding = p.optString("encoding", "utf8")
            val content = p.optString("content", "")
            val bytes = if (encoding == "base64") {
                android.util.Base64.decode(content, android.util.Base64.DEFAULT)
            } else {
                content.toByteArray(Charsets.UTF_8)
            }
            val append = p.optBoolean("append", false)
            f.parentFile?.mkdirs()
            if (append) f.appendBytes(bytes) else f.writeBytes(bytes)
            JSONObject().apply {
                put("path", f.absolutePath)
                put("bytes", bytes.size)
                put("appended", append)
                auditPathHint(f.absolutePath)?.let { put("hint", it) }
            }
        },
        "fs.list" to MethodDef(listOf("manage_external_storage"), false) { p ->
            val path = p.optString("path", "")
            val f = when {
                path.isBlank() -> File(Environment.getExternalStorageDirectory().absolutePath)
                else -> File(path)
            }
            if (!f.exists()) throw BridgeError(CODE_INVALID_PARAM, "路径不存在: ${f.absolutePath}")
            val recursive = p.optBoolean("recursive", false)
            val maxEntries = p.optInt("maxEntries", 1000).coerceIn(1, 10000)
            val arr = JSONArray()
            var truncated = false
            if (f.isDirectory) {
                if (recursive) f.walkTopDown().forEach { c ->
                    if (arr.length() >= maxEntries) { truncated = true; return@forEach }
                    if (c.absolutePath != f.absolutePath) arr.put(fileToJson(c))
                } else {
                    val kids = f.listFiles() ?: emptyArray()
                    for (c in kids) {
                        if (arr.length() >= maxEntries) { truncated = true; break }
                        arr.put(fileToJson(c))
                    }
                }
            }
            JSONObject().apply {
                put("path", f.absolutePath)
                put("isDirectory", f.isDirectory)
                put("entries", arr)
                put("count", arr.length())
                put("truncated", truncated)
            }
        },
        "fs.mkdir" to MethodDef(listOf("manage_external_storage"), true) { p ->
            val f = requireWritableFile(p.optString("path", ""))
            val ok = if (f.exists()) f.isDirectory else f.mkdirs()
            if (!ok) throw BridgeError(CODE_INTERNAL, "创建目录失败: ${f.absolutePath}")
            JSONObject().apply {
                put("path", f.absolutePath)
                put("existed", f.exists())
            }
        },
        "build.apk" to MethodDef(listOf("build_chain"), true) { _ -> throw BridgeError(CODE_CAPABILITY_MISSING, "构建链未内置") },
        "build.status" to MethodDef(listOf("build_chain"), true) { _ -> throw BridgeError(CODE_CAPABILITY_MISSING, "构建链未内置") }
    )

    // ---- fs.* 辅助 ----

    private fun fileToJson(f: File): JSONObject = JSONObject().apply {
        put("name", f.name)
        put("path", f.absolutePath)
        put("directory", f.isDirectory)
        put("size", if (f.isDirectory) 0L else f.length())
        put("modified", f.lastModified())
        put("readable", f.canRead())
        put("writable", f.canWrite())
    }

    private fun requireReadableFile(path: String): File {
        if (path.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "path 为空")
        val f = File(path)
        if (!f.exists()) throw BridgeError(CODE_INVALID_PARAM, "文件不存在: $path")
        if (f.isDirectory) throw BridgeError(CODE_INVALID_PARAM, "是目录而非文件: $path")
        if (!f.canRead()) throw BridgeError(CODE_INVALID_PARAM, "无读权限: $path")
        return f
    }

    private fun requireWritableFile(path: String): File {
        if (path.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "path 为空")
        val f = File(path)
        if (f.exists() && f.isDirectory) throw BridgeError(CODE_INVALID_PARAM, "是目录而非文件: $path")
        val parent = f.parentFile
        if (parent != null && !parent.exists()) {
            // 允许自动建父目录（fs.write/fs.mkdir 的常见用法），但父目录必须可创建。
            if (!parent.mkdirs() && !parent.exists()) {
                throw BridgeError(CODE_INVALID_PARAM, "无法创建父目录: ${parent.absolutePath}")
            }
        }
        if (parent != null && !parent.canWrite()) {
            throw BridgeError(CODE_INVALID_PARAM, "父目录不可写: ${parent.absolutePath}")
        }
        return f
    }

    /**
     * 危险路径提示（**不拦截**，仅回传给调用方 + 审计）。
     *
     * 用户明确选择了「全放开」策略，故不设白名单。但这些路径写入的后果不可逆
     * （设备变砖 / 内核崩溃 / 系统不可启动），调用方至少应当在日志里看见风险。
     */
    private fun auditPathHint(path: String): String? {
        val p = path.trim()
        return when {
            p.startsWith("/dev/") || p == "/dev" ->
                "⚠ 写入 /dev 下的块设备/字符设备可能立即损坏设备数据"
            p.startsWith("/proc/") || p.startsWith("/sys/") ->
                "⚠ /proc 与 /sys 是内核接口，写入可能使系统立即不稳定或崩溃"
            p.startsWith("/system") || p.startsWith("/vendor") || p.startsWith("/boot") ->
                "⚠ 系统分区受 verified boot 保护，写入通常失败；强行修改可能导致设备无法启动"
            p == "/" -> "⚠ 根目录写入：请确认目标路径"
            else -> null
        }
    }

    private fun getUserRestriction(key: String): String? = when (key) {
        "no_install_apps" -> android.os.UserManager.DISALLOW_INSTALL_APPS
        "no_uninstall_apps" -> android.os.UserManager.DISALLOW_UNINSTALL_APPS
        "no_debugging_features" -> android.os.UserManager.DISALLOW_DEBUGGING_FEATURES
        "no_sms" -> android.os.UserManager.DISALLOW_SMS
        else -> null
    }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        const val TAG = "HostBridgeService"
        const val SOCKET_NAME = "dsh_hostbridge"
        const val CODE_CAPABILITY_MISSING = -32001
        const val CODE_TIMEOUT = -32002
        const val CODE_INVALID_PARAM = -32602
        const val CODE_METHOD_NOT_FOUND = -32601
        const val CODE_INTERNAL = -32603

        /** PackageInstaller 回传广播里携带的目标（apk 路径或包名），见 PackageInstallReceiver。 */
        const val EXTRA_PKG = "dsh_target"

        /** shell.exec 单次输出上限（防止超大输出撑爆 JSON-RPC 帧）。 */
        const val MAX_SHELL_OUTPUT = 256 * 1024

        /** fs.read 默认读取上限（64MB 硬顶）—— 与 JSON-RPC 帧模型匹配。 */
        const val DEFAULT_FS_MAX_BYTES = 8L * 1024 * 1024
        const val MAX_FS_BYTES = 64L * 1024 * 1024

        // 能力分组 -> 代表能力（用于握手时的 groups 交集）
        val GROUP_REQUIRED = mapOf(
            "bridge:app_control" to "base",
            "bridge:notification" to "base",
            "bridge:system" to "base",
            "bridge:device_policy" to "device_owner",
            "bridge:ui_automation" to "accessibility",
            "bridge:shell" to "shizuku",
            "bridge:storage" to "manage_external_storage",
            "bridge:build" to "build_chain"
        )
    }
}

data class MethodDef(
    val caps: List<String>,
    val audit: Boolean,
    val handle: (JSONObject) -> JSONObject
)

class BridgeError(val code: Int, message: String) : Exception(message)

private fun JSONArray.toList(): List<String> {
    val out = mutableListOf<String>()
    for (i in 0 until length()) out.add(getString(i))
    return out
}
