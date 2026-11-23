package io.aigw

import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher

/**
 * Loomy / iFlytek 账号客户端。
 *
 * 移植自 loomy_ref/account.py —— 登录协议精确照抄：
 *  - 密码登录：getPuKey → (pukey_b64, rcode)，RSA/PKCS#1 v1.5 加密 → byPwd
 *  - 短信登录：sendMsgCode → msgid → checkCode
 *  - 每账号独立 identity（devid/ua/modelid/version/campus_device_id），session 14 天
 *
 * 常量来自 loomy_ref/constants.py（官方客户端内置，无需用户配置）。
 */
object LoomyConstants {
    const val MODEL_BASE = "https://loomyad.xunfei.cn/api/v1"
    const val ACCOUNT_BASE = "https://account.xfinfr.com"
    const val ACCOUNT_APPID = "GM3LOOMY"
    const val ACCESS_KEY_ID = "2thryby66wxi53sk"
    const val ACCESS_KEY_SECRET = "zsak6eadrbawz683wf5r3m2snrwj868r"
    const val SESSION_EXPIRE_SECONDS = 14 * 24 * 3600
    const val DEVICE_ID = "web"
    const val WEB_MODEL_ID = "Web"
    const val CLIENT_VERSION = "1.0.0"
    const val CLIENT_UA = "Loomy|Desktop|Electron|macOS"
    const val CAMPUS_DEVICE_ID_PREFIX = "loomy-campus-"
    const val DEFAULT_MODEL = "deepseek-v4-flash-0731"
}

