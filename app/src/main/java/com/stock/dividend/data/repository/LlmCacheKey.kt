package com.stock.dividend.data.repository

import java.security.MessageDigest

/**
 * LLM 结果缓存 key：SHA-256(system + "\n" + user) 的 hex（纯函数）。
 * prompt 由全部输入序列化而来，输入一变 key 必变，保证不返回过期解读。
 */
object LlmCacheKey {

    fun of(system: String, user: String): String = try {
        MessageDigest.getInstance("SHA-256")
            .digest((system + "\n" + user).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }
}
