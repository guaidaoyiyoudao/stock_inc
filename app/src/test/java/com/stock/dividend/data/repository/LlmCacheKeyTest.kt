package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlmCacheKeyTest {

    @Test
    fun `same inputs produce same 64-hex key`() {
        val a = LlmCacheKey.of("sys", "user")
        val b = LlmCacheKey.of("sys", "user")
        assertThat(a).isEqualTo(b)
        assertThat(a).hasLength(64)
        assertThat(a).matches("[0-9a-f]{64}")
    }

    @Test
    fun `different inputs produce different keys`() {
        assertThat(LlmCacheKey.of("sys", "user"))
            .isNotEqualTo(LlmCacheKey.of("sys", "user2"))
        assertThat(LlmCacheKey.of("sys", "user"))
            .isNotEqualTo(LlmCacheKey.of("sys2", "user"))
    }

    @Test
    fun `empty strings do not throw`() {
        assertThat(LlmCacheKey.of("", "")).isEqualTo(LlmCacheKey.of("", ""))
    }
}
