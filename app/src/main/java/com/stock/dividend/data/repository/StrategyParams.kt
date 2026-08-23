package com.stock.dividend.data.repository

import com.google.gson.Gson
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_DIVIDEND_REINVEST
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_DUAL_MA
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DEVIATION
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_TAKE_PROFIT
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_VALUATION_BAND
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_VALUE_AVERAGING
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_YIELD_BAND

/**
 * `strategy_plans.params` 列的编解码（纯函数 + Gson，无 Android 依赖；DB v31 起
 * 非 MA_DCA 策略类型的统一参数存储，模式参考 GridLevelWeights——**一次 Migration
 * 终身受用**，新增策略类型不再加列）。
 *
 * 容错纪律（与 BackupData.normalizeXxx 同因）：Gson 绕过构造函数反序列化，
 * 缺失字段 → 数值 0 / 对象 null——各 decode 一律把非法值**回退该类型默认值**，
 * 绝不让脏数据炸掉策略评估；完全非法 JSON → 全默认。字段级默认值兜底对任何
 * 版本都生效（防御手改备份/跨版本恢复）。
 */
object StrategyParams {

    /** 目标止盈：按摊薄成本涨幅分批卖出。 */
    data class TakeProfit(
        val halfGainPercent: Double = 15.0,
        val allGainPercent: Double = 25.0
    )

    /** 股息率带：达买入线买入、达加仓线加倍买，跌破卖出线清仓。 */
    data class YieldBand(
        val buyYieldPercent: Double = 6.0,
        val addYieldPercent: Double = 6.5,
        val sellYieldPercent: Double = 4.0
    )

    /** 双均线趋势：快线相对慢线上方=多头（金叉后持有），下方=空头（死叉卖出）。 */
    data class DualMa(
        val fastPeriod: Int = 50,
        val slowPeriod: Int = 250
    )

    /** 均线偏离回归：低于均线每 stepPercent 一档低吸，回归均线卖出低吸部分。 */
    data class MaDeviation(
        val maPeriod: Int = 250,
        val stepPercent: Double = 5.0,
        val buyLevels: Int = 3
    )

    /** 价值平均法：目标市值 = 每期金额 × 已过期数，缺口补足、超额卖出。 */
    data class ValueAveraging(
        val perPeriodAmount: Double = 1000.0
    )

    /** 估值带：PE/PB 绝对阈值低买高卖（百分位需历史累积，暂不支持）。 */
    data class ValuationBand(
        val metric: String = VALUATION_METRIC_PE,
        val lowThreshold: Double = 8.0,
        val highThreshold: Double = 15.0
    )

    /** 分红再投：未来 N 天内有除权 → 到账金额按现价折股提示再投入。 */
    data class DividendReinvest(
        val lookaheadDays: Int = 14
    )

    const val VALUATION_METRIC_PE = "PE"
    const val VALUATION_METRIC_PB = "PB"

    private val gson = Gson()

    /** 任意参数对象 → JSON 字符串（存库用）。 */
    fun encode(params: Any): String = gson.toJson(params)

    // ── 各类型解码（含脏数据回退默认）──

    fun decodeTakeProfit(raw: String?): TakeProfit {
        val p = parseOrNull(raw, TakeProfit::class.java) ?: return TakeProfit()
        return p.copy(
            halfGainPercent = p.halfGainPercent.takeIf { it > 0.0 } ?: 15.0,
            allGainPercent = p.allGainPercent.takeIf { it > 0.0 } ?: 25.0
        ).let { if (it.allGainPercent <= it.halfGainPercent) TakeProfit(halfGainPercent = it.halfGainPercent) else it }
    }

    fun decodeYieldBand(raw: String?): YieldBand {
        val p = parseOrNull(raw, YieldBand::class.java) ?: return YieldBand()
        var fixed = p.copy(
            buyYieldPercent = p.buyYieldPercent.takeIf { it > 0.0 } ?: 6.0,
            addYieldPercent = p.addYieldPercent.takeIf { it > 0.0 } ?: 6.5,
            sellYieldPercent = p.sellYieldPercent.takeIf { it > 0.0 } ?: 4.0
        )
        if (fixed.addYieldPercent < fixed.buyYieldPercent ||
            fixed.sellYieldPercent >= fixed.buyYieldPercent
        ) {
            val buy = fixed.buyYieldPercent
            fixed = fixed.copy(
                addYieldPercent = maxOf(fixed.addYieldPercent, buy),
                sellYieldPercent = minOf(fixed.sellYieldPercent, buy / 2.0)
            )
        }
        return fixed
    }

    fun decodeDualMa(raw: String?): DualMa {
        val p = parseOrNull(raw, DualMa::class.java) ?: return DualMa()
        val fixed = p.copy(
            fastPeriod = p.fastPeriod.takeIf { it >= 2 } ?: 50,
            slowPeriod = p.slowPeriod.takeIf { it >= 2 } ?: 250
        )
        return if (fixed.slowPeriod <= fixed.fastPeriod) DualMa(fastPeriod = fixed.fastPeriod) else fixed
    }

