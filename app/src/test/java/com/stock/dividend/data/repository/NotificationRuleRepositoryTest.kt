package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.NotificationRuleDao
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NotificationRuleRepositoryTest {

    private val dao: NotificationRuleDao = mockk(relaxed = true)
    private val repository = NotificationRuleRepository(dao)

    @Test
    fun `enabled per stock rule overrides global rule`() = runTest {
        val global = rule(id = "global", stockCode = null, threshold = 5.0)
        val override = rule(id = "stock", stockCode = "sz.000001", threshold = 6.0)
        coEvery { dao.getRulesByType(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD) } returns listOf(global, override)

        val result = repository.getEffectiveDividendYieldRules(listOf("sz.000001", "sh.600000"))

        assertThat(result["sz.000001"]?.thresholdPercent).isEqualTo(6.0)
        assertThat(result["sh.600000"]?.thresholdPercent).isEqualTo(5.0)
    }

    @Test
    fun `disabled per stock rule is ignored and global rule applies`() = runTest {
        val global = rule(id = "global", stockCode = null, threshold = 5.0)
        val disabledOverride = rule(id = "stock", stockCode = "sz.000001", threshold = 6.0, enabled = false)
        coEvery { dao.getRulesByType(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD) } returns listOf(global, disabledOverride)

        val result = repository.getEffectiveDividendYieldRules(listOf("sz.000001"))

        assertThat(result["sz.000001"]?.thresholdPercent).isEqualTo(5.0)
    }

    @Test
    fun `saves global dividend yield threshold with stable id`() = runTest {
        coEvery { dao.getGlobalRule(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD) } returns null

        repository.saveDividendYieldRule(stockCode = null, enabled = true, thresholdPercent = 5.5, now = 1000L)

        coVerify {
            dao.upsert(
                match {
                    it.id == "global-dividend-yield-threshold" &&
                        it.stockCode == null &&
                        it.enabled &&
                        it.thresholdPercent == 5.5 &&
                        it.createdAt == 1000L &&
                        it.updatedAt == 1000L
                }
            )
        }
    }

    private fun rule(
        id: String,
        stockCode: String?,
        threshold: Double,
        enabled: Boolean = true
    ) = NotificationRuleEntity(
        id = id,
        type = NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD,
        stockCode = stockCode,
        enabled = enabled,
        thresholdPercent = threshold,
        createdAt = 0L,
        updatedAt = 0L
    )
}
