package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.GridPlanEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 今日信号类型。 */
enum class TodaySignalType { BUY_TRIGGER, GRID_NEXT_LEVEL, DIVIDEND_COUNTDOWN }

/**
 * 单条今日信号（纯数据，UI 据此渲染一行）。
 * @param sortPriority 排序权重，小者在前（BUY=0 / GRID=1 / DIVIDEND=2）。
 * @param key LazyColumn 稳定唯一键（buy-{code} / grid-{planId} / div-{code}）。
 *   ⚠️ 不可用 stockCode+type 组合——同股多套网格计划会产生多条 GRID 信号，
 *   key 撞车会在今日页滚动到信号区时抛「Key was already used」闪退（2026-08-16 修复）。
 */
data class TodaySignal(
    val type: TodaySignalType,
    val stockCode: String,
    val stockName: String,
    val title: String,
    val detail: String,
    val sortPriority: Int,
    val key: String,
)

/** 单只股票的快照输入（聚合信号用，纯数据）。 */
data class TodayStockSnapshot(
    val code: String,
    val name: String,
    val price: Double?,
    val weeklyBand: BollBand? = null,
    val dailyBand: BollBand? = null,
    val monthlyBand: BollBand? = null,
    val latestYearlyDividend: Double? = null,
    val thresholds: DividendThresholds = DividendThresholds(),
    val buyThresholdMultiplier: Double = 2.5,
    val bondYield10Y: Double? = null,
)

/** 聚合输入。 */
data class TodaySignalInput(
    val stocks: List<TodayStockSnapshot>,
    val gridPlans: List<GridPlanEntity>,
    val gridCurrentPrices: Map<String, Double>,
    val dividends: List<DividendEntity>,
    val today: LocalDate,
    val dividendLookaheadDays: Long = 30,
)

/** 今日信号聚合（纯函数，无 Android 依赖）。复用 [HoldingRecommender] / [computeBuyThreshold] / [GridCalculator]。 */
object TodaySignalAggregator {

    fun aggregate(input: TodaySignalInput): List<TodaySignal> {
        val signals = mutableListOf<TodaySignal>()
        buyTriggers(input.stocks, signals)
        gridNextLevels(input, signals)
        dividendCountdowns(input, signals)
        // key 兜底去重：即使未来新增信号源破坏唯一约定，也不让今日页因 LazyColumn
        // 重复 key 闪退（少显示一条信号优于崩溃；正常路径不会触发，有单测锁定）
        return signals.distinctBy { it.key }
            .sortedWith(compareBy({ it.sortPriority }, { it.stockCode }))
    }

    private fun buyTriggers(stocks: List<TodayStockSnapshot>, out: MutableList<TodaySignal>) {
        for (s in stocks) {
            val price = s.price
            if (price == null || !price.isFinite() || price <= 0.0) continue

            // 1a. 三周期共振 BUY（日下轨 + 周下轨 + 月中轨及以下，股息率达 minYield）
            val rec = HoldingRecommender.recommend(
                price = price,
                band = s.weeklyBand,
                latestYearlyDividend = s.latestYearlyDividend,
                thresholds = s.thresholds,
                dailyBand = s.dailyBand,
                monthlyBand = s.monthlyBand,
            )
            if (rec.action == HoldingAction.BUY) {
                out += TodaySignal(
                    type = TodaySignalType.BUY_TRIGGER,
                    stockCode = s.code,
                    stockName = s.name,
                    title = "三周期共振买入",
                    detail = "现价 %.2f，股息率 %s".format(
                        price, rec.dividendYield?.let { "%.2f%%".format(it) } ?: "—"
                    ),
                    sortPriority = 0,
                    key = "buy-${s.code}", // 单股 BUY 三分支互斥（命中即 continue），code 维度唯一
                )
                continue // 同股已有买入信号，不再判门槛
            }

            // 1b. 股息率达买入线（10Y 国债 × 倍数）
            val bond = s.bondYield10Y
            if (bond != null && bond > 0.0 && s.latestYearlyDividend != null && s.latestYearlyDividend > 0.0) {
                val status = computeBuyThreshold(bond, s.buyThresholdMultiplier, s.latestYearlyDividend, price)
                if (status.reached == true) {
                    out += TodaySignal(
                        type = TodaySignalType.BUY_TRIGGER,
                        stockCode = s.code,
                        stockName = s.name,
                        title = "股息率达买入线",
                        detail = "现价 %.2f，股息率 %.2f%% → 目标 %.2f%%".format(
                            price, status.currentYieldPercent ?: 0.0, status.targetYieldPercent
                        ),
                        sortPriority = 0,
                        key = "buy-${s.code}",
                    )
                    continue
                }
            }

            // 1c. 周线跌破 BOLL 下轨（轻量：仅需周线 BOLL，今日页可触发）
            val weekly = s.weeklyBand
            if (weekly != null && price <= weekly.lower) {
                out += TodaySignal(
                    type = TodaySignalType.BUY_TRIGGER,
                    stockCode = s.code,
                    stockName = s.name,
                    title = "跌破周线BOLL下轨",
                    detail = "现价 %.2f，下轨 %.2f".format(price, weekly.lower),
                    sortPriority = 0,
                    key = "buy-${s.code}",
                )
            }
        }
    }

