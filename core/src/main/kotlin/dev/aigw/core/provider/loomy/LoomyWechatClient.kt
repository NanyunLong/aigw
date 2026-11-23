package dev.aigw.core.provider.loomy

import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * 微信网页扫码（`snsapi_login`）的 HTTP 交互。
 *
 * 官方客户端把扫码页交给 WebView `loadUrl(qrconnect 链接)`，授权成功后由 WebView 拦截
 * 回调 URL 取 `code`（回调地址是**官方固定远端** `loomy.xunfei.cn/oauth/wechat/callback`，
 * 本机接不住，所以这里不模拟 WebView，而是直接走微信网页扫码的标准三步）：
 *
 * 1. [beginQr]：拉 `qrconnect` 页面，从中解析出本次会话的 `uuid`；
 * 2. [qrImageUrl]：用 `uuid` 拼二维码图片地址（返回 JPEG 字节，可直接渲染）；
 * 3. [pollQr]：长轮询 `connect/l/qrconnect?uuid=…`，直到拿到 `code` / 过期 / 失败。
 *
 * 微信把扫码状态放在轮询响应的 JS 变量里：
 * ```
 * window.wx_errcode=408;window.wx_code='';   // 408 = 等待扫码
 * window.wx_errcode=405;window.wx_code='';   // 405 = 已扫码，等待确认
 * window.wx_errcode=404;window.wx_code='';   // 404 = 二维码过期
 * window.wx_errcode=200;window.wx_code='<code>'; // 200 = 授权成功，wx_code 是授权 code
 * ```
 *
 * 拿到的 `code` 交给 [LoomyAuthClient.loginByWechat] 换 session。
 *
 * 注意：微信网页扫码对 UA 敏感（移动 UA 会返回跳转页，拿不到 uuid），
 * 所以这里一律用桌面 Chrome UA，与官方桌面客户端行为一致。
 */
