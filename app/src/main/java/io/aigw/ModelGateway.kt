package io.aigw

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Loomy 模型网关客户端（移植 upstream.py）。
 *
 * 上游是 OpenAI 兼容：/chat/completions、/models、/embeddings、/images/generations。
 * 请求头照抄 build_model_headers：
 *   Authorization: Bearer <session> + token: <session> + traceparent + loomy-version
 */
object ModelGateway {
    private const val BASE = LoomyConstants.MODEL_BASE
    private const val LOOMY_VERSION = "0.9.38"

    fun buildHeaders(session: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = HashMap<String, String>()
        h["Accept"] = "application/json"
        h["Content-Type"] = "application/json"
        h["Authorization"] = "Bearer $session"
        h["token"] = session
        h["traceparent"] = "00-${UUID.randomUUID().toString().replace("-", "")}-${UUID.randomUUID().toString().replace("-", "").take(16)}-01"
        h["loomy-version"] = LOOMY_VERSION
        for ((k, v) in extra) if (v.isNotEmpty()) h[k] = v
        return h
    }

    private fun url(suffix: String): String = "${BASE.trimEnd('/')}/${suffix.trimStart('/')}"

    /** GET /models → 模型列表（JSONObject）。 */
    fun models(session: String): JSONObject {
        val resp = HttpUtil.get(url("models"), buildHeaders(session))
        return JSONObject(resp)
    }

    /** GET /points/records → 额度数据。 */
    fun pointsRecords(session: String, pageSize: Int = 20): JSONObject {
        val u = url("points/records?record_type=all&page_no=1&page_size=$pageSize")
        val resp = HttpUtil.get(u, buildHeaders(session))
        return JSONObject(resp)
    }

    /** POST 透传（embeddings / images/generations）。返回 (status, body)。 */
    fun call(session: String, suffix: String, payload: JSONObject, extra: Map<String, String> = emptyMap()): Pair<Int, String> {
        val u = url(suffix)
        val conn = HttpURLConnection(URL(u))
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 60000
            conn.readTimeout = 1200000
            conn.doOutput = true
            for ((k, v) in buildHeaders(session, extra)) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** POST /chat/completions 流式：打开连接返回流。 */
    fun openStream(session: String, suffix: String, payload: JSONObject, extra: Map<String, String> = emptyMap()): StreamResult {
        val u = url(suffix)
        val conn = HttpURLConnection(URL(u))
        conn.requestMethod = "POST"
        conn.connectTimeout = 60000
        conn.readTimeout = 1200000
        conn.doOutput = true
        for ((k, v) in buildHeaders(session, extra)) conn.setRequestProperty(k, v)
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = if (code in 200..299) null else stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        return StreamResult(code, conn, stream, text)
    }

    /** 简化配额：{balance, daily_balance, available}。 */
    fun quota(session: String): Map<String, Long?> {
        return try {
            val payload = pointsRecords(session, pageSize = 1)
            val data = payload.optJSONObject("data") ?: JSONObject()
            mapOf(
                "balance" to (data.optLong("balance", -1L).takeIf { it >= 0 }),
                "daily_balance" to (data.optLong("dailyBalance", -1L).takeIf { it >= 0 }),
                "available" to (data.optLong("availableBalance", -1L).takeIf { it >= 0 }),
            )
        } catch (e: Exception) {
            mapOf("balance" to null, "daily_balance" to null, "available" to null)
        }
    }

    /** 内置 fallback 模型清单（constants.py FALLBACK_MODELS）。 */
    fun fallbackModels(): List<Map<String, Any?>> {
        val fallback = listOf(
            mapOf("id" to "spark-x", "name" to "Spark X2.5 (x0.1)", "multiplier" to 0.1, "context_length" to 1048576, "modalities" to listOf("text")),
            mapOf("id" to "GLM-5.3-Flash", "name" to "GLM 5.3 Flash (x0.8)", "multiplier" to 0.8, "context_length" to 1048576, "modalities" to listOf("text", "image", "video")),
            mapOf("id" to "qwen3.8-flash", "name" to "qwen 3.8 flash (x0.8)", "multiplier" to 0.8, "context_length" to 1000000, "modalities" to listOf("text", "image", "video")),
            mapOf("id" to "deepseek-v4-flash-0731", "name" to "DeepSeek V4 Flash 0731 (x3.0)", "multiplier" to 3.0, "context_length" to 1048576, "modalities" to listOf("text")),
            mapOf("id" to "mimo-v2.5", "name" to "MiMo V2.5 (x3.3)", "multiplier" to 3.3, "context_length" to 1048576, "modalities" to listOf("text", "image", "audio", "video")),
            mapOf("id" to "MiniMax-M3", "name" to "MiniMax M3 (x4.0)", "multiplier" to 4.0, "context_length" to 1048576, "modalities" to listOf("text", "image", "video")),
            mapOf("id" to "Kimi-k2.6", "name" to "Kimi k2.6 (x6.5)", "multiplier" to 6.5, "context_length" to 262144, "modalities" to listOf("text", "image", "video")),
            mapOf("id" to "qwen-3.8-max", "name" to "Qwen 3.8 Max (x12.0)", "multiplier" to 12.0, "context_length" to 1000000, "modalities" to listOf("text")),
            mapOf("id" to "Hy-Image-3.5-preview", "name" to "Hy image 3.5 preview", "multiplier" to 0.0, "context_length" to 100000, "modalities" to listOf("text", "image")),
            mapOf("id" to "doubao-seedream-5-lite", "name" to "Doubao seedream 5 lite", "multiplier" to 0.0, "context_length" to 128000, "modalities" to listOf("text", "image")),
            mapOf("id" to "qwen-image-3.0-pro", "name" to "qwen image 3.0 pro", "multiplier" to 0.0, "context_length" to 0, "modalities" to listOf("text", "image")),
        )
        return fallback
    }
}

/** 流式结果。 */
class StreamResult(val status: Int, val conn: HttpURLConnection, val input: java.io.InputStream?, val errorBody: String?)

/** 从 SSE 行提取 usage（last write wins）。 */
fun sseUsage(line: String, sink: MutableMap<String, Any?>): Unit {
    if (!line.startsWith("data:")) return
    val chunk = line.removePrefix("data:").trim()
    if (chunk.isEmpty() || chunk == "[DONE]") return
    try {
        val obj = JSONObject(chunk)
        if (obj.has("usage") && obj.optJSONObject("usage") != null) {
            val usage = obj.getJSONObject("usage")
            for (key in usage.keys()) sink[key] = usage.opt(key)
        }
    } catch (e: Exception) {
        // 忽略非 JSON 行
    }
}

/** human_usage：格式化为可读。 */
fun humanUsage(usage: Map<String, Any?>): String {
    val parts = mutableListOf<String>()
    if (usage["prompt_tokens"] != null || usage["completion_tokens"] != null) {
        parts.add("tok=${usage["prompt_tokens"]}/${usage["completion_tokens"]}")
    }
    val reasoning = (usage["completion_tokens_details"] as? Map<*, *>)?.get("reasoning_tokens")
    if (reasoning != null) parts.add("think=$reasoning")
    if (usage["points_consumed"] != null) parts.add("points=${usage["points_consumed"]}")
    return parts.joinToString(" ")
}