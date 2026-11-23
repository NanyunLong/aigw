package dev.aigw.core.provider.loomy

import com.google.gson.JsonObject
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import java.util.UUID

/**
 * 讯飞账号服务客户端：短信验证码登录、用户信息、登出。
 *
 * 这一层的每个请求都要 HMAC-SHA1 签名（[LoomySign]），AccessKey 随客户端分发。
 */
class LoomyAuthClient(
    private val accountBase: String = LoomyConstants.ACCOUNT_BASE,
    private val accessKeyId: String = LoomyConstants.ACCESS_KEY_ID,
    private val accessKeySecret: String = LoomyConstants.ACCESS_KEY_SECRET,
    private val appId: String = LoomyConstants.APP_ID,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * 发送短信验证码，返回上游的 msgid（登录时回传）。
     *
     * [ccode] 是国家码：上游按它路由短信通道，**写死 86 会让非大陆号码收不到验证码**。
     */
    fun sendSmsCode(phone: String, ccode: String = LoomyConstants.DEFAULT_CCODE): String {
        val body = envelope(
            JsonObject().apply {
                addProperty("ccode", ccode)
                addProperty("phone", phone)
                addProperty("expire", LoomyConstants.SMS_CODE_EXPIRE_SECONDS)
            },
        )
        val data = call(LoomyConstants.PATH_SEND_SMS_CODE, body)
        val msgid = data.str("msgid").ifEmpty { data.str("msgId") }
        if (msgid.isEmpty()) {
            throw LoomyApiException(200, LoomyErrorKind.CLIENT, data.toString(), "MISSING_MSGID")
        }
        return msgid
    }

    /** 用验证码换 session；[ccode] 必须与发码时一致。 */
    fun loginBySmsCode(
        phone: String,
        code: String,
        msgid: String,
        ccode: String = LoomyConstants.DEFAULT_CCODE,
    ): LoomyAccount {
        val body = envelope(
            JsonObject().apply {
                addProperty("ccode", ccode)
                addProperty("phone", phone)
                addProperty("mcode", code)
                addProperty("msgid", msgid)
                addProperty("expire", LoomyConstants.SESSION_EXPIRE_SECONDS)
            },
        )
        val data = call(LoomyConstants.PATH_LOGIN_BY_SMS, body)
        val session = data.str("session")
        if (session.isEmpty()) {
            throw LoomyApiException(200, LoomyErrorKind.CLIENT, data.toString(), "MISSING_SESSION")
        }
        val uid = data.str("userid").ifEmpty { data.str("userId") }
        val issuedAt = nowMillis()
        return LoomyAccount(
            uid = uid,
            phone = phone,
            session = session,
            nickname = "",
            createdAtMillis = issuedAt,
            expiresAtMillis = issuedAt + LoomyConstants.SESSION_EXPIRE_SECONDS * 1000L,
        )
    }

    /**
     * 用微信扫码授权 code 换 session。
     *
     * 请求体结构逆向自 Loomy2API 1.2.31 `LE1/d;->R()`：
     *
     * ```
     * {"base": {...}, "param": {"tcode": {"code": "<微信 code>"}, "type": "wx"}}
     * ```
     *
     * **实测验证**（2026-11-23）：`tcode` 必须是**嵌套对象 `{code: ...}`**，不能是字符串。
     * 传字符串返回 `020001 解析请求包错误`；不传或结构不对返回 `010003 请求参数错误`；
     * 结构正确但 code 已过期时返回 `270101 第三方鉴权失败`——证明该结构已被上游正确解析。
     */
    fun loginByWechat(code: String): LoomyAccount {
        val body = envelope(
            JsonObject().apply {
                add("tcode", JsonObject().apply {
                    addProperty("code", code)
                })
                addProperty("type", LoomyConstants.WECHAT_TYPE)
            },
        )
        val data = call(LoomyConstants.PATH_LOGIN_BY_WECHAT, body)
        // 微信首次登录时，上游 bind/auth 不直接给 session，而是返回 rcode（需二次确认绑定），
        // 必须再拿 rcode 调 bind/skip 才发 session；老账号则 bind/auth 直接带 session。
        // 只做第一步、直接找 session 会命中 MISSING_SESSION，界面表现为「上游拒绝了本次请求（HTTP 200）」。
        var finalData = data
        var session = finalData.str("session")
        if (session.isEmpty()) {
            val rcode = finalData.str("rcode").ifEmpty { finalData.str("rCode") }
            if (rcode.isNotEmpty()) {
                val skipBody = envelope(JsonObject().apply { addProperty("rcode", rcode) })
                finalData = call(LoomyConstants.PATH_WECHAT_BIND_SKIP, skipBody)
                session = finalData.str("session")
            }
        }
        if (session.isEmpty()) {
            // 兜底：把上游 data 原文塞进异常，便于定位真实字段名（rcode/session 等）。
            throw IllegalStateException("微信登录未拿到 session；bind/auth 返回=$data；bind/skip 返回=$finalData")
        }
        val uid = finalData.str("userid").ifEmpty { finalData.str("userId") }
        val issuedAt = nowMillis()
        return LoomyAccount(
            uid = uid,
            phone = "",
            session = session,
            nickname = finalData.str("nickname"),
            createdAtMillis = issuedAt,
            expiresAtMillis = issuedAt + LoomyConstants.SESSION_EXPIRE_SECONDS * 1000L,
        )
    }

    /**
     * 查用户信息。
     *
     * 上游字段名未经真实账号验证，所以按多种可能取值，取不到就留空——
     * 昵称只用于界面展示，不该因为解析不到就让登录失败。
     */
    fun getUserInfo(session: String): JsonObject {
        val body = envelope(JsonObject().apply { addProperty("session", session) })
        return call(LoomyConstants.PATH_USER_INFO, body)
    }

    /** 从用户信息里尽力取昵称。 */
    fun nicknameOf(info: JsonObject): String {
        val candidates = listOf("nickname", "nickName", "name", "username", "uname")
        for (key in candidates) {
            val v = info.str(key)
            if (v.isNotEmpty()) return v
        }
        val nested = info.objOrNull("userinfo") ?: info.objOrNull("userInfo") ?: info.objOrNull("user")
        if (nested != null) {
            for (key in candidates) {
                val v = nested.str(key)
                if (v.isNotEmpty()) return v
            }
        }
        return ""
    }

    /** 组装 `{base, param}` 请求体。 */
    private fun envelope(param: JsonObject): String = JsonObject().apply {
        add("base", JsonObject().apply {
            addProperty("appid", appId)
            addProperty("modelid", "Web")
            addProperty("version", "1.0.0")
            addProperty("devid", "web")
            addProperty("ua", "Loomy|Desktop|Electron|macOS")
            addProperty("traceid", UUID.randomUUID().toString().replace("-", ""))
        })
        add("param", param)
    }.toString()

    /** 签名 → 请求 → 解出 `data`。业务码非成功时抛 [LoomyApiException]。 */
    private fun call(path: String, body: String): JsonObject {
        val signed = LoomySign.authorization(accessKeyId, accessKeySecret, "POST", path, body)
        val (status, responseBody) = httpJson(
            url = accountBase + path,
            method = "POST",
            headers = signed.toHeaders(),
            body = body,
        )
        return decodeEnvelope(status, responseBody).objOrNull("data") ?: JsonObject()
    }
}