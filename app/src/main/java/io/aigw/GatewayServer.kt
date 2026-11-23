package io.aigw

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内嵌网关服务器（移植 server.py 的路由逻辑）。
 *
 * 路由：
 *  - GET  /panel           → 面板 JSON（Compose 用）
 *  - GET  /api/panel/state → 面板状态（账号+额度+模型）
 *  - GET  /api/panel/logs  → 日志
 *  - GET  /v1/models       → 模型列表
 *  - POST /v1/chat/completions → OpenAI 兼容（loomy 上游 + 多提供商）
 *  - POST /v1/messages     → Anthropic 兼容
 *  - POST /v1/embeddings   → 透传
 *  - POST /v1/images/generations → 透传
 *  - POST /api/panel/accounts* → 账号增删改
 *
 * 落地页就是面板（/ 和 /panel 都返回面板），照 loomy。
 */
class GatewayServer(
    private val port: Int,
    private val pool: AccountPool,
) : NanoHTTPD(port) {

    private val logBuffer = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val logSeq = AtomicInteger(0)
    private var models: List<Map<String, Any?>> = ModelGateway.fallbackModels()

    fun log(msg: String) {
        val line = "[${fmtTime(System.currentTimeMillis() / 1000)}] $msg"
        logBuffer.addLast(line)
        while (logBuffer.size > 500) logBuffer.pollFirst()
        println(line)
    }

    // -------------------------------------------------------------- lifecycle

    fun startGateway() {
        pool.load()
        pool.bootstrap()
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            log("网关已启动: 127.0.0.1:$port")
            refreshModels()
        } catch (e: Exception) {
            log("网关启动失败: ${e.message}")
        }
    }

    fun refreshModels() {
        try {
            val acc = pool.acquire()
            val payload = ModelGateway.models(acc.effectiveToken())
            val data = payload.optJSONArray("data")
            if (data != null && data.length() > 0) {
                models = (0 until data.length()).map { idx ->
                    val m = data.getJSONObject(idx)
                    mapOf("id" to m.optString("id"), "name" to m.optString("name", m.optString("id")),
                        "multiplier" to m.optDouble("multiplier", 0.0),
                        "context_length" to m.optLong("context_length", 0L),
                        "modalities" to listOf("text"))
                }
                log("上游模型列表已刷新: ${models.size} 个")
            }
        } catch (e: Exception) {
            log("[warn] 拉取模型列表失败，使用内置清单: ${e.message}")
            models = ModelGateway.fallbackModels()
        }
    }

    private fun catalogue(): List<Map<String, Any?>> = models.ifEmpty { ModelGateway.fallbackModels() }

    // -------------------------------------------------------------- NanoHTTPD

    override fun serve(session: IHTTPSession): Response {
        val path = normalizePath(session.uri)
        return try {
            when {
                path == "/panel" || path == "/" -> panelPage()
                path == "/v1/health" || path == "/health" -> health()
                path == "/v1/models" -> modelsJson()
                path == "/v1/points" -> pointsJson()
                path == "/api/panel/state" -> panelState()
                path == "/api/panel/logs" -> panelLogs()
                path.startsWith("/api/panel/") -> panelApi(session, path)
                path == "/v1/chat/completions" && session.method == Method.POST -> chatCompletions(session)
                path == "/v1/messages" && session.method == Method.POST -> messages(session)
                path == "/v1/embeddings" && session.method == Method.POST -> passthrough(session, "embeddings")
                path == "/v1/images/generations" && session.method == Method.POST -> passthrough(session, "images/generations")
                else -> json(404, JSONObject().put("error", JSONObject().put("message", "未知路径: $path").put("type", "invalid_request_error").put("code", 404)))
            }
        } catch (e: PoolError) {
            json(503, JSONObject().put("error", JSONObject().put("message", e.message).put("type", "account_pool_error").put("code", 503)))
        } catch (e: Exception) {
            log("[error] ${session.method} ${session.uri}: ${e.message}")
            json(502, JSONObject().put("error", JSONObject().put("message", "上游调用失败: ${e.message}").put("type", "upstream_error").put("code", 502)))
        }
    }

    private fun normalizePath(uri: String): String {
        var p = uri.split("?")[0]
        while (p.contains("/v1/v1")) p = p.replace("/v1/v1", "/v1")
        if (p == "" || p == "/" || p == "/index.html") return "/panel"
        val exempt = listOf("/panel", "/api/", "/health", "/admin", "/favicon")
        if (!exempt.any { p.startsWith(it) }) {
            if (p != "/v1" && !p.startsWith("/v1/")) p = "/v1" + p
        }
        return p.trimEnd('/').ifEmpty { "/" }
    }

    // -------------------------------------------------------------- responses

    private fun json(status: Int, obj: JSONObject): Response {
        val body = obj.toString().toByteArray(Charsets.UTF_8)
        val resp = newFixedLengthResponse(Response.Status.lookup(status), "application/json; charset=utf-8", ByteArrayInputStream(body), body.size.toLong())
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun panelPage(): Response {
        val obj = JSONObject()
        obj.put("ok", true)
        obj.put("name", "aigw 网关")
        obj.put("port", port)
        obj.put("panel", "http://127.0.0.1:$port/panel")
        obj.put("accounts", pool.snapshot().size)
        obj.put("usable", pool.usable().size)
        val body = obj.toString().toByteArray(Charsets.UTF_8)
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", ByteArrayInputStream(body), body.size.toLong())
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun health(): Response {
        val obj = JSONObject()
        obj.put("status", if (pool.usable().isNotEmpty()) "ok" else "degraded")
        obj.put("name", "aigw")
        obj.put("accounts", pool.snapshot().size)
        obj.put("usable_accounts", pool.usable().size)
        obj.put("models", catalogue().size)
        obj.put("panel", "http://127.0.0.1:$port/panel")
        return json(200, obj)
    }

    private fun modelsJson(): Response {
        val arr = JSONArray()
        for (m in catalogue()) {
            val o = JSONObject()
            o.put("id", m["id"])
            o.put("name", m["name"])
            o.put("object", "model")
            o.put("owned_by", "loomy")
            arr.put(o)
        }
        val obj = JSONObject()
        obj.put("object", "list")
        obj.put("data", arr)
        return json(200, obj)
    }

    private fun pointsJson(): Response {
        val arr = JSONArray()
        var total = 0L
        val seen = HashMap<String, Long>()
        for (acc in pool.snapshot()) {
            pool.refreshQuota(acc)
            val o = JSONObject()
            o.put("name", acc.name)
            o.put("userid", acc.userid)
            o.put("available", acc.available)
            o.put("balance", acc.balance)
            o.put("requests", acc.requests)
            o.put("points_used", acc.pointsUsed)
            arr.put(o)
            val key = acc.userid.ifEmpty { acc.name }
            val avail = acc.available ?: 0L
            if (avail > 0) seen[key] = maxOf(seen[key] ?: 0, avail)
        }
        total = seen.values.sum()
        val obj = JSONObject()
        obj.put("total_available", total)
        obj.put("unique_accounts", seen.size)
        obj.put("accounts", arr)
        return json(200, obj)
    }

    private fun panelState(): Response {
        val obj = JSONObject()
        val accounts = JSONArray()
        for (acc in pool.snapshot()) {
            val a = JSONObject()
            a.put("name", acc.name)
            a.put("provider", acc.provider)
            a.put("loginid", acc.loginid)
            a.put("enabled", acc.enabled)
            a.put("has_password", acc.password.isNotEmpty())
            a.put("session_days_left", acc.daysLeft()?.let { Math.round(it * 100) / 100.0 })
            a.put("available", acc.available)
            a.put("balance", acc.balance)
            a.put("requests", acc.requests)
            a.put("failures", acc.failures)
            a.put("in_cooldown", acc.inCooldown())
            a.put("last_error", acc.lastError)
            a.put("source", acc.source)
            accounts.put(a)
        }
        obj.put("accounts", accounts)
        obj.put("total_available", pool.snapshot().sumOf { it.available ?: 0L })
        obj.put("models", JSONArray(catalogue().map { it["id"] }))
        obj.put("port", port)
        return json(200, obj)
    }

    private fun panelLogs(): Response {
        val arr = JSONArray()
        for (line in logBuffer) arr.put(line)
        val obj = JSONObject()
        obj.put("logs", arr)
        return json(200, obj)
    }

    // -------------------------------------------------------------- panel api

    private fun panelApi(session: IHTTPSession, path: String): Response {
        val payload = JSONObject(readBody(session))
        val result = when (path) {
            "/api/panel/accounts" -> {
                val name = payload.optString("name", "account${pool.snapshot().size + 1}")
                val provider = payload.optString("provider", "loomy")
                val acc = LoomyAccount(
                    name = name,
                    loginid = payload.optString("loginid", ""),
                    password = payload.optString("password", ""),
                    provider = provider,
                    token = payload.optString("token", ""),
                    cookies = payload.optString("cookies", ""),
                    userid = payload.optString("userid", ""),
                    expireAt = payload.optLong("expireAt", 0L),
                )
                val added = pool.add(acc)
                if (added) {
                    // 若是 loomy 且带密码，尝试自动登录拉 session
                    if (provider == "loomy" && acc.loginid.isNotEmpty() && acc.password.isNotEmpty()) {
                        try {
                            LoomyClient().fill(acc)
                            pool.save(true)
                        } catch (e: Exception) {
                            acc.lastError = e.message ?: "login failed"
                        }
                    }
                    JSONObject().put("ok", true).put("result", acc.publicDict())
                } else {
                    JSONObject().put("ok", false).put("error", "账号已存在")
                }
            }
            "/api/panel/accounts/remove" -> {
                val name = payload.optString("name", "")
                JSONObject().put("ok", pool.remove(name))
            }
            "/api/panel/refresh" -> {
                for (acc in pool.snapshot()) pool.refreshQuota(acc)
                pool.save(true)
                JSONObject().put("ok", true)
            }
            "/api/panel/accounts/renew" -> {
                val name = payload.optString("name", "")
                val acc = pool.get(name)
                if (acc != null) {
                    try {
                        LoomyClient().fill(acc)
                        pool.save(true)
                        JSONObject().put("ok", true).put("result", acc.publicDict())
                    } catch (e: Exception) {
                        JSONObject().put("ok", false).put("error", e.message)
                    }
                } else {
                    JSONObject().put("ok", false).put("error", "账号不存在")
                }
            }
            else -> JSONObject().put("ok", false).put("error", "未知面板接口: $path")
        }
        return json(200, result)
    }

    // -------------------------------------------------------------- chat core

    private fun chatCompletions(session: IHTTPSession): Response {
        val req = JSONObject(readBody(session))
        val model = resolveModel(req.optString("model", ""))
        val stream = req.optBoolean("stream", false)
        req.put("model", model)

        if (stream) return chatStream(req)

        val tried = mutableListOf<String>()
        var lastStatus = 502
        var lastBody = "{}"
        for (i in 0..2) {
            try {
                val acc = pool.acquire(exclude = tried)
                tried.add(acc.name)
                val started = System.currentTimeMillis()
                val (status, body) = ModelGateway.call(acc.effectiveToken(), "chat/completions", req)
                if (status == 200) {
                    pool.reportSuccess(acc)
                    log("[call] ${acc.name} $model ${(System.currentTimeMillis() - started) / 1000.0}s http=200")
                    return json(200, JSONObject(body))
                }
                lastStatus = status
                lastBody = body
                if (status == 401 || status == 403 || status == 429) {
                    pool.reportFailure(acc, status, body)
                }
                if (status == 400 || status == 404 || status == 422) break
            } catch (e: PoolError) {
                log("[warn] ${e.message}")
                break
            }
        }
        return try {
            json(lastStatus, JSONObject(lastBody))
        } catch (e: Exception) {
            json(lastStatus, JSONObject().put("error", JSONObject().put("message", lastBody.take(300)).put("type", "upstream_error").put("code", lastStatus)))
        }
    }

    private fun chatStream(req: JSONObject): Response {
        // 流式：转发给上游 SSE，逐行透传
        val tried = mutableListOf<String>()
        var lastStatus = 502
        var lastBody = "{}"
        for (i in 0..2) {
            try {
                val acc = pool.acquire(exclude = tried)
                tried.add(acc.name)
                val sr = ModelGateway.openStream(acc.effectiveToken(), "chat/completions", req)
                if (sr.status == 200 && sr.input != null) {
                    pool.reportSuccess(acc)
                    val resp = newChunkedResponse(Response.Status.OK, "text/event-stream", sr.input)
                    resp.addHeader("Access-Control-Allow-Origin", "*")
                    resp.addHeader("Cache-Control", "no-cache")
                    return resp
                }
                lastStatus = sr.status
                lastBody = sr.errorBody ?: "{}"
                if (sr.status == 401 || sr.status == 403 || sr.status == 429) {
                    pool.reportFailure(acc, sr.status, lastBody)
                }
            } catch (e: PoolError) {
                log("[warn] ${e.message}")
                break
            }
        }
        return json(lastStatus, JSONObject().put("error", JSONObject().put("message", lastBody.take(300)).put("type", "upstream_error").put("code", lastStatus)))
    }

    private fun messages(session: IHTTPSession): Response {
        // Anthropic 兼容：把 /v1/messages 转成 loomy 的 chat/completions 再透传
        val req = JSONObject(readBody(session))
        val model = resolveModel(req.optString("model", ""))
        val messages = req.optJSONArray("messages") ?: JSONArray()
        // 简化的转换：system 提出来，messages 直接映射
        val oaiMessages = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role", "user")
            val content = m.opt("content")
            val oai = JSONObject()
            oai.put("role", if (role == "assistant") "assistant" else "user")
            oai.put("content", if (content is JSONArray) {
                // 数组内容（多段）提取文本
                val sb = StringBuilder()
                for (j in 0 until content.length()) {
                    val seg = content.getJSONObject(j)
                    if (seg.optString("type") == "text") sb.append(seg.optString("text"))
                }
                sb.toString()
            } else content.toString())
            oaiMessages.put(oai)
        }
        val oaiReq = JSONObject()
        oaiReq.put("model", model)
        oaiReq.put("messages", oaiMessages)
        oaiReq.put("stream", req.optBoolean("stream", false))
        if (req.has("max_tokens")) oaiReq.put("max_tokens", req.optInt("max_tokens"))

        try {
            val acc = pool.acquire()
            val (status, body) = ModelGateway.call(acc.effectiveToken(), "chat/completions", oaiReq)
            if (status == 200) {
                pool.reportSuccess(acc)
                return json(200, JSONObject(body))
            }
            pool.reportFailure(acc, status, body)
            return json(status, JSONObject().put("error", JSONObject().put("message", body.take(300)).put("type", "upstream_error").put("code", status)))
        } catch (e: PoolError) {
            return json(503, JSONObject().put("error", JSONObject().put("message", e.message).put("type", "account_pool_error").put("code", 503)))
        }
    }

    private fun passthrough(session: IHTTPSession, suffix: String): Response {
        val raw = readBody(session)
        val payload = if (raw.isNotEmpty()) JSONObject(raw) else JSONObject()
        try {
            val acc = pool.acquire()
            val (status, body) = ModelGateway.call(acc.effectiveToken(), suffix, payload)
            if (status in 200..299) {
                pool.reportSuccess(acc)
                return json(status, JSONObject(body))
            }
            pool.reportFailure(acc, status, body)
            return json(status, JSONObject().put("error", JSONObject().put("message", body.take(300)).put("type", "upstream_error").put("code", status)))
        } catch (e: PoolError) {
            return json(503, JSONObject().put("error", JSONObject().put("message", e.message).put("type", "account_pool_error").put("code", 503)))
        }
    }

    private fun resolveModel(requested: String): String {
        var name = requested.trim()
        if (name.contains("/")) name = name.substringAfter("/")
        return name.ifEmpty { LoomyConstants.DEFAULT_MODEL }
    }

    private fun readBody(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (length == 0L) return ""
        val buffer = ByteArray(length.toInt())
        session.inputStream.read(buffer)
        return String(buffer, Charsets.UTF_8)
    }
}