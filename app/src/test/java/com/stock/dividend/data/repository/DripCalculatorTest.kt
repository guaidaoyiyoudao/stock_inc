package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendEntity
import org.junit.Test

/**
 * [DripCalculator]（分红再投资复利模拟）单测。
 *
 * 核心规则：每年分红现金 = 当前股数 × 当年每股分红；再投股数 = 现金 / 再投价；
 * 逐年累加。现金路径股数不变、分红落袋；再投路径扩股、分红不重复计现金。
 */
class DripCalculatorTest {

    private fun div(id: String, year: String, cashPerShare: Double) = DividendEntity(
        id = id,
        stockCode = "sh.600000",
        reportDate = year,
        cashPerShare = cashPerShare
    )

    /** 手算金标准：初始 10000 元、初始价 10 元 → 1000 股；每年每股分红 1 元、再投价 10 元。
     *  第1年：现金 1000 → 再投 100 股 → 累计 1100 股。
     *  第2年：现金 1100 → 再投 110 股 → 累计 1210 股。
     *  期末价 10：再投市值 = 12100；现金路径 = 1000×10 + 2100(累计现金) = 12100。
     *  注：本例再投价=期末价=初始价时两路径相等（无价格变动），验证公式自洽。 */
    @Test
    fun `drip compounding matches hand calc`() {
        val r = DripCalculator.simulate(
            dividends = listOf(div("1", "2021", 1.0), div("2", "2022", 1.0)),
            initialAmount = 10000.0,
            initialPrice = 10.0,
            reinvestPrice = 10.0,
            endPrice = 10.0
        )!!
        assertThat(r.initialShares).isWithin(0.0001).of(1000.0)
        assertThat(r.finalShares).isWithin(0.0001).of(1210.0)
        assertThat(r.reinvestedShares).isWithin(0.0001).of(210.0)
        assertThat(r.totalDividendCash).isWithin(0.0001).of(2100.0)
        assertThat(r.yearlyRows).hasSize(2)
        assertThat(r.yearlyRows[0].cumulativeShares).isWithin(0.0001).of(1100.0)
        assertThat(r.yearlyRows[1].cumulativeShares).isWithin(0.0001).of(1210.0)
    }

    /** 再投路径跑赢现金路径：期末价上涨时，扩股带来超额收益。 */
    @Test
    fun `drip beats cash path when price rises`() {
        val r = DripCalculator.simulate(
            dividends = listOf(div("1", "2021", 1.0), div("2", "2022", 1.0)),
            initialAmount = 10000.0,
            initialPrice = 10.0,
            reinvestPrice = 10.0,
            endPrice = 20.0  // 期末价翻倍
        )!!
        // 再投：1210 × 20 = 24200
        assertThat(r.dripPathFinalValue).isWithin(0.01).of(24200.0)
        // 现金：1000 × 20 + 2100 = 22100
        assertThat(r.cashPathFinalValue).isWithin(0.01).of(22100.0)
        assertThat(r.dripVsCashExcess).isWithin(0.01).of(2100.0)
        assertThat(r.dripVsCashExcessRate).isWithin(0.01).of(2100.0 / 22100.0 * 100.0)
    }

    /** 再投路径跑输现金路径：期末价下跌时，扩股反而放大亏损。 */
    @Test
    fun `cash path beats drip when price falls`() {
        val r = DripCalculator.simulate(
            dividends = listOf(div("1", "2021", 1.0)),
            initialAmount = 10000.0,
            initialPrice = 10.0,
            reinvestPrice = 10.0,
            endPrice = 5.0  // 期末腰斩
        )!!
        // 再投：1100 × 5 = 5500
        assertThat(r.dripPathFinalValue).isWithin(0.01).of(5500.0)
        // 现金：1000 × 5 + 1000(累计现金) = 6000
        assertThat(r.cashPathFinalValue).isWithin(0.01).of(6000.0)
        assertThat(r.dripVsCashExcess).isWithin(0.01).of(-500.0)
    }

