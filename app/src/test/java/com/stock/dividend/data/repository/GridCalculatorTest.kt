package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [GridCalculator]（纯买入网格档位表）单测。
 *
 * 核心规则：买入区间 [lowPrice, basePrice] 等分 grids 档（含两端），
 * 档位**全部为买入**（无卖出档）；资金 1/price 反比分配（越便宜买越多）；
 * 下一档 = 现价下方最近的买入档。
 */
class GridCalculatorTest {

    /** 基础场景：买入起点 10、资金用完位 8、4 档 → 档位 8/8.67/9.33/10（含两端等分）。 */
    @Test
    fun `basic grid generates buy levels from low to base`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.validationError).isNull()
        // 4 档：8、8.67、9.33、10（(10-8)/3 步长）
        assertThat(r.levels).hasSize(4)
        assertThat(r.levels.first().price).isEqualTo(8.0)      // 最便宜档在前
        assertThat(r.levels.last().price).isEqualTo(10.0)      // 买入起点=最贵档
        // 全部为买入
        assertThat(r.buyLevels).hasSize(4)
        assertThat(r.sellLevels).isEmpty()
        assertThat(r.levels.all { it.isBuy }).isTrue()
    }

    /** 卖出档恒为空（纯买入模型，杜绝「买了涨了就卖」）。 */
    @Test
    fun `no sell levels in pure buy model`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.sellLevels).isEmpty()
        assertThat(r.nextSellHint).isNull()
    }

    /** 资金反比分配：最便宜的档（8 元）买入金额最多。 */
    @Test
    fun `lower price gets more capital via inverse weighting`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        val first = r.levels.first()   // 8 元档
        val last = r.levels.last()     // 10 元档
        assertThat(first.amount).isGreaterThan(last.amount)
    }

    /** 股数按 100 股整手向下取整。 */
    @Test
    fun `shares are floored to 100-lot`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        r.levels.forEach { assertThat(it.shares % 100).isEqualTo(0) }
    }

    /** 下一档提示：现价 9.5 → 下方最近买入档 9.33。 */
    @Test
    fun `next buy hint points to level below current price`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0,
            currentPrice = 9.5
        )
        // 档位 8/8.67/9.33/10，现价 9.5 下方最近 = 9.33
        assertThat(r.nextBuyHint).isEqualTo(9.33)
        assertThat(r.nextSellHint).isNull()
    }

    /** 已买入的档不再提示（每档只买一次）：9.33 档已成交，现价回升到 9.5 → 下一买应跳到 8.67。 */
    @Test
    fun `next buy hint skips triggered levels`() {
        val base = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0,
            currentPrice = 9.4
        )
        // BUY@9.4 落在 9.33 档触发区间 → 9.33 已买；随后现价回升到 9.5
        val marked = GridCalculator.markTriggeredLevels(base, listOf(tx("BUY", 9.4, 300)))
        val recovered = marked.copy(currentPrice = 9.5)
        assertThat(recovered.nextBuyHint).isEqualTo(8.67)  // 不再指向已买的 9.33
    }

    /** 现价下方档位全部已买 → 下一买为 null（等跌破更低的未买档）。 */
    @Test
    fun `next buy hint null when all lower levels bought`() {
        val base = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0,
            currentPrice = 8.9
        )
        // 现价 8.9 下方有 8.0 与 8.67 两档，两笔成交分别把它们买掉
        val marked = GridCalculator.markTriggeredLevels(
            base, listOf(tx("BUY", 8.1, 500), tx("BUY", 8.7, 400))
        )
        assertThat(marked.nextBuyHint).isNull()
    }

    /** 现价已跌破资金用完位 → 无下一档（资金已用完）。 */
    @Test
    fun `no next buy hint below floor`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0,
            currentPrice = 7.5  // 跌破 8 元资金用完位
        )
        assertThat(r.nextBuyHint).isNull()
    }

    /** 无现价 → 无提示。 */
    @Test
    fun `no current price yields null hints`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.nextBuyHint).isNull()
        assertThat(r.nextSellHint).isNull()
    }

    /** 参考上界仅展示，不参与分档。 */
    @Test
    fun `high price is reference only`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.highPrice).isEqualTo(12.0)
        // 档位不包含 12（不参与买入分档）
        assertThat(r.levels.map { it.price }).doesNotContain(12.0)
    }

    /** 参数非法：买入起点 ≤ 资金用完位。 */
    @Test
    fun `invalid range returns error`() {
        val r = GridCalculator.generate(
            basePrice = 8.0, lowPrice = 10.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.validationError).isNotNull()
        assertThat(r.levels).isEmpty()
    }

    /** 参数非法：档数 < 2。 */
    @Test
    fun `invalid grids returns error`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 1, totalCapital = 100000.0
        )
        assertThat(r.validationError).isNotNull()
    }

    /** 参数非法：价格非正。 */
    @Test
    fun `nonpositive price returns error`() {
        val r = GridCalculator.generate(
            basePrice = 0.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        assertThat(r.validationError).isNotNull()
    }

    /** 资金全部分配到买入档（无卖出侧消耗）。 */
    @Test
    fun `all capital allocated to buy levels`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0
        )
        val buyTotal = r.buyLevels.sumOf { it.amount }
        // 整手取整有少量误差，允许 ±10% 容差
        assertThat(buyTotal).isWithin(10000.0).of(100000.0)
    }

    private fun tx(type: String, price: Double, shares: Int = 100) = com.stock.dividend.data.local.entity.TransactionEntity(
        id = 0L, stockCode = "sh.600000", type = type, shares = shares, price = price, date = "2026-01-01"
    )

    /** 关联交易：BUY 成交价落在档位触发区间（档位价 ± 半步长）→ 标记已触发。 */
    @Test
    fun `buy transactions mark matching levels triggered`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        // 档位 8/8.67/9.33/10，半步长 = (10-8)/3/2 = 0.333
        // BUY @ 9.5 → 落在 9.33 ± 0.333 区间内 → 9.33 档触发
        val marked = GridCalculator.markTriggeredLevels(
            base,
            listOf(tx("BUY", 9.5), tx("BUY", 12.0))  // 12 不在任何档位区间（参考上界）
        )
        assertThat(marked.levels.first { it.price == 9.33 }.triggered).isTrue()
        assertThat(marked.levels.first { it.price == 8.0 }.triggered).isFalse()
        assertThat(marked.levels.first { it.price == 10.0 }.triggered).isFalse()
    }

    /** SELL 交易不参与档位触发判定（纯买入模型，卖出是独立持仓管理动作）。 */
    @Test
    fun `sell transactions do not trigger levels`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val marked = GridCalculator.markTriggeredLevels(
            base,
            listOf(tx("SELL", 9.4))  // 价格落在 9.33 档区间，但类型是 SELL
        )
        assertThat(marked.levels.all { !it.triggered }).isTrue()
    }

    /** 无交易 → 无档位触发。 */
    @Test
    fun `no transactions trigger nothing`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val marked = GridCalculator.markTriggeredLevels(base, emptyList())
        assertThat(marked.levels.all { !it.triggered }).isTrue()
    }

    /** 多个 BUY 命中多个档位。 */
    @Test
    fun `multiple buys trigger multiple levels`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val marked = GridCalculator.markTriggeredLevels(
            base,
            listOf(tx("BUY", 8.1), tx("BUY", 10.0))  // 8.1→8 档；10.0→10 档
        )
        assertThat(marked.levels.first { it.price == 8.0 }.triggered).isTrue()
        assertThat(marked.levels.first { it.price == 10.0 }.triggered).isTrue()
        assertThat(marked.levels.first { it.price == 9.33 }.triggered).isFalse()
    }

    /** 档位触发不影响原对象（纯函数，返回副本）。 */
    @Test
    fun `markTriggered returns new instances`() {
        val base = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val marked = GridCalculator.markTriggeredLevels(base, listOf(tx("BUY", 8.1)))
        assertThat(base.levels.all { !it.triggered }).isTrue()          // 原对象不变
        assertThat(marked.levels.any { it.triggered }).isTrue()         // 副本有标记
    }

    // ── 等比网格（GEOM）──────────────────────────────

    /** 等比 4→8→16：相邻档比值恒为 2，两端精确命中 low/base。 */
    @Test
    fun `geometric grid keeps constant ratio and exact endpoints`() {
        val r = GridCalculator.generate(
            basePrice = 16.0, lowPrice = 4.0, highPrice = 20.0,
            grids = 3, totalCapital = 100000.0,
            gridType = GridType.GEOMETRIC
        )
        assertThat(r.validationError).isNull()
        assertThat(r.levels.map { it.price }).containsExactly(4.0, 8.0, 16.0).inOrder()
        // stepPercent = (ratio-1)×100 = 100%
        assertThat(r.stepPercent).isEqualTo(100.0)
    }

    /** 等比 10/8 区间 3 档：比值恒定 ≈1.118（每档步长 11.8%），非等差分布。 */
    @Test
    fun `geometric grid distributes by percent step not absolute`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 3, totalCapital = 100000.0,
            gridType = GridType.GEOMETRIC
        )
        val prices = r.levels.map { it.price }
        // 相邻比值一致（round2 后有 ±0.01 容差）
        val ratio1 = prices[1] / prices[0]
        val ratio2 = prices[2] / prices[1]
        assertThat(ratio1).isWithin(0.005).of(ratio2)
        // 与等差（8/9/10）明显不同
        assertThat(prices[1]).isNotEqualTo(9.0)
        assertThat(r.stepPercent).isWithin(0.05).of(11.80)
    }

    /** 等比下「越便宜买越多」仍成立（1/price 反比权重不变）。 */
    @Test
    fun `geometric grid keeps inverse capital weighting`() {
        val r = GridCalculator.generate(
            basePrice = 16.0, lowPrice = 4.0, highPrice = 20.0,
            grids = 3, totalCapital = 100000.0,
            gridType = GridType.GEOMETRIC
        )
        assertThat(r.levels.first().amount).isGreaterThan(r.levels.last().amount)
        r.levels.forEach { assertThat(it.shares % 100).isEqualTo(0) }
    }

    /** fromRaw：GEOM → 等比；YIELD → 按股息率；null/ARITH/未知 → 等差（旧数据兼容）。 */
    @Test
    fun `gridType fromRaw falls back to arithmetic`() {
        assertThat(GridType.fromRaw("GEOM")).isEqualTo(GridType.GEOMETRIC)
        assertThat(GridType.fromRaw("YIELD")).isEqualTo(GridType.YIELD)
        assertThat(GridType.fromRaw("ARITH")).isEqualTo(GridType.ARITHMETIC)
        assertThat(GridType.fromRaw(null)).isEqualTo(GridType.ARITHMETIC)
        assertThat(GridType.fromRaw("WHATEVER")).isEqualTo(GridType.ARITHMETIC)
    }

    // ── 按股息率网格（YIELD）──────────────────────

    /**
     * 金标准用例（即用户需求场景）：年分红 0.5 元，股息率 5.5%→6.5% 三档。
     * 档位价 = DPS ÷ 股息率 → 7.69（6.5%）/ 8.33（6.0%）/ 9.09（5.5%），
     * 从低到高排列，yieldPercent 严格递减（越便宜息越高）。
     */
    @Test
    fun `yield grid prices are dps divided by level yield`() {
        // base = 0.5/5.5% = 9.0909、low = 0.5/6.5% = 7.6923（由两端换算传入）
        val r = GridCalculator.generate(
            basePrice = 9.0909, lowPrice = 7.6923, highPrice = 9.0909,
            grids = 3, totalCapital = 100000.0,
            gridType = GridType.YIELD, dps = 0.5
        )
        assertThat(r.validationError).isNull()
        assertThat(r.levels).hasSize(3)
        // 价格从低到高：7.69 / 8.33 / 9.09
        assertThat(r.levels.map { it.price }).containsExactly(7.69, 8.33, 9.09).inOrder()
        // 每档股息率：6.5% / 6.0% / 5.5%（精确等差递减）
        assertThat(r.levels.map { it.yieldPercent }).containsExactly(6.5, 6.0, 5.5).inOrder()
        // 每档股息率步长 = (6.5-5.5)/2 = 0.5 个百分点
        assertThat(r.yieldStepPercent).isEqualTo(0.5)
    }

    /** 两端档位价精确等于 low/base（yield 由两端价格反推，含两端闭合）。 */
    @Test
    fun `yield grid endpoints are exact`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 10.0,
            grids = 4, totalCapital = 100000.0,
            gridType = GridType.YIELD, dps = 0.5
        )
        assertThat(r.levels.first().price).isEqualTo(8.0)
        assertThat(r.levels.last().price).isEqualTo(10.0)
        // 中间档价格单调递增（双曲线递减步长，但序列仍从低到高）
        val prices = r.levels.map { it.price }
        assertThat(prices).isInOrder()
        // 首末档股息率 = dps/low、dps/base
        assertThat(r.levels.first().yieldPercent).isEqualTo(6.25)
        assertThat(r.levels.last().yieldPercent).isEqualTo(5.0)
    }

    /** YIELD 模式缺 DPS（null/非正）→ 参数错误，不臆造档位。 */
    @Test
    fun `yield grid requires positive dps`() {
        val noDps = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 10.0,
            grids = 3, totalCapital = 100000.0,
            gridType = GridType.YIELD
        )
        assertThat(noDps.validationError).isNotNull()
        assertThat(noDps.levels).isEmpty()

        val zeroDps = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 10.0,
            grids = 3, totalCapital = 100000.0,
            gridType = GridType.YIELD, dps = 0.0
        )
        assertThat(zeroDps.validationError).isNotNull()
    }

    /** YIELD 模式「越便宜买越多」仍成立（1/price 反比权重不变）；整手取整不变。 */
    @Test
    fun `yield grid keeps inverse capital weighting`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 10.0,
            grids = 4, totalCapital = 100000.0,
            gridType = GridType.YIELD, dps = 0.5
        )
        assertThat(r.levels.first().amount).isGreaterThan(r.levels.last().amount)
        r.levels.forEach { assertThat(it.shares % 100).isEqualTo(0) }
    }

    /** YIELD 模式下一档提示照常：现价 8.5 → 下方最近档 8.33（5.5%→6.5% 场景）。 */
    @Test
    fun `yield grid next buy hint works`() {
        val r = GridCalculator.generate(
            basePrice = 9.0909, lowPrice = 7.6923, highPrice = 9.0909,
            grids = 3, totalCapital = 100000.0,
            currentPrice = 8.5,
            gridType = GridType.YIELD, dps = 0.5
        )
        assertThat(r.nextBuyHint).isEqualTo(8.33)
    }

    /** YIELD 模式触发标记照常：BUY@8.35 落在 8.33 档触发区间 → 标记已触发。 */
    @Test
    fun `yield grid markTriggered works`() {
        val base = GridCalculator.generate(
            basePrice = 9.0909, lowPrice = 7.6923, highPrice = 9.0909,
            grids = 3, totalCapital = 100000.0,
            gridType = GridType.YIELD, dps = 0.5
        )
        // 相邻价差 8.33-7.69=0.64，半步长 0.32；BUY@8.35 距 8.33 仅 0.02 → 命中
        val marked = GridCalculator.markTriggeredLevels(base, listOf(tx("BUY", 8.35)))
        assertThat(marked.levels.first { it.price == 8.33 }.triggered).isTrue()
        assertThat(marked.levels.first { it.price == 7.69 }.triggered).isFalse()
    }

    /** 等差/等比模式 yieldPercent 恒为 null（只有 YIELD 模式填充）。 */
    @Test
    fun `non-yield grids have null yield percent`() {
        val arith = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        assertThat(arith.levels.all { it.yieldPercent == null }).isTrue()
        assertThat(arith.yieldStepPercent).isNull()
        val geom = GridCalculator.generate(16.0, 4.0, 20.0, 3, 100000.0, gridType = GridType.GEOMETRIC)
        assertThat(geom.levels.all { it.yieldPercent == null }).isTrue()
        assertThat(geom.yieldStepPercent).isNull()
    }

    // ── 自定义档位资金比例（levelWeights）──────────────

    /**
     * 金标准用例：2 档（8/10 元），权重 [1, 3]（下标与档位一致，从最便宜档起）→
     * 归一化 25%/75% → 金额 25000/75000，股数按整手 3100/7500，amount = 股数×档位价。
     */
    @Test
    fun `custom weights allocate capital by given ratio`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 2, totalCapital = 100000.0,
            levelWeights = listOf(1.0, 3.0)
        )
        assertThat(r.validationError).isNull()
        assertThat(r.levels).hasSize(2)
        val cheap = r.levels[0]   // 8 元档，权重 1 → 25%
        val dear = r.levels[1]    // 10 元档，权重 3 → 75%
        assertThat(cheap.price).isEqualTo(8.0)
        assertThat(dear.price).isEqualTo(10.0)
        // 25000/8 = 3125 → 整手 3100 股，金额 3100×8 = 24800
        assertThat(cheap.shares).isEqualTo(3100)
        assertThat(cheap.amount).isEqualTo(24800.0)
        // 75000/10 = 7500 股整，金额 75000
        assertThat(dear.shares).isEqualTo(7500)
        assertThat(dear.amount).isEqualTo(75000.0)
    }

    /** 权重无需恰好合计 100：按相对比例归一化（[1,1,2] → 25%/25%/50%）。 */
    @Test
    fun `custom weights are normalized as relative ratio`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 3, totalCapital = 60000.0,
            levelWeights = listOf(1.0, 1.0, 2.0)
        )
        assertThat(r.validationError).isNull()
        // 8 元档 15000 → 1800 股（14400）；9 元档 15000 → 1600 股（14400）；10 元档 30000 → 3000 股
        assertThat(r.levels[0].shares).isEqualTo(1800)
        assertThat(r.levels[1].shares).isEqualTo(1600)
        assertThat(r.levels[2].shares).isEqualTo(3000)
        assertThat(r.levels[2].amount).isEqualTo(30000.0)
    }

    /** 自定义权重只改资金分配，不改档位价格与下一档提示。 */
    @Test
    fun `custom weights keep prices and next buy hint unchanged`() {
        val inverse = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0, currentPrice = 9.5)
        val custom = GridCalculator.generate(
            10.0, 8.0, 12.0, 4, 100000.0,
            currentPrice = 9.5, levelWeights = listOf(4.0, 3.0, 2.0, 1.0)
        )
        assertThat(custom.levels.map { it.price })
            .containsExactlyElementsIn(inverse.levels.map { it.price }).inOrder()
        assertThat(custom.nextBuyHint).isEqualTo(inverse.nextBuyHint)
        // 权重生效证明：贵档（10 元）金额较反比默认（≈22000）下降、便宜档（8 元，≈27200）上升
        assertThat(custom.levels.last().amount).isLessThan(inverse.levels.last().amount)
        assertThat(custom.levels.first().amount).isGreaterThan(inverse.levels.first().amount)
    }

    /** 权重档数与 grids 不一致 → 参数错误（防编辑档数后残留旧权重）。 */
    @Test
    fun `custom weights size mismatch returns error`() {
        val r = GridCalculator.generate(
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0,
            grids = 4, totalCapital = 100000.0,
            levelWeights = listOf(1.0, 2.0, 3.0)
        )
        assertThat(r.validationError).isNotNull()
        assertThat(r.levels).isEmpty()
    }

    /** 权重含 0/负数（对应 UI 上未填/填错）→ 参数错误。 */
    @Test
    fun `custom weights must be all positive`() {
        val zero = GridCalculator.generate(
            10.0, 8.0, 12.0, 2, 100000.0, levelWeights = listOf(1.0, 0.0)
        )
        assertThat(zero.validationError).isNotNull()
        val negative = GridCalculator.generate(
            10.0, 8.0, 12.0, 2, 100000.0, levelWeights = listOf(-1.0, 2.0)
        )
        assertThat(negative.validationError).isNotNull()
    }

    /** 等比/按股息率模式下自定义权重同样生效（贵档可多配，覆盖反比默认）。 */
    @Test
    fun `custom weights work with geometric and yield grids`() {
        val geom = GridCalculator.generate(
            basePrice = 16.0, lowPrice = 4.0, highPrice = 20.0,
            grids = 3, totalCapital = 100000.0,
            gridType = GridType.GEOMETRIC, levelWeights = listOf(1.0, 1.0, 5.0)
        )
        assertThat(geom.validationError).isNull()
        // 最贵档（16 元）拿 5/7 资金 ≈71428 → 4400 股；最便宜档（4 元）1/7 ≈14285 → 3500 股
        assertThat(geom.levels.last().amount).isGreaterThan(geom.levels.first().amount)

        val yieldGrid = GridCalculator.generate(
            basePrice = 9.0909, lowPrice = 7.6923, highPrice = 9.0909,
            grids = 3, totalCapital = 100000.0,
            gridType = GridType.YIELD, dps = 0.5, levelWeights = listOf(1.0, 1.0, 1.0)
        )
        assertThat(yieldGrid.validationError).isNull()
        // 等权 1/3 ≈33333：7.69 档 4300 股、8.33 档 4000 股、9.09 档 3600 股（金额基本一致）
        assertThat(yieldGrid.levels.map { it.shares }).containsExactly(4300, 4000, 3600).inOrder()
    }

    // ── 股息展望（dividendOutlook）──────────────────

    /** 展望 = Σ档位股数 × 每股年分红；收益率 = 年股息/总资金。 */
    @Test
    fun `dividend outlook sums level shares times dps`() {
        val r = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        val totalShares = r.levels.sumOf { it.shares }
        val outlook = GridCalculator.dividendOutlook(r, dps = 0.5, totalCapital = 100000.0)
        assertThat(outlook).isNotNull()
        assertThat(outlook!!.annualDividend).isWithin(0.01).of(totalShares * 0.5)
        assertThat(outlook.yieldOnCapitalPct)
            .isWithin(0.01).of(totalShares * 0.5 / 100000.0 * 100.0)
    }

    /** 无分红数据（null/非正）→ 展望为 null，不臆造。 */
    @Test
    fun `dividend outlook null without positive dps`() {
        val r = GridCalculator.generate(10.0, 8.0, 12.0, 4, 100000.0)
        assertThat(GridCalculator.dividendOutlook(r, dps = null, totalCapital = 100000.0)).isNull()
        assertThat(GridCalculator.dividendOutlook(r, dps = 0.0, totalCapital = 100000.0)).isNull()
    }
}
