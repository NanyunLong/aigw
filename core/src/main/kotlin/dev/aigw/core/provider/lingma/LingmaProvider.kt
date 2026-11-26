package dev.aigw.core.provider.lingma

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
import dev.aigw.core.provider.WebLoginSupport
import dev.aigw.core.provider.WebLoginTicket
import dev.aigw.core.provider.queryParam
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.HttpSupport
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * 通义灵码（`lingma.alibabacloud.com`）。
 *
 * 登录是带凭证重定向（WebLoginSupport）：App 构造登录入口 URL（含 state/PKCE/machine_id/回调端口），
 * 用户在系统浏览器完成阿里云登录后，灵码服务器把凭证（Encode=1 编码的 auth+token）
 * 拼在重定向 URL 参数里回到本地端口，App 拦截解析并本地派生 cosy 凭证。
 *
 * 对话走 `/algo/api/v2/service/pro/sse/agent_chat_generation`，请求需 COSY 签名头，
 * SSE 响应是双层包裹（outer{body,statusCodeValue} → inner 即标准 OpenAI delta），
 * 由行级翻译器剥壳透传。
 */
class LingmaProvider internal constructor(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val hooks: ProviderHooks = ProviderHooks(),
) : Provider, WebLoginSupport {

    override val id: String = ID
    override val displayName: String = "通义灵码"
    override val authKind: AuthKind = AuthKind.WEBVIEW_CALLBACK

    /** beginWebLogin 生成的 machineId：completeWebLogin 时取回（回调 URL 不带 state）。 */
    private var pendingMachineId: String = ""

    // ------------------------------------------------------------------ 登录

    override fun beginWebLogin(callbackUrl: String, region: String): WebLoginTicket {
        val machineId = newMachineId()
        pendingMachineId = machineId
        val state = "2-" + randomHex(16)
        val verifier = randomHex(32)
        val challenge = base64Url(sha256(verifier.toByteArray()))
        val nonce = randomHex(16)
        val port = callbackUrl.substringAfterLast(":").substringBefore("/").ifEmpty { "51123" }
        val loginUrl = "$BASE/lingma/login?" + formEncode(
            linkedMapOf(
                "state" to state,
                "challenge" to challenge,
                "challenge_method" to "S256",
                "machine_id" to machineId,
                "nonce" to nonce,
                "port" to port,
            ),
        )
        return WebLoginTicket(state, loginUrl, callbackUrl)
    }

    override fun completeWebLogin(callbackUrl: String): ProviderAccount {
        val query = callbackUrl.substringAfter("?", "")
        val authParam = runCatching { java.net.URLDecoder.decode(queryParam(callbackUrl, "auth").orEmpty(), "UTF-8") }.getOrDefault("")
        val tokenParam = runCatching { java.net.URLDecoder.decode(queryParam(callbackUrl, "token").orEmpty(), "UTF-8") }.getOrDefault("")

        var uid = ""
        var aid = ""
        var name = ""
        var oauthToken = ""
        var refreshToken = ""
        var expireTime = 0L

        if (authParam.isNotEmpty() && tokenParam.isNotEmpty()) {
            hooks.onLog("灵码回调原始 auth（前80字符）：${authParam.take(80)}")
            hooks.onLog("灵码回调原始 token（前80字符）：${tokenParam.take(80)}")
            // V2 回调：auth/token 都是 Encode=1 编码
            val authParts = LingmaCrypto.customDecryptParts(authParam, 3)
                ?: throw IllegalStateException(
                    "回调 auth 参数解码失败（Encode=1）原始长度=${authParam.length} 头=${authParam.take(20)}",
                )
            val tokenParts = LingmaCrypto.customDecryptParts(tokenParam, 3)
                ?: throw IllegalStateException("回调 token 参数解码失败（Encode=1）")
            uid = authParts[0]
            aid = authParts[1]
            name = authParts[2]
            oauthToken = tokenParts[0]
            refreshToken = tokenParts[1]
            expireTime = tokenParts[2].toLongOrNull() ?: 0L
        } else {
            // V1 明文兜底
            uid = runCatching { java.net.URLDecoder.decode(queryParam(callbackUrl, "uid").orEmpty(), "UTF-8") }.getOrDefault("")
            aid = runCatching { java.net.URLDecoder.decode(queryParam(callbackUrl, "aid").orEmpty(), "UTF-8") }.getOrDefault("")
            name = runCatching { java.net.URLDecoder.decode(queryParam(callbackUrl, "name").orEmpty(), "UTF-8") }.getOrDefault("")
            oauthToken = runCatching { java.net.URLDecoder.decode(queryParam(callbackUrl, "access_token").orEmpty(), "UTF-8") }.getOrDefault("")
            refreshToken = runCatching { java.net.URLDecoder.decode(queryParam(callbackUrl, "refresh_token").orEmpty(), "UTF-8") }.getOrDefault("")
            expireTime = queryParam(callbackUrl, "expire_time")?.toLongOrNull() ?: 0L
        }
        if (uid.isEmpty() || oauthToken.isEmpty()) {
            throw IllegalStateException("回调缺少凭证（uid/access_token 为空）")
        }
        hooks.onLog("灵码回调：uid=$uid name=$name expire=$expireTime")

        // 本地派生 cosy 凭证
        val (cosyKey, encryptUserInfo) = LingmaCrypto.generateCosyCredentials(
            name = name,
            uid = uid,
            aid = aid,
            oauthToken = oauthToken,
            refreshToken = refreshToken,
        ) ?: throw IllegalStateException("cosy 凭证派生失败（RSA/AES）")

        val machineId = pendingMachineId.ifEmpty { newMachineId() }
        pendingMachineId = ""
        val credential = Credential(
            cosyKey = cosyKey,
            encryptUserInfo = encryptUserInfo,
            userId = uid,
            machineId = machineId,
            nickname = name,
        )
        return toProviderAccount(credential)
    }

    /** 粘贴回调链接导入：与浏览器登录同一条解析链路。 */
    override fun importCredentials(raw: String): ProviderAccount? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { completeWebLogin(trimmed) }.getOrNull()
    }

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val credential = account?.let { parse(it) }
            ?: return ProviderModelCatalogView(emptyList(), fromFallback = false, error = "")
        return try {
            val models = fetchModels(credential)
            if (models.isEmpty()) {
                ProviderModelCatalogView(fallbackModels(), fromFallback = true, error = "上游返回空，已用内置清单")
            } else {
                ProviderModelCatalogView(models, fromFallback = false, error = "")
            }
        } catch (e: Exception) {
            ProviderModelCatalogView(fallbackModels(), fromFallback = true, error = e.message ?: "拉取模型失败")
        }
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim().lowercase()
        return MODEL_ALIASES[model] ?: when {
            model.isEmpty() || model == "auto" -> DEFAULT_MODEL
            else -> model
        }
    }

    override fun hosts(): List<String> = listOf("lingma.alibabacloud.com")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = parse(account) ?: return FailedChatCall(401, "凭证无法解析")
        val streaming = isStreamingBody(openAiBody)
        val modelKey = resolveModel(requestedModelOf(openAiBody))
        val body = buildChatBody(openAiBody, modelKey)

        val requestId = UUID.randomUUID().toString()
        val (bearer, date) = LingmaCrypto.buildCosySignature(
            cosyKey = credential.cosyKey,
            encryptUserInfo = credential.encryptUserInfo,
            path = CHAT_PATH,
            body = body,
            nowSeconds = nowMillis() / 1000,
            requestId = requestId,
        )
        val headers = chatHeaders(credential, bearer, date, body, streaming)

        val conn = try {
            HttpSupport.open(
                "$BASE$CHAT_PATH$CHAT_QUERY",
                "POST",
                headers,
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
        if (streaming) {
            val translated = LingmaSseTranslator(conn.inputStream).stream()
            return StreamingChatCall(status, translated) { conn.disconnect() }
        }
        val aggregated = try {
            OpenAiSseAggregator.aggregate(LingmaSseTranslator(conn.inputStream).stream(), modelKey)
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
        return UpstreamError(kind, "灵码上游 HTTP $status")
    }

    // ------------------------------------------------------------------ 内部

    private fun chatHeaders(
        credential: Credential,
        bearer: String,
        date: String,
        body: String,
        streaming: Boolean,
    ): Map<String, String> = buildMap {
        put("Authorization", "Bearer $bearer")
        put("Content-Type", "application/json")
        put("Appcode", "cosy")
        put("Cosy-Date", date)
        put("Cosy-Key", credential.cosyKey)
        put("Cosy-MachineId", credential.machineId)
        put("Cosy-User", credential.userId)
        put("Cosy-Clientip", "198.18.0.1")
        put("Cosy-Clienttype", "2")
        put("Cosy-Machineos", "x86_64_windows")
        put("Cosy-Machinetoken", "")
        put("Cosy-Machinetype", "")
        put("Cosy-Version", COSY_VERSION)
        put("Login-Version", "v2")
        put("Cosy-SigPath", LingmaCrypto.normalizePath(CHAT_PATH))
        put("Cosy-Data-Policy", "AGREE")
        put("Cosy-Organization-Tags", "")
        put("Cosy-Organization-Id", "")
        put("User-Agent", CLIENT_UA)
        if (body.isNotEmpty()) {
            put("Cosy-BodyHash", md5Hex(body))
            put("Cosy-BodyLength", body.length.toString())
        }
        put("Accept", if (streaming) "text/event-stream" else "application/json")
    }

    /** 把 OpenAI 请求体折叠成灵码的对话请求体（对照 body.go 的 BuildCanonical）。 */
    private fun buildChatBody(openAiBody: String, modelKey: String): String {
        val root = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull() ?: JsonObject()
        val messages = root.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

        val serialized = JsonArray()
        for (element in messages) {
            val msg = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val role = msg.get("role")?.asString ?: "user"
            val content = msg.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            serialized.add(JsonObject().apply {
                addProperty("role", role)
                addProperty("content", content)
                add("response_meta", JsonObject().apply {
                    addProperty("id", "")
                    add("usage", JsonObject().apply {
                        addProperty("prompt_tokens", 0)
                        addProperty("completion_tokens", 0)
                        addProperty("total_tokens", 0)
                    })
                })
                addProperty("reasoning_content_signature", "")
            })
        }

        val requestHex = randomHex(16)
        return JsonObject().apply {
            addProperty("request_id", requestHex)
            addProperty("request_set_id", "")
            addProperty("chat_record_id", requestHex)
            addProperty("stream", isStreamingBody(openAiBody))
            add("image_urls", JsonArray())
            addProperty("is_reply", false)
            addProperty("is_retry", false)
            addProperty("session_id", "")
            addProperty("code_language", "")
            addProperty("source", 0)
            addProperty("version", "3")
            addProperty("chat_prompt", "")
            add("parameters", JsonObject().apply { addProperty("temperature", 0.1) })
            addProperty("aliyun_user_type", "personal_standard")
            addProperty("agent_id", "agent_common")
            addProperty("task_id", "question_refine")
            add("model_config", JsonObject().apply {
                addProperty("key", modelKey)
                addProperty("display_name", "")
                addProperty("model", modelKey)
                addProperty("format", "")
                addProperty("is_vl", false)
                addProperty("is_reasoning", false)
                addProperty("api_key", "")
                addProperty("url", "")
                addProperty("source", "")
                addProperty("max_input_tokens", 0)
                addProperty("enable", false)
                addProperty("price_factor", 0)
                addProperty("original_price_factor", 0)
                addProperty("is_default", false)
                addProperty("is_new", false)
                addProperty("exclude_tags", null as String?)
                addProperty("tags", null as String?)
                addProperty("icon", null as String?)
                addProperty("strategies", null as String?)
            })
            add("messages", serialized)
            add("business", JsonObject().apply {
                addProperty("product", "jb_plugin")
                addProperty("version", COSY_VERSION)
                addProperty("type", "memory")
                addProperty("id", UUID.randomUUID().toString())
                addProperty("begin_at", nowMillis())
                addProperty("stage", "start")
                addProperty("name", "memory_intent_recognition_$requestHex")
            })
        }.toString()
    }

    private fun fetchModels(credential: Credential): List<ProviderModel> {
        val requestId = UUID.randomUUID().toString()
        val (bearer, date) = LingmaCrypto.buildCosySignature(
            cosyKey = credential.cosyKey,
            encryptUserInfo = credential.encryptUserInfo,
            path = MODEL_LIST_PATH,
            body = "",
            nowSeconds = nowMillis() / 1000,
            requestId = requestId,
        )
        val headers = mapOf(
            "Authorization" to "Bearer $bearer",
            "Appcode" to "cosy",
            "Cosy-Date" to date,
            "Cosy-Key" to credential.cosyKey,
            "Cosy-MachineId" to credential.machineId,
            "Cosy-User" to credential.userId,
            "Cosy-Clientip" to "198.18.0.1",
            "Cosy-Clienttype" to "2",
            "Cosy-Version" to COSY_VERSION,
            "Login-Version" to "v2",
            "Accept" to "application/json",
            "User-Agent" to CLIENT_UA,
        )
        val conn = try {
            HttpSupport.open("$BASE$MODEL_LIST_PATH", "GET", headers, null, CONNECT_TIMEOUT_MS, MODELS_TIMEOUT_MS)
        } catch (e: Exception) {
            throw IllegalStateException("模型接口请求失败：${e.message}", e)
        }
        return try {
            val status = conn.responseCode
            if (status !in 200..299) throw IllegalStateException("模型接口 HTTP $status")
            val text = HttpSupport.readBody(conn)
            val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                ?: throw IllegalStateException("模型响应不是合法 JSON")
            // 兼容 Data 数组与 data.data 双层
            val arr = root.get("Data")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return emptyList()
            arr.mapNotNull { element ->
                val m = element.asJsonObject
                val key = m.get("key")?.asString.orEmpty()
                val display = m.get("display_name")?.asString.orEmpty().ifEmpty { key }
                if (key.isEmpty() || m.get("enable")?.asBoolean == false) null
                else ProviderModel(key, display)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fallbackModels(): List<ProviderModel> = listOf(
        ProviderModel(DEFAULT_MODEL, "Qwen3 Max（灵码默认）"),
        ProviderModel("dashscope_qwen3_coder", "Qwen3 Coder"),
        ProviderModel("dashscope_qwen_plus_20250428_thinking", "Qwen Plus Thinking"),
    )

    private fun parse(account: ProviderAccount): Credential? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull() ?: return null
        val cosyKey = obj.get("cosyKey")?.asString.orEmpty()
        val encryptUserInfo = obj.get("encryptUserInfo")?.asString.orEmpty()
        if (cosyKey.isEmpty() || encryptUserInfo.isEmpty()) return null
        return Credential(
            cosyKey = cosyKey,
            encryptUserInfo = encryptUserInfo,
            userId = obj.get("userId")?.asString.orEmpty(),
            machineId = obj.get("machineId")?.asString.orEmpty(),
            nickname = obj.get("nickname")?.asString.orEmpty(),
        )
    }

    private fun toProviderAccount(credential: Credential): ProviderAccount {
        val secret = JsonObject().apply {
            addProperty("cosyKey", credential.cosyKey)
            addProperty("encryptUserInfo", credential.encryptUserInfo)
            addProperty("userId", credential.userId)
            addProperty("machineId", credential.machineId)
            addProperty("nickname", credential.nickname)
        }.toString()
        return ProviderAccount(id, credential.userId, credential.nickname, secret)
    }

    private fun newMachineId(): String {
        val buf = ByteArray(16)
        SecureRandom().nextBytes(buf)
        buf[6] = (buf[6].toInt() and 0x0F or 0x40).toByte()
        buf[8] = (buf[8].toInt() and 0x3F or 0x80).toByte()
        val hex = LingmaCrypto.let { buf.joinToString("") { "%02x".format(it) } }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    private fun base64Url(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun md5Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun formEncode(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

    private data class Credential(
        val cosyKey: String,
        val encryptUserInfo: String,
        val userId: String,
        val machineId: String,
        val nickname: String,
    )

    /**
     * SSE 双层剥壳：灵码的每帧是 `data: {"body":"<inner OpenAI JSON>","statusCodeValue":200}`，
     * body 就是标准 OpenAI delta——剥壳直接透传；statusCodeValue≥400 当错误帧输出。
     */
    private class LingmaSseTranslator(source: java.io.InputStream) {
        private val stream = LineTransformStream(
            source = source,
            transform = { line -> transformLine(line) },
            onFinish = { listOf(SSE_DONE) },
        )

        fun stream(): java.io.InputStream = stream

        private fun transformLine(line: String): List<String> {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return emptyList()
            val payload = trimmed.removePrefix("data:").trim()
            if (payload == "[DONE]") return listOf(SSE_DONE)
            val outer = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
                ?: return emptyList()
            val statusCode = outer.get("statusCodeValue")?.asLong ?: 0L
            if (statusCode >= 400) {
                val err = outer.get("body")?.asString.orEmpty()
                    .ifEmpty { outer.get("statusMessage")?.asString.orEmpty() }
                return listOf("data: {\"error\":{\"message\":${JsonParser.parseString(err)}}}\n\n")
            }
            val body = outer.get("body")?.asString.orEmpty()
            if (body.isEmpty()) return emptyList()
            if (body == "[DONE]") return listOf(SSE_DONE)
            return listOf("data: $body\n\n")
        }
    }

    companion object {
        const val ID = "lingma"
        const val BASE = "https://lingma.alibabacloud.com"
        const val CHAT_PATH = "/algo/api/v2/service/pro/sse/agent_chat_generation"
        const val CHAT_QUERY = "?FetchKeys=llm_model_result&AgentId=agent_common"
        const val MODEL_LIST_PATH = "/algo/api/v2/model/list"
        const val COSY_VERSION = "2.11.2"
        const val DEFAULT_MODEL = "dashscope_qwen_max_latest"
        const val CLIENT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
        const val MODELS_TIMEOUT_MS = 30_000

        /** 模型别名 → 灵码的 model_config.key（对照 lingma2api types.go 的 DefaultAliases）。 */
        val MODEL_ALIASES = mapOf(
            "qwen3-coder" to "dashscope_qwen3_coder",
            "qwen3-coder-default" to "dashscope_qwen3_coder_default",
            "qwen-plus-thinking" to "dashscope_qwen_plus_20250428_thinking",
            "qwen-max" to "dashscope_qwen_max_latest",
        )
    }
}