    private fun gridNextLevels(input: TodaySignalInput, out: MutableList<TodaySignal>) {
        for (plan in input.gridPlans) {
            val current = input.gridCurrentPrices[plan.stockCode]
                ?: input.stocks.firstOrNull { it.code == plan.stockCode }?.price
                ?: continue
            val result = GridCalculator.generate(
                basePrice = plan.basePrice,
                lowPrice = plan.lowPrice,
                highPrice = plan.highPrice,
                grids = plan.grids,
                totalCapital = plan.totalCapital,
                currentPrice = current,
                gridType = GridType.fromRaw(plan.gridType),
            )
            val next = result.nextBuyHint
            if (next != null && result.validationError == null) {
                out += TodaySignal(
                    type = TodaySignalType.GRID_NEXT_LEVEL,
                    stockCode = plan.stockCode,
                    stockName = plan.stockName,
                    title = "网格下一档买入",
                    detail = "现价 %.2f，下一档 %.2f".format(current, next),
                    sortPriority = 1,
                    // 用 plan.id 不用 stockCode：同股多套网格计划是合法场景，各自一条信号
                    key = "grid-${plan.id}",
                )
            }
        }
    }

    private fun dividendCountdowns(input: TodaySignalInput, out: MutableList<TodaySignal>) {
        val horizon = input.today.plusDays(input.dividendLookaheadDays)
        // 每只股取最近一笔除权（去重），日期带 " 00:00:00" 后缀也兼容（§4.9.5）
        val upcomingByCode = input.dividends
            .mapNotNull { d ->
                val raw = d.exDividendDate ?: return@mapNotNull null
                val date = parseDate(raw) ?: return@mapNotNull null
                d to date
            }
            .filter { (_, date) -> !date.isBefore(input.today) && !date.isAfter(horizon) }
            .groupBy { it.first.stockCode }
        for ((code, list) in upcomingByCode) {
            val (d, date) = list.minByOrNull { it.second } ?: continue
            val name = input.stocks.firstOrNull { it.code == code }?.name ?: code
            val days = ChronoUnit.DAYS.between(input.today, date)
            out += TodaySignal(
                type = TodaySignalType.DIVIDEND_COUNTDOWN,
                stockCode = code,
                stockName = name,
                title = if (days <= 0L) "今日除权" else "${days}天后除权",
                detail = "每股分红 %.4f 元".format(d.cashPerShare),
                sortPriority = 2,
                key = "div-$code", // groupBy code 后取最近一笔，code 维度唯一
            )
        }
    }

    /** 解析除权日字符串（兼容 "yyyy-MM-dd 00:00:00" 后缀）。失败返回 null。 */
    private fun parseDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw.substringBefore(" ").trim()) }.getOrNull()
}
