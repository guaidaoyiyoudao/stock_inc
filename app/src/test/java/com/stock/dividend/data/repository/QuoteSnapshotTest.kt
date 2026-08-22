package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.dto.QuoteItem
import org.junit.Test

/**
 * [toQuoteSnapshot] 纯函数解析测试。
 *
 * Fixture 取自 **实测**（2026-08-01 东方财富 push2 `api/qt/ulist.np/get` 招行 600036 / 平安 000001），
 * 裸值÷100规则经腾讯 qt 同时刻可读值交叉验证：
 * - 招行 f2=3962 → 39.62元；f9=660 → PE 6.60；f23=90 → PB 0.90；f20=999210282712 → 总市值 9992亿
 * - 平安 f2=1163 → 11.63元；f9=389 → PE 3.89；f23=49 → PB 0.49
 */
class QuoteSnapshotTest {

    // ── 招行 600036（沪市 market=1，实测裸值）──────────────────────
    private val cmbItem = QuoteItem(
        price = 3962.0,
        changePct = -229.0,
        change = -93.0,
        volume = 1491550.0,
        amount = 5898211993.0,
        amplitude = 279.0,
        turnoverRate = 72.0,
        pe = 660.0,
        volumeRatio = 141.0,
        code = "600036",
        market = 1,
        high = 4017.0,
        low = 3904.0,
        open = 3985.0,
        prevClose = 4055.0,
        totalMarketCap = 999210282712.0,
        circMarketCap = 817318778277.0,
        pb = 90.0
    )

    // ── 平安银行 000001（深市 market=0，实测裸值）──────────────────
    private val pabItem = QuoteItem(
        price = 1163.0,
        changePct = 17.0,
        change = 2.0,
        volume = 2024979.0,
        amount = 2318839881.31,
        amplitude = 301.0,
        turnoverRate = 104.0,
        pe = 389.0,
        volumeRatio = 136.0,
        code = "000001",
        market = 0,
        high = 1163.0,
        low = 1128.0,
        open = 1150.0,
        prevClose = 1161.0,
        totalMarketCap = 225690828643.0,
        circMarketCap = 225687135594.0,
        pb = 49.0
    )

