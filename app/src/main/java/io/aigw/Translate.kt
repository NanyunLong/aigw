package io.aigw

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.Writer

/**
 * 协议转译：OpenAI Chat Completions <-> Anthropic / Gemini / Ollama。
 *
 * 只处理纯文本对话。tool calling / 多模态不转译（openai 类型上游不受影响，走直通）。
 * 出站请求按上游协议构造，入站响应统一还原成 OpenAI schema，
 * 因此客户端只需要说 OpenAI 协议。
 */
object Translate {

    // ---------------------------------------------------------- 通用

    fun textOf(content: Any?): String = when (content) {
        null -> ""
        is String -> content
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val p = content.opt(i)
                when (p) {
                    is String -> sb.append(p)
                    is JSONObject -> {
                        val t = p.optString("type", "")
                        if (t.isEmpty() || t == "text" || t == "input_text") {
                            sb.append(p.optString("text", ""))
                        }
                    }
                }
            }
            sb.toString()
        }
        else -> content.toString()
    }

    /** 拆 system 与对话，返回 (system, [(role, content)])。 */
    fun splitSystem(messages: JSONArray?): Pair<String, MutableList<Pair<String, String>>> {
        val sys = StringBuilder()
        val conv = ArrayList<Pair<String, String>>()
        if (messages == null) return "" to conv
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "")
            val text = textOf(m.opt("content"))
            when (role) {
                "system" -> { if (sys.isNotEmpty()) sys.append('\n'); sys.append(text) }
                "user", "assistant" -> conv.add(role to text)
                "" -> {}
                else -> conv.add("user" to text)
            }
        }
        return sys.toString() to conv
    }

    fun mergeAdjacent(conv: List<Pair<String, String>>): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (m in conv) {
            if (out.isNotEmpty() && out.last().first == m.first) {
                out[out.size - 1] = m.first to (out.last().second + "\n" + m.second)
            } else out.add(m)
        }
        return out
    }

    fun matchPatterns(patterns: List<String>, value: String?): Boolean {
        if (value.isNullOrEmpty()) return true
        for (p in patterns.ifEmpty { listOf("*") }) {
            if (p == "*" || p == value) return true
            if (p.endsWith("*") && value.startsWith(p.dropLast(1))) return true
        }
        return false
    }

    // ---------------------------------------------------------- 出站构造

    fun anthropicHeaders(key: String): Map<String, String> {
        val h = mutableMapOf("Content-Type" to "application/json", "anthropic-version" to "2023-06-01")
        if (key.isNotEmpty()) h["x-api-key"] = key
        return h
    }

    fun geminiHeaders(key: String): Map<String, String> {
        val h = mutableMapOf("Content-Type" to "application/json")
        if (key.isNotEmpty()) h["x-goog-api-key"] = key
        return h
    }

    fun ollamaHeaders(key: String): Map<String, String> {
        val h = mutableMapOf("Content-Type" to "application/json")
        if (key.isNotEmpty()) h["Authorization"] = "Bearer $key"
        return h
    }

    fun urlFor(up: Upstream, path: String, model: String): String = when (up.type) {
        "anthropic" -> up.baseUrl + "/messages"
        "gemini" -> {
            val m = if (model.startsWith("models/")) model.removePrefix("models/") else model
            up.baseUrl + "/models/" + m + ":generateContent"
        }
        "ollama" -> up.baseUrl + "/api/chat"
        else -> {
            var p = path
            if (up.stripV1 && p.startsWith("/v1/")) p = p.removePrefix("/v1")
            up.baseUrl + p
        }
    }

    fun streamUrlFor(up: Upstream, path: String, model: String): String = when (up.type) {
        "gemini" -> {
            val m = if (model.startsWith("models/")) model.removePrefix("models/") else model
            up.baseUrl + "/models/" + m + ":streamGenerateContent?alt=sse"
        }
        else -> urlFor(up, path, model)
    }

    fun buildBody(up: Upstream, body: JSONObject, model: String, stream: Boolean, maxTokens: Int): JSONObject {
        return when (up.type) {
            "anthropic" -> {
                val (sys, conv0) = splitSystem(body.optJSONArray("messages"))
                val conv = mergeAdjacent(conv0)
                if (conv.isEmpty()) conv.add("user" to "hi")
                if (conv.first().first != "user") conv.add(0, "user" to "…")
                if (conv.last().first != "user") conv.add("user" to "…")
                val msgs = JSONArray()
                for (m in conv) {
                    msgs.put(JSONObject().put("role", m.first)
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", m.second))))
                }
                val o = JSONObject()
                    .put("model", model)
                    .put("max_tokens", if (maxTokens > 0) maxTokens else body.optInt("max_tokens", 1024))
                    .put("messages", msgs)
                    .put("stream", stream)
                if (sys.isNotEmpty()) o.put("system", sys)
                if (body.has("temperature")) o.put("temperature", body.getDouble("temperature"))
                if (body.has("top_p")) o.put("top_p", body.getDouble("top_p"))
                body.opt("stop")?.let { st ->
                    o.put("stop_sequences", if (st is String) JSONArray().put(st) else st)
                }
                o
            }
            "gemini" -> {
                val (sys, conv) = splitSystem(body.optJSONArray("messages"))
                val contents = JSONArray()
                for (m in conv) {
                    val role = if (m.first == "assistant") "model" else "user"
                    if (contents.length() > 0) {
                        val last = contents.getJSONObject(contents.length() - 1)
                        if (last.optString("role") == role) {
                            last.getJSONArray("parts").put(JSONObject().put("text", m.second))
                            continue
                        }
                    }
                    contents.put(JSONObject().put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", m.second))))
                }
                if (contents.length() == 0) {
                    contents.put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", "hi"))))
                }
                val o = JSONObject().put("contents", contents)
                if (sys.isNotEmpty()) {
                    o.put("systemInstruction", JSONObject()
                        .put("parts", JSONArray().put(JSONObject().put("text", sys))))
                }
                val gc = JSONObject()
                if (maxTokens > 0) gc.put("maxOutputTokens", maxTokens)
                if (body.has("temperature")) gc.put("temperature", body.getDouble("temperature"))
                if (body.has("top_p")) gc.put("topP", body.getDouble("top_p"))
                body.opt("stop")?.let { st ->
                    gc.put("stopSequences", if (st is String) JSONArray().put(st) else st)
                }
                if (gc.length() > 0) o.put("generationConfig", gc)
                o
            }
            else -> {
                val msgs = JSONArray()
                val arr = body.optJSONArray("messages")
                if (arr != null) for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    var role = m.optString("role", "user")
                    if (role !in listOf("system", "user", "assistant")) role = "user"
                    msgs.put(JSONObject().put("role", role).put("content", textOf(m.opt("content"))))
                }
                val opts = JSONObject()
                if (maxTokens > 0) opts.put("num_predict", maxTokens)
                if (body.has("temperature")) opts.put("temperature", body.getDouble("temperature"))
                if (body.has("top_p")) opts.put("top_p", body.getDouble("top_p"))
                body.opt("stop")?.let { st ->
                    opts.put("stop", if (st is String) JSONArray().put(st) else st)
                }
                val o = JSONObject().put("model", model).put("messages", msgs).put("stream", stream)
                if (opts.length() > 0) o.put("options", opts)
                o
            }
        }
    }

    // ---------------------------------------------------------- 入站解析（非流式）

    private fun usage(pt: Int, ct: Int) = JSONObject()
        .put("prompt_tokens", pt).put("completion_tokens", ct).put("total_tokens", pt + ct)

    private fun envelope(id: String, model: String, text: String, finish: String, u: JSONObject) =
        JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(JSONObject()
                .put("index", 0)
                .put("message", JSONObject().put("role", "assistant").put("content", text))
                .put("finish_reason", finish)))
            .put("usage", u)

    private val ANTHROPIC_FINISH = mapOf(
        "end_turn" to "stop", "max_tokens" to "length",
        "stop_sequence" to "stop", "tool_use" to "tool_calls"
    )
    private val GEMINI_FINISH = mapOf(
        "STOP" to "stop", "MAX_TOKENS" to "length", "SAFETY" to "content_filter",
        "RECITATION" to "content_filter", "OTHER" to "stop"
    )

    fun parse(up: Upstream, obj: JSONObject, model: String): JSONObject = when (up.type) {
        "anthropic" -> {
            val sb = StringBuilder()
            val c = obj.optJSONArray("content")
            if (c != null) for (i in 0 until c.length()) {
                val p = c.optJSONObject(i) ?: continue
                if (p.optString("type") == "text") sb.append(p.optString("text", ""))
            }
            val u = obj.optJSONObject("usage") ?: JSONObject()
            envelope(
                obj.optString("id", "chatcmpl-anthropic"), model, sb.toString(),
                ANTHROPIC_FINISH[obj.optString("stop_reason", "end_turn")] ?: "stop",
                usage(u.optInt("input_tokens"), u.optInt("output_tokens"))
            )
        }
        "gemini" -> {
            val sb = StringBuilder()
            val cands = obj.optJSONArray("candidates")
            var finish = "stop"
            if (cands != null && cands.length() > 0) {
                val c0 = cands.getJSONObject(0)
                finish = GEMINI_FINISH[c0.optString("finishReason", "STOP")] ?: "stop"
                val parts = c0.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null) for (i in 0 until parts.length()) {
                    sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                }
            }
            val um = obj.optJSONObject("usageMetadata") ?: JSONObject()
            envelope(
                "chatcmpl-gemini", model, sb.toString(), finish,
                usage(um.optInt("promptTokenCount"), um.optInt("candidatesTokenCount"))
            )
        }
        else -> {
            val msg = obj.optJSONObject("message") ?: JSONObject()
            envelope(
                "chatcmpl-ollama", model, msg.optString("content", ""),
                if (obj.optString("done_reason") == "length") "length" else "stop",
                usage(obj.optInt("prompt_eval_count"), obj.optInt("eval_count"))
            )
        }
    }

    // ---------------------------------------------------------- 流式

    private fun sse(out: Writer, obj: JSONObject) {
        out.write("data: ")
        out.write(obj.toString())
        out.write("\n\n")
        out.flush()
    }

    private fun chunk(cid: String, created: Long, model: String, delta: JSONObject, finish: String? = null) =
        JSONObject()
            .put("id", cid)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(JSONObject()
                .put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL)))

    /**
     * 把上游流翻译成 OpenAI SSE 并写入 out。
     * 返回 (promptTokens, completionTokens)。
     */
    fun streamToOpenAi(
        up: Upstream, input: InputStream, out: Writer, model: String
    ): Pair<Int, Int> {
        val cid = "chatcmpl-" + System.currentTimeMillis()
        val created = System.currentTimeMillis() / 1000
        var pt = 0
        var ct = 0
        var finish = "stop"

        sse(out, chunk(cid, created, model, JSONObject().put("role", "assistant")))

        val reader = input.bufferedReader(Charsets.UTF_8)
        reader.useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val payload = when {
                    line.startsWith("data:") -> line.removePrefix("data:").trim()
                    up.type == "ollama" -> line          // ollama 是 NDJSON
                    else -> continue
                }
                if (payload.isEmpty() || payload == "[DONE]") continue
                val o = try { JSONObject(payload) } catch (e: Exception) { continue }

                when (up.type) {
                    "anthropic" -> when (o.optString("type")) {
                        "content_block_delta" -> {
                            val d = o.optJSONObject("delta") ?: JSONObject()
                            if (d.optString("type") == "text_delta") {
                                val t = d.optString("text", "")
                                if (t.isNotEmpty()) sse(out, chunk(cid, created, model,
                                    JSONObject().put("content", t)))
                            }
                        }
                        "message_start" -> {
                            pt = o.optJSONObject("message")?.optJSONObject("usage")
                                ?.optInt("input_tokens") ?: pt
                        }
                        "message_delta" -> {
                            o.optJSONObject("usage")?.let { ct = it.optInt("output_tokens", ct) }
                            val fr = o.optJSONObject("delta")?.optString("stop_reason", "")
                            if (!fr.isNullOrEmpty()) finish = ANTHROPIC_FINISH[fr] ?: "stop"
                        }
                    }
                    "gemini" -> {
                        val cands = o.optJSONArray("candidates")
                        if (cands != null && cands.length() > 0) {
                            val c0 = cands.getJSONObject(0)
                            val parts = c0.optJSONObject("content")?.optJSONArray("parts")
                            if (parts != null) for (i in 0 until parts.length()) {
                                val t = parts.optJSONObject(i)?.optString("text", "") ?: ""
                                if (t.isNotEmpty()) sse(out, chunk(cid, created, model,
                                    JSONObject().put("content", t)))
                            }
                            val fr = c0.optString("finishReason", "")
                            if (fr.isNotEmpty()) finish = GEMINI_FINISH[fr] ?: "stop"
                        }
                        o.optJSONObject("usageMetadata")?.let {
                            pt = it.optInt("promptTokenCount", pt)
                            ct = it.optInt("candidatesTokenCount", ct)
                        }
                    }
                    else -> {
                        val msg = o.optJSONObject("message") ?: JSONObject()
                        val t = msg.optString("content", "")
                        if (t.isNotEmpty()) sse(out, chunk(cid, created, model,
                            JSONObject().put("content", t)))
                        if (o.optBoolean("done", false)) {
                            pt = o.optInt("prompt_eval_count", pt)
                            ct = o.optInt("eval_count", ct)
                            if (o.optString("done_reason") == "length") finish = "length"
                        }
                    }
                }
            }
        }

        sse(out, chunk(cid, created, model, JSONObject(), finish))
        if (pt > 0 || ct > 0) {
            sse(out, JSONObject()
                .put("id", cid).put("object", "chat.completion.chunk")
                .put("created", created).put("model", model)
                .put("choices", JSONArray())
                .put("usage", usage(pt, ct)))
        }
        out.write("data: [DONE]\n\n")
        out.flush()
        return pt to ct
    }
}