    fun decodeMaDeviation(raw: String?): MaDeviation {
        val p = parseOrNull(raw, MaDeviation::class.java) ?: return MaDeviation()
        return p.copy(
            maPeriod = p.maPeriod.takeIf { it >= 2 } ?: 250,
            stepPercent = p.stepPercent.takeIf { it > 0.0 } ?: 5.0,
            buyLevels = p.buyLevels.takeIf { it in 1..10 } ?: 3
        )
    }

    fun decodeValueAveraging(raw: String?): ValueAveraging {
        val p = parseOrNull(raw, ValueAveraging::class.java) ?: return ValueAveraging()
        return p.copy(
            perPeriodAmount = p.perPeriodAmount.takeIf { it > 0.0 } ?: 1000.0
        )
    }

    fun decodeValuationBand(raw: String?): ValuationBand {
        val p = parseOrNull(raw, ValuationBand::class.java) ?: return ValuationBand()
        val metric = p.metric.takeIf { it == VALUATION_METRIC_PE || it == VALUATION_METRIC_PB }
            ?: VALUATION_METRIC_PE
        var fixed = p.copy(
            metric = metric,
            lowThreshold = p.lowThreshold.takeIf { it > 0.0 } ?: 8.0,
            highThreshold = p.highThreshold.takeIf { it > 0.0 } ?: 15.0
        )
        if (fixed.highThreshold <= fixed.lowThreshold) {
            // 默认比例 8:15 折算回退，保持用户低阈值意图
            fixed = fixed.copy(highThreshold = fixed.lowThreshold * 15.0 / 8.0)
        }
        return fixed
    }

    fun decodeDividendReinvest(raw: String?): DividendReinvest {
        val p = parseOrNull(raw, DividendReinvest::class.java) ?: return DividendReinvest()
        return p.copy(
            lookaheadDays = p.lookaheadDays.takeIf { it in 1..90 } ?: 14
        )
    }