    @Test
    fun `price div 100 to yuan - 招行 3962 to 39_62`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.price).isWithin(0.01).of(39.62)
    }

    @Test
    fun `changePct div 100 - 招行 -229 to -2_29 percent`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.changePct).isWithin(0.01).of(-2.29)
    }

    @Test
    fun `change div 100 - 招行 -93 to -0_93 yuan`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.change).isWithin(0.01).of(-0.93)
    }

    @Test
    fun `pe div 100 - 招行 660 to 6_60`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.pe).isWithin(0.001).of(6.60)
    }

    @Test
    fun `pb div 100 - 招行 90 to 0_90`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.pb).isWithin(0.01).of(0.90)
    }

    @Test
    fun `totalMarketCap stays raw yuan - 招行 9992亿`() {
        val snap = toQuoteSnapshot(cmbItem)
        // 不除：999210282712 元 ≈ 9992.10 亿
        assertThat(snap.totalMarketCap).isEqualTo(999210282712.0)
        assertThat(snap.totalMarketCap!! / 1e8).isWithin(0.1).of(9992.10)
    }

    @Test
    fun `circMarketCap stays raw yuan - 招行`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.circMarketCap).isEqualTo(817318778277.0)
    }

    @Test
    fun `volume stays raw shou - 招行 1491550`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.volume).isEqualTo(1491550.0)
    }

    @Test
    fun `amount stays raw yuan - 招行`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.amount).isEqualTo(5898211993.0)
    }

    @Test
    fun `turnoverRate div 100 - 招行 72 to 0_72`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.turnoverRate).isWithin(0.01).of(0.72)
    }

    @Test
    fun `volumeRatio div 100 - 招行 141 to 1_41`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.volumeRatio).isWithin(0.01).of(1.41)
    }

    @Test
    fun `ohlc div 100 - 招行 high 4017 to 40_17`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.high).isWithin(0.01).of(40.17)
        assertThat(snap.low).isWithin(0.01).of(39.04)
        assertThat(snap.open).isWithin(0.01).of(39.85)
        assertThat(snap.prevClose).isWithin(0.01).of(40.55)
    }

    @Test
    fun `amplitude div 100 - 招行 279 to 2_79`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.amplitude).isWithin(0.01).of(2.79)
    }

    @Test
    fun `shenzhen market 0 maps to sz prefix - 平安`() {
        val snap = toQuoteSnapshot(pabItem)
        assertThat(snap.stockCode).isEqualTo("sz.000001")
        assertThat(snap.price).isWithin(0.01).of(11.63)
    }

    @Test
    fun `shanghai market 1 maps to sh prefix - 招行`() {
        val snap = toQuoteSnapshot(cmbItem)
        assertThat(snap.stockCode).isEqualTo("sh.600036")
    }

    @Test
    fun `null fields degrade to null not crash`() {
        // 停牌：除 code/market 外全 null
        val suspended = QuoteItem(price = null, code = "600036", market = 1)
        val snap = toQuoteSnapshot(suspended)
        assertThat(snap.stockCode).isEqualTo("sh.600036")
        assertThat(snap.price).isNull()
        assertThat(snap.pe).isNull()
        assertThat(snap.pb).isNull()
        assertThat(snap.totalMarketCap).isNull()
    }

    @Test
    fun `non-finite pe degrades to null`() {
        // NaN/Infinity（接口偶发返回）必须降为 null，避免污染下游 LLM/评估计算
        val item = cmbItem.copy(pe = Double.NaN, pb = Double.POSITIVE_INFINITY)
        val snap = toQuoteSnapshot(item)
        assertThat(snap.pe).isNull()
        assertThat(snap.pb).isNull()
        // 其他字段不受影响
        assertThat(snap.price).isWithin(0.01).of(39.62)
    }

    @Test
    fun `all populated fields parsed for 平安`() {
        val snap = toQuoteSnapshot(pabItem)
        assertThat(snap.price).isWithin(0.01).of(11.63)
        assertThat(snap.changePct).isWithin(0.01).of(0.17)
        assertThat(snap.pe).isWithin(0.01).of(3.89)
        assertThat(snap.pb).isWithin(0.01).of(0.49)
        assertThat(snap.turnoverRate).isWithin(0.01).of(1.04)
        assertThat(snap.volumeRatio).isWithin(0.01).of(1.36)
        // 市值原值不除
        assertThat(snap.totalMarketCap).isEqualTo(225690828643.0)
    }

    // ── 场内基金（ETF/LOF）：价格类裸值 ×1000（2026-08-22 实测 push2delay ulist，
    //     腾讯 qt 同时刻交叉验证：510880 f2=3387 → 3.387、159915 f2=3560 → 3.560、
    //     股票对照 600519 f2=127283 → 1272.83 仍 ×100）──────────────────────

    private val etfShItem = QuoteItem(
        price = 3387.0,
        changePct = 15.0,
        change = 5.0,
        volume = 13626.0,
        amount = 4610000.0,
        turnoverRate = 56.0,
        code = "510880",
        market = 1,
        high = 3397.0,
        low = 3372.0,
        open = 3378.0,
        prevClose = 3382.0
    )

    private val etfSzItem = QuoteItem(
        price = 3560.0,
        changePct = 137.0,
        change = 48.0,
        code = "159915",
        market = 0
    )

    @Test
    fun `fund price div 1000 - 红利ETF f2 3387 to 3_387`() {
        val snap = toQuoteSnapshot(etfShItem)
        assertThat(snap.stockCode).isEqualTo("sh.510880")
        assertThat(snap.price).isWithin(0.0001).of(3.387)
        assertThat(snap.high).isWithin(0.0001).of(3.397)
        assertThat(snap.low).isWithin(0.0001).of(3.372)
        assertThat(snap.open).isWithin(0.0001).of(3.378)
        assertThat(snap.prevClose).isWithin(0.0001).of(3.382)
    }

    @Test
    fun `fund change div 1000 - 红利ETF f4 5 to 0_005`() {
        val snap = toQuoteSnapshot(etfShItem)
        assertThat(snap.change).isWithin(0.00001).of(0.005)
    }

    @Test
    fun `fund percent fields still div 100 - changePct and turnover`() {
        // 百分比类不随标的类型变：f3=15 → 0.15%、f8=56 → 0.56%（腾讯同刻 0.15% 交叉验证）
        val snap = toQuoteSnapshot(etfShItem)
        assertThat(snap.changePct).isWithin(0.001).of(0.15)
        assertThat(snap.turnoverRate).isWithin(0.001).of(0.56)
        // 绝对量类原值不除
        assertThat(snap.volume).isEqualTo(13626.0)
        assertThat(snap.amount).isEqualTo(4610000.0)
    }

    @Test
    fun `shenzhen fund also div 1000 - 创业板ETF f2 3560 to 3_560`() {
        val snap = toQuoteSnapshot(etfSzItem)
        assertThat(snap.stockCode).isEqualTo("sz.159915")
        assertThat(snap.price).isWithin(0.0001).of(3.560)
        assertThat(snap.change).isWithin(0.0001).of(0.048)
        assertThat(snap.changePct).isWithin(0.01).of(1.37)   // f3=137 → 1.37%
    }
}
