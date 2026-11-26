package dev.aigw.core.provider.lingma

import com.google.gson.JsonObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * 通义灵码的加密工具：Encode=1 解码、cosy 凭证派生、COSY 签名。
 *
 * 全部对照 lingma2api（Go）的 internal/auth 与 internal/proxy 实现：
 * - [lingmaDecode] ← encode1.go 的 lingmaDecode（自定义 base64 字母表 + 三块重排）
 * - [generateCosyCredentials] ← cosy_generate.go（RSA-PKCS1v15 加密临时密钥 + AES-128-CBC 加密用户信息）
 * - [buildCosySignature] ← signature.go 的 BuildBearer（MD5 五段拼接）
 */
internal object LingmaCrypto {

    /** Encode=1 的自定义 base64 字母表（对照 lingma2api encode1.go）。 */
    private const val ALPHA = "_doRTgHZBKcGVjlvpC,@aFSx#DPuNJme&i*MzLOEn)sUrthbf%Y^w.(kIQyXqWA!"
    private const val STD_B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 内嵌的 1024-bit RSA 公钥（IDA 从 Lingma 客户端提取，PKIX DER）。 */
    private const val RSA_PUB_KEY_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDA8iMH5c02LilrsERw9t6Pv5Nc" +
            "4k6Pz1EaDicBMpdpxKduSZu5OANqUq8er4GM95omAGIOPOh+Nx0spthYA2BqGz+l" +
            "6HRkPJ7S236FZz73In/KVuLnwI8JJ2CbuJap8kvheCCZpmAWpb/cPx/3Vr/J6I17" +
            "XcW+ML9FoCI6AOvOzwIDAQAB"

    private val alphaToStd = HashMap<Char, Char>().apply {
        for (i in ALPHA.indices) put(ALPHA[i], STD_B64[i])
    }

    /**
     * Encode=1 解码：去 $ 填充 → 三块重排（b2+b1+b0 → b0+b1+b2）→ 字母表反替换 → base64 解码。
     * 对照 encode1.go 的 lingmaDecode。
     */
    fun lingmaDecode(body: String): ByteArray? {
        // 1. 去掉 $ 填充段
        val dollarStart = body.indexOf('$')
        val clean = if (dollarStart < 0) {
            body
        } else {
            var p = 0
            var pos = dollarStart
            while (pos < body.length && body[pos] == '$') {
                p++
                pos++
            }
            body.substring(0, dollarStart) + body.substring(dollarStart + p)
        }
        if (clean.isEmpty()) return null
        val e = clean.length

        // 2. 全枚举分块：lingma2api 的 Encode/Decode 公式在 e%3!=0 时不自洽，
        //    真实分块可能与理论值差 ±1；枚举所有 (b2,b1) 组合，选明文合法者。
        //    合法性判据：UTF-8 解码后含恰好 expectedParts-1 个 \n，且各段可打印。
        for (b2Size in 0..e) {
            for (b1Size in 0..(e - b2Size)) {
                val b0Size = e - b2Size - b1Size
                if (b0Size < 0) continue
                val reordered = clean.substring(b2Size + b1Size) +
                    clean.substring(b2Size, b2Size + b1Size) +
                    clean.substring(0, b2Size)
                val converted = StringBuilder(reordered.length)
                for (ch in reordered) {
                    alphaToStd[ch]?.let { converted.append(it) }
                }
                var stdStr = converted.toString()
                if (stdStr.isEmpty()) continue
                val missing = (4 - stdStr.length % 4) % 4
                stdStr += "=".repeat(missing)
                val bytes = runCatching { Base64.getDecoder().decode(stdStr) }.getOrNull() ?: continue
                val text = String(bytes, Charsets.UTF_8)
                // 必须恰好 3 段（uid\naid\nname），且各段不含控制字符
                val parts = text.split("\n")
                if (parts.size == 3 && parts.all { p -> p.all { it.code in 0x20..0x7E || it.code > 0x7F } }) {
                    return bytes
                }
            }
        }
        return null
    }

    /** 解码 Encode=1 字符串并按换行拆成恰好 [expectedParts] 段。 */
    fun customDecryptParts(encoded: String, expectedParts: Int): List<String>? {
        val raw = lingmaDecode(encoded) ?: return null
        return String(raw, Charsets.UTF_8).split("\n")
    }

    /** 诊断：解码失败时打印每步中间结果，定位是分块、字母表还是 base64 环节出错。 */
    fun diagnoseDecode(encoded: String): String {
        val sb = StringBuilder()
        sb.append("[len=${encoded.length}] ")
        // 去 $
        val dollarStart = encoded.indexOf('$')
        val clean = if (dollarStart < 0) encoded else {
            var p = 0
            var pos = dollarStart
            while (pos < encoded.length && encoded[pos] == '$') { p++; pos++ }
            encoded.substring(0, dollarStart) + encoded.substring(dollarStart + p)
        }
        sb.append("[clean=${clean.length}] ")
        // 非法字符
        val illegal = clean.filter { it !in ALPHA }
        if (illegal.isNotEmpty()) sb.append("[非法字符=${illegal.take(10)}] ")
        val e = clean.length
        val c = (e + 2) / 3
        val b2 = maxOf(0, e - 2 * c)
        var b1 = c
        if (b2 + b1 > e) b1 = e - b2
        sb.append("[分块 b2=$b2 b1=$b1 b0=${e - b2 - b1}] ")
        // 重排 + 替换
        val reordered = clean.substring(b2 + b1) + clean.substring(b2, b2 + b1) + clean.substring(0, b2)
        val conv = StringBuilder()
        for (ch in reordered) alphaToStd[ch]?.let { conv.append(it) }
        var stdStr = conv.toString()
        val missing = (4 - stdStr.length % 4) % 4
        stdStr += "=".repeat(missing)
        sb.append("[stdLen=${conv.length} pad=$missing] ")
        sb.append("stdStr=${stdStr.take(80)} ")
        val bytes = runCatching { Base64.getDecoder().decode(stdStr) }.getOrNull()
        if (bytes == null) sb.append("[base64解码失败]")
        else sb.append("[base64OK len=${bytes.size}] 明文=${String(bytes, Charsets.UTF_8).take(80)}")
        return sb.toString()
    }

