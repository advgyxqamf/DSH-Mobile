package com.example.nodecontainer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 入口 Activity，也是“可观测”面板 + 内核 UI 宿主帧：
 *  - 内核未就绪时显示“启动诊断”文本（逐阶段、带时间戳的状态，来自 RuntimeDiagnostics）。
 *  - 探测到内核控制面（127.0.0.1:KERNEL_CONTROL_PORT）就绪后，切换到 WebView 加载**内核同源托管的宿主帧**
 *    （http://127.0.0.1:<port>/__host）。宿主帧内以 iframe 嵌内核面板（同源）—— 这样面板既满足
 *    `hasHostBridge()`（window.parent !== window），又满足内核 Origin 闸（同源→写操作不被 403）。
 *  - 面板经 postMessage 发 dsh:kernel-update-request → 宿主帧转交本 Activity（DshNative.onRequest）
 *    → 重启 :node 进程（重读 CURRENT / 触发 OTA）→ 回灌 dsh:kernel-update-result（**严格按内核契约**）。
 *  - 提供“重试”按钮：清空诊断、重启 NodeRuntimeService 重新走全流程。
 *  - 提供“授权屏幕捕获”按钮：MediaProjection 授权**无法预置**（不同于 Device Owner），
 *    必须由用户点系统弹窗。授权结果缓存到 files/screen-capture-grant.json，之后可后台复用，
 *    这是内核 ui.screenshot 能工作的前置条件。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var diagText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var webView: WebView
    private lateinit var retryBtn: Button
    private lateinit var captureBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private var uiMode = false // false=诊断面板, true=WebView(内核 /__host 宿主帧 + 面板 iframe)

    /** 内核更新桥协议版本：必须与内核 kernelUpdateBridge.ts 的 BRIDGE_PROTOCOL_VERSION 一致。 */
    private val kernelUpdateProtocol = 1

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 即使被拒也尽力启动服务 */ }

    /**
     * 截屏授权（ui.screenshot 的前置）。
     *
     * ⚠ MediaProjection 与 Device Owner 的本质区别：它**不能预置**，必须由用户在系统弹窗
     * 上点一次「开始录制」。所以这里必须有个 Activity 承接 startActivityForResult。
     * 拿到 resultCode + data 后：
     *   ① saveGrant() 落盘缓存（进程重启后可复用，避免每次截图都弹窗）；
     *   ② 拉起 ScreenCaptureService 建 projection。
     */
    private val requestCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.saveGrant(this, result.resultCode, data)
            startCaptureService(result.resultCode, data)
            RuntimeDiagnostics.append(
                this, "screenshot", true, "截屏授权已获取", "已缓存，可后台复用；ui.screenshot 现在可用"
            )
        } else {
            RuntimeDiagnostics.append(
                this, "screenshot", false, "截屏授权被取消",
                "ui.screenshot 将继续返回 -32001；可随时点「授权屏幕捕获」重来"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        diagText = findViewById(R.id.diagText)
        scroll = findViewById(R.id.scroll)
        webView = findViewById(R.id.webview)
        retryBtn = findViewById(R.id.retryBtn)
        captureBtn = findViewById(R.id.captureBtn)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        retryBtn.setOnClickListener { restartRuntime() }
        captureBtn.setOnClickListener { requestScreenCapture() }

        setupWebView()
        // 复用上次授权：进程/设备重启后若 grant 仍在，直接拉起服务，无需用户再点一次。
        reuseExistingCaptureGrant()
        startRuntime()
        startPolling()
    }

    /** 尝试用上次缓存的 MediaProjection 授权直接建 projection（失败则静默，等用户手动授权）。 */
    private fun reuseExistingCaptureGrant() {
        if (ScreenCaptureService.isReady()) return
        val grant = ScreenCaptureService.loadGrant(this) ?: return
        startCaptureService(grant.first, grant.second)
    }

    /** 拉起截屏前台服务（Android 14+ 必须以前台服务承载 MediaProjection）。 */
    private fun startCaptureService(resultCode: Int, data: Intent) {
        try {
            val svc = Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_START)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "screenshot", false, "启动截屏服务失败",
                "${e::class.java.simpleName}: ${e.message}")
        }
    }

    /** 向系统申请截屏授权（弹窗由系统渲染，用户须手动确认）。 */
    private fun requestScreenCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            RuntimeDiagnostics.append(this, "screenshot", false, "平台不支持 MediaProjection", "需 API 21+")
            return
        }
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            requestCapture.launch(mpm.createScreenCaptureIntent())
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "screenshot", false, "发起截屏授权失败",
                "${e::class.java.simpleName}: ${e.message}")
        }
    }

    private fun setupWebView() {
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(DshBridge(this), "DshNative")
    }

    /**
     * 原生侧接收内核发来的 dsh:kernel-update-request，处理并回灌结果。
     * 回灌**严格按内核 kernelUpdateBridge.ts 的契约**：{v,type,requestId,ok,stage,version,restartUncertain,error}。
     * ⚠ 缺 v / ok 会导致内核侧直接丢弃消息（面板超时）。
     */
    fun handleKernelUpdateRequest(json: String) {
        try {
            val req = org.json.JSONObject(json)
            val requestId = req.optString("requestId", "")
            // 重启前先尽力读一次内核只读版本端点（供面板展示；失败不阻断）。
            val version = tryReadKernelVersion()
            // 处理：重启 :node 进程（重读 CURRENT / OTA 钩子由 Node 侧承接），拉起最新内核。
            restartRuntime()
            val result = org.json.JSONObject().apply {
                put("v", kernelUpdateProtocol)
                put("type", "dsh:kernel-update-result")
                put("requestId", requestId)
                put("ok", true)
                put("stage", "restarting")
                if (version != null) put("version", version) else put("version", org.json.JSONObject.NULL)
                // 重启是「结果不确定」操作：拉起后是否真的加载了新版本由内核自行确认。
                put("restartUncertain", true)
                put("error", org.json.JSONObject.NULL)
            }
            val jsonStr = result.toString()
            webView.post {
                // 宿主帧暴露 dshDeliverResult(json)；用 JSON 字符串安全注入（避免拼接注入）。
                webView.evaluateJavascript(
                    "window.dshDeliverResult && window.dshDeliverResult(${org.json.JSONObject.quote(jsonStr)})",
                    null
                )
            }
        } catch (_: Throwable) {
        }
    }

    /** 读取内核只读版本端点（GET /guard/version）。失败返回 null（不阻断更新流程）。 */
    private fun tryReadKernelVersion(): String? = try {
        val c = java.net.URL("http://127.0.0.1:${NodeRuntimeService.KERNEL_CONTROL_PORT}/guard/version").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 500
        c.readTimeout = 500
        c.requestMethod = "GET"
        if (c.responseCode == 200) {
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val v = org.json.JSONObject(body).optString("version", null)
            if (v.isNullOrBlank()) null else v
        } else null
    } catch (_: Throwable) {
        null
    }

    private fun startRuntime() {
        val svc = Intent(this, NodeRuntimeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    private fun restartRuntime() {
        try {
            stopService(Intent(this, NodeRuntimeService::class.java))
        } catch (_: Throwable) {
        }
        uiMode = false
        webView.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        retryBtn.visibility = View.VISIBLE
        RuntimeDiagnostics.clear(this)
        diagText.text = "正在重启运行时..."
        startRuntime()
    }

    private fun startPolling() {
        handler.post(object : Runnable {
            override fun run() {
                if (!uiMode) {
                    val log = RuntimeDiagnostics.read(this@MainActivity)
                    diagText.text = if (log.isBlank()) "初始化中..." else log
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    if (isPortUp()) enterWebView()
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun enterWebView() {
        uiMode = true
        scroll.visibility = View.GONE
        retryBtn.visibility = View.GONE
        webView.visibility = View.VISIBLE
        // 加载**内核同源托管**的宿主帧（非容器 assets）：宿主页与面板同源 → 面板写操作不被内核 403。
        webView.loadUrl("http://127.0.0.1:${NodeRuntimeService.KERNEL_CONTROL_PORT}/__host")
    }

    private fun isPortUp(): Boolean = try {
        val c = java.net.URL("http://127.0.0.1:${NodeRuntimeService.KERNEL_CONTROL_PORT}/status").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 300
        c.readTimeout = 300
        c.requestMethod = "GET"
        c.responseCode == 200
    } catch (_: Throwable) {
        false
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** 内核 ↔ 原生桥：宿主帧经 window.DshNative.onRequest 把更新请求交给原生。 */
    private class DshBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun onRequest(json: String) {
            activity.runOnUiThread { activity.handleKernelUpdateRequest(json) }
        }
    }
}
