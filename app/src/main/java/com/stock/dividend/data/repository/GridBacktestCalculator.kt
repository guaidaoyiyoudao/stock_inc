package com.stock.dividend.data.repository

/**
 * 网格历史回测结果（基于日收盘价的口径还原）。
 *
 * **口径（如实声明）**：
 * - 只有日**收盘价**可回放，盘中触及但收盘回落的档位无法还原（会低估触发数）；
 * - 成交价按**档位价**假设（网格挂单语义：跌到档位价即成交），非当日收盘价。
 *
 * @property tradingDays          回测窗口交易日数。
 * @property windowStart/windowEnd 窗口首/末交易日（yyyy-MM-dd）。
 * @property triggeredCount       期间触发过的档数（波段模式 = 至少买入过一次的档，
 *   含已卖出释放的档；期末底仓已建的档见 [heldLevelCount]）。
 * @property totalLevels          总档数。
 * @property investedAmount       已投入（元，按档位价 × 档位股数）。波段模式 = 期末
 *   底仓 + 仍在持波段部分的持仓成本（已卖出波段部分资金已回流，不含波段利润）。
 * @property boughtShares         已买股数（波段模式 = 期末净持仓 = 底仓股数 + 在持波段股数）。
 * @property avgBuyPrice          已买部分均价；无触发为 null。
 * @property capitalUtilizationPct 资金使用率（%，已投入/总资金）。
 * @property lumpSumPrice         对照基准：窗口首日收盘价（一次性全买）。
 * @property lumpSumShares        同样资金首日一次性可买股数（A 股整手）。
 * @property costSavingPct        网格均价相对首日一次性买入的成本节省（%，正=网格买得更便宜）；无触发为 null。
 * @property firstTriggerDate/lastTriggerDate 首次/最近触发日。
 * @property minClose/maxClose    窗口最低/最高收盘价。
 * @property roundTrips           波段回合数（买波段→涨到卖出锚→卖波段，完整循环）；
 *   纯买入模式恒 0。
 * @property swingProfit          波段已落袋利润（元，**净额** = Σ (卖出锚价 − 档位价) × 波段股数 − 费用）；
 *   纯买入模式恒 0。
 * @property swingProfitPct       波段利润占总资金比例（%）；纯买入模式为 null。
 * @property feesPaid             回测期间累计费用（元，按传入费率假设：买入佣金 + 卖出佣金·印花税）。
 * @property heldLevelCount       波段模式：期末**底仓已建**的档数（底仓永续，含波段已卖出的档）。
 */
data class GridBacktestResult(
    val tradingDays: Int,
    val windowStart: String,
    val windowEnd: String,
    val triggeredCount: Int,
    val totalLevels: Int,
    val investedAmount: Double,
    val boughtShares: Int,
    val avgBuyPrice: Double?,
    val capitalUtilizationPct: Double?,
    val lumpSumPrice: Double,
    val lumpSumShares: Int,
    val costSavingPct: Double?,
    val firstTriggerDate: String?,
    val lastTriggerDate: String?,
    val minClose: Double,
    val maxClose: Double,
    val roundTrips: Int = 0,
    val swingProfit: Double = 0.0,
    val swingProfitPct: Double? = null,
    val feesPaid: Double = 0.0,
    /** 波段模式：期末底仓已建的档数（底仓永续，含波段已卖出的档）。 */
    val heldLevelCount: Int = 0)

