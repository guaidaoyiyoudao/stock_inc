package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.plane.MarketDataPlane
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 组合诊断共享装配器：持仓 + 现价 → [DiagnoseHolding] 列表 → [PortfolioRiskDiagnoser.diagnose]。
 *
 * 供今日页（复用已刷新行情）与 Agent 工具 `diagnose_portfolio`（主动拉新价）共用，
 * 装配口径与既有工具实现一致：
 * - [annualDividend] = ForecastCalculator.latestYearlyCashPerShare × 股数；
 * - [DiagnoseHolding.dividendYieldPct] = 年均每股分红 ÷ 现价（与 get_stock_info 同口径）；
 * - [DiagnoseHolding.payoutRatio] = 基本面（7 天缓存）+ 股息表 EPS_DIV enrich，取最新一期；
 * - [DiagnoseHolding.consecutiveYears] = DividendMetricsCalculator 连续分红年数。
 *
 * 现价缺失的持仓跳过（不臆造，宪法原则 III）；任何失败吞异常返回 null（红线 #2）。
 */
@Singleton
class PortfolioDiagnosisAssembler @Inject constructor(
    private val marketDataPlane: MarketDataPlane,
) {

    /**
     * @param stocks 全部持仓（调用方保证 shares>0）
     * @param prices 现价 map（code → 现价），缺价的持仓跳过
     * @return 诊断结果；无持仓有价或整体失败返回 null
     */
    suspend fun assemble(stocks: List<StockEntity>, prices: Map<String, Double>): PortfolioRiskDiagnosis? =
        runCatching {
            if (stocks.isEmpty()) return@runCatching null
            val bondYield = runCatching { marketDataPlane.get10YBondYield() }.getOrNull()
            // 基本面 7 天缓存读为主，过期刷新会联网——限流防高频（红线 #5 精神）
            val semaphore = Semaphore(3)
            coroutineScope {
                val holdings = stocks.mapNotNull { s ->
                    val price = prices[s.code]?.takeIf { it > 0.0 } ?: return@mapNotNull null
                    // 与 plane.getDps 同新鲜度策略（2026-08-20 审计修复 M7）：先确保分红拉取过，
                    // 否则从未刷新过分红的股票在诊断里 annualDividend/yieldPct=null，同会话其他工具却有值
                    runCatching { marketDataPlane.ensureDividendsFresh(s.code) }
                    val dividends = runCatching { marketDataPlane.getDividends(s.code) }
                        .getOrDefault(emptyList())
                    val yearlyCash = ForecastCalculator.latestYearlyCashPerShare(dividends)
                    val metrics = DividendMetricsCalculator.calculate(dividends)
                    // 平面版基本面已补派息率（enrichPayoutRatio 收敛于数据平面）
                    val payoutRatio = semaphore.withPermit {
                        runCatching { marketDataPlane.getFundamentals(s.code) }
                            .getOrNull()
                            ?.periods?.firstOrNull { it.payoutRatio != null }?.payoutRatio
                    }
                    DiagnoseHolding(
                        code = s.code,
                        name = s.name,
                        industry = s.industry.takeIf { it.isNotBlank() },
                        marketValue = price * s.shares,
                        annualDividend = yearlyCash?.takeIf { it > 0.0 }?.let { it * s.shares },
                        dividendYieldPct = if (yearlyCash != null && yearlyCash > 0.0) {
                            yearlyCash / price * 100.0
                        } else null,
                        consecutiveYears = metrics?.consecutiveYears,
                        payoutRatio = payoutRatio,
                    )
                }
                PortfolioRiskDiagnoser.diagnose(holdings, bondYield10yPct = bondYield)
            }
        }.getOrNull()
}
