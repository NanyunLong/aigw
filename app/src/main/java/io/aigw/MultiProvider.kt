package io.aigw

import org.json.JSONObject
import java.util.UUID

/**
 * 多提供商统一接口。
 *
 * 登录方式优先级（用户定死）：① 手机验证码 > ② 网页登录 > ③ 微信扫码 > ④ 自己配 key。
 * 每个平台给出它"最好的"登录方式，能自动的自动，不让用户干麻烦事。
 */
enum class ProviderId(val id: String, val label: String, val loginPriority: String) {
    LOOMY("loomy", "Loomy 讯飞星火", "手机验证码 > 密码"),
    DOUBAO("doubao", "豆包", "网页登录"),
    WORKBUDDY("workbuddy", "WorkBuddy 腾讯", "网页登录(浏览器授权)"),
    QODER("qoder", "Qoder 阿里", "网页登录(浏览器授权)"),
    MS("ms", "Microsoft Copilot", "网页登录(GitHub授权)"),
    GROK("grok", "Grok xAI", "官方 API Key"),
    MINI("mini", "MiniMax", "官方 API Key"),
    QWEN("qwen", "通义千问 Qwen", "官方 API Key"),
    GEMINI("gemini", "Gemini", "官方 API Key"),
    OPENAI("openai", "OpenAI", "官方 API Key"),
    ANTHROPIC("anthropic", "Claude Anthropic", "官方 API Key"),
}

/** 一个提供商账号（通用模型，loomy 复用 LoomyAccount 字段）。 */
data class ProviderAccount(
    var name: String,
    var provider: String = "loomy",
    var loginid: String = "",        // 手机号/用户名
    var password: String = "",       // 密码（loomy RSA 加密用）
    var token: String = "",          // 通用 access token / API key / session
    var userid: String = "",
    var cookies: String = "",        // 网页登录 Cookie（豆包/工作台）
    var extra: Map<String, String> = emptyMap(),
    var enabled: Boolean = true,
    var expireAt: Long = 0L,
    var obtainedAt: Long = 0L,
    var available: Long? = null,
    var cooldownUntil: Double = 0.0,
    var requests: Int = 0,
    var failures: Int = 0,
    var lastError: String = "",
) {
    fun sessionValid(now: Long = System.currentTimeMillis() / 1000): Boolean =
        (token.isNotEmpty() || cookies.isNotEmpty()) && (expireAt == 0L || expireAt > now)
}

/**
 * 提供商登录器：按优先级返回最好的登录方式。
 * 各平台实际协议在 ProviderProtocol 里实现。
 */
object ProviderLogin {
    /** 返回该平台所有可用登录方式，按优先级排序（0 最好）。 */
    fun methods(provider: ProviderId): List<LoginMethod> = when (provider) {
        ProviderId.LOOMY -> listOf(
            LoginMethod.SMS, LoginMethod.PASSWORD,
        )
        ProviderId.DOUBAO, ProviderId.WORKBUDDY, ProviderId.QODER, ProviderId.MS -> listOf(
            LoginMethod.WEB,
        )
        ProviderId.GROK, ProviderId.MINI, ProviderId.QWEN, ProviderId.GEMINI,
        ProviderId.OPENAI, ProviderId.ANTHROPIC -> listOf(
            LoginMethod.APIKEY,
        )
    }
}

enum class LoginMethod { SMS, PASSWORD, WEB, WECHAT, APIKEY }

/**
 * 各提供商协议实现。
 * loomy 走 account.py 协议；其余走各自 upstream；通用 key 直接透传。
 */
