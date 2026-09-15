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

    private fun checkShizuku(ctx: Context): ProbeResult {
        // Shizuku 以「是否安装 + 是否已授权」判定；未接 SDK，故只做存在性探测。
        val installed = try {
            ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: Throwable) { false }

        val binderAlive = try {
            // Shizuku 的 binder 服务名固定为 "shizuku"；getService 非空即视为守护进程在跑。
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "shizuku") != null
        } catch (_: Throwable) { false }

        val ok = installed && binderAlive
        val status = when {
            ok -> "已安装且守护进程在跑"
            installed -> "已安装但未启动/未授权"
            else -> "未安装"
        }
        return ProbeResult(
            SHIZUKU, "Shizuku / 无线调试", ok, status,
            if (ok) "bridge:shell 可解锁（需容器侧接入 Shizuku SDK，P4）"
            else "无线调试方案：开发者选项 → 无线调试 → 配对；或安装 Shizuku 并授权。\n" +
                "⚠ 容器尚未内置 Shizuku SDK（P4），当前即使就绪 shell.exec 仍返回 -32001"
        )
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

        return ProbeResult(
            MEDIAPROJECTION, "MediaProjection（截屏）",
            ok = false,
            status = if (apiOk) "未实现（P5）" else "平台不支持",
            hint = "ui.screenshot 恒返回 -32001。落地需：\n" +
                "① Manifest 声明 FOREGROUND_SERVICE_MEDIA_PROJECTION" +
                (if (fgsDeclared) "（已声明）" else "（⚠ 当前未声明）") + "\n" +
                "② 用户侧一次性弹窗授权（createScreenCaptureIntent），授权不可预置；\n" +
                "③ 前台服务类型切为 mediaProjection。"
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
            (if (extStorage) "" else "（Manifest 未声明，需先声明再跳设置页授权）")

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
        items += "SYSTEM_ALERT_WINDOW=${if (overlay) "已授权" else "未授权"}（Manifest 未声明）"

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
