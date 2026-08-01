package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LlmAnalysisCacheStoreTest {

    private val dao: LlmAnalysisCacheDao = mockk()
    private fun store() = LlmAnalysisCacheStore(dao)

    private val portfolio = LlmAnalysis(
        overview = "组合偏防御",
        stockComments = mapOf("600036" to StockLlmComment("低估", listOf("银行占比高"))),
        risks = listOf("整体股息率偏低")
    )

    @Test
    fun `portfolio entry round trip`() = runTest {
        coEvery { dao.get("k", "PORTFOLIO") } returns LlmAnalysisCacheEntity(
            "k", "PORTFOLIO",
            """{"overview":"组合偏防御","stockComments":{"600036":{"brief":"低估","risks":["银行占比高"]}},"risks":["整体股息率偏低"]}""",
            123L
        )
        val hit = store().getPortfolio("k")
        assertThat(hit).isNotNull()
        assertThat(hit!!.createdAt).isEqualTo(123L)
        assertThat(hit.analysis.overview).isEqualTo("组合偏防御")
        assertThat(hit.analysis.stockComments["600036"]?.brief).isEqualTo("低估")
        assertThat(hit.analysis.risks).containsExactly("整体股息率偏低")
    }

    @Test
    fun `corrupt payload yields null`() = runTest {
        coEvery { dao.get("k", "PORTFOLIO") } returns LlmAnalysisCacheEntity("k", "PORTFOLIO", "not json", 1L)
        assertThat(store().getPortfolio("k")).isNull()
    }

    @Test
    fun `miss yields null`() = runTest {
        coEvery { dao.get("k", "PORTFOLIO") } returns null
        assertThat(store().getPortfolio("k")).isNull()
    }

    @Test
    fun `stock put serializes with scope STOCK`() = runTest {
        coEvery { dao.upsert(any()) } returns Unit
        val entitySlot = slot<LlmAnalysisCacheEntity>()
        coEvery { dao.upsert(capture(entitySlot)) } returns Unit
        val analysis = StockLlmAnalysis("偏低", "稳", "可关注", listOf("波动"))

        store().putStock("key", analysis, 456L)

        assertThat(entitySlot.captured.cacheKey).isEqualTo("key")
        assertThat(entitySlot.captured.scope).isEqualTo("STOCK")
        assertThat(entitySlot.captured.createdAt).isEqualTo(456L)
        assertThat(entitySlot.captured.payload).contains("valuation")
        assertThat(entitySlot.captured.payload).contains("波动")
    }
}