/**
 * 网格历史回测（纯函数，无 Android 依赖，便于单测）。
 *
 * **纯买入模式**：逐日推进收盘价——某档位首次出现「收盘 ≤ 档位价」即视为触发
 * （按档位价成交，每档只买一次），对照基准为窗口首日收盘一次性买入。
 *
 * **波段模式（swingMode=true）**：三态回合模拟——每档拆**底仓 + 波段**（底仓只买不卖、
 * 永续持有；波段部分 = 该档股数 × 波段仓位比例）。收盘跌破档位价：底仓未建则买全量
 * （底仓+波段），底仓已建而波段已释放则只补**波段股数**；收盘涨到**股息率卖出锚**
 * （[GridLevel.pairedSellPrice]）且波段在持 → 卖出**波段股数**（底仓不动、利润落袋、
 * 波段部分重新武装），资金随回合滚动；卖出受 **T+1** 约束（当日建仓次日及以后方可卖，
 * A 股股票法定口径）。费用按传入费率假设逐笔计提（[buyFeePercent]/[sellFeePercent]，
 * 0 = 不计费），波段利润为扣除费用后的净额。
 *
 * 回答「过去这段行情里，这套网格值不值 / 波段模式跑了多少回合赚了多少（底仓还留着多少股）」。
 */
object GridBacktestCalculator {

