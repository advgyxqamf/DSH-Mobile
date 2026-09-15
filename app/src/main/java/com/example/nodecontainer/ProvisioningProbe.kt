package com.example.nodecontainer

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 预置自检探针（PROVISIONING.md §4）—— 「这台设备现在到底能干什么」的**唯一事实来源**。
 *
 * 为什么需要它：控制面能力（Device Owner / 无障碍 / Shizuku / MediaProjection / 特殊权限）
 * 是**焊死在设备 + APK 里**的，无法经内核包热更新获得。设备换机、恢复出厂、`dpm remove-active-admin`
 * 之后，能力会**静默消失**——内核侧只会看到桥握手少了几组，却不知道是「没预置」还是「预置坏了」。
 * 探针把这件事变成开机可见的体检报告，落 files/diagnostics.txt，由 MainActivity 轮询渲染。
 *
 * 复用 [RuntimeDiagnostics]（文件型跨进程），因此探针可在 :node 进程跑、UI 进程读。
 */
object ProvisioningProbe {

    private const val TAG = "ProvisioningProbe"

    /** 检查项 id —— 与 PROVISIONING.md §4 逐条对齐。 */
    const val DEVICE_OWNER = "device-owner"
    const val ACCESSIBILITY = "accessibility"
    const val SHIZUKU = "shizuku"
    const val MEDIAPROJECTION = "mediaprojection"
    const val SPECIAL_PERMS = "special-perms"

    /** 与 [ScreenCaptureService.GRANT_FILE] 保持一致（探针提示文案里引用）。 */
    private const val GRANT_FILE = ScreenCaptureService.GRANT_FILE

    /**
     * 跑全量体检并把结果写入诊断日志。
     * @return 通过项数 / 总项数
     */
    fun run(ctx: Context): Pair<Int, Int> {
        val results = listOf(
            checkDeviceOwner(ctx),
            checkAccessibility(ctx),
            checkShizuku(ctx),
            checkMediaProjection(ctx),
            checkSpecialPerms(ctx)
        )
        for (r in results) {
            RuntimeDiagnostics.append(
                ctx,
                "probe:${r.id}",
                r.ok,
                "${r.label} —— ${r.status}",
                r.hint
            )
        }
        val passed = results.count { it.ok }
        RuntimeDiagnostics.append(
            ctx,
            "probe",
            passed == results.size,
            "预置体检：$passed/${results.size} 项通过",
            if (passed == results.size) "全部控制面能力就绪"
            else "缺失项对应的 bridge 方法组会返回 -32001（这是预期降级，不是崩溃）"
        )
        // 附一份机器可读快照，便于内核对账 / 脚本解析。
        writeSnapshot(ctx, results)
        return passed to results.size
    }

    // ---- 各项检查 ----

    private fun checkDeviceOwner(ctx: Context): ProbeResult {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return ProbeResult(DEVICE_OWNER, "Device Owner (DPC)", false, "DevicePolicyManager 不可用", "")

        val owner = try { dpm.isDeviceOwnerApp(ctx.packageName) } catch (_: Throwable) { false }
        if (!owner) {
            return ProbeResult(
                DEVICE_OWNER, "Device Owner (DPC)", false, "未激活",
                "预置命令：adb shell dpm set-device-owner " +
                    "${ctx.packageName}/${DeviceAdminReceiver::class.java.name}\n" +
                    "⚠ 需设备未添加任何账号且未设置锁屏密码；激活后影响 bridge:device_policy 整组及 app.install/uninstall"
            )
        }

        // 进一步摸清真正可用的策略面：device_admin.xml 声明的 uses-policies 与实际授予是否一致。
        val active = try {
            dpm.getActiveAdmins()?.count { it.packageName == ctx.packageName } ?: 0
        } catch (_: Throwable) { 0 }
        return ProbeResult(
            DEVICE_OWNER, "Device Owner (DPC)", true, "已激活",
            "活动管理员数=$active；device_admin.xml 已声明 9 条 uses-policies"
        )
    }

    private fun checkAccessibility(ctx: Context): ProbeResult {
        // 三层判定：① 系统设置里是否勾选（配置层）② 服务实例是否真连上（运行层）③ 能力令牌
        val inSettings = try {
            val csv = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            csv.split(":").any { it.contains("DshAccessibilityService") }
        } catch (_: Throwable) { false }

        val bound = DshAccessibilityService.isReady()

        return when {
            bound -> ProbeResult(
                ACCESSIBILITY, "AccessibilityService", true, "已连接（ui_automation 可用）",
                "手势(dispatchGesture) / 节点树(getWindows+rootInActiveWindow) / 文本(ACTION_SET_TEXT) 全部就绪"
            )
            inSettings -> ProbeResult(
                ACCESSIBILITY, "AccessibilityService", false, "设置已勾选但服务未连接",
                "系统可能刚回收过服务（常见于低内存/省电模式）。打开本 App 或重启设备会重新绑定；" +
                    "若持续如此，检查 accessibility_service_config.xml 是否被 ROM 拒绝"
            )
            else -> ProbeResult(
                ACCESSIBILITY, "AccessibilityService", false, "未启用",
                "提前台：设置 → 无障碍 → 已下载的服务 → DSH 容器 → 开启\n" +
                    "或 adb shell settings put secure enabled_accessibility_services " +
                    "${ctx.packageName}/${DshAccessibilityService::class.java.name}\n" +
                    "开启后 bridge:ui_automation 整组解锁（Agent 操作手机的核心通道）"
            )
        }
    }

