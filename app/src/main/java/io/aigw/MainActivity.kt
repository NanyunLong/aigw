package io.aigw

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * 主界面：WebView 加载内置控制面板（assets/panel.html）。
 * 网关服务随 app 启动自动拉起；面板通过 http://127.0.0.1:8787/api/panel/* 与网关交互。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val confFile: File by lazy { File(filesDir, "config.json") }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensureConfig()

        webView = WebView(this)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        // 加载内置面板。网关监听 127.0.0.1:8787（同源，面板 JS 直接 /api/panel/*）。
        webView.loadUrl("http://127.0.0.1:8787/panel")

        setContentView(webView)

        startGateway()
    }

    override fun onResume() {
        super.onResume()
        // 从后台回前台时刷新面板（网关可能已被系统杀掉，尝试拉起）
        startGateway()
        webView.reload()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    private fun ensureConfig() {
        if (!confFile.exists()) GatewayConfig.writeDefault(confFile)
    }

    private fun startGateway() {
        try {
            val i = Intent(this, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        } catch (e: Exception) {
            // 已在前台服务状态或权限问题，忽略；面板仍可打开
        }
    }
}