    /**
     * 本地派生 cosy 凭证（对照 cosy_generate.go 的 GenerateCosyCredentials）：
     * 1. 随机 16 字节 hex 取前 16 字符作 AES 临时密钥
     * 2. RSA-PKCS1v15 加密临时密钥 → base64 → cosy_key
     * 3. 21 字段用户信息 JSON → AES-128-CBC(key=IV=临时密钥, PKCS5) → base64 → encrypt_user_info
     */
    fun generateCosyCredentials(
        name: String,
        uid: String,
        aid: String,
        oauthToken: String,
        refreshToken: String,
    ): Pair<String, String>? {
        return try {
            // 1. 生成临时密钥（16 字节 hex 的前 16 字符）
            val randBuf = ByteArray(16)
            SecureRandom().nextBytes(randBuf)
            val tempKey = hex(randBuf).substring(0, 16)
            val keyBytes = tempKey.toByteArray(Charsets.UTF_8)

            // 2. RSA-PKCS1v15 加密临时密钥 → base64 → cosy_key
            val pubKeyBytes = Base64.getDecoder().decode(RSA_PUB_KEY_B64)
            val pubKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(pubKeyBytes))
            val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsaCipher.init(Cipher.ENCRYPT_MODE, pubKey)
            val encryptedKey = rsaCipher.doFinal(keyBytes)
            val cosyKey = Base64.getEncoder().encodeToString(encryptedKey)

            // 3. 21 字段用户信息 JSON → AES-128-CBC → base64 → encrypt_user_info
            val effectiveAid = aid.ifEmpty { uid }
            val info = JsonObject().apply {
                addProperty("name", name)
                addProperty("aid", effectiveAid)
                addProperty("uid", uid)
                addProperty("yx_uid", "")
                addProperty("organization_id", "")
                addProperty("organization_name", "")
                addProperty("staffId", "")
                addProperty("avatar_url", "")
                addProperty("key", "")
                addProperty("encrypt_user_info", "")
                addProperty("user_source_channel", "")
                addProperty("security_oauth_token", oauthToken)
                addProperty("refresh_token", refreshToken)
                addProperty("expire_time", 0)
                addProperty("user_type", "")
                addProperty("data_policy_agreed", false)
                addProperty("email", "")
                addProperty("is_data_policy_modifiable", false)
                addProperty("is_quota_exceeded", false)
                addProperty("organization_tags", null as String?)
            }
            val infoJson = info.toString()
            val aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
            val ivSpec = javax.crypto.spec.IvParameterSpec(keyBytes)
            aesCipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            val encrypted = aesCipher.doFinal(infoJson.toByteArray(Charsets.UTF_8))
            val encryptUserInfo = Base64.getEncoder().encodeToString(encrypted)

            cosyKey to encryptUserInfo
        } catch (e: Exception) {
            null
        }
    }

    /**
     * COSY 签名（对照 signature.go 的 BuildBearer）：
     * payload = {cosyVersion, ideVersion:"", info, requestId, version:"v1"} → base64
     * sign = md5(payloadBase64 + "\n" + cosyKey + "\n" + date + "\n" + body + "\n" + sigPath)
     * 返回 (Bearer 值, date)。
     */
    fun buildCosySignature(
        cosyKey: String,
        encryptUserInfo: String,
        path: String,
        body: String,
        nowSeconds: Long,
        requestId: String,
    ): Pair<String, String> {
        val date = nowSeconds.toString()
        val payload = JsonObject().apply {
            addProperty("cosyVersion", LingmaProvider.COSY_VERSION)
            addProperty("ideVersion", "")
            addProperty("info", encryptUserInfo)
            addProperty("requestId", requestId)
            addProperty("version", "v1")
        }
        val payloadBase64 = Base64.getEncoder().encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
        val sigPath = normalizePath(path)
        val preimage = payloadBase64 + "\n" + cosyKey + "\n" + date + "\n" + body + "\n" + sigPath
        val signature = md5Hex(preimage)
        return "COSY.$payloadBase64.$signature" to date
    }

    /** 归一化路径：去 query、去 /algo 前缀（对照 signature.go 的 normalizePath）。 */
    fun normalizePath(path: String): String {
        val noQuery = path.substringBefore("?")
        return if (noQuery.startsWith("/algo")) noQuery.removePrefix("/algo") else noQuery
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
