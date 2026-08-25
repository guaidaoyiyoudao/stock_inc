package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.GridLevelWeights
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 今日信号类型。 */
enum class TodaySignalType {
    BUY_TRIGGER, SELL_TRIGGER, GRID_NEXT_LEVEL, DIVIDEND_COUNTDOWN,
    STRATEGY_DCA, STRATEGY_SELL
}

/**
 * 单条今日信号（纯数据，UI 据此渲染一行）。
 * @param sortPriority 排序权重，小者在前。全量权重表：
 *   BUY_TRIGGER=0 / STRATEGY_DCA=0 / SELL_TRIGGER=1 / GRID_NEXT_LEVEL=1 /
 *   STRATEGY_SELL=1 / DIVIDEND_COUNTDOWN=2。
 * @param key LazyColumn 稳定唯一键（前缀区分信号源，全量清单：
 *   buy-{code} / gridsell-{planId} / grid-{planId} / strategydca-{planId} /
 *   strategysell-{planId} / div-{code}）。
 *   ⚠️ 不可用 stockCode+type 组合——同股多套网格/策略计划会产生多条 GRID/STRATEGY 信号，
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
    /** 网格标的交易记录（按 stockCode 分组）：把已买入档从「下一档」提示中排除（每档只买一次）。 */
    val gridTransactionsByStock: Map<String, List<TransactionEntity>> = emptyMap(),
    /** 交易策略计划（全部类型），VM 经装配器+调度器算好统一评估传入（纯函数无 IO）。 */
    val strategyPlans: List<StrategyPlanEntity> = emptyList(),
    /** 策略统一评估结果（planId → 评估；缺失 = 数据不足，跳过）。 */
    val strategyEvaluations: Map<String, StrategyEvaluation> = emptyMap(),
)

/** 今日信号聚合（纯函数，无 Android 依赖）。复用 [HoldingRecommender] / [computeBuyThreshold] / [GridCalculator]。 */
object TodaySignalAggregator {

    fun aggregate(input: TodaySignalInput): List<TodaySignal> {
        val signals = mutableListOf<TodaySignal>()
        buyTriggers(input.stocks, signals)
        gridSellTriggers(input, signals)
        gridNextLevels(input, signals)
        strategySignals(input, signals)
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
            // 关联实际交易标记已买档：下一档提示须跳过已买档（每档只买一次）
            val result = GridCalculator.markTriggeredLevels(
                GridCalculator.generate(
                    basePrice = plan.basePrice,
                    lowPrice = plan.lowPrice,
                    highPrice = plan.highPrice,
                    grids = plan.grids,
                    totalCapital = plan.totalCapital,
                    currentPrice = current,
                    gridType = GridType.fromRaw(plan.gridType),
                    dps = plan.dpsPerShare,
                    levelWeights = GridLevelWeights.parse(plan.levelWeights),
                    swingMode = plan.swingMode,
                    swingStepPercent = plan.swingStepPercent,
                    swingRatioPercent = plan.swingRatioPercent
                ),
                input.gridTransactionsByStock[plan.stockCode].orEmpty()
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

    /**
     * 波段网格卖出到档信号：现价 ≥ 某在持档的**卖出锚**（股息率锚）→ 「现在就该减仓
     * 波段部分」(区别于「下一档买入」的预警视角，这是即时可执行信号；底仓不动）。
     * 取已到达目标中最高的一档；纯买入计划不产生本类信号。
     */
    private fun gridSellTriggers(input: TodaySignalInput, out: MutableList<TodaySignal>) {
        for (plan in input.gridPlans) {
            if (!plan.swingMode) continue
            val current = input.gridCurrentPrices[plan.stockCode]
                ?: input.stocks.firstOrNull { it.code == plan.stockCode }?.price
                ?: continue
            val result = GridCalculator.markTriggeredLevels(
                GridCalculator.generate(
                    basePrice = plan.basePrice,
                    lowPrice = plan.lowPrice,
                    highPrice = plan.highPrice,
                    grids = plan.grids,
                    totalCapital = plan.totalCapital,
                    currentPrice = current,
                    gridType = GridType.fromRaw(plan.gridType),
                    dps = plan.dpsPerShare,
                    levelWeights = GridLevelWeights.parse(plan.levelWeights),
                    swingMode = plan.swingMode,
                    swingStepPercent = plan.swingStepPercent,
                    swingRatioPercent = plan.swingRatioPercent
                ),
                input.gridTransactionsByStock[plan.stockCode].orEmpty()
            )
            if (result.validationError != null) continue
            // 已到达（现价 ≥ 卖出锚）的在持档中最高的一档——pairedSellPrice 与档位配对取出，
            // 后续全部用局部变量，不再重复非空断言
            val reached = result.levels
                .mapNotNull { level -> level.pairedSellPrice?.let { level to it } }
                .filter { (level, sellPrice) -> level.triggered && current >= sellPrice }
                .maxByOrNull { it.second }
                ?: continue
            val (reachedLevel, reachedSellPrice) = reached
            out += TodaySignal(
                type = TodaySignalType.SELL_TRIGGER,
                stockCode = plan.stockCode,
                stockName = plan.stockName,
                title = "波段网格到卖出档",
                detail = "现价 %.2f ≥ 卖出锚 %.2f，减仓波段 %d 股（底仓不动）".format(
                    current, reachedSellPrice, reachedLevel.swingShares
                ),
                sortPriority = 1,
                key = "gridsell-${plan.id}",  // 与买入侧信号（grid-）分键，互不顶替
            )
        }
    }

    /**
     * 交易策略信号（全部类型，统一评估）：买入方向（BUY）→ 买点信号（常驻提示，
     * 按约定不发推送）；卖出方向（SELL_HALF/SELL_ALL）→ 卖出信号（推送走通知链路，
     * 今日页仅展示）。HOLD 不产生信号。
     */
    private fun strategySignals(input: TodaySignalInput, out: MutableList<TodaySignal>) {
        for (plan in input.strategyPlans) {
            val evaluation = input.strategyEvaluations[plan.id] ?: continue
            val typeName = StrategyEvaluator.displayName(plan.strategyType)
            val metricText = evaluation.metrics.take(2)
                .joinToString(" · ") { "${it.label} ${it.value}" }
            when (evaluation.action) {
                StrategyAction.BUY -> out += TodaySignal(
                    type = TodaySignalType.STRATEGY_DCA,
                    stockCode = plan.stockCode,
                    stockName = plan.stockName,
                    title = "${typeName}：${evaluation.headline}",
                    detail = metricText + if (evaluation.buyShares > 0) {
                        " · 可买 ${evaluation.buyShares} 股"
                    } else "",
                    sortPriority = 0,
                    key = "strategydca-${plan.id}", // 同股多策略各自一条，planId 维度唯一
                )
                StrategyAction.SELL_HALF, StrategyAction.SELL_ALL -> out += TodaySignal(
                    type = TodaySignalType.STRATEGY_SELL,
                    stockCode = plan.stockCode,
                    stockName = plan.stockName,
                    title = "${typeName}：${evaluation.headline}",
                    detail = metricText + if (evaluation.sellShares > 0) {
                        " · 卖出 ${evaluation.sellShares} 股"
                    } else "",
                    sortPriority = 1,
                    key = "strategysell-${plan.id}",
                )
                StrategyAction.HOLD -> Unit
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
