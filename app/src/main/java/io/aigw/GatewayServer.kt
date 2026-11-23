package io.aigw

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 网关本体。
 *
 * 关键安全约束（与 Python 版一致）：
 *  - 客户端 Authorization 不转发给上游；上游 key 只从配置取
 *  - 只接受白名单路径，其余 404
 *  - 上游错误体消毒后才回给客户端
 *  - 日志只记元数据，不落 body
 */
class GatewayServer(
    private val ctx: Context,
    private val cfg: GatewayConfig
) : NanoHTTPD("127.0.0.1", cfg.port) {

    companion object {
        private val PROXY_PATHS = setOf("/v1/chat/completions", "/v1/completions", "/v1/embeddings")
    }

    /** NanoHTTPD 的 Status 枚举缺 413/429/502，这里补一个实现 IStatus 的通用状态。 */
    private class SimpleStatus(private val code: Int, private val desc: String) : Response.IStatus {
        override fun getRequestStatus(): Int = code
        override fun getDescription(): String = "$code $desc"
    }

    private fun st(code: Int): Response.IStatus =
        Response.Status.lookup(code) ?: SimpleStatus(code, "Gateway")

    private val logFile = File(ctx.filesDir, cfg.logPath)
    private val stateFile = File(ctx.filesDir, "state.json")

    private val lock = Any()
    private val qpsMarks = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private var day: String = today()
    private val tokensUsed = HashMap<String, Long>()
    private val startedAt = System.currentTimeMillis()
    private val requestCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)

    @Volatile
    var lastError: String? = null
        private set

    init {
        loadState()
    }

    private fun today(): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return f.format(java.util.Date())
    }

    private fun loadState() {
        try {
            if (!stateFile.exists()) return
            val o = JSONObject(stateFile.readText())
            if (o.optString("day") == day) {
                val t = o.optJSONObject("tokens") ?: return
                for (k in t.keys()) tokensUsed[k] = t.getLong(k)
            }
        } catch (e: Exception) {
            // 读不到就当空预算
        }
    }

    private fun saveState() {
        try {
            val t = JSONObject()
            for ((k, v) in tokensUsed) t.put(k, v)
            stateFile.writeText(JSONObject().put("day", day).put("tokens", t).toString())
        } catch (e: Exception) {
        }
    }

    fun status(): JSONObject = synchronized(lock) {
        val t = JSONObject()
        for ((k, v) in tokensUsed) t.put(k, v)
        JSONObject()
            .put("running", true)
            .put("port", cfg.port)
            .put("uptimeSec", (System.currentTimeMillis() - startedAt) / 1000)
            .put("requests", requestCount.get())
            .put("errors", errorCount.get())
            .put("day", day)
            .put("tokens", t)
            .put("upstreams", JSONArray(cfg.upstreams.keys.toList()))
            .put("lastError", lastError ?: JSONObject.NULL)
    }

    // ------------------------------------------------------------ 配额

    private fun auth(token: String?): ClientConf? {
        if (token.isNullOrEmpty()) return null
        return cfg.clients[GatewayConfig.sha256(token)]
    }

    private fun acquire(c: ClientConf): String? = synchronized(lock) {
        val d = today()
        if (day != d) {
            day = d
            tokensUsed.clear()
            saveState()
        }
        if (!c.enabled) return "key disabled"
        if (c.dailyTokens > 0 && (tokensUsed[c.id] ?: 0L) >= c.dailyTokens) {
            return "daily token budget exhausted"
        }
        if (c.qps > 0) {
            val q = qpsMarks.getOrPut(c.id) { ArrayDeque() }
            val now = System.nanoTime()
            while (q.isNotEmpty() && now - q.peekFirst() > 1_000_000_000L) q.pollFirst()
            if (q.size >= c.qps.toInt()) return "rate limit exceeded"
            q.addLast(now)
        }
        null
    }

    private fun charge(c: ClientConf, tokens: Int) {
        if (tokens <= 0) return
        synchronized(lock) {
            tokensUsed[c.id] = (tokensUsed[c.id] ?: 0L) + tokens
            saveState()
        }
    }

    private fun clampTokens(c: ClientConf, body: JSONObject): Int {
        var cap = cfg.hardMaxTokens
        if (c.maxTokens > 0) cap = minOf(cap, c.maxTokens)
        for (f in listOf("max_tokens", "max_completion_tokens")) {
            if (body.has(f) && body.optInt(f, 0) > cap) {
                body.put(f, cap)
            }
        }
        if (cfg.forceDefaultMaxTokens && !body.has("max_tokens") && !body.has("max_completion_tokens")) {
            body.put("max_tokens", minOf(cfg.defaultMaxTokens, cap))
        }
        val eff = if (body.has("max_tokens")) body.optInt("max_tokens", cap)
        else if (body.has("max_completion_tokens")) body.optInt("max_completion_tokens", cap)
        else cap
        return if (eff > 0) eff else cap
    }

    // ------------------------------------------------------------ 日志

    private fun log(rec: JSONObject) {
        try {
            rec.put("ts", System.currentTimeMillis())
            if (logFile.length() > 8L * 1024 * 1024) {
                // 简单轮转：超过 8MB 就清空，避免手机存储被日志吃光
                logFile.writeText("")
            }
            logFile.appendText(rec.toString() + "\n")
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------ 路由

    private fun resolve(model: String?): Pair<Upstream, String> {
        if (!model.isNullOrEmpty() && model.contains("/")) {
            val head = model.substringBefore("/")
            val rest = model.substringAfter("/")
            val up = cfg.upstreams[head]
            if (up != null && rest.isNotEmpty()) return up to rest
        }
        return cfg.upstreams[cfg.defaultUpstream]!! to (model ?: "")
    }

    // ------------------------------------------------------------ HTTP 入口

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.split("?")[0]
        return try {
            when {
                path == "/health" || path == "/v1/health" -> json(Response.Status.OK, JSONObject()
                    .put("ok", true).put("version", BuildConfig.VERSION_NAME))

                path == "/admin/status" -> {
                    val c = auth(bearer(session))
                    if (c == null || !c.admin) err(Response.Status.FORBIDDEN, "admin key required")
                    else json(Response.Status.OK, status())
                }

                path == "/v1/models" -> {
                    val c = auth(bearer(session))
                    if (c == null) err(Response.Status.UNAUTHORIZED, "invalid api key")
                    else {
                        acquire(c)?.let { return err(st(429), it) }
                        val arr = JSONArray()
                        for ((uid, up) in cfg.upstreams) {
                            if (!Translate.matchPatterns(c.upstreams, uid)) continue
                            arr.put(JSONObject().put("id", "$uid/*").put("object", "model")
                                .put("owned_by", up.type))
                        }
                        json(Response.Status.OK, JSONObject().put("object", "list").put("data", arr))
                    }
                }

                path == "/panel" || path == "/panel/" -> {
                    val html = panelHtml()
                    if (html == null) err(Response.Status.NOT_FOUND, "panel not found")
                    else {
                        val b = html.toByteArray(Charsets.UTF_8)
                        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", b.inputStream(), b.size.toLong())
                    }
                }

                path.startsWith("/api/panel/") -> panelApi(session, path)

                session.method == NanoHTTPD.Method.POST && path in PROXY_PATHS -> handleProxy(session, path)

                else -> err(Response.Status.NOT_FOUND, "not found")
            }
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            lastError = e.message
            err(Response.Status.INTERNAL_ERROR, "gateway internal error")
        }
    }

    private fun bearer(session: IHTTPSession): String? {
        val raw = session.headers["authorization"]?.trim() ?: return null
        return if (raw.lowercase().startsWith("bearer ")) raw.substring(7).trim() else raw
    }

    // ------------------------------------------------------------ 面板

    /** 从 assets 读内置面板 HTML；读不到返回 null。 */
    private fun panelHtml(): String? {
        return try {
            ctx.assets.open("panel.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    /** 面板 API：/api/panel/state · upstreams · accounts · sign · logs · clients。 */
    private fun panelApi(session: IHTTPSession, path: String): Response {
        return try {
            when {
                path == "/api/panel/state" || path == "/api/panel/state/" ->
                    json(Response.Status.OK, panelState())

                path == "/api/panel/logs" || path == "/api/panel/logs/" ->
                    panelLogs()

                session.method == NanoHTTPD.Method.POST && path == "/api/panel/upstreams" ->
                    panelAddUpstream(session)

                session.method == NanoHTTPD.Method.POST && path == "/api/panel/upstreams/remove" ->
                    panelRemoveUpstream(session)

                session.method == NanoHTTPD.Method.POST && path == "/api/panel/accounts" ->
                    panelAddAccount(session)

                session.method == NanoHTTPD.Method.POST && path.startsWith("/api/panel/accounts/") ->
                    panelAccountAction(session, path)

                session.method == NanoHTTPD.Method.POST && path == "/api/panel/clients/update" ->
                    panelClientUpdate(session)

                session.method == NanoHTTPD.Method.POST && path == "/api/panel/sign" ->
                    panelSign()

                else -> err(Response.Status.NOT_FOUND, "panel api not found")
            }
        } catch (e: Exception) {
            err(Response.Status.INTERNAL_ERROR, "panel api error: ${e.message}")
        }
    }

    private fun panelState(): JSONObject {
        val upArr = JSONArray()
        for ((uid, up) in cfg.upstreams) {
            upArr.put(JSONObject()
                .put("id", uid)
                .put("type", up.type)
                .put("base_url", up.baseUrl)
                .put("auth_mode", up.authMode)
                .put("models", if (up.modelsUrl.isNotEmpty()) "登录式" else "静态"))
        }
        val clArr = JSONArray()
        for ((_, cl) in cfg.clients) {
            clArr.put(JSONObject()
                .put("id", cl.id)
                .put("qps", cl.qps)
                .put("daily_tokens", cl.dailyTokens)
                .put("max_tokens", cl.maxTokens)
                .put("admin", cl.admin)
                .put("enabled", cl.enabled))
        }
        return JSONObject()
            .put("totals", JSONObject()
                .put("upstreams", cfg.upstreams.size)
                .put("models", cfg.upstreams.values.count { it.modelsUrl.isNotEmpty() })
                .put("accounts", accountCount())
                .put("usable", usableCount())
                .put("requests", requestCount.get())
                .put("tokens", tokensUsed.values.sum())
                .put("errors", errorCount.get()))
            .put("config", JSONObject()
                .put("port", cfg.port)
                .put("upstreams", upArr)
                .put("clients", clArr))
            .put("accounts", accountsJson())
    }

    private fun accountsJson(): JSONArray {
        val arr = JSONArray()
        val upList = cfg.upstreams.values.filter { it.hasAccountPool() }
        for (up in upList) {
            for (acc in up.accounts) {
                arr.put(acc.toPanelJson(up.id))
            }
        }
        return arr
    }

    private fun accountCount(): Int = cfg.upstreams.values.sumOf { it.accounts.size }
    private fun usableCount(): Int = cfg.upstreams.values.sumOf { up -> up.accounts.count { it.usable() } }

    private fun panelLogs(): Response {
        val lines = ArrayList<String>()
        try {
            if (logFile.exists()) {
                val all = logFile.readLines()
                val n = minOf(250, all.size)
                for (i in all.size - n until all.size) lines.add(all[i])
            }
        } catch (e: Exception) {
        }
        return json(Response.Status.OK, JSONObject()
            .put("path", logFile.absolutePath)
            .put("lines", JSONArray(lines)))
    }

    // ---- upstreams ----

    private fun panelAddUpstream(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return err(Response.Status.BAD_REQUEST, "empty body")
        val id = body.optString("id", "").trim()
        if (id.isEmpty()) return err(Response.Status.BAD_REQUEST, "id required")
        if (cfg.upstreams.containsKey(id)) return err(Response.Status.BAD_REQUEST, "upstream exists: $id")
        val type = body.optString("type", "openai").lowercase()
        val base = body.optString("base_url", "").trimEnd('/')
        val mode = body.optString("auth_mode", "none").lowercase()
        val key = body.optString("api_key", "")
        if (!base.startsWith("http")) return err(Response.Status.BAD_REQUEST, "base_url must start with http")
        if (type !in listOf("openai", "anthropic", "gemini", "ollama", "loomy", "custom"))
            return err(Response.Status.BAD_REQUEST, "type 不支持：$type")
        val up = Upstream(
            id = id, type = type, baseUrl = base, stripV1 = true, timeoutSec = 300,
            key = key, authMode = mode,
            loginUrl = body.optString("login_url", ""),
            usernameField = body.optString("username_field", "username"),
            passwordField = body.optString("password_field", "password"),
            tokenField = body.optString("token_field", "token"),
            cookieUrl = body.optString("cookie_url", ""),
            cookieName = body.optString("cookie_name", ""),
            modelsUrl = body.optString("models_url", ""),
            quotaUrl = body.optString("quota_url", ""),
            accounts = ArrayList()
        )
        cfg.upstreams[id] = up
        saveConfig()
        return json(Response.Status.OK, JSONObject().put("ok", true).put("id", id).put("state", panelState()))
    }

    private fun panelRemoveUpstream(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return err(Response.Status.BAD_REQUEST, "empty body")
        val id = body.optString("id", "")
        if (!cfg.upstreams.containsKey(id)) return err(Response.Status.BAD_REQUEST, "upstream not found: $id")
        if (id == cfg.defaultUpstream) return err(Response.Status.BAD_REQUEST, "cannot remove default upstream")
        cfg.upstreams.remove(id)
        saveConfig()
        return json(Response.Status.OK, JSONObject().put("ok", true).put("state", panelState()))
    }

    // ---- accounts ----

    private fun panelAddAccount(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return err(Response.Status.BAD_REQUEST, "empty body")
        val upId = body.optString("upstream", "")
        val up = cfg.upstreams[upId]
            ?: return err(Response.Status.BAD_REQUEST, "upstream not found: $upId")
        if (!up.hasAccountPool()) return err(Response.Status.BAD_REQUEST, "upstream $upId 不支持账号池")
        val name = body.optString("name", "").trim()
        if (name.isEmpty()) return err(Response.Status.BAD_REQUEST, "name required")
        if (up.accounts.any { it.name == name }) return err(Response.Status.BAD_REQUEST, "account exists: $name")
        val acc = UpstreamAccount(
            name = name,
            loginid = body.optString("loginid", ""),
            password = body.optString("password", ""),
            session = body.optString("session", ""),
            enabled = true,
            upstream = upId
        )
        up.accounts.add(acc)
        val wantLogin = body.optBoolean("login", false)
        var loggedIn = false
        var loginErr: String? = null
        if (wantLogin) {
            try {
                val r = loginAccount(up, acc)
                loggedIn = r.first
                loginErr = r.second
            } catch (e: Exception) {
                loginErr = e.message
            }
        }
        saveConfig()
        val resp = JSONObject().put("ok", true).put("name", name).put("logged_in", loggedIn)
        if (loginErr != null) resp.put("error", loginErr)
        resp.put("state", panelState())
        return json(Response.Status.OK, resp)
    }

    private fun panelAccountAction(session: IHTTPSession, path: String): Response {
        val body = readJsonBody(session) ?: return err(Response.Status.BAD_REQUEST, "empty body")
        val upId = body.optString("upstream", "")
        val name = body.optString("name", "")
        val up = cfg.upstreams[upId] ?: return err(Response.Status.BAD_REQUEST, "upstream not found: $upId")
        val acc = up.accounts.firstOrNull { it.name == name }
            ?: return err(Response.Status.BAD_REQUEST, "account not found: $name")
        when {
            path.endsWith("/remove") -> up.accounts.remove(acc)
            path.endsWith("/renew") -> {
                val r = loginAccount(up, acc)
                if (!r.first) return err(Response.Status.BAD_REQUEST, r.second ?: "renew failed")
            }
            path.endsWith("/toggle") -> {
                acc.enabled = body.optBoolean("enabled", !acc.enabled)
            }
            else -> return err(Response.Status.NOT_FOUND, "unknown action")
        }
        saveConfig()
        return json(Response.Status.OK, JSONObject().put("ok", true).put("state", panelState()))
    }

    private fun panelClientUpdate(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return err(Response.Status.BAD_REQUEST, "empty body")
        val id = body.optString("id", "")
        val cl = cfg.clients.values.firstOrNull { it.id == id }
            ?: return err(Response.Status.BAD_REQUEST, "client not found: $id")
        if (body.has("enabled")) {
            cfg.clients[cl.keyHash] = cl.copy(enabled = body.optBoolean("enabled", cl.enabled))
            saveConfig()
        }
        return json(Response.Status.OK, JSONObject().put("ok", true).put("state", panelState()))
    }

    // ---- sign ----

    private fun panelSign(): Response {
        var signed = 0
        val detail = StringBuilder()
        for ((upId, up) in cfg.upstreams) {
            if (up.type != "loomy") continue
            for (acc in up.accounts) {
                if (!acc.session.isNullOrEmpty()) {
                    try {
                        val r = loomySign(up, acc)
                        signed++
                        detail.append("$upId/${acc.name}:${if (r) "ok" else "fail"} ")
                    } catch (e: Exception) {
                        detail.append("$upId/${acc.name}:err(${e.message}) ")
                    }
                }
            }
        }
        return json(Response.Status.OK, JSONObject()
            .put("ok", true).put("signed", signed).put("detail", detail.toString().trim()))
    }

    private fun saveConfig() {
        // 就地回写 config.json，保持配置与内存一致（面板改完即生效）
        try {
            val f = File(ctx.filesDir, "config.json")
            val root = JSONObject()
            root.put("listen", JSONObject().put("host", cfg.host).put("port", cfg.port))
            root.put("allow_public_listen", cfg.allowPublicListen)
            val upArr = JSONArray()
            for ((id, up) in cfg.upstreams) {
                upArr.put(up.toJson())
            }
            root.put("upstreams", upArr)
            root.put("default_upstream", cfg.defaultUpstream)
            root.put("limits", JSONObject()
                .put("max_body_bytes", cfg.maxBodyBytes)
                .put("max_response_bytes", cfg.maxResponseBytes)
                .put("hard_max_tokens", cfg.hardMaxTokens)
                .put("default_max_tokens", cfg.defaultMaxTokens)
                .put("force_default_max_tokens", cfg.forceDefaultMaxTokens))
            root.put("sanitize_errors", cfg.sanitizeErrors)
            root.put("allow_anonymous", cfg.allowAnonymous)
            root.put("logging", JSONObject().put("path", cfg.logPath).put("log_body", cfg.logBody))
            val clArr = JSONArray()
            for ((_, cl) in cfg.clients) {
                clArr.put(JSONObject()
                    .put("id", cl.id)
                    .put("key_sha256", cl.keyHash)
                    .put("qps", cl.qps)
                    .put("daily_tokens", cl.dailyTokens)
                    .put("max_tokens", cl.maxTokens)
                    .put("models", JSONArray(cl.models))
                    .put("upstreams", JSONArray(cl.upstreams))
                    .put("admin", cl.admin)
                    .put("enabled", cl.enabled))
            }
            root.put("clients", clArr)
            f.writeText(root.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
        }
    }

    private fun readJsonBody(session: IHTTPSession): JSONObject? {
        return try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val raw = map["postData"] ?: return null
            if (raw.isEmpty()) return null
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ 登录式上游

    /**
     * 登录一个账号。返回 (是否成功, 错误信息)。
     * mode=password: POST login_url 带 {username_field, password_field}，取 token_field 作为 session。
     * mode=cookie: GET cookie_url，从 Set-Cookie 提取 cookieName 作为 session。
     * mode=apikey: 直接用 acc.password 作为 session（或 up.key）。
     */
    private fun loginAccount(up: Upstream, acc: UpstreamAccount): Pair<Boolean, String?> {
        return try {
            when (up.authMode) {
                "password" -> loginByPassword(up, acc)
                "cookie" -> loginByCookie(up, acc)
                "apikey" -> {
                    acc.session = acc.password.ifEmpty { up.key }
                    true to null
                }
                else -> false to "auth mode none 无需登录"
            }
        } catch (e: Exception) {
            acc.lastError = e.message
            false to e.message
        }
    }

    private fun loginByPassword(up: Upstream, acc: UpstreamAccount): Pair<Boolean, String?> {
        if (up.loginUrl.isEmpty()) return false to "login_url 未配置"
        val url = if (up.loginUrl.startsWith("http")) up.loginUrl
        else up.baseUrl.trimEnd('/') + "/" + up.loginUrl.trimStart('/')
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = up.timeoutSec * 1000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        val payload = JSONObject()
            .put(up.usernameField, acc.loginid)
            .put(up.passwordField, acc.password)
            .toString()
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val msg = try { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(200) } catch (e: Exception) { null }
            return false to "login HTTP $code ${msg ?: ""}"
        }
        val raw = conn.inputStream.readBytes().toString(Charsets.UTF_8)
        val obj = try { JSONObject(raw) } catch (e: Exception) { null }
        val token = obj?.optString(up.tokenField, "")
            ?: (obj?.optJSONObject("data")?.optString(up.tokenField, "") ?: "")
        if (token.isNullOrEmpty()) return false to "登录响应里没有 token_field=${up.tokenField}"
        acc.session = token
        acc.lastError = null
        true to null
    }

    private fun loginByCookie(up: Upstream, acc: UpstreamAccount): Pair<Boolean, String?> {
        if (up.cookieUrl.isEmpty()) return false to "cookie_url 未配置"
        val url = if (up.cookieUrl.startsWith("http")) up.cookieUrl
        else up.baseUrl.trimEnd('/') + "/" + up.cookieUrl.trimStart('/')
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = up.timeoutSec * 1000
        conn.doInput = true
        conn.setRequestProperty("Accept", "application/json")
        // 带账号密码做基本认证（可选），或依赖 URL
        if (acc.loginid.isNotEmpty()) {
            val basic = android.util.Base64.encodeToString(
                "${acc.loginid}:${acc.password}".toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $basic")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val msg = try { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(200) } catch (e: Exception) { null }
            return false to "cookie HTTP $code ${msg ?: ""}"
        }
        // 找 Set-Cookie 里 cookieName 的值
        val cookies = conn.headerFields?.get("Set-Cookie") ?: emptyList()
        var token: String? = null
        for (c in cookies) {
            if (c.startsWith("${up.cookieName}=")) {
                token = c.substringAfter("=").substringBefore(";")
                break
            }
        }
        // 有的平台把 cookie 放在响应体 JSON
        if (token.isNullOrEmpty()) {
            try {
                val raw = conn.inputStream.readBytes().toString(Charsets.UTF_8)
                val obj = JSONObject(raw)
                token = obj.optString(up.cookieName, "")
            } catch (e: Exception) { }
        }
        if (token.isNullOrEmpty()) return false to "Set-Cookie 里没有 cookieName=${up.cookieName}"
        acc.session = token
        acc.lastError = null
        true to null
    }

    /**
     * loomy 一键签到：GET baseUrl + /points/activation 和 /points/first-login。
     * 已反编译确认：纯 GET + session 头（Authorization: Bearer <session>, token: <session>），无需 body。
     */
    private fun loomySign(up: Upstream, acc: UpstreamAccount): Boolean {
        val base = up.baseUrl.trimEnd('/')
        for (ep in listOf("/points/activation", "/points/first-login")) {
            val conn = URL(base + ep).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            if (!acc.session.isNullOrEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer ${acc.session}")
                conn.setRequestProperty("token", acc.session)
            }
            conn.setRequestProperty("loomy-version", "0.9.38")
            val code = conn.responseCode
            if (code !in 200..299) return false
        }
        return true
    }

    private fun handleProxy(session: IHTTPSession, path: String): Response {
        val c = auth(bearer(session))
        if (c == null) return err(Response.Status.UNAUTHORIZED, "invalid api key")
        acquire(c)?.let { return err(st(429), it) }

        val bodyBytes = HashMap<String, String>()
        try {
            session.parseBody(bodyBytes)
        } catch (e: Exception) {
            return err(Response.Status.BAD_REQUEST, "cannot read body")
        }
        val raw = bodyBytes["postData"] ?: return err(Response.Status.BAD_REQUEST, "empty body")
        if (raw.toByteArray(Charsets.UTF_8).size > cfg.maxBodyBytes) {
            return err(st(413), "request body too large")
        }
        val body = try { JSONObject(raw) } catch (e: Exception) {
            return err(Response.Status.BAD_REQUEST, "request body must be a JSON object")
        }

        val model = body.optString("model", "")
        val (up, modelOut) = resolve(model)
        if (!Translate.matchPatterns(c.upstreams, up.id)) {
            return err(Response.Status.FORBIDDEN, "upstream not allowed for this key")
        }
        if (!Translate.matchPatterns(c.models, model)) {
            return err(Response.Status.FORBIDDEN, "model not allowed for this key")
        }

        val effMax = clampTokens(c, body)
        val stream = body.optBoolean("stream", false)
        val t0 = System.currentTimeMillis()
        requestCount.incrementAndGet()

        val translate = up.type != "openai" && path == "/v1/chat/completions"

        return try {
            if (translate) proxyTranslated(session, path, body, model, modelOut, up, c, stream, effMax, t0)
            else proxyRaw(session, path, body, model, modelOut, up, c, stream, t0)
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            lastError = e.message
            log(JSONObject().put("client", c.id).put("up", up.id).put("model", model)
                .put("status", 502).put("ms", System.currentTimeMillis() - t0)
                .put("error", e.message ?: ""))
            err(st(502), "upstream unreachable")
        }
    }

    // ------------------------------------------------------------ 直通

    private fun proxyRaw(
        session: IHTTPSession, path: String, body: JSONObject, model: String, modelOut: String,
        up: Upstream, c: ClientConf, stream: Boolean, t0: Long
    ): Response {
        val url = if (stream) Translate.streamUrlFor(up, path, modelOut)
        else Translate.urlFor(up, path, modelOut)
        val conn = open(url, up, "POST")

        val payload = body.toString().toByteArray(Charsets.UTF_8)
        conn.outputStream.use { it.write(payload) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val msg = sanitize(code, conn.errorStream)
            log(JSONObject().put("client", c.id).put("up", up.id).put("model", model)
                .put("status", code).put("ms", System.currentTimeMillis() - t0))
            errorCount.incrementAndGet()
            lastError = "upstream $code"
            return err(st(code), msg)
        }

        if (!stream) {
            val out = conn.inputStream.readBytes()
            val (pt, ct) = usageFrom(out)
            charge(c, pt + ct)
            log(JSONObject().put("client", c.id).put("up", up.id).put("model", model)
                .put("status", code).put("ms", System.currentTimeMillis() - t0)
                .put("prompt_tokens", pt).put("completion_tokens", ct))
            return newFixedLengthResponse(Response.Status.OK,
                "application/json; charset=utf-8", out.inputStream(), out.size.toLong())
        }

        // 流式：把上游 SSE 直接透传（openai 类型无需改写），顺便统计 usage
        val resp = newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", conn.inputStream)
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("X-Accel-Buffering", "no")
        log(JSONObject().put("client", c.id).put("up", up.id).put("model", model)
            .put("status", 200).put("stream", true).put("ms", System.currentTimeMillis() - t0))
        return resp
    }

    // ------------------------------------------------------------ 转译

    private fun proxyTranslated(
        session: IHTTPSession, path: String, body: JSONObject, model: String, modelOut: String,
        up: Upstream, c: ClientConf, stream: Boolean, effMax: Int, t0: Long
    ): Response {
        val outBody = Translate.buildBody(up, body, modelOut, stream, effMax)
        val url = if (stream) Translate.streamUrlFor(up, path, modelOut)
        else Translate.urlFor(up, path, modelOut)
        val conn = open(url, up, "POST")
        conn.outputStream.use { it.write(outBody.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val msg = sanitize(code, conn.errorStream)
            log(JSONObject().put("client", c.id).put("up", up.id).put("model", model)
                .put("status", code).put("translated", up.type)
                .put("ms", System.currentTimeMillis() - t0))
            errorCount.incrementAndGet()
            lastError = "upstream $code ($up.type)"
            return err(st(code), msg)
        }

        if (!stream) {
            val raw = conn.inputStream.readBytes().toString(Charsets.UTF_8)
            val obj = try { JSONObject(raw) } catch (e: Exception) {
                return err(st(502), "upstream response not parseable")
            }
            val out = Translate.parse(up, obj, model)
            val u = out.optJSONObject("usage")
            charge(c, (u?.optInt("total_tokens", 0) ?: 0))
            log(JSONObject().put("client", c.id).put("up", up.id).put("model", model)
                .put("status", 200).put("translated", up.type)
                .put("ms", System.currentTimeMillis() - t0))
            val bytes = out.toString().toByteArray(Charsets.UTF_8)
            return newFixedLengthResponse(Response.Status.OK,
                "application/json; charset=utf-8", bytes.inputStream(), bytes.size.toLong())
        }

        // 流式：需要边读边翻译成 OpenAI SSE，用管道线程
        val pipeIn = java.io.PipedInputStream(64 * 1024)
        val pipeOut = java.io.PipedOutputStream(pipeIn)
        val writer = BufferedWriter(OutputStreamWriter(pipeOut, Charsets.UTF_8))
        val connRef = conn

        Thread {
            try {
                val (pt, ct) = Translate.streamToOpenAi(up, connRef.inputStream, writer, model)
                charge(c, pt + ct)
                log(JSONObject().put("client", c.id).put("up", up.id).put("model", model)
                    .put("status", 200).put("translated", up.type).put("stream", true)
                    .put("prompt_tokens", pt).put("completion_tokens", ct)
                    .put("ms", System.currentTimeMillis() - t0))
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                lastError = e.message
            } finally {
                try { writer.flush() } catch (e: Exception) {}
                try { pipeOut.close() } catch (e: Exception) {}
                try { connRef.disconnect() } catch (e: Exception) {}
            }
        }.start()

        val resp = newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", pipeIn)
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("X-Accel-Buffering", "no")
        return resp
    }

    // ------------------------------------------------------------ 工具

    private fun open(url: String, up: Upstream, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = up.timeoutSec * 1000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        when (up.type) {
            "anthropic" -> {
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                if (up.key.isNotEmpty()) conn.setRequestProperty("x-api-key", up.key)
            }
            "gemini" -> if (up.key.isNotEmpty()) conn.setRequestProperty("x-goog-api-key", up.key)
            "ollama" -> if (up.key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer ${up.key}")
            else -> if (up.key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer ${up.key}")
        }
        return conn
    }

    private fun sanitize(code: Int, stream: java.io.InputStream?): String {
        if (cfg.sanitizeErrors) return "upstream error (status $code)"
        return try {
            stream?.readBytes()?.toString(Charsets.UTF_8)?.take(800) ?: "upstream error (status $code)"
        } catch (e: Exception) {
            "upstream error (status $code)"
        }
    }

    private fun usageFrom(raw: ByteArray): Pair<Int, Int> {
        return try {
            val o = JSONObject(raw.toString(Charsets.UTF_8))
            val u = o.optJSONObject("usage") ?: JSONObject()
            u.optInt("prompt_tokens", 0) to u.optInt("completion_tokens", 0)
        } catch (e: Exception) {
            (raw.size / 4) to 0
        }
    }

    private fun json(status: Response.IStatus, o: JSONObject): Response {
        val b = o.toString().toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(status, "application/json; charset=utf-8", b.inputStream(), b.size.toLong())
    }

    private fun err(status: Response.IStatus, msg: String): Response {
        errorCount.incrementAndGet()
        return json(status, JSONObject().put("error", JSONObject().put("message", msg)))
    }
}