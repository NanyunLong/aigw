package dev.aigw.core.provider.glm

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.LineTransformStream
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.SSE_DONE
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.WebViewExtractSupport
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.newCompletionId
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * 智谱清言（chatglm.cn）网页版免费通道。
 *
 * 登录是内置 WebView 提取型：用户登录后从 localStorage 读 `chatglm_refresh_token`。
 * 对话走私有 HTTP + SSE：`POST /chatglm/backend-api/assistant/stream`，
 * 需要 md5 签名（timestamp-nonce-密钥）与设备标识头，响应是「事件流 + parts」结构，
 * 由 [GlmSseTranslator] 翻译成标准 OpenAI SSE。
 *
 * 参考 Chat2API 的 glm adapter（`xiaoY233/Chat2API`）。
 */
class GlmProvider(
    private val hooks: ProviderHooks = ProviderHooks(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val base: String = BASE,
) : Provider, WebViewExtractSupport {

    override val id: String = ID
    override val displayName: String = "智谱清言"
    override val authKind: AuthKind = AuthKind.WEBVIEW_EXTRACT

    override val loginUrl: String = "https://chatglm.cn"
    override val localStorageKeys: List<String> = listOf(REFRESH_TOKEN_KEY)
    // 兜底：部分登录方式（扫码/第三方）把 refresh_token 放在 Cookie 里而不是 localStorage。
    override val cookieNames: List<String> = listOf(REFRESH_TOKEN_KEY)

    private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, AccessToken>()

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        // 网页版没有公开模型列表端点，返回固定的 GLM 系列；有账号与否都列出，
        // 让模型页能看到「可反代的模型」，实际可用性由 openChat 判定。
        return ProviderModelCatalogView(
            models = MODELS.map { ProviderModel(it.first, it.second) },
            fromFallback = false,
            error = "",
        )
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return DEFAULT_MODEL
        return model
    }

    override fun hosts(): List<String> = listOf("chatglm.cn")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = parse(account) ?: return FailedChatCall(401, "凭证无法解析")
        val streaming = isStreamingBody(openAiBody)
        val model = resolveModel(requestedModelOf(openAiBody))
        val body = buildRequestBody(openAiBody, model)

        val token = runCatching { acquireToken(credential) }.getOrElse {
            return FailedChatCall(401, "换取访问令牌失败：${it.message}")
        }
        // 上游轮换了 refresh_token：旧 token 已作废，必须回写池子持久化，
        // 否则重启/下次请求还拿旧 token 换，必然 401。
        if (token.rotatedRefreshToken.isNotEmpty() && token.rotatedRefreshToken != credential.refreshToken) {
            hooks.onAccountUpdated(
                ProviderAccount(
                    id,
                    jwtUserId(token.rotatedRefreshToken).ifEmpty { account.uid },
                    account.nickname,
                    Credential(token.rotatedRefreshToken).toJson(),
                ),
            )
        }
        val sign = sign()
        val headers = LinkedHashMap<String, String>().apply {
            put("Authorization", "Bearer $token")
            put("Content-Type", "application/json")
            put("Accept", "text/event-stream")
            put("Origin", "https://chatglm.cn")
            put("App-Name", "chatglm")
            put("X-App-Fr", "browser_extension")
            put("X-App-Platform", "pc")
            put("X-App-Version", "0.0.1")
            put("X-Lang", "zh")
            put("X-Device-Id", uuid())
            put("X-Request-Id", uuid())
            put("X-Sign", sign)
            put("X-Timestamp", timestamp())
            put("X-Nonce", nonce())
            put("User-Agent", USER_AGENT)
        }

        val conn = try {
            HttpSupport.open("$base/backend-api/assistant/stream", "POST", headers, body, 15_000, 120_000)
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

        val translator = GlmSseTranslator(newCompletionId(), model)
        val transformed = LineTransformStream(
            source = conn.inputStream,
            transform = { line -> translator.translate(line) },
            onFinish = { translator.finish() },
        )
        if (streaming) {
            return StreamingChatCall(status, transformed) { conn.disconnect() }
        }
        val aggregated = try {
            OpenAiSseAggregator.aggregate(transformed, model)
        } finally {
            conn.disconnect()
        }
        return AggregatedChatCall(status, aggregated)
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 429 -> ErrorKind.SOFT_RATE
            status in 500..599 -> ErrorKind.SERVER
            status == 404 -> ErrorKind.NOT_FOUND
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, "智谱上游 HTTP $status")
    }

    // ------------------------------------------------------------------ 登录

    /** 粘贴 refresh_token 导入：直接当凭证用，登录时校验。 */
    override fun importCredentials(raw: String): ProviderAccount? {
        val token = raw.trim()
        if (token.isEmpty()) return null
        return extractAccount(mapOf(REFRESH_TOKEN_KEY to token), emptyMap())
    }

    override fun extractAccount(localStorage: Map<String, String>, cookies: Map<String, String>): ProviderAccount {
        val refreshToken = localStorage[REFRESH_TOKEN_KEY].orEmpty()
            .ifEmpty { cookies[REFRESH_TOKEN_KEY].orEmpty() }
        if (refreshToken.isEmpty()) {
            throw IllegalStateException("未在 localStorage/Cookie 里找到 chatglm_refresh_token，请确认已登录")
        }
        // 只从 token 提取 uid（JWT payload 的 sub/user_id），不做在线校验——
        // 在线校验会多打一次上游，网络抖动或上游接口变动就整段登录失败。
        val uid = jwtUserId(refreshToken).ifEmpty { refreshToken.take(16) }
        return ProviderAccount(id, uid, "智谱账号", Credential(refreshToken).toJson())
    }

    /** 从 JWT refresh_token 的 payload 里取 sub / user_id（取不到返回空串，不抛异常）。 */
    private fun jwtUserId(jwt: String): String {
        if (jwt.count { it == '.' } != 2) return ""
        return runCatching {
            val payload = jwt.split('.')[1]
            val decoded = String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            val obj = JsonParser.parseString(decoded).asJsonObject
            obj.get("sub")?.asString.orEmpty().ifEmpty { obj.get("user_id")?.asString.orEmpty() }
        }.getOrDefault("")
    }

    // ------------------------------------------------------------------ 内部

    private fun acquireToken(credential: Credential): AccessToken {
        val cached = tokenCache[credential.refreshToken]
        if (cached != null && cached.expiresAtMillis > nowMillis()) return cached

        val headers = LinkedHashMap<String, String>().apply {
            put("Authorization", "Bearer ${credential.refreshToken}")
            put("Content-Type", "application/json")
            put("App-Name", "chatglm")
            put("X-App-Fr", "browser_extension")
            put("X-App-Platform", "pc")
            put("X-App-Version", "0.0.1")
            put("X-Device-Id", uuid())
            put("X-Request-Id", uuid())
            put("X-Sign", sign())
            put("X-Timestamp", timestamp())
            put("X-Nonce", nonce())
            put("User-Agent", USER_AGENT)
        }
        val (status, text) = post("$base/user-api/user/refresh", "{}", headers)
        if (status !in 200..299) {
            throw IllegalStateException("HTTP $status：${text.take(120)}")
        }
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("响应不是 JSON")
        // 上游成功标志有两种口径：code=0 或 status=0（不同端点不一样），任一即成功
        val code = root.get("code")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: root.get("status")?.takeIf { it.isJsonPrimitive }?.asLong ?: 1L
        if (code != 0L) {
            val msg = root.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                ?: root.get("msg")?.takeIf { it.isJsonPrimitive }?.asString
                ?: "上游错误($code)"
            throw IllegalStateException(msg)
        }
        val result = root.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException("响应缺少 result")
        val accessToken = result.get("access_token")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (accessToken.isEmpty()) throw IllegalStateException("响应缺少 access_token")
        val userId = result.get("user_id")?.takeIf { it.isJsonPrimitive }?.asString
            ?: result.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        // 上游可能轮换 refresh_token（旧 token 随即作废）：带上新 token 让上层回写池子
        val newRefresh = result.get("refresh_token")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val access = AccessToken(accessToken, userId, nowMillis() + ACCESS_TOKEN_TTL_MS, newRefresh)
        tokenCache[credential.refreshToken] = access
        return access
    }

    private fun buildRequestBody(openAiBody: String, model: String): String {
        val root = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull()
            ?: JsonObject()
        val messages = root.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

        // 把 OpenAI messages 折叠成 GLM 的「单条 user + 对话文本」格式
        val sb = StringBuilder()
        for (element in messages) {
            val msg = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val role = msg.get("role")?.takeIf { it.isJsonPrimitive }?.asString ?: "user"
            val content = msg.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val label = when (role) {
                "system" -> "System"
                "assistant" -> "Assistant"
                else -> "User"
            }
            if (content.isNotEmpty()) sb.append("$label: $content\n\n")
        }
        sb.append("Assistant: ")

        val prepared = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", sb.toString())
                    })
                })
            })
        }

        val body = JsonObject().apply {
            addProperty("assistant_id", DEFAULT_ASSISTANT_ID)
            addProperty("conversation_id", "")
            addProperty("project_id", "")
            addProperty("chat_type", "user_chat")
            add("messages", prepared)
            add("meta_data", JsonObject().apply {
                addProperty("channel", "")
                addProperty("draft_id", "")
                addProperty("if_plus_model", true)
                addProperty("input_question_type", "xxxx")
                addProperty("is_networking", false)
                addProperty("is_test", false)
                addProperty("platform", "pc")
                addProperty("quote_log_id", "")
                add("cogview", JsonObject().apply {
                    addProperty("rm_label_watermark", false)
                })
            })
        }
        return body.toString()
    }

    private fun post(url: String, body: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = HttpSupport.open(url, "POST", headers, body, 15_000, 30_000)
        return try {
            val status = conn.responseCode
            val text = HttpSupport.readBody(conn)
            conn.disconnect()
            status to text
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    private fun sign(): String {
        val ts = timestamp()
        val n = nonce()
        return md5("$ts-$n-$SIGN_SECRET")
    }

    /** 与官方 Web 一致（glm_adapter.ts generateSign）：取「全部数字位之和 - 倒数第二位」的个位做校验位。 */
    private fun timestamp(): String {
        val digits = nowMillis().toString()
        val t = digits.length
        val sum = digits.map { it - '0' }.reduce { acc, d -> acc + d } - (digits[t - 2] - '0')
        return digits.substring(0, t - 2) + (sum % 10) + digits.substring(t - 1)
    }

    /** 32 位 hex（UUID 去连字符），与官方客户端一致。 */
    private fun nonce(): String = UUID.randomUUID().toString().replace("-", "")

    private fun uuid(): String = UUID.randomUUID().toString().replace("-", "")

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun parse(account: ProviderAccount): Credential? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull() ?: return null
        val token = obj.get("refresh_token")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (token.isEmpty()) return null
        return Credential(token)
    }

    private data class Credential(val refreshToken: String) {
        fun toJson(): String = JsonObject().apply {
            addProperty("refresh_token", refreshToken)
        }.toString()
    }

    private data class AccessToken(
        val accessToken: String,
        val userId: String,
        val expiresAtMillis: Long,
        /** 上游轮换出的新 refresh_token；空串表示未轮换。 */
        val rotatedRefreshToken: String = "",
    )

    companion object {
        const val ID = "glm"
        private const val BASE = "https://chatglm.cn/chatglm"
        private const val REFRESH_TOKEN_KEY = "chatglm_refresh_token"
        private const val SIGN_SECRET = "8a1317a7468aa3ad86e997d08f3f31cb"
        private const val DEFAULT_ASSISTANT_ID = "65940acff94777010aa6b796"
        private const val DEFAULT_MODEL = "glm-5.1"
        private const val ACCESS_TOKEN_TTL_MS = 3_600_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

        private val MODELS = listOf(
            "glm-5.1" to "GLM-5.1",
            "glm-4.7" to "GLM-4.7",
        )
    }
}