class LoomyWechatClient(
    private val appId: String = LoomyConstants.WECHAT_APP_ID,
    private val redirectUri: String = LoomyConstants.WECHAT_REDIRECT_URI,
) {

    /** 桌面 UA：微信 qrconnect 只对桌面 UA 返回完整扫码页（含 uuid）。 */
    private val desktopUA = DESKTOP_UA

    /** 一次扫码会话的开始结果。 */
    data class QrSession(val uuid: String, val qrImageUrl: String)

    /** 轮询结果。 */
    sealed interface PollResult {
        /** 还在等用户扫码（或已扫码待确认）。 */
        data object Waiting : PollResult
        /** 授权成功，[code] 是微信授权 code（一次性，需立即换 session）。 */
        data class Success(val code: String) : PollResult
        /** 二维码已过期，需要重新 [beginQr]。 */
        data object Expired : PollResult
        /** 微信侧拒绝/异常（如 appid 无效）。 */
        data class Failed(val reason: String) : PollResult
    }

    /**
     * 拉扫码页并解析出 `uuid`。
     *
     * @throws LoomyApiException 网络失败或页面里找不到 uuid（上游改版 / appid 被禁）。
     */
    fun beginQr(state: String = "loomy"): QrSession {
        val url = qrconnectUrl(state)
        val (status, body) = httpGet(url)
        if (status !in 200..299) {
            throw LoomyApiException(status, LoomyErrorKind.SERVER, body)
        }
        val html = body
        val uuid = UUID_PATTERNS.asSequence()
            .mapNotNull { pattern ->
                val m = pattern.matcher(html)
                if (m.find()) m.group(1) else null
            }
            .firstOrNull()
            ?: throw LoomyApiException(
                status,
                LoomyErrorKind.CLIENT,
                "微信扫码页没有返回 uuid（页面片段：${html.take(160)}）",
            )
        return QrSession(uuid = uuid, qrImageUrl = QR_IMAGE_BASE + uuid)
    }

    /**
     * 下载二维码图片字节（JPEG）。
     *
     * 调用方负责渲染。图片通常几十 KB，直接读入内存。
     */
    fun fetchQrImage(uuid: String): ByteArray {
        val (status, bytes) = httpGetBytes(QR_IMAGE_BASE + uuid)
        if (status !in 200..299) {
            throw LoomyApiException(status, LoomyErrorKind.SERVER, "拉取二维码图片失败（HTTP $status）")
        }
        return bytes
    }

    /**
     * 长轮询一次：微信接口会挂起等待，直到用户扫码 / 超时 / 过期才返回。
     *
     * 单次 [pollQr] 最长约 30~60 秒（由上游控制）。返回 [PollResult.Waiting] 时调用方
     * 应隔一小段时间再轮询，直到拿到非 Waiting 结果或超过总时限。
     */
    fun pollQr(uuid: String): PollResult {
        val url = QR_POLL_BASE + "?uuid=$uuid&_=${System.currentTimeMillis()}"
        val (status, body) = httpGet(url)
        if (status !in 200..299) {
            return PollResult.Failed("轮询失败（HTTP $status）")
        }
        val errcode = WX_ERRCODE.matcher(body).let { if (it.find()) it.group(1) else "" }
        val wxCode = WX_CODE.matcher(body).let { if (it.find()) it.group(1) else "" }
        return when (errcode) {
            "200" -> if (wxCode.isEmpty()) PollResult.Failed("微信返回成功但没有授权码")
            else PollResult.Success(wxCode)
            "404" -> PollResult.Expired
            "408", "405" -> PollResult.Waiting
            else -> {
                // 其它码（如 402/403）是微信侧状态，先看 body 里有没有可读信息
                val hint = body.replace(Regex("""\s+"""), " ").take(160)
                PollResult.Failed("微信返回异常状态（code=$errcode）：$hint")
            }
        }
    }

    /** 拼 qrconnect 链接（与 [LoomyConstants.wechatQrUrl] 一致，但返回完整 URL）。 */
    private fun qrconnectUrl(state: String): String =
        "https://open.weixin.qq.com/connect/qrconnect" +
            "?appid=$appId" +
            "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
            "&response_type=code&scope=snsapi_login&state=$state#wechat_redirect"

    // ------------------------------------------------------------------ HTTP

    private fun httpGet(url: String): Pair<Int, String> {
        val (status, bytes) = httpGetBytes(url)
        return status to String(bytes, Charsets.UTF_8)
    }

    private fun httpGetBytes(url: String): Pair<Int, ByteArray> {
        val conn = try {
            (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = LoomyHttp.CONNECT_TIMEOUT_MS
                readTimeout = LoomyHttp.READ_TIMEOUT_MS
                setRequestProperty("User-Agent", desktopUA)
                setRequestProperty("Referer", "https://open.weixin.qq.com/")
                setRequestProperty("Accept", "*/*")
                instanceFollowRedirects = true
            }
        } catch (e: Exception) {
            throw LoomyApiException(0, LoomyErrorKind.NETWORK, e.message.orEmpty(), cause = e)
        }
        return try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            status to bytes
        } catch (e: Exception) {
            throw LoomyApiException(0, LoomyErrorKind.NETWORK, e.message.orEmpty(), cause = e)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val QR_IMAGE_BASE = "https://open.weixin.qq.com/connect/qrcode/"
        private const val QR_POLL_BASE = "https://open.weixin.qq.com/connect/l/qrconnect"

        /** qrconnect 页面里 uuid 的可能写法（官方页面用 `connect/qrcode/<uuid>` 拼图）。 */
        private val UUID_PATTERNS = listOf(
            Pattern.compile("""connect/qrcode/([A-Za-z0-9_\-]{10,})"""),
            Pattern.compile("""uuid\s*[=:]\s*["']([A-Za-z0-9_\-]{10,})"""),
            Pattern.compile("""["']uuid["']\s*:\s*["']([A-Za-z0-9_\-]{10,})"""),
        )

        private val WX_ERRCODE = Pattern.compile("""wx_errcode\s*=\s*(\d+)""")
        private val WX_CODE = Pattern.compile("""wx_code\s*=\s*'([^']*)'""")
    }
}