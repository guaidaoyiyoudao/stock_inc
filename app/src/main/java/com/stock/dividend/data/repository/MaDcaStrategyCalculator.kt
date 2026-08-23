package com.stock.dividend.data.repository

/**
 * 年线定投策略信号（红利 ETF 经典款）。
 *
 * - [DCA_WINDOW]：现价低于均线（年线）→ 开启定投买入窗口；
 * - [HOLD]：年线与卖半阈值之间 → 持有不动；
 * - [SELL_HALF]：高于年线卖半阈值 → 卖出一半；
 * - [SELL_ALL]：高于年线清仓阈值 → 全部卖出。
 */
enum class MaDcaSignal { DCA_WINDOW, HOLD, SELL_HALF, SELL_ALL }

/**
 * 年线定投策略评估结果（[MaDcaStrategyCalculator.evaluate] 输出）。
 *
 * @param maValue 均线值（末 [maPeriod][MaDcaStrategyCalculator.evaluate] 根收盘价 SMA）。
 * @param deviationPercent 现价相对均线偏离度（%，(现价÷均线−1)×100）。
 * @param signal 当前信号。
 * @param sellHalfTriggerPrice 卖出一半触发价 = 均线 ×(1+卖半阈值/100)。
 * @param sellAllTriggerPrice 全部卖出触发价 = 均线 ×(1+清仓阈值/100)。
 */
data class MaDcaEvaluation(
    val maValue: Double,
    val deviationPercent: Double,
    val signal: MaDcaSignal,
    val sellHalfTriggerPrice: Double,
    val sellAllTriggerPrice: Double
)

/**
 * 年线定投策略计算器（纯函数，无 Android 依赖）：
 * 250 日均线下方定投买入；高于年线 7.5% 卖出一半、15% 全部卖出（参数可调）。
 * 均线 = 末 maPeriod 根收盘价的简单移动平均；数据不足（上市不足周期长度）返回 null。
 */
object MaDcaStrategyCalculator {

    /** A 股最小交易单位（股），卖出/买入股数按整手向下取整。 */
    const val LOT_SIZE = 100

    /** 偏离度阈值比较容差（百分点）：抵消 price/ma 的二进制浮点噪声，使「恰达阈值」计为触发。 */
    private const val DEVIATION_EPS = 1e-9

    /**
     * 评估当前信号。
     *
     * @param closes 日收盘价序列（升序，允许超过 maPeriod 根，只取末段）。
     * @param currentPrice 现价。
     * @param maPeriod 均线周期（日），默认 250 = 年线。
     * @param sellHalfPercent 高于均线该百分比（%）→ 卖出一半，默认 7.5。
     * @param sellAllPercent 高于均线该百分比（%）→ 全部卖出，默认 15。
     * @return 评估结果；收盘价不足周期根数 / 现价或收盘价非法 → null。
     */
    fun evaluate(
        closes: List<Double>,
        currentPrice: Double,
        maPeriod: Int = 250,
        sellHalfPercent: Double = 7.5,
        sellAllPercent: Double = 15.0
    ): MaDcaEvaluation? {
        if (closes.size < maPeriod) return null
        if (!currentPrice.isFinite() || currentPrice <= 0.0) return null
        if (!closes.all { it.isFinite() && it > 0.0 }) return null
        val ma = closes.takeLast(maPeriod).average()
        if (!ma.isFinite() || ma <= 0.0) return null
        val deviation = (currentPrice / ma - 1.0) * 100.0
        val signal = when {
            currentPrice < ma -> MaDcaSignal.DCA_WINDOW
            deviation >= sellAllPercent - DEVIATION_EPS -> MaDcaSignal.SELL_ALL
            deviation >= sellHalfPercent - DEVIATION_EPS -> MaDcaSignal.SELL_HALF
            else -> MaDcaSignal.HOLD
        }
        return MaDcaEvaluation(
            maValue = ma,
            deviationPercent = deviation,
            signal = signal,
            sellHalfTriggerPrice = ma * (1.0 + sellHalfPercent / 100.0),
            sellAllTriggerPrice = ma * (1.0 + sellAllPercent / 100.0)
        )
    }

    /**
     * 卖出目标股数：卖一半 = 持仓的一半按整手向下取整（不足一手为 0，
     * 由 UI 提示「持仓不足一手」）；全卖 = 全部持仓；非卖出信号为 0。
     */
    fun sellSharesFor(signal: MaDcaSignal, holdingShares: Int): Int = when (signal) {
        MaDcaSignal.SELL_HALF -> (holdingShares / 2 / LOT_SIZE) * LOT_SIZE
        MaDcaSignal.SELL_ALL -> holdingShares
        MaDcaSignal.HOLD, MaDcaSignal.DCA_WINDOW -> 0
    }

    /** 定投金额按现价折整手股数（不足一手为 0，一键记账预填用）。 */
    fun dcaBuyShares(dcaAmount: Double, price: Double): Int {
        if (!dcaAmount.isFinite() || dcaAmount <= 0.0) return 0
        if (!price.isFinite() || price <= 0.0) return 0
        return (dcaAmount / price).toInt() / LOT_SIZE * LOT_SIZE
    }

    /**
     * 滚动均线序列（K 线叠加用）：与 [closes] 等长，前 period−1 个为 null，
     * 第 i（≥ period−1）个 = closes[i−period+1..i] 的简单平均。
     */
    fun maSeries(closes: List<Double>, period: Int): List<Double?> {
        if (period <= 0) return closes.map { null }
        val result = arrayOfNulls<Double>(closes.size)
        var windowSum = 0.0
        for (i in closes.indices) {
            windowSum += closes[i]
            if (i >= period) windowSum -= closes[i - period]
            if (i >= period - 1) result[i] = windowSum / period
        }
        return result.toList()
    }

    /**
     * 策略参数校验（编辑保存前用）。
     * @return 中文错误提示；合法返回 null。
     */
    fun validateParams(
        maPeriod: Int,
        sellHalfPercent: Double,
        sellAllPercent: Double,
        dcaAmount: Double
    ): String? = when {
        maPeriod < 2 -> "均线周期至少 2 日"
        sellHalfPercent <= 0.0 || !sellHalfPercent.isFinite() -> "卖出一半阈值必须大于 0"
        sellAllPercent <= sellHalfPercent || !sellAllPercent.isFinite() -> "清仓阈值必须大于卖出一半阈值"
        dcaAmount <= 0.0 || !dcaAmount.isFinite() -> "每期定投金额必须大于 0"
        else -> null
    }
}