    private fun <T> parseOrNull(raw: String?, cls: Class<T>): T? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { gson.fromJson(it, cls) }.getOrNull() }

    // ── 编辑器辅助：默认输入 / 存档参数 → 输入框字符串 ──

    private fun num(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /** 各类型新建时的默认输入（键 → 输入框字符串；MA_DCA → 空表，走专用列）。 */
    fun defaultsFor(strategyType: String): Map<String, String> = when (strategyType) {
        STRATEGY_TYPE_TAKE_PROFIT -> mapOf("halfGainPercent" to "15", "allGainPercent" to "25")
        STRATEGY_TYPE_YIELD_BAND -> mapOf(
            "buyYieldPercent" to "6", "addYieldPercent" to "6.5", "sellYieldPercent" to "4"
        )
        STRATEGY_TYPE_DUAL_MA -> mapOf("fastPeriod" to "50", "slowPeriod" to "250")
        STRATEGY_TYPE_MA_DEVIATION -> mapOf("maPeriod" to "250", "stepPercent" to "5", "buyLevels" to "3")
        STRATEGY_TYPE_VALUE_AVERAGING -> mapOf("perPeriodAmount" to "1000")
        STRATEGY_TYPE_VALUATION_BAND -> mapOf(
            "metric" to VALUATION_METRIC_PE, "lowThreshold" to "8", "highThreshold" to "15"
        )
        STRATEGY_TYPE_DIVIDEND_REINVEST -> mapOf("lookaheadDays" to "14")
        else -> emptyMap()
    }

    /** 存档 params JSON → 输入框回填字符串（含脏数据回退默认；MA_DCA → 空表）。 */
    fun toInputs(strategyType: String, raw: String?): Map<String, String> = when (strategyType) {
        STRATEGY_TYPE_TAKE_PROFIT -> decodeTakeProfit(raw).let {
            mapOf("halfGainPercent" to num(it.halfGainPercent), "allGainPercent" to num(it.allGainPercent))
        }
        STRATEGY_TYPE_YIELD_BAND -> decodeYieldBand(raw).let {
            mapOf(
                "buyYieldPercent" to num(it.buyYieldPercent),
                "addYieldPercent" to num(it.addYieldPercent),
                "sellYieldPercent" to num(it.sellYieldPercent)
            )
        }
        STRATEGY_TYPE_DUAL_MA -> decodeDualMa(raw).let {
            mapOf("fastPeriod" to it.fastPeriod.toString(), "slowPeriod" to it.slowPeriod.toString())
        }
        STRATEGY_TYPE_MA_DEVIATION -> decodeMaDeviation(raw).let {
            mapOf(
                "maPeriod" to it.maPeriod.toString(),
                "stepPercent" to num(it.stepPercent),
                "buyLevels" to it.buyLevels.toString()
            )
        }
        STRATEGY_TYPE_VALUE_AVERAGING -> decodeValueAveraging(raw).let {
            mapOf("perPeriodAmount" to num(it.perPeriodAmount))
        }
        STRATEGY_TYPE_VALUATION_BAND -> decodeValuationBand(raw).let {
            mapOf(
                "metric" to it.metric,
                "lowThreshold" to num(it.lowThreshold),
                "highThreshold" to num(it.highThreshold)
            )
        }
        STRATEGY_TYPE_DIVIDEND_REINVEST -> decodeDividendReinvest(raw).let {
            mapOf("lookaheadDays" to it.lookaheadDays.toString())
        }
        else -> emptyMap()
    }

    // ── 编辑器输入 → 参数对象 / 编码（校验失败返回中文错误）──

    /**
     * 按类型把编辑器字符串输入转成 params JSON。
     * @return (encoded, error)：成功 encoded 非空 error 为 null；校验失败 encoded 为 null；
     *   MA_DCA 不走 params 通道（专用列），两者皆 null。
     */
    fun fromInputs(strategyType: String, inputs: Map<String, String>): Pair<String?, String?> {
        fun num(key: String): Double? = inputs[key]?.trim()?.toDoubleOrNull()
        return when (strategyType) {
            STRATEGY_TYPE_TAKE_PROFIT -> {
                val half = num("halfGainPercent") ?: return null to "卖出一半涨幅须为数字"
                val all = num("allGainPercent") ?: return null to "清仓涨幅须为数字"
                if (half <= 0.0) return null to "卖出一半涨幅必须大于 0"
                if (all <= half) return null to "清仓涨幅必须大于卖出一半涨幅"
                encode(TakeProfit(half, all)) to null
            }
            STRATEGY_TYPE_YIELD_BAND -> {
                val buy = num("buyYieldPercent") ?: return null to "买入股息率须为数字"
                val add = num("addYieldPercent") ?: return null to "加仓股息率须为数字"
                val sell = num("sellYieldPercent") ?: return null to "卖出股息率须为数字"
                if (buy <= 0.0) return null to "买入股息率必须大于 0"
                if (add < buy) return null to "加仓股息率不能低于买入股息率"
                if (sell <= 0.0 || sell >= buy) return null to "卖出股息率必须大于 0 且低于买入股息率"
                encode(YieldBand(buy, add, sell)) to null
            }
            STRATEGY_TYPE_DUAL_MA -> {
                val fast = num("fastPeriod")?.toInt() ?: return null to "快线周期须为整数"
                val slow = num("slowPeriod")?.toInt() ?: return null to "慢线周期须为整数"
                if (fast < 2) return null to "快线周期至少 2 日"
                if (slow <= fast) return null to "慢线周期必须大于快线周期"
                encode(DualMa(fast, slow)) to null
            }
            STRATEGY_TYPE_MA_DEVIATION -> {
                val ma = num("maPeriod")?.toInt() ?: return null to "均线周期须为整数"
                val step = num("stepPercent") ?: return null to "偏离步长须为数字"
                val levels = num("buyLevels")?.toInt() ?: return null to "买入档数须为整数"
                if (ma < 2) return null to "均线周期至少 2 日"
                if (step <= 0.0) return null to "偏离步长必须大于 0"
                if (levels !in 1..10) return null to "买入档数须在 1~10"
                encode(MaDeviation(ma, step, levels)) to null
            }
            STRATEGY_TYPE_VALUE_AVERAGING -> {
                val amount = num("perPeriodAmount") ?: return null to "每期增长金额须为数字"
                if (amount <= 0.0) return null to "每期增长金额必须大于 0"
                encode(ValueAveraging(amount)) to null
            }
            STRATEGY_TYPE_VALUATION_BAND -> {
                val metric = inputs["metric"]?.trim()?.uppercase()
                if (metric != VALUATION_METRIC_PE && metric != VALUATION_METRIC_PB) {
                    return null to "估值指标只支持 PE 或 PB"
                }
                val low = num("lowThreshold") ?: return null to "低估值阈值须为数字"
                val high = num("highThreshold") ?: return null to "高估值阈值须为数字"
                if (low <= 0.0) return null to "低估值阈值必须大于 0"
                if (high <= low) return null to "高估值阈值必须大于低估值阈值"
                encode(ValuationBand(metric, low, high)) to null
            }
            STRATEGY_TYPE_DIVIDEND_REINVEST -> {
                val days = num("lookaheadDays")?.toInt() ?: return null to "展望天数须为整数"
                if (days !in 1..90) return null to "展望天数须在 1~90"
                encode(DividendReinvest(days)) to null
            }
            else -> null to null   // MA_DCA 及未知类型：不走 params 通道
        }
    }
}
