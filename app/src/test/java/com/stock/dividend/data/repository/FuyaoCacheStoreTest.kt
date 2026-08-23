package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.reflect.TypeToken
import com.stock.dividend.data.local.dao.FuyaoCacheDao
import com.stock.dividend.data.local.entity.FuyaoCacheEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * [FuyaoCacheStore] 三种持久缓存语义（DB v28 fuyao_cache）：
 * ①合并式 fetchFirstMerge（历史不可变：远端覆盖同期、缓存独有旧期次保留）；
 * ②覆盖式 fetchFirstReplace（成功整体替换、失败回退缓存）；
 * ③按日缓存优先 cacheFirstForDate（过去日期命中零网络）。
 */
class FuyaoCacheStoreTest {

    private val dao: FuyaoCacheDao = mockk(relaxed = true)
    private val store = FuyaoCacheStore(dao)

    @Before
    fun setUp() {
        coEvery { dao.get(any()) } returns null
    }

    private fun entityOf(payload: String) = FuyaoCacheEntity(
        key = "k", payload = payload, fetchedAt = System.currentTimeMillis() - 86_400_000L
    )

    @Test
    fun `merge keeps cached-only history and persists union`() = runTest {
        coEvery { dao.get("k") } returns entityOf("""["2025-12-31"]""")
        val upsertSlot = slot<FuyaoCacheEntity>()
        coEvery { dao.upsert(capture(upsertSlot)) } returns Unit

        val result = store.fetchFirstMerge(
            "k", object : TypeToken<List<String>>() {}.type,
            merge = { cached, fresh -> (cached.orEmpty() + fresh).distinct().sorted() }
        ) { listOf("2026-08-21") }

        assertThat(result).containsExactly("2025-12-31", "2026-08-21").inOrder()
        // 合并结果持久化
        assertThat(upsertSlot.captured.payload).contains("2025-12-31")
    }

    @Test
    fun `fetch failure falls back to cache`() = runTest {
        coEvery { dao.get("k") } returns entityOf("""["cached-value"]""")

        val result: List<String>? = store.fetchFirstReplace(
            "k", object : TypeToken<List<String>>() {}.type
        ) { null }   // fetch 失败/禁用语义

        assertThat(result).containsExactly("cached-value")
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `replace overwrites cache with fresh value`() = runTest {
        coEvery { dao.get("k") } returns entityOf("""["old"]""")
        val upsertSlot = slot<FuyaoCacheEntity>()
        coEvery { dao.upsert(capture(upsertSlot)) } returns Unit

        val result = store.fetchFirstReplace(
            "k", object : TypeToken<List<String>>() {}.type
        ) { listOf("new") }

        assertThat(result).containsExactly("new")
        assertThat(upsertSlot.captured.payload).isEqualTo("""["new"]""")
    }

    @Test
    fun `past date cache hit serves without network`() = runTest {
        coEvery { dao.get("k") } returns entityOf("""["2026-08-21-data"]""")

        var fetchCalled = false
        val result = store.cacheFirstForDate(
            "k", object : TypeToken<List<String>>() {}.type, isPastDate = true
        ) {
            fetchCalled = true
            listOf("fresh")
        }

        assertThat(result).containsExactly("2026-08-21-data")
        assertThat(fetchCalled).isFalse()   // 过去日期零网络
    }

    @Test
    fun `current date fetches and caches for offline fallback`() = runTest {
        val upsertSlot = slot<FuyaoCacheEntity>()
        coEvery { dao.upsert(capture(upsertSlot)) } returns Unit

        val result = store.cacheFirstForDate(
            "k", object : TypeToken<List<String>>() {}.type, isPastDate = false
        ) { listOf("live-data") }

        assertThat(result).containsExactly("live-data")
        assertThat(upsertSlot.captured.payload).isEqualTo("""["live-data"]""")
    }

    @Test
    fun `corrupted payload degrades to null without throw`() = runTest {
        coEvery { dao.get("k") } returns entityOf("{not valid json")

        val result = store.fetchFirstReplace(
            "k", object : TypeToken<List<String>>() {}.type
        ) { null }

        assertThat(result == null).isTrue()   // 损坏缓存不崩（红线 #2）
    }
}