    /**
     * Shizuku 三态探测：**未安装 / 已安装未授权 / 已授权可用**。
     *
     * 为什么必须细分：这三种状态对内核的**处置方式完全不同**——
     *   · 未安装   → 引导用户去装（或改用无线调试路径）；
     *   · 未授权   → 只需在 Shizuku App 里点一次授权，成本极低，值得重试；
     *   · 已授权   → shell 能力理论上可用（但容器侧尚未接入 SDK，见下）。
     * 只报「不可用」会让内核既不知道要不要重试，也不知道该提示用户做什么。
     *
     * ⚠ 当前容器**未内置 Shizuku SDK**（P4 决策：先做 shell 兜底 + 探测增强，不引入
     *   第三方 AAR 以免污染冻结容器的信任边界）。因此即使 Shizuku 完全就绪，
     *   `shell.exec` 仍以**应用 uid** 执行（privileged=false），不会冒充 shell uid(2000)。
     *   真正的特权 shell 需要 Shizuku SDK 的 `Shell.newProcess(...)` 通道。
     */
    private fun checkShizuku(ctx: Context): ProbeResult {
        // ① 是否安装（包存在性）
        val installedVersion = try {
            @Suppress("DEPRECATION")
            val pi = ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            pi.versionName ?: "unknown"
        } catch (_: Throwable) { null }

        // ② 守护进程是否在跑（binder 服务名固定为 "shizuku"）
        val binderAlive = try {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "shizuku") != null
        } catch (_: Throwable) { false }

        // ③ 是否已授权本应用。
        //    Shizuku 的授权记录存在 Settings.Secure 的 "shizuku_authorized_packages" 之类字段上，
        //    不同版本键名不一致；稳妥做法是「binder 在跑 + 本包已安装」即视为可尝试授权，
        //    这里额外读一次常见键做增强判断，读不到不影响主判定。
        val authedPkgs = try {
            Settings.Secure.getString(ctx.contentResolver, "shizuku_authorized_packages") ?: ""
        } catch (_: Throwable) { "" }
        val selfAuthorized = authedPkgs.contains(ctx.packageName)

        val ok = binderAlive
        val status = when {
            binderAlive && selfAuthorized -> "已授权且守护进程在跑"
            binderAlive -> "守护进程在跑（本应用可能尚未授权）"
            installedVersion != null -> "已安装 v$installedVersion 但守护进程未启动"
            else -> "未安装"
        }

        val hint = when {
            !ok && installedVersion == null ->
                "两条路二选一：\n" +
                    "  A) 安装 Shizuku（moe.shizuku.privileged.api）并启动，然后在其中授权本应用；\n" +
                    "  B) 用无线调试：开发者选项 → 无线调试 → 配对（Android 11+，adb 一次即可）。\n" +
                    "⚠ 容器尚未内置 Shizuku SDK（P4 决策），当前 shell.exec 以**应用 uid** 执行兜底，" +
                    "能跑 getprop / pm list 等只读命令，但不具备 shell uid(2000) 特权。"
            !ok ->
                "Shizuku 已安装但守护进程没起来。打开 Shizuku App 点一次「启动」（Android 11+ 需先经无线调试配对）。\n" +
                    "启动后 bridge 握手会多出 shizuku 能力标记。"
            !selfAuthorized ->
                "守护进程在跑，但本应用可能还没在 Shizuku 里授权。打开 Shizuku → 已授权应用 → 添加本应用。\n" +
                    "⚠ 容器尚未内置 Shizuku SDK（P4 决策），shell.exec 仍走应用 uid 兜底。"
            else ->
                "Shizuku 完全就绪。⚠ 但容器尚未内置 Shizuku SDK（P4 决策），" +
                    "shell.exec 目前仍以应用 uid 执行（privileged=false）。接入 SDK 后可解锁 shell uid(2000)。"
        }

