package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_DIVIDEND_REINVEST
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_DUAL_MA
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_BREAKOUT
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_TAKE_PROFIT
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_VALUATION_BAND
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_YIELD_BAND
import org.junit.Test

/**
 * [StrategyParams] 单测：params JSON 列的编解码、脏数据容错（Gson 绕过构造函数 →
 * 缺字段变 0/null，必须回退各类型默认值）与编辑器输入校验。
 */
class StrategyParamsTest {

    // ── 编解码 round-trip ──

    @Test
    fun `各类型参数编码后可无损解码`() {
        val takeProfit = StrategyParams.TakeProfit(halfGainPercent = 10.0, allGainPercent = 30.0)
        val decoded = StrategyParams.decodeTakeProfit(StrategyParams.encode(takeProfit))
        assertThat(decoded).isEqualTo(takeProfit)

        val yieldBand = StrategyParams.YieldBand(buyYieldPercent = 5.5, addYieldPercent = 6.0, sellYieldPercent = 3.5)
        assertThat(StrategyParams.decodeYieldBand(StrategyParams.encode(yieldBand))).isEqualTo(yieldBand)

        val dualMa = StrategyParams.DualMa(fastPeriod = 20, slowPeriod = 60)
        assertThat(StrategyParams.decodeDualMa(StrategyParams.encode(dualMa))).isEqualTo(dualMa)

        val va = StrategyParams.ValueAveraging(perPeriodAmount = 2000.0)
        assertThat(StrategyParams.decodeValueAveraging(StrategyParams.encode(va))).isEqualTo(va)

        val valuation = StrategyParams.ValuationBand(metric = "PB", lowThreshold = 0.7, highThreshold = 1.4)
        assertThat(StrategyParams.decodeValuationBand(StrategyParams.encode(valuation))).isEqualTo(valuation)
    }

    // ── 脏数据容错：缺字段/0 值 → 回退默认 ──

    @Test
    fun `缺失或非法字段回退默认值`() {
        // Gson 绕过构造函数：JSON 缺字段 → 数值 0.0 / Int 0 / String null
        assertThat(StrategyParams.decodeTakeProfit("""{"halfGainPercent":12.0}"""))
            .isEqualTo(StrategyParams.TakeProfit(halfGainPercent = 12.0, allGainPercent = 25.0))
        assertThat(StrategyParams.decodeDualMa("""{"fastPeriod":0,"slowPeriod":0}"""))
            .isEqualTo(StrategyParams.DualMa())
        assertThat(StrategyParams.decodeValuationBand("""{"metric":"PB","lowThreshold":0.0}"""))
            .isEqualTo(StrategyParams.ValuationBand(metric = "PB"))
        // 完全非法 JSON / null → 全默认
        assertThat(StrategyParams.decodeTakeProfit(null)).isEqualTo(StrategyParams.TakeProfit())
        assertThat(StrategyParams.decodeYieldBand("not json")).isEqualTo(StrategyParams.YieldBand())
    }

    @Test
    fun `估值带 metric 非法值回退 PE`() {
        assertThat(StrategyParams.decodeValuationBand("""{"metric":"XX"}""").metric).isEqualTo("PE")
    }

    // ── 编辑器输入 → 参数对象 / 校验 ──

    @Test
    fun `合法输入构建参数并编码`() {
        val (params, error) = StrategyParams.fromInputs(
            STRATEGY_TYPE_TAKE_PROFIT,
            mapOf("halfGainPercent" to "12", "allGainPercent" to "28")
        )
        assertThat(error).isNull()
        assertThat(params).isNotNull()
        assertThat(StrategyParams.decodeTakeProfit(params)).isEqualTo(
            StrategyParams.TakeProfit(halfGainPercent = 12.0, allGainPercent = 28.0)
        )
    }