    /** 无效入参返回 null。 */
    @Test
    fun `invalid inputs return null`() {
        val d = listOf(div("1", "2021", 1.0))
        assertThat(DripCalculator.simulate(d, 0.0, 10.0, 10.0, 10.0)).isNull()       // 初始金额≤0
        assertThat(DripCalculator.simulate(d, 10000.0, 0.0, 10.0, 10.0)).isNull()    // 初始价≤0
        assertThat(DripCalculator.simulate(d, 10000.0, 10.0, 10.0, 0.0)).isNull()    // 期末价≤0
        assertThat(DripCalculator.simulate(emptyList(), 10000.0, 10.0, 10.0, 10.0)).isNull() // 无分红
    }

    /** 再投价≤0 禁用再投：退化为现金路径（股数不变）。 */
    @Test
    fun `disabled reinvest degrades to cash path`() {
        val r = DripCalculator.simulate(
            dividends = listOf(div("1", "2021", 1.0), div("2", "2022", 1.0)),
            initialAmount = 10000.0,
            initialPrice = 10.0,
            reinvestPrice = 0.0,  // 禁用
            endPrice = 10.0
        )!!
        assertThat(r.reinvestDisabled).isTrue()
        assertThat(r.finalShares).isWithin(0.0001).of(1000.0)  // 股数不变
        assertThat(r.reinvestedShares).isWithin(0.0001).of(0.0)
        // 两条路径市值相等（都 = 初始股数×期末价 + 累计现金）
        assertThat(r.dripPathFinalValue).isWithin(0.01).of(r.cashPathFinalValue)
    }

    /** 过滤 ≤0 的分红年份（噪声）。 */
    @Test
    fun `filters nonpositive dividend years`() {
        val r = DripCalculator.simulate(
            dividends = listOf(
                div("1", "2021", 1.0),
                div("2", "2022", 0.0),     // 占位，跳过
                div("3", "2022", -0.5),    // 负值，跳过
                div("4", "2023", 1.0)
            ),
            initialAmount = 10000.0,
            initialPrice = 10.0,
            reinvestPrice = 10.0,
            endPrice = 10.0
        )!!
        // 仅 2021、2023 两年有效
        assertThat(r.yearlyRows.map { it.year }).containsExactly("2021", "2023").inOrder()
        // 现金：1000×10 + (1000 + 1100) = 12100（2022 无分红，2023 股数仍为 1100）
        assertThat(r.totalDividendCash).isWithin(0.01).of(2100.0)
    }

    /** startYear/endYear 截取窗口：仅模拟指定年份区间。 */
    @Test
    fun `window clamps to start and end year`() {
        val r = DripCalculator.simulate(
            dividends = listOf(div("1", "2020", 1.0), div("2", "2021", 1.0), div("3", "2022", 1.0)),
            initialAmount = 10000.0,
            initialPrice = 10.0,
            reinvestPrice = 10.0,
            endPrice = 10.0,
            startYear = "2021",
            endYear = "2021"
        )!!
        assertThat(r.yearlyRows).hasSize(1)
        assertThat(r.yearlyRows[0].year).isEqualTo("2021")
        assertThat(r.startYear).isEqualTo("2021")
        assertThat(r.endYear).isEqualTo("2021")
    }

    /** startYear > endYear 返回 null。 */
    @Test
    fun `inverted window returns null`() {
        val r = DripCalculator.simulate(
            dividends = listOf(div("1", "2021", 1.0), div("2", "2022", 1.0)),
            initialAmount = 10000.0,
            initialPrice = 10.0,
            reinvestPrice = 10.0,
            endPrice = 10.0,
            startYear = "2022",
            endYear = "2021"
        )
        assertThat(r).isNull()
    }

    /** 同一年多笔分红按年汇总（年度每股分红 = 年内 sumOf）。 */
    @Test
    fun `multiple dividends per year are summed`() {
        val r = DripCalculator.simulate(
            dividends = listOf(
                div("1", "2021", 0.3),  // 中报
                div("2", "2021", 0.7)   // 年报 → 年度合计 1.0
            ),
            initialAmount = 10000.0,
            initialPrice = 10.0,
            reinvestPrice = 10.0,
            endPrice = 10.0
        )!!
        assertThat(r.yearlyRows).hasSize(1)
        assertThat(r.yearlyRows[0].cashPerShare).isWithin(0.0001).of(1.0)
        assertThat(r.yearlyRows[0].reinvestedShares).isWithin(0.0001).of(100.0)
    }
}
