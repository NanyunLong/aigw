package dev.aigw.core.provider.qwen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.jwtClaim
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 通义千问 Qwen（`chat.qwen.ai`）。
 *
 * 登录是 OAuth 设备码流程（RFC 8628）：App 请求 `device/code` 端点拿到
 * device_code + user_code + 授权链接，用户在系统浏览器打开授权链接登录，
 * App 用 device_code 轮询 `token` 端点换取 access_token。
 *
 * 对话走 OpenAI 兼容模式（`api.qwen.ai/compatible-mode/v1`），
 * 请求体与响应均为标准 OpenAI 格式，无需协议转换。
 */
class QwenProvider internal constructor(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val hooks: ProviderHooks = ProviderHooks(),
) : Provider, DeviceCodeSupport {

    override val id: String = ID
    override val displayName: String = "Qwen 千问"
    override val authKind: AuthKind = AuthKind.DEVICE_CODE

    // ------------------------------------------------------------------ 登录（设备码）

    override fun startDeviceAuth(region: String): DeviceAuthTicket {
        val (codeVerifier, codeChallenge) = pkcePair()
        val body = formEncode(
            linkedMapOf(
                "client_id" to CLIENT_ID,
                "scope" to SCOPE,
                "code_challenge" to codeChallenge,
                "code_challenge_method" to "S256",
            ),
        )
        val (status, text) = postForm(DEVICE_CODE_ENDPOINT, body)
        if (status !in 200..299) {
            throw IllegalStateException("请求设备码失败（HTTP $status）：${text.take(160)}")
        }
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("设备码响应不是合法 JSON：${text.trim().take(160)}")
        val deviceCode = obj.get("device_code")?.asString.orEmpty()
        val userCode = obj.get("user_code")?.asString.orEmpty()
        var loginUrl = obj.get("verification_uri_complete")?.asString.orEmpty()
        if (loginUrl.isEmpty()) {
            val uri = obj.get("verification_uri")?.asString.orEmpty()
            if (uri.isEmpty()) throw IllegalStateException("设备码响应缺少 verification_uri")
            loginUrl = "$uri?user_code=$userCode"
        }
        if (deviceCode.isEmpty()) throw IllegalStateException("设备码响应缺少 device_code")
        // 对齐官方 CLI：给授权链接追加 tracking 参数
        loginUrl = withTracking(loginUrl)
        val state = encodeState(deviceCode, codeVerifier)
        return DeviceAuthTicket(loginUrl = loginUrl, state = state)
    }

    override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll {
        val (deviceCode, codeVerifier) = decodeState(state)
            ?: return DeviceAuthPoll.Failed("state 无法解析，请重新登录")
        val body = formEncode(
            linkedMapOf(
                "grant_type" to GRANT_TYPE_DEVICE,
                "client_id" to CLIENT_ID,
                "device_code" to deviceCode,
                "code_verifier" to codeVerifier,
            ),
        )
        val (status, text) = postForm(TOKEN_ENDPOINT, body)
        // 上游用 400 + error 字段表示「还没授权」，不能当失败
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        val error = obj?.get("error")?.asString.orEmpty()
        if (error.isNotEmpty()) {
            if (error == "authorization_pending" || error == "slow_down") return DeviceAuthPoll.Pending
            return DeviceAuthPoll.Failed("轮询失败：$error ${obj?.get("error_description")?.asString.orEmpty()}")
        }
        if (status !in 200..299) {
            return DeviceAuthPoll.Failed("轮询失败（HTTP $status）：${text.take(160)}")
        }
        val credential = credentialOf(obj)
        if (credential.accessToken.isEmpty()) {
            return DeviceAuthPoll.Failed("轮询响应里没有 access_token")
        }
        return DeviceAuthPoll.Success(toProviderAccount(credential))
    }

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val credential = account?.let { parse(it) }
            ?: return ProviderModelCatalogView(emptyList(), fromFallback = false, error = "")
        return try {
            val models = fetchModels(credential)
            if (models.isEmpty()) {
                ProviderModelCatalogView(emptyList(), fromFallback = false, error = "上游返回空模型列表")
            } else {
                ProviderModelCatalogView(models, fromFallback = false, error = "")
            }
        } catch (e: Exception) {
            ProviderModelCatalogView(emptyList(), fromFallback = false, error = e.message ?: "拉取模型失败")
        }
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return DEFAULT_MODEL
        return model
    }

    override fun hosts(): List<String> = listOf("chat.qwen.ai", "api.qwen.ai")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = parse(account) ?: return FailedChatCall(401, "凭证无法解析")
        val streaming = isStreamingBody(openAiBody)
        val model = resolveModel(requestedModelOf(openAiBody))
        val body = withModel(openAiBody, model)

        val conn = try {
            HttpSupport.open(
                "${chatBase(credential)}/chat/completions",
                "POST",
                chatHeaders(credential, streaming),
                body,
                CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        if (status !in 200..299) {
            val errorBody = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errorBody)
        }

        val contentType = conn.contentType.orEmpty()
        val stream = conn.inputStream
        if (contentType.contains("event-stream", ignoreCase = true)) {
            if (streaming) {
                return StreamingChatCall(status, stream) { conn.disconnect() }
            }
            val aggregated = try {
                OpenAiSseAggregator.aggregate(stream, model)
            } finally {
                conn.disconnect()
            }
            return AggregatedChatCall(status, aggregated)
        }

        val text = runCatching { stream.use { HttpSupport.readLimited(it) } }.getOrDefault("")
        conn.disconnect()
        if (text.isBlank()) {
            return StreamFailureChatCall(
                status,
                UpstreamError(ErrorKind.CLIENT, "上游响应为空（HTTP $status，Content-Type: ${contentType.ifEmpty { "—" }}）"),
            )
        }
        if (streaming) {
            return StreamingChatCall(
                status,
                OpenAiSseAggregator.completionAsSse(text).byteInputStream(Charsets.UTF_8),
            )
        }
        return AggregatedChatCall(status, text)
    }

    // ------------------------------------------------------------------ 刷新

    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = parse(account) ?: return null
        val nowSeconds = nowMillis() / 1000
        if (credential.expiresAt > 0 && credential.expiresAt - nowSeconds > skewSeconds) return null
        if (credential.refreshToken.isEmpty()) return null
        val body = formEncode(
            linkedMapOf(
                "grant_type" to "refresh_token",
                "client_id" to CLIENT_ID,
                "refresh_token" to credential.refreshToken,
            ),
        )
        val (status, text) = postForm(TOKEN_ENDPOINT, body)
        if (status !in 200..299) {
            hooks.onLog("Qwen 刷新凭证失败（HTTP $status）：${text.take(160)}")
            return null
        }
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val parsed = credentialOf(obj)
        if (parsed.accessToken.isEmpty()) return null
        val refreshed = credential.copy(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken.ifEmpty { credential.refreshToken },
            expiresAt = parsed.expiresAt,
            resourceUrl = parsed.resourceUrl.ifEmpty { credential.resourceUrl },
            uid = parsed.uid.ifEmpty { credential.uid },
            nickname = parsed.nickname.ifEmpty { credential.nickname },
        )
        return toProviderAccount(refreshed)
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 429 -> ErrorKind.SOFT_RATE
            status in 500..599 -> ErrorKind.SERVER
            status == 404 -> ErrorKind.NOT_FOUND
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, "Qwen 上游 HTTP $status")
    }

    /** 粘贴凭证 JSON 导入：直接当凭证用。 */
    override fun importCredentials(raw: String): ProviderAccount? {
        val obj = runCatching { JsonParser.parseString(raw.trim()).asJsonObject }.getOrNull() ?: return null
        val credential = credentialOf(obj)
        if (credential.accessToken.isEmpty()) return null
        return toProviderAccount(credential)
    }

    // ------------------------------------------------------------------ 内部

    private fun postForm(url: String, body: String): Pair<Int, String> {
        val conn = HttpSupport.open(
            url, "POST",
            deviceHeaders(),
            body,
            CONNECT_TIMEOUT_MS,
            READ_TIMEOUT_MS,
        )
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun deviceHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/x-www-form-urlencoded",
        "Accept" to "application/json",
        "User-Agent" to CLIENT_UA,
        "Origin" to BASE,
        "Referer" to "$BASE/",
    )

    private fun chatHeaders(credential: Credential, streaming: Boolean): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", if (streaming) "text/event-stream" else "application/json")
        put("Authorization", "Bearer ${credential.accessToken}")
        put("User-Agent", CLIENT_UA)
    }

    private fun fetchModels(credential: Credential): List<ProviderModel> {
        val conn = try {
            HttpSupport.open(
                "${chatBase(credential)}/models",
                "GET",
                mapOf("Authorization" to "Bearer ${credential.accessToken}", "User-Agent" to CLIENT_UA),
                null,
                CONNECT_TIMEOUT_MS,
                MODELS_READ_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            throw IllegalStateException("模型接口请求失败：${e.message}", e)
        }
        return try {
            val status = conn.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("模型接口 HTTP $status")
            }
            val text = HttpSupport.readBody(conn)
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                ?: throw IllegalStateException("模型响应不是合法 JSON")
            val data = obj.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            if (data == null) return fallbackModels()
            data.mapNotNull { element ->
                val m = element.asJsonObject
                val id = m.get("id")?.asString.orEmpty()
                if (id.isEmpty()) null else ProviderModel(id, id)
            }.ifEmpty { fallbackModels() }
        } finally {
            conn.disconnect()
        }
    }

    private fun fallbackModels(): List<ProviderModel> = listOf(
        ProviderModel(DEFAULT_MODEL, "Qwen3.8 Max（订阅默认）"),
        ProviderModel("qwen3-max", "Qwen3 Max"),
        ProviderModel("qwen3-flash", "Qwen3 Flash"),
    )

    private fun withModel(body: String, model: String): String {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: JsonObject()
        obj.addProperty("model", model)
        return obj.toString()
    }

    private fun parse(account: ProviderAccount): Credential? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull() ?: return null
        return credentialOf(obj).takeIf { it.accessToken.isNotEmpty() }
    }

    private fun credentialOf(obj: JsonObject?): Credential {
        val o = obj ?: JsonObject()
        // 兼容 camelCase（存储格式）与 snake_case（上游响应）两套命名
        val accessToken = o.get("access_token")?.asString
            .orEmpty()
            .ifEmpty { o.get("accessToken")?.asString.orEmpty() }
        return Credential(
            accessToken = accessToken,
            refreshToken = o.get("refresh_token")?.asString.orEmpty()
                .ifEmpty { o.get("refreshToken")?.asString.orEmpty() },
            expiresAt = o.get("expires_in")?.asLong?.takeIf { it > 0 }
                ?.let { nowMillis() / 1000 + it }
                ?: o.get("expiresAt")?.asLong?.takeIf { it > 0 }
                ?: 0L,
            resourceUrl = o.get("resource_url")?.asString.orEmpty()
                .ifEmpty { o.get("resourceUrl")?.asString.orEmpty() },
            uid = o.get("uid")?.asString.orEmpty().ifEmpty { jwtClaim(accessToken, "sub", "uid", "user_id") },
            nickname = o.get("nickname")?.asString.orEmpty(),
        )
    }

    private fun toProviderAccount(credential: Credential): ProviderAccount {
        val secret = JsonObject().apply {
            addProperty("accessToken", credential.accessToken)
            addProperty("refreshToken", credential.refreshToken)
            addProperty("expiresAt", credential.expiresAt)
            addProperty("resourceUrl", credential.resourceUrl)
            addProperty("uid", credential.uid)
            addProperty("nickname", credential.nickname)
        }.toString()
        val uid = credential.uid.ifEmpty { "qwen-" + credential.accessToken.hashCode().toUInt().toString(16) }
        return ProviderAccount(id, uid, credential.nickname, secret)
    }

    private fun chatBase(credential: Credential): String {
        val base = credential.resourceUrl.ifEmpty { DEFAULT_CHAT_BASE }
        return if (base.endsWith("/v1")) base else "$base/v1"
    }

    private fun pkcePair(): Pair<String, String> {
        val verifierBytes = ByteArray(32)
        SecureRandom().nextBytes(verifierBytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
        )
        return verifier to challenge
    }

    private fun formEncode(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

    private fun encodeState(deviceCode: String, verifier: String): String {
        val json = """{"d":"$deviceCode","v":"$verifier"}"""
        return Base64.getEncoder().encodeToString(json.toByteArray())
    }

    private fun decodeState(state: String): Pair<String, String>? {
        val json = runCatching { String(Base64.getDecoder().decode(state), Charsets.UTF_8) }.getOrNull() ?: return null
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        val d = obj.get("d")?.asString.orEmpty()
        val v = obj.get("v")?.asString.orEmpty()
        return if (d.isNotEmpty() && v.isNotEmpty()) d to v else null
    }

    private fun withTracking(url: String): String {
        val sep = if ('?' in url) "&" else "?"
        return "${url}${sep}entry=qwen_code&paywallScene=cli"
    }

    private data class Credential(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val resourceUrl: String,
        val uid: String,
        val nickname: String,
    )

    companion object {
        const val ID = "qwen"
        const val BASE = "https://chat.qwen.ai"
        const val DEVICE_CODE_ENDPOINT = "$BASE/api/v1/oauth2/device/code"
        const val TOKEN_ENDPOINT = "$BASE/api/v1/oauth2/token"
        const val DEFAULT_CHAT_BASE = "https://api.qwen.ai/compatible-mode/v1"
        const val CLIENT_ID = "f0304373b74a44d2b584a3fb70ca9e56"
        const val SCOPE = "openid profile email model.completion"
        const val GRANT_TYPE_DEVICE = "urn:ietf:params:oauth:grant-type:device_code"
        const val DEFAULT_MODEL = "qwen3.8-max"
        const val CLIENT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
        const val MODELS_READ_TIMEOUT_MS = 30_000
    }
}