    fun backtest(
        klines: List<KlineBar>,
        basePrice: Double,
        lowPrice: Double,
        highPrice: Double,
        grids: Int,
        totalCapital: Double,
        gridType: GridType = GridType.ARITHMETIC,
        dps: Double? = null,
        levelWeights: List<Double>? = null,
        swingMode: Boolean = false,
        swingStepPercent: Double? = null,
        buyFeePercent: Double = 0.0,
        sellFeePercent: Double = 0.0,
        swingRatioPercent: Double = GridCalculator.DEFAULT_SWING_RATIO_PERCENT
    ): GridBacktestResult? {
        val usable = klines.filter { it.close > 0.0 }
        if (usable.size < 2) return null

        val result = GridCalculator.generate(
            basePrice = basePrice,
            lowPrice = lowPrice,
            highPrice = highPrice,
            grids = grids,
            totalCapital = totalCapital,
            gridType = gridType,
            dps = dps,
            levelWeights = levelWeights,
            swingMode = swingMode,
            swingStepPercent = swingStepPercent,
            swingRatioPercent = swingRatioPercent
        )
        if (result.validationError != null || result.levels.isEmpty()) return null

        // 对照基准：窗口首日收盘一次性买入
        val lumpSumPrice = usable.first().close
        val lumpSumShares = ((totalCapital / lumpSumPrice) / 100).toInt() * 100

        if (!swingMode) {
            // 纯买入：档位首次收盘跌破即触发（每档只触发一次）
            val triggered = mutableSetOf<Double>()
            var firstTriggerDate: String? = null
            var lastTriggerDate: String? = null
            for (bar in usable) {
                for (level in result.levels) {
                    if (level.price !in triggered && bar.close <= level.price) {
                        triggered += level.price
                        if (firstTriggerDate == null) firstTriggerDate = bar.date
                        lastTriggerDate = bar.date
                    }
                }
            }
            val hitLevels = result.levels.filter { it.price in triggered }
            val invested = hitLevels.sumOf { it.price * it.shares }
            val shares = hitLevels.sumOf { it.shares }
            val avg = if (shares > 0) invested / shares else null
            val saving = if (avg != null && lumpSumPrice > 0.0) {
                (lumpSumPrice - avg) / lumpSumPrice * 100.0
            } else null

            return GridBacktestResult(
                tradingDays = usable.size,
                windowStart = usable.first().date,
                windowEnd = usable.last().date,
                triggeredCount = hitLevels.size,
                totalLevels = result.levels.size,
                investedAmount = round2(invested),
                boughtShares = shares,
                avgBuyPrice = avg?.let(::round2),
                capitalUtilizationPct = if (totalCapital > 0.0) round2(invested / totalCapital * 100.0) else null,
                lumpSumPrice = round2(lumpSumPrice),
                lumpSumShares = lumpSumShares,
                costSavingPct = saving?.let(::round2),
                firstTriggerDate = firstTriggerDate,
                lastTriggerDate = lastTriggerDate,
                minClose = round2(usable.minOf { it.close }),
                maxClose = round2(usable.maxOf { it.close })
            )
        }

        // ── 波段模式：三态回合模拟（底仓永续 + 波段高抛低吸，T+1 守卫）──
        val everTriggered = mutableSetOf<Double>()
        val baseHeld = mutableMapOf<Double, GridLevel>()               // 底仓已建档（永续）
        val swingHeld = mutableMapOf<Double, Pair<GridLevel, Int>>()   // 波段在持 → (档位, 建仓日下标)
        var firstTriggerDate: String? = null
        var lastTriggerDate: String? = null
        var roundTrips = 0
        var grossProfit = 0.0
        var feesPaid = 0.0

        for ((dayIndex, bar) in usable.withIndex()) {
            // 买入：收盘 ≤ 档位价——底仓未建买全量；底仓已建、波段已释放只补波段股数
            for (level in result.levels) {
                if (bar.close > level.price || level.price in swingHeld) continue
                val isRebuy = level.price in baseHeld
                if (level.price !in everTriggered) {
                    everTriggered += level.price
                    if (firstTriggerDate == null) firstTriggerDate = bar.date
                }
                lastTriggerDate = bar.date
                if (!isRebuy && level.baseShares > 0) baseHeld[level.price] = level
                swingHeld[level.price] = level to dayIndex
                val buyAmount = if (isRebuy) level.swingShares * level.price else level.amount
                if (buyFeePercent > 0.0) feesPaid += buyAmount * buyFeePercent / 100.0
            }
            // 卖出：收盘 ≥ 卖出锚 且 波段在持 且 次日及以后（T+1）——只卖波段股数，底仓不动
            val iterator = swingHeld.entries.iterator()
            while (iterator.hasNext()) {
                val (level, buyDay) = iterator.next().value
                val target = level.pairedSellPrice ?: continue
                if (dayIndex > buyDay && bar.close >= target) {
                    iterator.remove()
                    roundTrips += 1
                    grossProfit += (target - level.price) * level.swingShares
                    lastTriggerDate = bar.date
                    if (sellFeePercent > 0.0) {
                        feesPaid += target * level.swingShares * sellFeePercent / 100.0
                    }
                }
            }
        }

        val netSwingProfit = grossProfit - feesPaid
        // 期末持仓：底仓（永续）+ 仍在持的波段部分
        val heldShares = baseHeld.values.sumOf { it.baseShares } +
            swingHeld.values.sumOf { it.first.swingShares }
        val invested = baseHeld.values.sumOf { it.baseShares * it.price } +
            swingHeld.values.sumOf { it.first.swingShares * it.first.price }
        val avg = if (heldShares > 0) invested / heldShares else null
        val saving = if (avg != null && lumpSumPrice > 0.0) {
            (lumpSumPrice - avg) / lumpSumPrice * 100.0
        } else null

        return GridBacktestResult(
            tradingDays = usable.size,
            windowStart = usable.first().date,
            windowEnd = usable.last().date,
            triggeredCount = everTriggered.size,
            totalLevels = result.levels.size,
            investedAmount = round2(invested),
            boughtShares = heldShares,
            avgBuyPrice = avg?.let(::round2),
            capitalUtilizationPct = if (totalCapital > 0.0) round2(invested / totalCapital * 100.0) else null,
            lumpSumPrice = round2(lumpSumPrice),
            lumpSumShares = lumpSumShares,
            costSavingPct = saving?.let(::round2),
            firstTriggerDate = firstTriggerDate,
            lastTriggerDate = lastTriggerDate,
            minClose = round2(usable.minOf { it.close }),
            maxClose = round2(usable.maxOf { it.close }),
            roundTrips = roundTrips,
            swingProfit = round2(netSwingProfit),
            swingProfitPct = if (totalCapital > 0.0) round2(netSwingProfit / totalCapital * 100.0) else null,
            feesPaid = round2(feesPaid),
            heldLevelCount = baseHeld.size
        )
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0
}