        return ProbeResult(SHIZUKU, "Shizuku / 无线调试", ok, status, hint)
    }

    private fun checkMediaProjection(ctx: Context): ProbeResult {
        // MediaProjection 的授权是**运行时、每次会话**的（弹窗授权 + 前台服务类型），
        // 无法像 DO 那样一次性预置。此处只校验「平台版本 + 前台服务类型声明」这两个硬前提。
        val apiOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        val fgsDeclared = try {
            // Android 14+ 截屏必须声明 FOREGROUND_SERVICE_MEDIA_PROJECTION
            val pi = ctx.packageManager.getPackageInfo(
                ctx.packageName,
                PackageManager.GET_PERMISSIONS
            )
            pi.requestedPermissions?.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION") == true
        } catch (_: Throwable) { false }

        // P5 已落地：ScreenCaptureService 真实存在，判定维度从「声明」升级为「授权是否已生效」。
        val granted = ScreenCaptureService.isReady()
        val hasCachedGrant = ScreenCaptureService.loadGrant(ctx) != null

        val status = when {
            !apiOk -> "平台不支持（需 API 21+）"
            granted -> "已授权，截屏可用"
            hasCachedGrant -> "有缓存授权，服务未启动（点「授权屏幕捕获」或重启 App）"
            fgsDeclared -> "未授权（点诊断面板的「授权屏幕捕获」按钮）"
            else -> "⚠ 未声明 FOREGROUND_SERVICE_MEDIA_PROJECTION"
        }

        return ProbeResult(
            MEDIAPROJECTION, "MediaProjection（截屏）",
            ok = apiOk && fgsDeclared,
            status = status,
            hint = if (granted) {
                "ui.screenshot 可用。默认返回 PNG 落盘路径，传 inline=true 可内联 base64。"
            } else {
                "① Manifest 声明 FOREGROUND_SERVICE_MEDIA_PROJECTION" +
                    (if (fgsDeclared) "（已声明 ✓）" else "（⚠ 当前未声明）") + "\n" +
                    "② 用户在 App 内点一次「授权屏幕捕获」完成系统弹窗授权（**不可预置**，这是与 Device Owner 的本质区别）；\n" +
                    "③ 授权缓存于 files/$GRANT_FILE，进程重启后自动复用。"
            }
        )
    }

    private fun checkSpecialPerms(ctx: Context): ProbeResult {
        val items = mutableListOf<String>()
        var okCount = 0

        // 1) 外部存储管理（bridge:storage 的代表能力）
        val extStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else false
        if (extStorage) okCount++
        items += "MANAGE_EXTERNAL_STORAGE=${if (extStorage) "已授权" else "未授权"}" +
            (if (extStorage) "" else "（已声明，需跳设置页或由 Device Owner 静默授予）")

        // 2) 通知访问
        val notifAccess = try {
            val csv = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
            csv.split(":").any { it.contains(ctx.packageName) }
        } catch (_: Throwable) { false }
        if (notifAccess) okCount++
        items += "通知访问=${if (notifAccess) "已授权" else "未授权"}"

        // 3) 通知发送（Android 13+ 运行时权限）
        val postNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        if (postNotif) okCount++
        items += "POST_NOTIFICATIONS=${if (postNotif) "已授权" else "未授权（notif.post 会被系统静默丢弃）"}"

        // 4) 安装未知应用（Device Owner 静默安装依赖；AppOps 特殊权限）
        val installPkgs = try {
            ctx.packageManager.canRequestPackageInstalls()
        } catch (_: Throwable) { false }
        if (installPkgs) okCount++
        items += "REQUEST_INSTALL_PACKAGES=${if (installPkgs) "已授权" else "未授权"}" +
            "（Device Owner 可经 setPermissionGrantState 直接授予）"

        // 5) 悬浮窗
        val overlay = try {
            Settings.canDrawOverlays(ctx)
        } catch (_: Throwable) { false }
        if (overlay) okCount++
        items += "SYSTEM_ALERT_WINDOW=${if (overlay) "已授权" else "未授权"}" +
            "（Device Owner 可静默授予，无需用户点确认）"

        return ProbeResult(
            SPECIAL_PERMS, "标准特殊权限", okCount == 5, "$okCount/5 已就绪",
            items.joinToString("\n")
        )
    }

    // ---- 机器可读快照 ----

    private fun writeSnapshot(ctx: Context, results: List<ProbeResult>) {
        try {
            val obj = org.json.JSONObject().apply {
                put("schema", 1)
                put("checkedAt", System.currentTimeMillis())
                put("androidApi", Build.VERSION.SDK_INT)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("checks", org.json.JSONArray().apply {
                    for (r in results) {
                        put(org.json.JSONObject().apply {
                            put("id", r.id)
                            put("label", r.label)
                            put("ok", r.ok)
                            put("status", r.status)
                            put("hint", r.hint)
                        })
                    }
                })
            }
            File(ctx.filesDir, "provisioning.json").writeText(obj.toString(2))
        } catch (_: Throwable) {
            // 探针失败绝不影响启动流程
        }
    }

    data class ProbeResult(
        val id: String,
        val label: String,
        val ok: Boolean,
        val status: String,
        val hint: String
    )
}
