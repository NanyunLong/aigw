package io.aigw

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 配置模型。全部从 filesDir/config.json 读取，字段与 Python 版保持一致，
 * 方便同一份配置在两边通用（除 listen 字段在 Android 上仅取 port）。
 *
 * v2.1 扩展：
 *  - Upstream 支持 type = openai | anthropic | gemini | ollama | loomy | custom
 *  - Upstream.authMode = none | password | cookie | apikey
 *  - Upstream.accounts 账号池（登录式上游自动轮换）
 *  - Upstream.cachedModels 登录后拉取的模型清单缓存
 */
data class Upstream(
    val id: String,
    val type: String,               // openai | anthropic | gemini | ollama | loomy | custom
    val baseUrl: String,
    val stripV1: Boolean,
    val timeoutSec: Int,
    val key: String,
    // ---- v2.1 登录式扩展 ----
    val authMode: String = "none",  // none | password | cookie | apikey
    val loginUrl: String = "",
    val usernameField: String = "username",
    val passwordField: String = "password",
    val tokenField: String = "token",
    val cookieUrl: String = "",
    val cookieName: String = "",
    val modelsUrl: String = "",
    val quotaUrl: String = "",
    val accounts: MutableList<UpstreamAccount> = ArrayList()
) {
    /** 是否有账号池（登录式上游才支持）。 */
    fun hasAccountPool(): Boolean = type == "loomy" || type == "custom"

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("type", type)
        o.put("base_url", baseUrl)
        o.put("strip_v1_prefix", stripV1)
        o.put("timeout_sec", timeoutSec)
        if (key.isNotEmpty()) o.put("api_key", key)
        o.put("auth", JSONObject()
            .put("mode", authMode)
            .put("login_url", loginUrl)
            .put("username_field", usernameField)
            .put("password_field", passwordField)
            .put("token_field", tokenField)
            .put("cookie_url", cookieUrl)
            .put("cookie_name", cookieName)
            .put("models_url", modelsUrl)
            .put("quota_url", quotaUrl))
        val accArr = JSONArray()
        for (a in accounts) accArr.put(a.toJson())
        if (accArr.length() > 0) o.put("accounts", accArr)
        return o
    }
}

/**
 * 账号池里的一个账号。登录式上游（loomy/custom）用它做轮换。
 */
data class UpstreamAccount(
    val name: String,
    var loginid: String = "",
    var password: String = "",
    var session: String? = null,
    var enabled: Boolean = true,
    var upstream: String = "",
    var balance: Double = 0.0,
    var dailyBalance: Double = 0.0,
    var available: Double = 0.0,
    var requests: Long = 0,
    var pointsUsed: Double = 0.0,
    var cooldownUntil: Long = 0,
    var lastError: String? = null,
    var lastUsed: Long = 0
) {
    fun usable(): Boolean = enabled && !session.isNullOrEmpty() && available != 0.0
            && System.currentTimeMillis() >= cooldownUntil

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        if (loginid.isNotEmpty()) o.put("loginid", loginid)
        if (password.isNotEmpty()) o.put("password", password)
        if (!session.isNullOrEmpty()) o.put("session", session)
        o.put("enabled", enabled)
        o.put("upstream", upstream)
        o.put("balance", balance)
        o.put("daily_balance", dailyBalance)
        o.put("available", available)
        o.put("requests", requests)
        o.put("points_used", pointsUsed)
        return o
    }

    fun toPanelJson(upstreamId: String): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("upstream", upstreamId)
        o.put("loginid", loginid)
        o.put("enabled", enabled)
        o.put("session", session != null)
        o.put("available", available)
        o.put("balance", balance)
        o.put("daily_balance", dailyBalance)
        o.put("requests", requests)
        o.put("points_used", pointsUsed)
        o.put("in_cooldown", System.currentTimeMillis() < cooldownUntil)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): UpstreamAccount {
            return UpstreamAccount(
                name = o.optString("name", ""),
                loginid = o.optString("loginid", ""),
                password = o.optString("password", ""),
                session = if (o.has("session") && !o.isNull("session")) o.optString("session") else null,
                enabled = o.optBoolean("enabled", true),
                upstream = o.optString("upstream", ""),
                balance = o.optDouble("balance", 0.0),
                dailyBalance = o.optDouble("daily_balance", 0.0),
                available = o.optDouble("available", 0.0),
                requests = o.optLong("requests", 0),
                pointsUsed = o.optDouble("points_used", 0.0),
                cooldownUntil = o.optLong("cooldown_until", 0),
                lastError = if (o.has("last_error") && !o.isNull("last_error")) o.optString("last_error") else null,
                lastUsed = o.optLong("last_used", 0)
            )
        }
    }
}

