package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [MarketMoodCalculator] 纯函数单测：领涨/领跌两端分组（口径同 get_market_sentiment 工具）。
 */
class MarketMoodCalculatorTest {

    private fun item(name: String?, changePct: Double? = null) = MarketListItem(
        code = null, name = name, price = null, changePct = changePct,
        pe = null, pb = null, totalMarketCap = null, turnoverRate = null,
        industry = null, mainNetInflow = null, mainNetInflowPct = null,
        leaderName = null, leaderCode = null, leaderChangePct = null,
    )

    @Test
    fun `split takes both ends sorted by change pct`() {
        val list = listOf(
            item("银行", 2.5), item("煤炭", -1.2), item("白酒", 5.0),
            item("电力", 0.3), item("家电", -3.4),
        )
        val mood = MarketMoodCalculator.splitGainersLosers(list, topN = 2)
        assertThat(mood.topGainers.map { it.name }).containsExactly("白酒", "银行").inOrder()
        assertThat(mood.topLosers.map { it.name }).containsExactly("家电", "煤炭").inOrder()
    }

    @Test
    fun `split drops items without change pct or name`() {
        // 停牌占位（changePct=null）与无名板块剔除，不臆造
        val list = listOf(
            item("银行", 1.0), item(null, 2.0), item("煤炭"), item("白酒", -0.5),
        )
        val mood = MarketMoodCalculator.splitGainersLosers(list, topN = 3)
        assertThat(mood.topGainers.map { it.name }).containsExactly("银行", "白酒").inOrder()
        assertThat(mood.topLosers.map { it.name }).containsExactly("白酒", "银行").inOrder()
    }

    @Test
    fun `split with fewer than topN returns all`() {
        val list = listOf(item("银行", 1.0), item("煤炭", -0.5))
        val mood = MarketMoodCalculator.splitGainersLosers(list, topN = 3)
        assertThat(mood.topGainers).hasSize(2)
        assertThat(mood.topLosers).hasSize(2)
    }

    @Test
    fun `split empty list yields empty mood`() {
        val mood = MarketMoodCalculator.splitGainersLosers(emptyList())
        assertThat(mood.topGainers).isEmpty()
        assertThat(mood.topLosers).isEmpty()
    }

    @Test
    fun `split default topN is three`() {
        val list = (1..10).map { item("板块$it", it * 1.0) }
        val mood = MarketMoodCalculator.splitGainersLosers(list)
        assertThat(mood.topGainers).hasSize(3)
        assertThat(mood.topLosers).hasSize(3)
        assertThat(mood.topGainers.first().name).isEqualTo("板块10")
        assertThat(mood.topLosers.first().name).isEqualTo("板块1")
    }
}