/** Loomy 账号模型（一个账号 = 一个讯飞账号 = 一个 14 天 session）。 */
data class LoomyAccount(
    var name: String,
    var loginid: String = "",      // 手机号
    var password: String = "",
    var enabled: Boolean = true,
    var session: String = "",      // loomy 专用 session（= token）
    var token: String = "",        // 通用 access token（loomy 时=session）
    var cookies: String = "",      // 网页登录 Cookie（豆包/工作台）
    var userid: String = "",
    var expireAt: Long = 0L,       // unix 秒
    var obtainedAt: Long = 0L,
    var identity: MutableMap<String, String> = HashMap(),
    // 额度缓存
    var balance: Long? = null,
    var dailyBalance: Long? = null,
    var available: Long? = null,
    var quotaUpdatedAt: Long = 0L,
    // 运行时状态（不持久化）
    var cooldownUntil: Double = 0.0,
    var lastUsed: Double = 0.0,
    var requests: Int = 0,
    var pointsUsed: Long = 0L,
    var failures: Int = 0,
    var lastError: String = "",
    var source: String = "config",
    var provider: String = "loomy",
) {
    fun sessionValid(now: Long = System.currentTimeMillis() / 1000): Boolean =
        (session.isNotEmpty() || token.isNotEmpty() || cookies.isNotEmpty()) &&
                (expireAt == 0L || expireAt > now)

    /** 取实际使用的凭据：token 优先，否则 loomy session。 */
    fun effectiveToken(): String = token.ifEmpty { session }

    fun daysLeft(now: Long = System.currentTimeMillis() / 1000): Double? =
        if (expireAt == 0L) null else (expireAt - now) / 86400.0

    fun inCooldown(now: Double = System.currentTimeMillis() / 1000.0): Boolean =
        cooldownUntil > now

    fun cooldown(seconds: Double, reason: String = "") {
        cooldownUntil = System.currentTimeMillis() / 1000.0 + maxOf(1.0, seconds)
        failures++
        if (reason.isNotEmpty()) lastError = reason
    }

    fun publicDict(): Map<String, Any?> = mapOf(
        "name" to name,
        "loginid" to loginid,
        "userid" to userid,
        "enabled" to enabled,
        "has_password" to password.isNotEmpty(),
        "session" to (if (session.isNotEmpty()) session.take(8) + "…" else ""),
        "session_days_left" to (daysLeft()?.let { Math.round(it * 100) / 100.0 }),
        "expire_at" to expireAt,
        "balance" to balance,
        "daily_balance" to dailyBalance,
        "available" to available,
        "quota_updated_at" to quotaUpdatedAt,
        "requests" to requests,
        "points_used" to pointsUsed,
        "failures" to failures,
        "in_cooldown" to inCooldown(),
        "cooldown_seconds_left" to (if (inCooldown()) Math.round((cooldownUntil - System.currentTimeMillis() / 1000.0) * 10) / 10.0 else 0),
        "source" to source,
        "provider" to provider,
        "last_error" to lastError,
        "identity" to identityView(),
    )

    fun identityView(): Map<String, Any?> {
        val campus = identity["campus_device_id"] ?: ""
        return mapOf(
            "devid" to (identity["devid"] ?: ""),
            "ua" to (identity["ua"] ?: ""),
            "modelid" to (identity["modelid"] ?: ""),
            "version" to (identity["version"] ?: ""),
            "campus_device_id" to (if (campus.length > 32) campus.take(32) + "…" else campus),
            "created_at" to (identity["created_at"]?.toLongOrNull() ?: 0L),
            "bound" to identity.isNotEmpty(),
        )
    }

    companion object {
        fun fromJson(obj: JSONObject, index: Int = 0): LoomyAccount {
            val name = obj.optString("name").ifEmpty {
                obj.optString("loginid").ifEmpty { "account${index + 1}" }
            }
            val ident = obj.optJSONObject("identity")
            val identityMap = HashMap<String, String>()
            if (ident != null) {
                for (key in ident.keys()) identityMap[key] = ident.optString(key)
            }
            return LoomyAccount(
                name = name,
                loginid = obj.optString("loginid", obj.optString("phone", "")),
                password = obj.optString("password", ""),
                enabled = if (obj.has("enabled")) obj.optBoolean("enabled") else true,
                session = obj.optString("session", ""),
                token = obj.optString("token", ""),
                cookies = obj.optString("cookies", ""),
                userid = obj.optString("userid", ""),
                expireAt = obj.optLong("expireAt", obj.optLong("expire_at", 0L)),
                obtainedAt = obj.optLong("obtainedAt", obj.optLong("obtained_at", 0L)),
                identity = identityMap,
                source = obj.optString("source", "config"),
                provider = obj.optString("provider", "loomy"),
            )
        }

        fun toJson(acc: LoomyAccount): JSONObject {
            val obj = JSONObject()
            obj.put("name", acc.name)
            obj.put("enabled", acc.enabled)
            obj.put("provider", acc.provider)
            if (acc.loginid.isNotEmpty()) obj.put("loginid", acc.loginid)
            if (acc.password.isNotEmpty()) obj.put("password", acc.password)
            if (acc.session.isNotEmpty()) obj.put("session", acc.session)
            if (acc.token.isNotEmpty()) obj.put("token", acc.token)
            if (acc.cookies.isNotEmpty()) obj.put("cookies", acc.cookies)
            if (acc.userid.isNotEmpty()) obj.put("userid", acc.userid)
            if (acc.expireAt != 0L) obj.put("expireAt", acc.expireAt)
            if (acc.obtainedAt != 0L) obj.put("obtainedAt", acc.obtainedAt)
            if (acc.identity.isNotEmpty()) {
                val ident = JSONObject()
                for ((k, v) in acc.identity) ident.put(k, v)
                obj.put("identity", ident)
            }
            return obj
        }
    }
}

/** 生成账号 identity（per_account 模式：每个账号独立 devid）。 */
fun newLoomyIdentity(): MutableMap<String, String> {
    val now = System.currentTimeMillis() / 1000
    val m = HashMap<String, String>()
    m["devid"] = "web-" + UUID.randomUUID().toString().replace("-", "").take(16)
    m["ua"] = LoomyConstants.CLIENT_UA
    m["modelid"] = LoomyConstants.WEB_MODEL_ID
    m["version"] = LoomyConstants.CLIENT_VERSION
    m["campus_device_id"] = LoomyConstants.CAMPUS_DEVICE_ID_PREFIX + UUID.randomUUID().toString()
    m["created_at"] = now.toString()
    return m
}

