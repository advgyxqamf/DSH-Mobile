package com.example.nodecontainer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * 入口 Activity，也是“可观测”面板：
 *  - 默认显示“启动诊断”文本（逐阶段、带时间戳的状态与报错，来自 RuntimeDiagnostics 文件）。
 *  - 一旦探测到 127.0.0.1:3080 就绪，自动切换到 WebView 加载 Node 探针 UI。
 *  - 提供“重试”按钮：清空诊断、重启 NodeRuntimeService 重新走全流程。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var diagText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var webView: WebView
    private lateinit var retryBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private var uiMode = false // false=诊断面板, true=WebView

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 即使被拒也尽力启动服务，仅通知可能不显示 */ }

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

        startRuntime()
        startPolling()
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
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.loadUrl("http://127.0.0.1:${NodeRuntimeService.PORT}/")
    }

    private fun isPortUp(): Boolean = try {
        val c = URL("http://127.0.0.1:${NodeRuntimeService.PORT}/api/version").openConnection() as HttpURLConnection
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
}