/**
 * 把 GLM 的「事件流 + parts」响应翻译成 OpenAI SSE。
 *
 * GLM 响应是 SSE：每行 `data: {...}`，JSON 里有 `status`（finish / intervene / 其它）
 * 与 `parts`（含 `content[].type` 为 text/think/code/image）。增量在 parts 里，
 * 同一 logic_id 的 part 会被覆盖更新，需按「累计全文减去已发送」算增量。
 */
private class GlmSseTranslator(
    private val id: String,
    private val model: String,
) {
    private val created = System.currentTimeMillis() / 1000
    private val parts = LinkedHashMap<String, JsonObject>()
    private var sentText = ""
    private var sentReasoning = ""
    private var roleSent = false

    fun translate(line: String): List<String> {
        if (!line.startsWith("data:")) return emptyList()
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return emptyList()
        val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: return emptyList()
        val status = obj.get("status")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

        val out = ArrayList<String>()
        if (status == "finish" || status == "intervene") {
            // 收尾帧
            out.add(chunk("", "", "stop"))
            out.add(SSE_DONE)
            return out
        }

        val newParts = obj.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray ?: return out
        for (element in newParts) {
            val part = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val logicId = part.get("logic_id")?.takeIf { it.isJsonPrimitive }?.asString
                ?: part.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            parts[logicId] = part
        }

        // 累计全文
        var fullText = ""
        var fullReasoning = ""
        for (part in parts.values) {
            val content = part.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (item in content) {
                val c = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                when (c.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
                    "text" -> fullText += c.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    "think" -> fullReasoning += c.get("think")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    "code" -> fullText += c.get("code")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    "execution_output" -> fullText += c.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                }
            }
        }

        // 推理增量
        val reasoningChunk = if (fullReasoning.startsWith(sentReasoning)) fullReasoning.substring(sentReasoning.length) else fullReasoning
        if (reasoningChunk.isNotEmpty()) {
            sentReasoning = fullReasoning
            out.add(chunk("", reasoningChunk, null))
        }
        // 文本增量
        val textChunk = if (fullText.startsWith(sentText)) fullText.substring(sentText.length) else fullText
        if (textChunk.isNotEmpty()) {
            sentText = fullText
            out.add(chunk(textChunk, "", null))
        }
        return out
    }

    fun finish(): List<String> = listOf(chunk("", "", "stop"), SSE_DONE)

    private fun chunk(content: String, reasoningContent: String, finish: String?): String {
        val delta = JsonObject().apply {
            if (!roleSent) addProperty("role", "assistant")
            if (content.isNotEmpty()) addProperty("content", content)
            if (reasoningContent.isNotEmpty()) addProperty("reasoning_content", reasoningContent)
        }
        roleSent = true
        val choice = JsonObject().apply {
            addProperty("index", 0)
            add("delta", delta)
            if (finish != null) addProperty("finish_reason", finish)
        }
        val obj = JsonObject().apply {
            addProperty("id", id)
            addProperty("object", "chat.completion.chunk")
            addProperty("created", created)
            addProperty("model", model)
            add("choices", JsonArray().apply { add(choice) })
        }
        return "data: ${obj}\n\n"
    }
}
