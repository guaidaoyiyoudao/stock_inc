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
 * @property triggeredCount       期间触发的档数。
 * @property totalLevels          总档数。
 * @property investedAmount       已投入（元，按档位价 × 档位股数）。
 * @property boughtShares         已买股数。
 * @property avgBuyPrice          已买部分均价；无触发为 null。
 * @property capitalUtilizationPct 资金使用率（%，已投入/总资金）。
 * @property lumpSumPrice         对照基准：窗口首日收盘价（一次性全买）。
 * @property lumpSumShares        同样资金首日一次性可买股数（A 股整手）。
 * @property costSavingPct        网格均价相对首日一次性买入的成本节省（%，正=网格买得更便宜）；无触发为 null。
 * @property firstTriggerDate/lastTriggerDate 首次/最近触发日。
 * @property minClose/maxClose    窗口最低/最高收盘价。
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
    val maxClose: Double
)

/**
 * 网格历史回测（纯函数，无 Android 依赖，便于单测）。
 *
 * 逐日推进收盘价：某档位首次出现「收盘 ≤ 档位价」即视为触发（按档位价成交，
 * 档位股数沿用 [GridCalculator.generate] 的分配——默认反比，传 levelWeights 时按自定义比例），
 * 对照基准为窗口首日收盘一次性买入（同样总资金、A 股整手取整）。
 * 回答「过去这段行情里，这套网格值不值」。
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
        levelWeights: List<Double>? = null
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
            levelWeights = levelWeights
        )
        if (result.validationError != null || result.levels.isEmpty()) return null

        // 对照基准：窗口首日收盘一次性买入
        val lumpSumPrice = usable.first().close
        val lumpSumShares = ((totalCapital / lumpSumPrice) / 100).toInt() * 100

        // 逐日推进：档位首次收盘跌破即触发（每档只触发一次）
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

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0
}