data class ClientConf(
    val id: String,
    val keyHash: String,
    val qps: Double,
    val dailyTokens: Long,
    val maxTokens: Int,
    val models: List<String>,
    val upstreams: List<String>,
    val admin: Boolean,
    val enabled: Boolean
)

class GatewayConfig(
    val host: String,
    val port: Int,
    val allowPublicListen: Boolean,
    val upstreams: LinkedHashMap<String, Upstream>,
    val defaultUpstream: String,
    val hardMaxTokens: Int,
    val defaultMaxTokens: Int,
    val forceDefaultMaxTokens: Boolean,
    val maxBodyBytes: Long,
    val maxResponseBytes: Long,
    val sanitizeErrors: Boolean,
    val allowAnonymous: Boolean,
    val logPath: String,
    val logBody: Boolean,
    val clients: LinkedHashMap<String, ClientConf>   // key: 小写 sha256
) {
    companion object {
        fun sha256(s: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in d) sb.append("%02x".format(b))
            return sb.toString()
        }

        fun load(f: File): GatewayConfig {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val listen = root.optJSONObject("listen") ?: JSONObject()
            val lim = root.optJSONObject("limits") ?: JSONObject()
            val lg = root.optJSONObject("logging") ?: JSONObject()

            val ups = LinkedHashMap<String, Upstream>()
            val arr = root.optJSONArray("upstreams") ?: throw IllegalArgumentException("配置缺少 upstreams 数组")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id", "default")
                val type = o.optString("type", "openai").lowercase()
                require(type in listOf("openai", "anthropic", "gemini", "ollama", "loomy", "custom")) {
                    "upstream $id 的 type 不支持：$type"
                }
                val base = o.optString("base_url", "").trimEnd('/')
                require(base.startsWith("http")) { "upstream $id 的 base_url 必须以 http(s) 开头" }
                val envName = o.optString("api_key_env", "")
                val envKey = if (envName.isNotEmpty()) System.getenv(envName) ?: "" else ""
                val auth = o.optJSONObject("auth") ?: JSONObject()
                val accounts = ArrayList<UpstreamAccount>()
                o.optJSONArray("accounts")?.let { a ->
                    for (k in 0 until a.length()) accounts.add(UpstreamAccount.fromJson(a.getJSONObject(k)))
                }
                ups[id] = Upstream(
                    id = id,
                    type = type,
                    baseUrl = base,
                    stripV1 = o.optBoolean("strip_v1_prefix", true),
                    timeoutSec = o.optInt("timeout_sec", 300),
                    key = envKey.ifEmpty { o.optString("api_key", "") },
                    authMode = auth.optString("mode", "none").lowercase(),
                    loginUrl = auth.optString("login_url", ""),
                    usernameField = auth.optString("username_field", "username"),
                    passwordField = auth.optString("password_field", "password"),
                    tokenField = auth.optString("token_field", "token"),
                    cookieUrl = auth.optString("cookie_url", ""),
                    cookieName = auth.optString("cookie_name", ""),
                    modelsUrl = auth.optString("models_url", ""),
                    quotaUrl = auth.optString("quota_url", ""),
                    accounts = accounts
                )
            }
            require(ups.isNotEmpty()) { "配置缺少 upstreams" }

            val clients = LinkedHashMap<String, ClientConf>()
            val cArr = root.optJSONArray("clients") ?: throw IllegalArgumentException("配置缺少 clients 数组")
            for (i in 0 until cArr.length()) {
                val o = cArr.getJSONObject(i)
                val id = o.optString("id", "unnamed")
                val plain = o.optString("key", "")
                val hash = when {
                    plain.isNotEmpty() -> sha256(plain)
                    o.has("key_sha256") -> o.getString("key_sha256").lowercase()
                    else -> throw IllegalArgumentException("client $id 既没 key 也没 key_sha256")
                }
                val models = ArrayList<String>()
                o.optJSONArray("models")?.let { for (k in 0 until it.length()) models.add(it.getString(k)) }
                val upsList = ArrayList<String>()
                o.optJSONArray("upstreams")?.let { for (k in 0 until it.length()) upsList.add(it.getString(k)) }
                clients[hash] = ClientConf(
                    id = id,
                    keyHash = hash,
                    qps = o.optDouble("qps", 0.0),
                    dailyTokens = o.optLong("daily_tokens", 0L),
                    maxTokens = o.optInt("max_tokens", 0),
                    models = if (models.isEmpty()) listOf("*") else models,
                    upstreams = if (upsList.isEmpty()) listOf("*") else upsList,
                    admin = o.optBoolean("admin", false),
                    enabled = o.optBoolean("enabled", true)
                )
            }

            val defaultValue = ups.keys.first()
            val def = root.optString("default_upstream", defaultValue)
            require(ups.containsKey(def)) { "default_upstream=$def 不存在" }

            return GatewayConfig(
                host = listen.optString("host", "127.0.0.1"),
                port = listen.optInt("port", 8787),
                allowPublicListen = root.optBoolean("allow_public_listen", false),
                upstreams = ups,
                defaultUpstream = def,
                hardMaxTokens = lim.optInt("hard_max_tokens", 4096),
                defaultMaxTokens = lim.optInt("default_max_tokens", 1024),
                forceDefaultMaxTokens = lim.optBoolean("force_default_max_tokens", false),
                maxBodyBytes = lim.optLong("max_body_bytes", 2L * 1024 * 1024),
                maxResponseBytes = lim.optLong("max_response_bytes", 32L * 1024 * 1024),
                sanitizeErrors = root.optBoolean("sanitize_errors", true),
                allowAnonymous = root.optBoolean("allow_anonymous", false),
                logPath = lg.optString("path", "aigw.log.jsonl"),
                logBody = lg.optBoolean("log_body", false),
                clients = clients
            )
        }

        /** 写一份默认配置，首次启动用。 */
        fun writeDefault(f: File) {
            val s = """
            {
              "listen": { "host": "127.0.0.1", "port": 8787 },
              "allow_public_listen": false,
              "upstreams": [
                { "id": "local", "type": "openai", "base_url": "http://127.0.0.1:8080/v1",
                  "strip_v1_prefix": true, "timeout_sec": 300, "api_key": "" }
              ],
              "default_upstream": "local",
              "limits": {
                "max_body_bytes": 2097152,
                "max_response_bytes": 33554432,
                "hard_max_tokens": 4096,
                "default_max_tokens": 1024,
                "force_default_max_tokens": false
              },
              "sanitize_errors": true,
              "allow_anonymous": false,
              "logging": { "path": "aigw.log.jsonl", "log_body": false },
              "clients": [
                { "id": "me", "key": "sk-local-CHANGE-ME", "qps": 2,
                  "daily_tokens": 200000, "max_tokens": 2048, "models": ["*"] }
              ]
            }
            """.trimIndent()
            f.writeText(s, Charsets.UTF_8)
        }
    }
}