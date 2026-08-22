package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.dto.IndexQuoteResponse
import com.stock.dividend.data.remote.dto.MarketClistResponse
import org.junit.Test

/**
 * clist / stock/get 解析函数的**函数级**规则锁定（2026-08-20 审计 M8 补齐）。
 *
 * §4.9.1 最大易错点：clist（fltt=2）全字段真实值**不 ÷100**，stock/get 价格百分比
 * **×100 整数需 ÷100**——两者规则相反。此前 toIndexQuote/toMarketList 无任何函数级
 * 测试（若有人把 stock/get 当 clist 处理去掉 ÷100，不会有测试变红）。
 */
class MarketDataRepositoryParseTest {

    // ── toMarketList：clist 真实值不除 ──

    @Test
    fun `toMarketList keeps clist values as-is without dividing by 100`() {
        // 实测样本（2026-08-20）：志邦家居 f2=7.23 / f133=5.4 / f62 净额（元）
        val response = MarketClistResponse(
            MarketClistResponse.MarketClistData(
                listOf(
                    MarketClistResponse.MarketClistItem(
                        code = "603801", name = "志邦家居",
                        price = 7.23, changePct = 10.05, pe = 12.5, pb = 1.8,
                        totalMarketCap = 3171000000.0, turnoverRate = 6.44,
                        mainNetInflow = 743194.0, mainNetInflowPct = 0.05,
                        dividendYield = 5.4
                    )
                )
            )
        )
        val item = response.toMarketList().single()

        assertThat(item.price).isEqualTo(7.23)          // 不除！ulist 同值会是 723
        assertThat(item.changePct).isEqualTo(10.05)
        assertThat(item.dividendYield).isEqualTo(5.4)   // f133 股息率 % 真实值
        assertThat(item.mainNetInflow).isEqualTo(743194.0) // 净额（元）不除
    }

    @Test
    fun `toMarketList degrades dirty numeric values to null`() {
        // "-" 占位经容错 Gson 已读成 null；NaN/Infinity 由 takeIfFinite 降 null
        val response = MarketClistResponse(
            MarketClistResponse.MarketClistData(
                listOf(
                    MarketClistResponse.MarketClistItem(
                        code = "000004", name = "国华退",
                        price = null, changePct = Double.NaN, dividendYield = Double.POSITIVE_INFINITY
                    )
                )
            )
        )
        val item = response.toMarketList().single()

        assertThat(item.price).isNull()
        assertThat(item.changePct).isNull()
        assertThat(item.dividendYield).isNull()
        assertThat(item.code).isEqualTo("000004")   // 文本字段照常保留
    }

    @Test
    fun `toMarketList maps industry peer and leader fields`() {
        val response = MarketClistResponse(
            MarketClistResponse.MarketClistData(
                listOf(
                    MarketClistResponse.MarketClistItem(
                        code = "600519", name = "贵州茅台",
                        industry = "白酒Ⅱ", leaderName = "贵州茅台", leaderCode = "600519",
                        leaderChangePct = -1.02
                    )
                )
            )
        )
        val item = response.toMarketList().single()

        assertThat(item.industry).isEqualTo("白酒Ⅱ")
        assertThat(item.leaderName).isEqualTo("贵州茅台")
        assertThat(item.leaderChangePct).isEqualTo(-1.02)
    }

    // ── toIndexQuote：stock/get ÷100 ──

    @Test
    fun `toIndexQuote divides x100 raw integers by 100`() {
        // 实测样本（2026-08-20）：中国移动 f43=9664 → 96.64；f48 成交额（元）原值不除
        val response = IndexQuoteResponse(
            IndexQuoteResponse.IndexQuoteData(
                code = "600941", name = "中国移动",
                price = 9664.0, high = 9744.0, low = 9644.0, open = 9686.0,
                prevClose = 9717.0, changePct = -44.0,
                amount = 23209306500.0
            )
        )
        val quote = response.toIndexQuote(fallbackCode = "600941")

        assertThat(quote.price).isEqualTo(96.64)      // ÷100
        assertThat(quote.high).isEqualTo(97.44)
        assertThat(quote.low).isEqualTo(96.44)
        assertThat(quote.open).isEqualTo(96.86)
        assertThat(quote.prevClose).isEqualTo(97.17)
        assertThat(quote.changePct).isEqualTo(-0.44)  // ÷100
        assertThat(quote.amount).isEqualTo(23209306500.0) // 成交额（元）不除
    }

    @Test
    fun `toIndexQuote uses fallback code when data missing`() {
        val quote = IndexQuoteResponse(null).toIndexQuote(fallbackCode = "000300")

        assertThat(quote.code).isEqualTo("000300")
        assertThat(quote.price).isNull()
        assertThat(quote.changePct).isNull()
    }

    @Test
    fun `toIndexQuote degrades non-finite raw values to null`() {
        val response = IndexQuoteResponse(
            IndexQuoteResponse.IndexQuoteData(
                price = Double.NaN, changePct = Double.POSITIVE_INFINITY, amount = Double.NEGATIVE_INFINITY
            )
        )
        val quote = response.toIndexQuote(fallbackCode = "000001")

        assertThat(quote.price).isNull()
        assertThat(quote.changePct).isNull()
        assertThat(quote.amount).isNull()
    }

    // ── toIndexQuote：场内基金（ETF/LOF）价格类 ×1000（2026-08-22 实测 push2delay stock/get，
    //     腾讯 qt 同时刻交叉验证：510880 f43=3387/f44=3397/f45=3372/f46=3378/f60=3382 → 3.387/3.397/3.372/3.378/3.382；
    //     股票对照 600519 f43=127283 → 1272.83 仍 ×100）──

    @Test
    fun `toIndexQuote divides fund price fields by 1000`() {
        val response = IndexQuoteResponse(
            IndexQuoteResponse.IndexQuoteData(
                code = "510880", name = "红利ETF华泰柏瑞",
                price = 3387.0, high = 3397.0, low = 3372.0, open = 3378.0,
                prevClose = 3382.0, changePct = 15.0,
                amount = 4610000.0
            )
        )
        val quote = response.toIndexQuote(fallbackCode = "510880")

        assertThat(quote.price).isEqualTo(3.387)       // ×1000 ÷1000
        assertThat(quote.high).isEqualTo(3.397)
        assertThat(quote.low).isEqualTo(3.372)
        assertThat(quote.open).isEqualTo(3.378)
        assertThat(quote.prevClose).isEqualTo(3.382)
        assertThat(quote.changePct).isEqualTo(0.15)    // 涨跌幅两类标的均 ×100
        assertThat(quote.amount).isEqualTo(4610000.0)  // 成交额原值不除
    }

    @Test
    fun `toIndexQuote index codes stay divided by 100`() {
        // 主要指数代码（000001 上证/000300 沪深300/399001 深成/399006 创业板等）均不以 5/15/16 开头，不误判为基金
        val response = IndexQuoteResponse(
            IndexQuoteResponse.IndexQuoteData(
                code = "000300", name = "沪深300",
                price = 396200.0, prevClose = 395000.0, changePct = 30.0
            )
        )
        val quote = response.toIndexQuote(fallbackCode = "000300")

        assertThat(quote.price).isEqualTo(3962.0)
        assertThat(quote.changePct).isEqualTo(0.30)
    }
}
