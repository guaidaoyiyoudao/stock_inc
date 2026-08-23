package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_DIVIDEND_REINVEST
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_VALUATION_BAND
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_YIELD_BAND
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.plane.MarketDataPlane
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 策略评估输入装配器（@Singleton）：按计划列表从数据平面采集各类型所需数据
 * （日线收盘/DPS/估值快照/近期除权/持仓与成本/期数），产出 planId → [StrategyInput]。
 * 策略页、今日页、通知协调器三处共用，避免输入采集逻辑分叉。
 *
 * 全部取数走 [MarketDataPlane]（§4.2A 红线）；各源失败吞异常返回空/null（§4.3），
 * 对应计划评估自然降级为「数据不足」。
 */
@Singleton
class StrategyInputAssembler @Inject constructor(
    private val marketDataPlane: MarketDataPlane,
    private val transactionRepository: TransactionRepository
) {

    suspend fun assemble(
        plans: List<StrategyPlanEntity>,
        prices: Map<String, Double>,
        today: LocalDate = LocalDate.now()
    ): Map<String, StrategyInput> {
        if (plans.isEmpty()) return emptyMap()

        // 持仓股数 + 摊薄成本（交易流水摊薄口径，一次取全量按股分组）
        val holdings = runCatching {
            transactionRepository.getAll()
                .groupBy { it.stockCode }
                .mapValues { (_, list) -> HoldingCalculator.calculate(list) }
        }.getOrDefault(emptyMap())

        // 每股一次拉足该股全部计划所需的最大收盘价根数（仅需要 K 线的类型）
        val closesByCode: Map<String, List<Double>> = coroutineScope {
            plans.groupBy({ it.stockCode }) { StrategyEvaluator.requiredCloses(it) }
                .mapValues { (_, bars) -> bars.maxOrNull() ?: 0 }
                .filterValues { it > 0 }
                .map { (code, bars) ->
                    code to async {
                        runCatching {
                            marketDataPlane.getKlines(code, KlinePeriod.DAILY, bars).map { it.close }
                        }.getOrDefault(emptyList())
                    }
                }
                .mapNotNull { (code, deferred) ->
                    deferred.await().takeIf { it.isNotEmpty() }?.let { code to it }
                }
                .toMap()
        }

        // 股息率带需要 DPS（TTM 口径，平面自动 ensureDividendsFresh）
        val dpsByCode: Map<String, Double> = plans
            .filter { it.strategyType == STRATEGY_TYPE_YIELD_BAND }
            .map { it.stockCode }.toSet()
            .mapNotNull { code ->
                runCatching { marketDataPlane.getDps(code) }.getOrNull()
                    ?.takeIf { it > 0.0 }?.let { code to it }
            }.toMap()

        // 估值带需要 PE/PB 快照（批量一次；扶摇独有，未配置 key 时为空 → 数据不足）
        val valuationByCode: Map<String, Pair<Double?, Double?>> = plans
            .filter { it.strategyType == STRATEGY_TYPE_VALUATION_BAND }
            .map { it.stockCode }.toSet()
            .takeIf { it.isNotEmpty() }
            ?.let { codes ->
                runCatching { marketDataPlane.getValuations(codes.toList()) }.getOrDefault(emptyMap())
                    .mapValues { (_, v) -> v.peTtm to v.pbMrq }
            } ?: emptyMap()

        // 分红再投需要未来 N 天内最近一笔除权
        val nextDividendByCode: Map<String, StrategyDividendEvent> = plans
            .filter { it.strategyType == STRATEGY_TYPE_DIVIDEND_REINVEST }
            .map { it.stockCode }.toSet()
            .mapNotNull { code ->
                val lookahead = StrategyParams.decodeDividendReinvest(
                    plans.first { it.strategyType == STRATEGY_TYPE_DIVIDEND_REINVEST && it.stockCode == code }.params
                ).lookaheadDays
                val dividends = runCatching { marketDataPlane.getDividends(code) }.getOrDefault(emptyList())
                findNextDividendEvent(dividends, today, lookahead)?.let { code to it }
            }.toMap()

        return plans.associate { plan ->
            val holding = holdings[plan.stockCode]
            val valuation = valuationByCode[plan.stockCode]
            plan.id to StrategyInput(
                currentPrice = prices[plan.stockCode]?.takeIf { it > 0.0 },
                closes = closesByCode[plan.stockCode].orEmpty(),
                dps = dpsByCode[plan.stockCode],
                holdingShares = holding?.totalShares ?: 0,
                avgCostPerShare = holding?.avgCostPerShare ?: 0.0,
                valuationPeTtm = valuation?.first,
                valuationPbMrq = valuation?.second,
                nextDividend = nextDividendByCode[plan.stockCode],
                monthsSinceStart = ChronoUnit.MONTHS.between(
                    epochMillisToLocalDate(plan.createdAt), today
                ).coerceAtLeast(0L)
            )
        }
    }

    companion object {
        /** 未来 [lookaheadDays] 天内（含今日）最近一笔已排期除权 → 再投事件；无 → null。 */
        fun findNextDividendEvent(
            dividends: List<DividendEntity>,
            today: LocalDate,
            lookaheadDays: Int
        ): StrategyDividendEvent? {
            return dividends.mapNotNull { d ->
                val raw = d.exDividendDate ?: return@mapNotNull null
                val date = runCatching { LocalDate.parse(raw.substringBefore(" ").trim()) }.getOrNull()
                    ?: return@mapNotNull null
                if (d.cashPerShare <= 0.0) return@mapNotNull null
                Triple(d, date, ChronoUnit.DAYS.between(today, date))
            }.filter { (_, date, days) -> !date.isBefore(today) && days <= lookaheadDays }
                .minByOrNull { (_, _, days) -> days }
                ?.let { (d, _, days) ->
                    StrategyDividendEvent(
                        exDate = d.exDividendDate!!.substringBefore(" "),
                        daysAway = days,
                        cashPerShare = d.cashPerShare
                    )
                }
        }

        private fun epochMillisToLocalDate(millis: Long): LocalDate =
            java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    }
}
