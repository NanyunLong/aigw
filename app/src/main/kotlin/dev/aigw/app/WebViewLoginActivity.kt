package dev.aigw.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import dev.aigw.core.gateway.LoginOutcome
import dev.aigw.core.gateway.LoginState
import dev.aigw.core.provider.WebViewExtractSupport

/**
 * 内置 WebView 登录页：打开 [WebViewExtractSupport.loginUrl]，**自动**检测登录态。
 *
 * 界面只有网页本身，不额外画标题栏/按钮 —— 与系统浏览器登录的观感一致，
 * 用户看到的就是原站登录页。每次页面加载完成、以及每 [POLL_INTERVAL_MS] 定时，
 * 都会读取目标域名下的 localStorage 与 Cookie；字段齐全即自动提取凭证落池并关闭。
 * 返回走系统返回键/手势（有历史时先回上一页）。
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
        wv.settings.databaseEnabled = true
        wv.settings.javaScriptCanOpenWindowsAutomatically = true
        wv.settings.userAgentString = MOBILE_UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // 页面加载完成后延迟检测：登录成功跳转后 localStorage 可能异步写入
                handler.postDelayed({ detectNow(sup) }, 500)
            }
        }
        wv.addJavascriptInterface(ExtractBridge(sup), JS_BRIDGE)
        webView = wv

        // 只有网页本身：不画标题栏、不画按钮
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                wv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    /** 返回键：WebView 有历史就先退一页，否则退出登录页。 */
    @Deprecated("Activity.onBackPressed")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else super.onBackPressed()
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
        // 目标字段都为空 = 还没登录，静默等待，不打扰。
        // 兜底扫描的键带 ls:/ss: 前缀，按目标键名模糊匹配（不区分大小写、含子串）
        fun lookupValue(targetKey: String): String {
            val exact = storage[targetKey]
            if (!exact.isNullOrEmpty()) return exact
            val lower = targetKey.lowercase()
            return storage.entries.firstOrNull { it.key.lowercase().contains(lower) && it.value.isNotEmpty() }?.value.orEmpty()
        }
        val resolvedStorage = sup.localStorageKeys.associateWith { lookupValue(it) }
        val hasStorage = resolvedStorage.values.any { it.isNotEmpty() }
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
            engine.completeWebViewExtract(providerId, resolvedStorage, cookies)
        }.getOrElse {
            LoginOutcome(LoginState.FAILED, error = it.message ?: "登录失败")
        }
        if (outcome.ok) {
            finished = true
            toast("登录成功：${outcome.nickname.ifEmpty { outcome.uid }}")
            finish()
        } else {
            // 提取失败（字段在但校验不过）：提示具体原因，继续等待用户重新登录
            toast("登录失败：${outcome.error}")
            scheduleNextDetect(sup)
        }
    }

    private fun scheduleNextDetect(sup: WebViewExtractSupport) {
        if (finished) return
        handler.removeCallbacksAndMessages(null)
        // 校验失败后降频：频繁重试会反复打上游 token 接口，也弹一堆 toast
        handler.postDelayed({ detectNow(sup) }, SLOW_POLL_INTERVAL_MS)
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
        /** 提取校验失败后的慢轮询间隔：避免反复打上游 token 接口。 */
        private const val SLOW_POLL_INTERVAL_MS = 10_000L
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
                  // 兜底：目标键没取到时，扫 localStorage+sessionStorage 的全部键，
                  // 按目标键名做模糊匹配（chatglm_refresh_token 之类可能带前缀/后缀）
                  var found = false;
                  for (var k in out) if (out[k]) found = true;
                  if (!found) {
                    var wanted = keys.map(function(k){return k.toLowerCase();});
                    var scan = function(store, tag) {
                      try {
                        for (var i=0;i<store.length;i++){
                          var sk = store.key(i);
                          var lsk = sk.toLowerCase();
                          for (var j=0;j<wanted.length;j++){
                            if (lsk.indexOf(wanted[j]) >= 0) {
                              var sv = store.getItem(sk);
                              if (sv) { out[tag + ':' + sk] = sv; }
                            }
                          }
                        }
                      } catch(e){}
                    };
                    scan(window.localStorage, 'ls');
                    scan(window.sessionStorage, 'ss');
                  }
                  AigwExtract.submit(JSON.stringify(out));
                })();
            """.trimIndent()
        }
    }
}
