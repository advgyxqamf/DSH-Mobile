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
 *  - 探测到 127.0.0.1:3080 就绪后，切换到 WebView 加载 host.html（内嵌内核 UI 的 iframe）。
 *  - 宿主帧经 JavascriptInterface 桥接内核的 dsh:kernel-update-request，
 *    处理完（此处为重启 :node 进程以重读 CURRENT / 触发 OTA）后回灌 dsh:kernel-update-result。
 *  - 提供“重试”按钮：清空诊断、重启 NodeRuntimeService 重新走全流程。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var diagText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var webView: WebView
    private lateinit var retryBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private var uiMode = false // false=诊断面板, true=WebView(host.html + 内核 iframe)

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 即使被拒也尽力启动服务 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        diagText = findViewById(R.id.diagText)
        scroll = findViewById(R.id.scroll)
        webView = findViewById(R.id.webview)
        retryBtn = findViewById(R.id.retryBtn)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        retryBtn.setOnClickListener { restartRuntime() }

        setupWebView()
        startRuntime()
        startPolling()
    }

    private fun setupWebView() {
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(DshBridge(this), "DshNative")
    }

    /** 原生侧接收内核发来的 dsh:kernel-update-request，处理并回灌结果。 */
    fun handleKernelUpdateRequest(json: String) {
        try {
            val req = org.json.JSONObject(json)
            val requestId = req.optString("requestId", "")
            // 处理：重启 :node 进程（重读 CURRENT / OTA 钩子由 Node 侧承接），拉起最新内核。
            restartRuntime()
            val result = org.json.JSONObject().apply {
                put("type", "dsh:kernel-update-result")
                put("requestId", requestId)
                put("status", "restarting")
                put("message", "已重启运行时以加载内核更新")
            }
            webView.post {
                webView.evaluateJavascript("dshDeliverResult($result)", null)
            }
        } catch (_: Throwable) {
        }
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
        webView.loadUrl("file:///android_asset/host.html")
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

    /** 内核 ↔ 原生桥：内核 UI 经 window.DshNative.onRequest 把更新请求交给原生。 */
    private class DshBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun onRequest(json: String) {
            activity.runOnUiThread { activity.handleKernelUpdateRequest(json) }
        }
    }
}
