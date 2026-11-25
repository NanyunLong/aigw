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
        // 无痕模式：清掉上个账号留下的 cookie/localStorage，否则打开就是已登录状态，
        // 提取到的永远是旧账号，没法反复加多个账号（要换号就得先手动退出）。
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // domStorage 的清法：先加载 about:blank 让 storage 就绪，再 clearFormData
        android.webkit.WebStorage.getInstance().deleteAllData()
        wv.settings.databaseEnabled = true
        wv.settings.javaScriptCanOpenWindowsAutomatically = true
        wv.settings.userAgentString = PC_UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                // 文档一开始就挂错误钩子，白屏时能拿到真实 JS 异常（诊断用，不影响页面）。
                view.evaluateJavascript(
                    "window.__aigwErrs=[];window.addEventListener('error',function(e){" +
                        "window.__aigwErrs.push((e.message||'')+' @'+(e.filename||'')+':'+(e.lineno||0));},true);" +
                        "window.addEventListener('unhandledrejection',function(e){" +
                        "window.__aigwErrs.push('reject:'+String(e.reason).slice(0,120));});",
                    null,
                )
            }

            override fun onPageFinished(view: WebView, url: String) {
                // 页面加载完成后延迟检测：登录成功跳转后 localStorage 可能异步写入
                handler.postDelayed({ detectNow(sup) }, 500)
                // 白屏诊断：3 秒后若 #app 仍为空，把 JS 错误与资源失败 toast 出来
                handler.postDelayed({
                    view.evaluateJavascript(
                        "(function(){var a=document.getElementById('app');" +
                            "var n=a?a.innerHTML.length:-1;" +
                            "if(n>200)return 'ok:'+n;" +
                            "var errs=(window.__aigwErrs||[]).slice(0,3).join(' | ');" +
                            "var res=performance.getEntriesByType('resource').filter(function(r){" +
                            "return r.responseStatus>=400||r.transferSize===0&&r.decodedBodySize===0;})" +
                            ".slice(0,3).map(function(r){return r.name.split('/').pop();}).join(',');" +
                            "return 'app='+n+' errs='+errs+' res='+res+' url='+location.href;})()",
                    ) { r ->
                        val s = r?.trim('"') ?: ""
                        if (s.startsWith("app=")) toast("白屏诊断：$s".take(300))
                    }
                }, 3000)
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

    /** 读当前 URL：诊断白屏时确认 WebView 实际停在哪个地址。 */
    private fun currentUrl(): String = webView?.url.orEmpty()

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
     * 尝试提取：字段齐全就落池并关闭；不齐全（还没登录）则静默等待下一次检测。
     * 诊断：若扫描到了键名但匹配不到目标键，弹一次提示让用户看到键名，方便定位。
     */
    private fun tryExtract(
        sup: WebViewExtractSupport,
        storage: Map<String, String>,
        cookies: Map<String, String>,
    ) {
        val allKeys = storage["_all_keys"].orEmpty()
        // 匹配：精确键名 → 键名含目标子串（大小写不敏感）
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
            // 诊断：扫到了键名但匹配不上目标键，提示用户把键名告诉我
            if (allKeys.isNotEmpty() && !diagnosed) {
                diagnosed = true
                val sample = allKeys.split('|').take(8).joinToString("、")
            toast("已登录但没抓到目标键（${currentUrl().take(60)}）。页面上有这些存储键：$sample")
            }
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
            // 校验失败：带上 token 片段和来源键名，方便定位是 token 错还是签名错
            val tokenSample = resolvedStorage.values.firstOrNull { it.isNotEmpty() }?.take(24).orEmpty()
            val keySample = resolvedStorage.entries.firstOrNull { it.value.isNotEmpty() }?.key.orEmpty()
            toast("登录失败：${outcome.error}｜token=${tokenSample}… 键=$keySample")
            scheduleNextDetect(sup)
        }
    }

    private var diagnosed = false

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
        /** PC UA：chatglm 的 refresh_token（chatglm_refresh_token 键）只存在 PC 版页面的 localStorage；
         *  移动版页面登录后存的是另一套短时效 session，用它换 refresh 恒 401。 */
        private const val PC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

        /** 生成读取 localStorage 的 JS：全量扫描+回传所有键名，供排查与匹配。 */
        private fun extractLocalStorageJs(keys: List<String>): String {
            val keyList = keys.joinToString(",") { "\"$it\"" }
            return """
                (function(){
                  var out = {};
                  var keys = [$keyList];
                  // 先精确取目标键
                  for (var i=0;i<keys.length;i++){
                    var v = null;
                    try { v = window.localStorage.getItem(keys[i]); } catch(e){}
                    if (v !== null && v !== '') out[keys[i]] = v;
                  }
                  // 全量扫 localStorage+sessionStorage：
                  // 1) 目标键没取到时，把含 token/refresh/jwt 特征的值全抓出来
                  // 2) 所有键名放 _all_keys 供排查（不抓非特征键的值，避免把用户隐私带出去）
                  var wanted = keys.map(function(k){return k.toLowerCase();});
                  var allKeys = [];
                  var scan = function(store, tag) {
                    try {
                      for (var i=0;i<store.length;i++){
                        var sk = store.key(i);
                        allKeys.push(tag + ':' + sk);
                        var lsk = sk.toLowerCase();
                        for (var j=0;j<wanted.length;j++){
                          if (lsk.indexOf(wanted[j]) >= 0) {
                            var sv = store.getItem(sk);
                            if (sv) { out[sk] = sv; }
                          }
                        }
                        // 补充：键名含 token/refresh/jwt/session 但不在目标键里的也抓（如智谱可能存别的键）
                        if (lsk.indexOf('refresh') >= 0 || lsk.indexOf('token') >= 0 || lsk.indexOf('jwt') >= 0) {
                          var sv2 = store.getItem(sk);
                          if (sv2) { out[sk] = sv2; }
                        }
                      }
                    } catch(e){}
                  };
                  scan(window.localStorage, 'ls');
                  scan(window.sessionStorage, 'ss');
                  out['_all_keys'] = allKeys.join('|');
                  AigwExtract.submit(JSON.stringify(out));
                })();
            """.trimIndent()
        }
    }
}
