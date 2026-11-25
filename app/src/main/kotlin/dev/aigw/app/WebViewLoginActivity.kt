package dev.aigw.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import dev.aigw.core.gateway.LoginOutcome
import dev.aigw.core.gateway.LoginState
import dev.aigw.core.provider.WebViewExtractSupport

/**
 * 内置 WebView 登录页：打开 [WebViewExtractSupport.loginUrl]，**自动**检测登录态。
 *
 * 每次页面加载完成、以及每 [POLL_INTERVAL_MS] 定时，都会读取目标域名下的
 * localStorage 与 Cookie；一旦 provider 需要的字段齐全就自动提取凭证并落池，
 * 用户登录后无需任何额外点击。
 *
 * 与系统浏览器登录完全独立：只在 WEBVIEW_EXTRACT 型供应商登录时使用，
 * 不影响 Trae/Antigravity/WorkBuddy 等原有登录通道。
 */
class WebViewLoginActivity : Activity() {

    private var providerId: String = ""
    private var support: WebViewExtractSupport? = null
    private var webView: WebView? = null
    private var finished = false
    private val handler = Handler(Looper.getMainLooper())

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
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // 页面加载完成后立即检测一次（登录成功通常会跳转回首页）
                detectNow(sup)
            }
        }
        wv.addJavascriptInterface(ExtractBridge(sup), JS_BRIDGE)
        webView = wv

        // 顶栏：返回 + 提示；不设「完成登录」，登录后自动提取
        val close = Button(this).apply {
            text = "返回"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(120, 120, 120))
            setOnClickListener { finish() }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 12, 12, 12)
            addView(close, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return root
    }

    /** 读取 localStorage 目标键（JS），有值则由桥提交回 Kotlin 提取。 */
    private fun detectNow(sup: WebViewExtractSupport) {
        if (finished) return
        val js = extractLocalStorageJs(sup.localStorageKeys)
        webView?.evaluateJavascript(js, null)
    }

    /** JS 桥：localStorage 采集结果交回 Kotlin，再读 Cookie 一起提取。 */
    private inner class ExtractBridge(private val sup: WebViewExtractSupport) {
        @JavascriptInterface
        fun submit(localStorageJson: String) {
            runOnUiThread {
                if (finished) return@runOnUiThread
                val storage = parsePairs(localStorageJson)
                val cookies = readCookies(sup.cookieNames)
                tryExtract(sup, storage, cookies)
            }
        }
    }

    /**
     * 尝试提取：字段齐全就落池并关闭；不齐全（还没登录）则静默等待下一次检测，
     * 不弹「未找到」的报错打扰用户。
     */
    private fun tryExtract(
        sup: WebViewExtractSupport,
        storage: Map<String, String>,
        cookies: Map<String, String>,
    ) {
        // 目标字段都为空 = 还没登录，静默等待，不打扰
        val hasStorage = sup.localStorageKeys.any { storage[it].orEmpty().isNotEmpty() }
        val hasCookie = sup.cookieNames.any { cookies[it].orEmpty().isNotEmpty() }
        if (!hasStorage && !hasCookie) {
            scheduleNextDetect(sup)
            return
        }

        val engine = runCatching { gatewayEngine }.getOrNull()
        if (engine == null) {
            toast("引擎未就绪")
            finish()
            return
        }
        val outcome: LoginOutcome = runCatching {
            engine.completeWebViewExtract(providerId, storage, cookies)
        }.getOrElse {
            LoginOutcome(LoginState.FAILED, error = it.message ?: "登录失败")
        }
        if (outcome.ok) {
            finished = true
            toast("登录成功：${outcome.nickname.ifEmpty { outcome.uid }}")
            finish()
        } else {
            // 提取失败（字段在但校验不过）：提示一次，继续等待用户重新登录
            toast("登录失败：${outcome.error}")
            scheduleNextDetect(sup)
        }
    }

    private fun scheduleNextDetect(sup: WebViewExtractSupport) {
        if (finished) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ detectNow(sup) }, POLL_INTERVAL_MS)
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
        handler.removeCallbacksAndMessages(null)
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROVIDER_ID = "provider_id"
        private const val JS_BRIDGE = "AigwExtract"
        private const val POLL_INTERVAL_MS = 1_500L
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