    @Test
    fun `非法输入返回中文错误`() {
        // 清仓阈值 ≤ 卖半阈值
        val (_, e1) = StrategyParams.fromInputs(
            STRATEGY_TYPE_TAKE_PROFIT,
            mapOf("halfGainPercent" to "15", "allGainPercent" to "15")
        )
        assertThat(e1).isNotNull()
        // 非数字
        val (_, e2) = StrategyParams.fromInputs(
            STRATEGY_TYPE_TAKE_PROFIT,
            mapOf("halfGainPercent" to "abc", "allGainPercent" to "25")
        )
        assertThat(e2).isNotNull()
        // 股息率带：卖出线必须低于买入线
        val (_, e3) = StrategyParams.fromInputs(
            STRATEGY_TYPE_YIELD_BAND,
            mapOf("buyYieldPercent" to "6", "addYieldPercent" to "6.5", "sellYieldPercent" to "7")
        )
        assertThat(e3).isNotNull()
        // 双均线：慢线必须大于快线
        val (_, e4) = StrategyParams.fromInputs(
            STRATEGY_TYPE_DUAL_MA,
            mapOf("fastPeriod" to "250", "slowPeriod" to "50")
        )
        assertThat(e4).isNotNull()
        // 估值带：低阈值必须小于高阈值
        val (_, e5) = StrategyParams.fromInputs(
            STRATEGY_TYPE_VALUATION_BAND,
            mapOf("metric" to "PE", "lowThreshold" to "15", "highThreshold" to "8")
        )
        assertThat(e5).isNotNull()
        // 分红再投：天数范围 1..90
        val (_, e6) = StrategyParams.fromInputs(
            STRATEGY_TYPE_DIVIDEND_REINVEST,
            mapOf("lookaheadDays" to "0")
        )
        assertThat(e6).isNotNull()
        // 缺输入键
        val (_, e7) = StrategyParams.fromInputs(
            STRATEGY_TYPE_TAKE_PROFIT,
            mapOf("halfGainPercent" to "15")
        )
        assertThat(e7).isNotNull()
    }

    // ── 编辑器辅助：默认输入 / 存档参数 → 输入框回填 ──

    @Test
    fun `各类型默认输入与存档参数回填`() {
        val defaults = StrategyParams.defaultsFor(STRATEGY_TYPE_TAKE_PROFIT)
        assertThat(defaults["halfGainPercent"]).isEqualTo("15")
        assertThat(defaults["allGainPercent"]).isEqualTo("25")

        val raw = StrategyParams.encode(
            StrategyParams.TakeProfit(halfGainPercent = 12.0, allGainPercent = 28.0)
        )
        val inputs = StrategyParams.toInputs(STRATEGY_TYPE_TAKE_PROFIT, raw)
        assertThat(inputs["halfGainPercent"]).isEqualTo("12")
        assertThat(inputs["allGainPercent"]).isEqualTo("28")

        // 脏存档 → 回填默认
        val dirty = StrategyParams.toInputs(STRATEGY_TYPE_DUAL_MA, "{\"fastPeriod\":0}")
        assertThat(dirty["fastPeriod"]).isEqualTo("50")

        // MA_DCA 无 params 字段 → 空表
        assertThat(StrategyParams.defaultsFor(com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DCA)).isEmpty()
    }

    @Test
    fun `MA_DCA 类型不走 params 通道`() {
        val (params, error) = StrategyParams.fromInputs(
            com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DCA,
            emptyMap()
        )
        assertThat(params).isNull()
        assertThat(error).isNull()
    }

    // ── 均线突破 ──

    @Test
    fun `均线突破 编解码 round-trip 与脏数据回退`() {
        val breakout = StrategyParams.MaBreakout(maPeriod = 10)
        assertThat(StrategyParams.decodeMaBreakout(StrategyParams.encode(breakout))).isEqualTo(breakout)
        // Gson 绕过构造函数：缺字段/0 值 → 回退默认 5 日
        assertThat(StrategyParams.decodeMaBreakout("""{"maPeriod":0}"""))
            .isEqualTo(StrategyParams.MaBreakout())
        assertThat(StrategyParams.decodeMaBreakout(null)).isEqualTo(StrategyParams.MaBreakout())
    }

