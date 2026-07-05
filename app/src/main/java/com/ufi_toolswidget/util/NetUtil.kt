package com.ufi_toolswidget.util

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object NetUtil {
    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    // 合并而非替换：保留同 host 下其他请求设置的 cookie
                    val existing = cookieStore[url.host].orEmpty().toMutableList()
                    for (newCookie in cookies) {
                        existing.removeAll { it.name == newCookie.name }
                        existing.add(newCookie)
                    }
                    cookieStore[url.host] = existing
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    // 过滤已过期和不匹配的 cookie
                    val now = System.currentTimeMillis()
                    return cookieStore[url.host]?.filter {
                        it.matches(url) && it.expiresAt > now
                    } ?: emptyList()
                }
            }).build()
    }

    /** 清除所有缓存的 cookie（登出/切换设备时调用） */
    fun clearCookies() {
        cookieStore.clear()
    }

    fun saveCookies(host: String, cookies: List<Cookie>) {
        cookieStore[host] = cookies
    }

    // 缓存 MessageDigest / Mac 实例，避免每次调用都执行 JCA Provider 查找
    private val sha256Digest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
    private val hmacMd5Mac = ThreadLocal.withInitial { Mac.getInstance("HmacMD5") }

    // SHA256 字符串哈希 (返回十六进制字符串，用于 Authorization 等)
    fun sha256(input: String): String {
        val digest = sha256Digest.get() ?: MessageDigest.getInstance("SHA-256")
        digest.reset()
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    // SHA256 字节哈希 (返回原始字节数组)
    private fun sha256Bytes(input: ByteArray): ByteArray {
        val digest = sha256Digest.get() ?: MessageDigest.getInstance("SHA-256")
        digest.reset()
        return digest.digest(input)
    }

    // 内部通用哈希：字节 -> 十六进制字符串
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // HMAC-MD5 运算
    private fun hmacMd5(data: String, key: String): ByteArray {
        val mac = hmacMd5Mac.get() ?: Mac.getInstance("HmacMD5")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacMD5")
        mac.init(secretKey)
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    /**
     * 严格匹配 UFI-TOOLS (JS) 的签名逻辑：
     *   HMAC-MD5 → 16 bytes → 二分(各8 bytes)
     *   → SHA256(part1) + SHA256(part2) = 64 bytes
     *   → SHA256(64 bytes) → hex
     */
    fun generateKanoSign(method: String, path: String, timestamp: Long, context: android.content.Context): String {
        // 1. 构造 rawData
        val rawData = "minikano${method.uppercase()}$path$timestamp"
        
        // 2. HMAC-MD5 加密 → 16 字节（使用用户配置的密钥）
        val hmacBytes = hmacMd5(rawData, SPUtil.getSecretKey(context))
        
        // 3. 将 HMAC-MD5 的原始字节二分为两部分 (各 8 字节)
        val mid = hmacBytes.size / 2  // 16 / 2 = 8
        val part1 = hmacBytes.copyOfRange(0, mid)      // bytes[0..7]
        val part2 = hmacBytes.copyOfRange(mid, hmacBytes.size) // bytes[8..15]
        
        // 4. 分别对这两段原始字节做 SHA256 (各输出 32 字节)
        val sha1 = sha256Bytes(part1)
        val sha2 = sha256Bytes(part2)
        
        // 5. 将 sha1 + sha2 的原始字节拼接 (32 + 32 = 64 字节) 并进行最终 SHA256
        val combined = sha1 + sha2
        val finalHash = sha256Bytes(combined)
        
        return bytesToHex(finalHash)
    }
}