object ProviderProtocol {
    /** 根据 provider 组装请求头。 */
    fun headersFor(acc: ProviderAccount, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = HashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json"
        when (acc.provider) {
            "loomy" -> {
                h["Authorization"] = "Bearer ${acc.token}"
                h["token"] = acc.token
                h["traceparent"] = "00-${UUID.randomUUID().toString().replace("-", "")}-${UUID.randomUUID().toString().replace("-", "").take(16)}-01"
                h["loomy-version"] = "0.9.38"
            }
            "doubao" -> {
                h["Authorization"] = "Bearer ${acc.token}"
                if (acc.cookies.isNotEmpty()) h["Cookie"] = acc.cookies
            }
            "workbuddy", "qoder" -> {
                h["Authorization"] = "Bearer ${acc.token}"
                if (acc.userid.isNotEmpty()) h["X-User-Id"] = acc.userid
                if (acc.extra["client"] != null) h["X-Client-Id"] = acc.extra["client"]!!
            }
            "ms", "grok", "mini", "qwen", "gemini", "openai", "anthropic" -> {
                h["Authorization"] = "Bearer ${acc.token}"
            }
        }
        for ((k, v) in extra) if (v.isNotEmpty()) h[k] = v
        return h
    }

    /** 根据 provider 返回上游 base url。 */
    fun baseUrl(provider: String): String = when (provider) {
        "loomy" -> LoomyConstants.MODEL_BASE
        "doubao" -> "https://www.doubao.com"
        "workbuddy" -> "https://www.workbuddy.ai/v2"
        "qoder" -> "https://api.qoder.com/v2"
        "ms" -> "https://api.githubcopilot.com"
        "grok" -> "https://api.x.ai/v1"
        "mini" -> "https://api.minimax.chat/v1"
        "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
        "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
        "openai" -> "https://api.openai.com/v1"
        "anthropic" -> "https://api.anthropic.com/v1"
        else -> LoomyConstants.MODEL_BASE
    }

    /** 提供商模型列表（内置）。 */
    fun models(provider: String): List<Map<String, Any?>> = when (provider) {
        "loomy" -> ModelGateway.fallbackModels()
        "doubao" -> listOf(
            mapOf("id" to "doubao", "name" to "豆包"),
            mapOf("id" to "doubao-pro", "name" to "豆包 Pro"),
        )
        "workbuddy" -> listOf(
            mapOf("id" to "deepseek-v4.1-flash", "name" to "DeepSeek V4.1 Flash"),
            mapOf("id" to "hy3", "name" to "腾讯混元思考"),
            mapOf("id" to "step-5-preview", "name" to "阶跃星辰"),
        )
        "qoder" -> listOf(
            mapOf("id" to "deepseek-v4.1-flash-1017", "name" to "DeepSeek V4.1 Flash 1017"),
            mapOf("id" to "qwen3.8-flash", "name" to "Qwen 3.8 Flash"),
        )
        "ms" -> listOf(
            mapOf("id" to "gpt-4.1", "name" to "GPT-4.1"),
            mapOf("id" to "gpt-4o", "name" to "GPT-4o"),
        )
        "grok" -> listOf(
            mapOf("id" to "grok-3", "name" to "Grok 3"),
            mapOf("id" to "grok-4", "name" to "Grok 4"),
        )
        "mini" -> listOf(
            mapOf("id" to "MiniMax-M3", "name" to "MiniMax M3"),
            mapOf("id" to "MiniMax-M1", "name" to "MiniMax M1"),
        )
        "qwen" -> listOf(
            mapOf("id" to "qwen3.8-flash", "name" to "Qwen 3.8 Flash"),
            mapOf("id" to "qwen-max", "name" to "Qwen Max"),
        )
        "gemini" -> listOf(
            mapOf("id" to "gemini-3-flash", "name" to "Gemini 3 Flash"),
            mapOf("id" to "gemini-3-pro", "name" to "Gemini 3 Pro"),
        )
        "openai" -> listOf(
            mapOf("id" to "gpt-4o", "name" to "GPT-4o"),
            mapOf("id" to "gpt-5", "name" to "GPT-5"),
        )
        "anthropic" -> listOf(
            mapOf("id" to "claude-3-5-sonnet", "name" to "Claude 3.5 Sonnet"),
            mapOf("id" to "claude-opus-4", "name" to "Claude Opus 4"),
        )
        else -> ModelGateway.fallbackModels()
    }
}