    @Test
    fun `均线突破 输入校验与编辑器回填`() {
        val (params, e1) = StrategyParams.fromInputs(STRATEGY_TYPE_MA_BREAKOUT, mapOf("maPeriod" to "20"))
        assertThat(e1).isNull()
        assertThat(StrategyParams.decodeMaBreakout(params)).isEqualTo(StrategyParams.MaBreakout(maPeriod = 20))
        // 非整数拒绝而非静默截断（与双均线/展望天数同语义）
        val (_, e2) = StrategyParams.fromInputs(STRATEGY_TYPE_MA_BREAKOUT, mapOf("maPeriod" to "5.5"))
        assertThat(e2).isEqualTo("均线周期须为整数")
        // 周期下限 2 日
        val (_, e3) = StrategyParams.fromInputs(STRATEGY_TYPE_MA_BREAKOUT, mapOf("maPeriod" to "1"))
        assertThat(e3).isEqualTo("均线周期至少 2 日")
        // 默认输入 + 存档回填
        assertThat(StrategyParams.defaultsFor(STRATEGY_TYPE_MA_BREAKOUT)).isEqualTo(mapOf("maPeriod" to "5"))
        assertThat(
            StrategyParams.toInputs(
                STRATEGY_TYPE_MA_BREAKOUT,
                StrategyParams.encode(StrategyParams.MaBreakout(maPeriod = 10))
            )
        ).isEqualTo(mapOf("maPeriod" to "10"))
    }

    // ── 2026-08-24 评审修复：decode 兜底不变量闭合 + 整数输入不静默截断 ──

    @Test
    fun `decodeTakeProfit 脏组合兜底后仍满足 all 大于 half`() {
        // 只带 half=50：all 回退默认 25 仍 ≤ half → 必须抬高 all 闭合不变量，
        // 否则涨幅 25% 即 SELL_ALL 清仓，违背存档 half=50 的意图
        val fixed = StrategyParams.decodeTakeProfit("""{"halfGainPercent":50}""")
        assertThat(fixed.halfGainPercent).isEqualTo(50.0)
        assertThat(fixed.allGainPercent).isEqualTo(60.0)   // max(25, 50+10)
        // 只带 all=10：half 回退默认 15 > all → all 抬到 max(25, 15+10)=25
        val fixed2 = StrategyParams.decodeTakeProfit("""{"allGainPercent":10}""")
        assertThat(fixed2.halfGainPercent).isEqualTo(15.0)
        assertThat(fixed2.allGainPercent).isEqualTo(25.0)
    }

    @Test
    fun `decodeDualMa 脏组合兜底后仍满足 slow 大于 fast`() {
        // 只带 fast=300：slow 回退默认 250 仍 ≤ fast → 必须抬 slow，否则快慢线语义颠倒
        val fixed = StrategyParams.decodeDualMa("""{"fastPeriod":300}""")
        assertThat(fixed.fastPeriod).isEqualTo(300)
        assertThat(fixed.slowPeriod).isEqualTo(350)   // max(250, 300+50)
    }

    @Test
    fun `fromInputs 整数字段拒绝小数而非静默截断`() {
        // "50.5" 此前经 toDoubleOrNull().toInt() 偷偷变 50；现在必须报错让用户改输入
        val (_, e1) = StrategyParams.fromInputs(
            STRATEGY_TYPE_DUAL_MA,
            mapOf("fastPeriod" to "50.5", "slowPeriod" to "250")
        )
        assertThat(e1).isEqualTo("快线周期须为整数")
        val (_, e2) = StrategyParams.fromInputs(
            STRATEGY_TYPE_DIVIDEND_REINVEST,
            mapOf("lookaheadDays" to "2.9")
        )
        assertThat(e2).isEqualTo("展望天数须为整数")
        // 合法整数输入不受影响
        val (params, e3) = StrategyParams.fromInputs(
            STRATEGY_TYPE_DUAL_MA,
            mapOf("fastPeriod" to "50", "slowPeriod" to "250")
        )
        assertThat(e3).isNull()
        assertThat(params).isNotNull()
    }
}