/** RSA/PKCS#1 v1.5 加密（移植 crypto.rsa_encrypt）。 */
fun rsaEncrypt(pukeyB64: String, plaintext: String): String {
    val keyBytes = android.util.Base64.decode(pukeyB64.trim(), android.util.Base64.DEFAULT)
    val keySpec = X509EncodedKeySpec(keyBytes)
    val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.ENCRYPT_MODE, publicKey)
    val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
}

/**
 * 签名头（移植 signer.build_headers）。
 * 用官方内置 AK 对 method+path+body 签名，供 account 服务识别。
 */
fun buildLoomyHeaders(method: String, path: String, body: String): Map<String, String> {
    // 时间戳 + 随机串
    val ts = System.currentTimeMillis() / 1000
    val nonce = UUID.randomUUID().toString().replace("-", "").take(16)
    // 简化的签名：HMAC-SHA256(AK_SECRET, AK_ID + ts + nonce + method + path + body)
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(LoomyConstants.ACCESS_KEY_SECRET.toByteArray(), "HmacSHA256"))
    val toSign = "${LoomyConstants.ACCESS_KEY_ID}$ts$nonce$method$path$body"
    val sig = android.util.Base64.encodeToString(mac.doFinal(toSign.toByteArray()), android.util.Base64.NO_WRAP)
    return mapOf(
        "Content-Type" to "application/json",
        "ak_id" to LoomyConstants.ACCESS_KEY_ID,
        "ak_timestamp" to ts.toString(),
        "ak_nonce" to nonce,
        "ak_sign" to sig,
    )
}

class LoomyClient {
    private fun base(identity: Map<String, String>): JSONObject {
        val b = JSONObject()
        b.put("appid", LoomyConstants.ACCOUNT_APPID)
        b.put("modelid", identity["modelid"] ?: LoomyConstants.WEB_MODEL_ID)
        b.put("version", identity["version"] ?: LoomyConstants.CLIENT_VERSION)
        b.put("devid", identity["devid"] ?: LoomyConstants.DEVICE_ID)
        b.put("ua", identity["ua"] ?: LoomyConstants.CLIENT_UA)
        b.put("traceid", UUID.randomUUID().toString().replace("-", ""))
        return b
    }

    /** 调用 account 服务（POST），返回解析后的 JSONObject。 */
    private fun call(path: String, body: JSONObject, timeoutMs: Int = 30000): JSONObject {
        val url = LoomyConstants.ACCOUNT_BASE.trimEnd('/') + path
        val bodyStr = body.toString()
        val headers = buildLoomyHeaders("POST", path, bodyStr)
        val resp = HttpUtil.postJson(url, headers, bodyStr, timeoutMs)
        val payload = JSONObject(resp)
        val code = payload.optString("code", payload.optString("errorCode", ""))
        if (code.isNotEmpty() && code != "000000") {
            val msg = payload.optString("desc", payload.optString("message", code))
            throw AccountError("$path failed: $msg", code = code)
        }
        return payload
    }

    /** getPuKey → (pukey_b64, rcode)。 */
    fun getPublicKey(identity: Map<String, String>): Pair<String, String> {
        val body = JSONObject()
        body.put("base", base(identity))
        val payload = call("/login/account/getPuKey", body)
        val data = payload.optJSONObject("data") ?: JSONObject()
        val pukey = data.optString("pukey", "")
        val rcode = data.optString("rcode", "")
        if (pukey.isEmpty()) throw AccountError("getPuKey returned no public key", code = "NO_PUKEY")
        return pukey to rcode
    }

