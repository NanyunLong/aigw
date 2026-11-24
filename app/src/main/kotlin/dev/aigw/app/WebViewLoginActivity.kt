package dev.aigw.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import dev.aigw.core.gateway.LoginOutcome
import dev.aigw.core.gateway.LoginState
import dev.aigw.core.provider.WebViewExtractSupport

/**
 * 内置 WebView 登录页：打开 [WebViewExtractSupport.loginUrl]，用户登录后
 * 点顶栏「完成登录」，由 App 读取目标域名下的 localStorage 与 Cookie，
 * 交给 provider 提取凭证并落池。
 *
 * 与系统浏览器登录完全独立：只在 WEBVIEW_EXTRACT 型供应商登录时使用，
 * 不影响 Trae/Antigravity/WorkBuddy 等原有登录通道。
 */
class WebViewLoginActivity : Activity() {

    private var providerId: String = ""
    private var support: WebViewExtractSupport? = null
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID).orEmpty()
        val engine = runCatching { gatewayEngine }.getOrNull()
        val sup = engine?.registry?.get(providerId) as? WebViewExtractSupport
        if (engine == null || sup == null) {
            toast("该供应商不支持内置浏览器登录")
            finish()
            return
        }
        support = sup
        setContentView(buildLayout(sup))
        webView?.loadUrl(sup.loginUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLayout(sup: WebViewExtractSupport): ViewGroup {
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.javaScriptCanOpenWindowsAutomatically = true
        wv.settings.userAgentString = MOBILE_UA
        wv.webViewClient = object : WebViewClient() {}
        wv.addJavascriptInterface(ExtractBridge(sup), JS_BRIDGE)
        webView = wv

        val done = Button(this).apply {
            text = "完成登录"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(212, 168, 67))
            setOnClickListener { onDoneClicked(sup) }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 12, 12, 12)
            addView(done, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return root
    }

    private fun onDoneClicked(sup: WebViewExtractSupport) {
        // 读 localStorage（必须用 JS，WebView 无原生 API）后，回读 Cookie 一起提交
        val js = extractLocalStorageJs(sup.localStorageKeys)
        webView?.evaluateJavascript(js, null)
    }

    /** JS 桥：把 localStorage 采集结果交给 Kotlin，再读 Cookie 一起提取。 */
    private inner class ExtractBridge(private val sup: WebViewExtractSupport) {
        @JavascriptInterface
        fun submit(localStorageJson: String) {
            runOnUiThread {
                val storage = parsePairs(localStorageJson)
                val cookies = readCookies(sup.cookieNames)
                completeLogin(sup, storage, cookies)
            }
        }
    }

    private fun completeLogin(
        sup: WebViewExtractSupport,
        storage: Map<String, String>,
        cookies: Map<String, String>,
    ) {
        val engine = runCatching { gatewayEngine }.getOrNull()
        if (engine == null) {
            toast("引擎未就绪")
            return
        }
        val outcome: LoginOutcome = runCatching {
            engine.completeWebViewExtract(providerId, storage, cookies)
        }.getOrElse {
            LoginOutcome(LoginState.FAILED, error = it.message ?: "登录失败")
        }
        toast(if (outcome.ok) "登录成功：${outcome.nickname.ifEmpty { outcome.uid }}" else "失败：${outcome.error}")
        finish()
    }

    private fun readCookies(names: List<String>): Map<String, String> {
        if (names.isEmpty()) return emptyMap()
        val cm = CookieManager.getInstance()
        val host = webView?.url?.let { runCatching { java.net.URI(it).host }.getOrNull() }.orEmpty()
        if (host.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val all = cm.getCookie("https://$host").orEmpty()
        for (part in all.split(';')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (key in names) result[key] = value
        }
        return result
    }

    private fun parsePairs(json: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (json.isBlank()) return result
        runCatching {
            com.google.gson.JsonParser.parseString(json).asJsonObject.entrySet().forEach {
                result[it.key] = it.value.takeIf { v -> !v.isJsonNull }?.asString.orEmpty()
            }
        }
        return result
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROVIDER_ID = "provider_id"
        private const val JS_BRIDGE = "AigwExtract"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /** 生成读取 localStorage 的 JS：把指定键拼成 JSON 提交回 Kotlin。 */
        private fun extractLocalStorageJs(keys: List<String>): String {
            if (keys.isEmpty()) return "AigwExtract.submit('{}');"
            val keyList = keys.joinToString(",") { "\"$it\"" }
            return """
                (function(){
                  var out = {};
                  var keys = [$keyList];
                  for (var i=0;i<keys.length;i++){
                    var v = null;
                    try { v = window.localStorage.getItem(keys[i]); } catch(e){}
                    if (v !== null) out[keys[i]] = v;
                  }
                  AigwExtract.submit(JSON.stringify(out));
                })();
            """.trimIndent()
        }
    }
}