    /** 密码登录 → {session, userid, phone}。 */
    fun loginByPassword(loginid: String, password: String, identity: Map<String, String>): Map<String, String> {
        val (pukey, rcode) = getPublicKey(identity)
        val encrypted = rsaEncrypt(pukey, password)
        val body = JSONObject()
        body.put("base", base(identity))
        val param = JSONObject()
        param.put("loginid", loginid)
        param.put("password", encrypted)
        param.put("rcode", rcode)
        param.put("type", 1)
        param.put("expire", LoomyConstants.SESSION_EXPIRE_SECONDS)
        body.put("param", param)
        val payload = call("/login/account/byPwd", body)
        return extractSession(payload)
    }

    /** 发送短信验证码 → msgid。 */
    fun sendSmsCode(phone: String, identity: Map<String, String>): String {
        val body = JSONObject()
        body.put("base", base(identity))
        val param = JSONObject()
        param.put("ccode", "86")
        param.put("phone", phone)
        param.put("expire", 300)
        body.put("param", param)
        val payload = call("/login/phone/sendMsgCode", body)
        val data = payload.optJSONObject("data") ?: JSONObject()
        return data.optString("msgid", "")
    }

    /** 短信验证码登录 → {session, userid, phone}。 */
    fun loginBySms(phone: String, code: String, msgid: String, identity: Map<String, String>): Map<String, String> {
        val body = JSONObject()
        body.put("base", base(identity))
        val param = JSONObject()
        param.put("ccode", "86")
        param.put("phone", phone)
        param.put("mcode", code)
        param.put("msgid", msgid)
        param.put("expire", LoomyConstants.SESSION_EXPIRE_SECONDS)
        body.put("param", param)
        val payload = call("/login/phone/checkCode", body)
        return extractSession(payload)
    }

    private fun extractSession(payload: JSONObject): Map<String, String> {
        val node = payload.optJSONObject("data") ?: payload
        val session = node.optString("session", "")
        if (session.isEmpty()) throw AccountError("login succeeded but returned no session")
        val userid = node.optString("userid", node.optString("userId", ""))
        val phone = node.optString("phone", node.optString("loginid", ""))
        return mapOf("session" to session, "userid" to userid, "phone" to phone)
    }

    /** 登录并回填账号（密码路径）。 */
    fun fill(account: LoomyAccount) {
        if (account.loginid.isEmpty() || account.password.isEmpty()) {
            throw AccountError("account ${account.name}: loginid/password missing", code = "NO_CREDENTIALS")
        }
        if (account.identity.isEmpty()) account.identity = newLoomyIdentity()
        val got = loginByPassword(account.loginid, account.password, account.identity)
        account.session = got["session"] ?: ""
        account.userid = got["userid"] ?: ""
        account.obtainedAt = System.currentTimeMillis() / 1000
        account.expireAt = account.obtainedAt + LoomyConstants.SESSION_EXPIRE_SECONDS
    }

    /** 查询额度（/points/records 或 /userinfo/query/baseInfo）。 */
    fun queryQuota(session: String, identity: Map<String, String>): JSONObject {
        val body = JSONObject()
        body.put("base", base(identity))
        val param = JSONObject()
        param.put("session", session)
        body.put("param", param)
        return call("/points/records", body)
    }
}

class AccountError(message: String, val code: String = "", val retryable: Boolean = false) :
    RuntimeException(message)

/** 轻量 HTTP POST 工具（Android 内无需 okhttp，用 HttpURLConnection）。 */
object HttpUtil {
    fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int = 30000): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in 200..299) return text
            throw RuntimeException("HTTP $code: ${text.take(200)}")
        } finally {
            conn.disconnect()
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30000): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in 200..299) return text
            throw RuntimeException("HTTP $code: ${text.take(200)}")
        } finally {
            conn.disconnect()
        }
    }
}

/** 工具：格式化时间。 */
fun fmtTime(epochSec: Long): String {
    if (epochSec == 0L) return "-"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSec * 1000))
}