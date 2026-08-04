package com.stock.dividend.data.repository

import kotlin.math.abs

/**
 * 网格交易档位计算（纯函数，无 Android 依赖，便于单测）。
 *
 * 模型（等差网格，最通用）：在 [lowPrice, highPrice] 区间内按 [grids] 等分生成档位线，
 * 基准价 [basePrice] 为中轴。价格从基准向**下**穿某档 → 该档触发**买入**；向**上**穿某档 →
 * 触发**卖出**。资金按等差权重分配：越靠近下界（越便宜）分配越多资金（低吸），越靠近上界
 * 分配越少（高抛减仓）。
 *
 * **说明**：本计算器仅生成计划档位表与提示，**不联网下单、不记账**——网格的实际执行
 * （挂单/成交）由用户在券商端手动完成，本 App 提供的是计划与「下一档」提示。
 *
 * @property price     档位价格（元）。
 * @property side      买/卖方向（相对基准价：低于基准为 BUY、高于为 SELL）。
 * @property shares    该档分配股数（按资金权重，向下越多）。
 * @property amount    该档分配金额（元）。
 * @property deviation 相对基准价偏离（%，负=低于基准）。
 */
data class GridLevel(
    val price: Double,
    val side: String,           // BUY / SELL
    val shares: Int,
    val amount: Double,
    val deviation: Double       // (price - base) / base * 100
) {
    val isBuy: Boolean get() = side == "BUY"
}

/**
 * 网格计算结果。
 *
 * @property levels          档位列表（价格从低到高；基准价本身不入列）。
 * @property stepPercent     相邻档位价差占基准价的百分比（%）。
 * @property buyLevels       买入档（价格 < base）。
 * @property sellLevels      卖出档（价格 > base）。
 * @property nextBuyHint     当前价对应的「下一档买入」价格（现价上方最近的买入档）；无现价/无档为 null。
 * @property nextSellHint    当前价对应的「下一档卖出」价格（现价下方最近的卖出档）。
 * @property validationError 参数非法时的提示；非 null 表示无有效档位。
 */
data class GridResult(
    val levels: List<GridLevel>,
    val stepPercent: Double,
    val buyLevels: List<GridLevel>,
    val sellLevels: List<GridLevel>,
    val nextBuyHint: Double?,
    val nextSellHint: Double?,
    val validationError: String?
)

object GridCalculator {

    /**
     * 生成等差网格档位表。
     *
     * @param basePrice    基准价（中轴，> 0）。
     * @param lowPrice     网格下界（> 0，< basePrice）。
     * @param highPrice    网格上界（> basePrice）。
     * @param grids        网格档数（[lowPrice, highPrice] 等分份数，≥ 2）。
     * @param totalCapital 投入总资金（元，> 0）。
     * @param currentPrice 当前价（元），可选；用于「下一档」提示。
     */
    fun generate(
        basePrice: Double,
        lowPrice: Double,
        highPrice: Double,
        grids: Int,
        totalCapital: Double,
        currentPrice: Double? = null
    ): GridResult {
        // 参数校验
        if (basePrice <= 0.0 || lowPrice <= 0.0 || highPrice <= 0.0) {
            return empty("价格必须为正数")
        }
        if (!(lowPrice < basePrice && basePrice < highPrice)) {
            return empty("需满足：下界 < 基准价 < 上界")
        }
        if (grids < 2) {
            return empty("网格档数至少为 2")
        }
        if (totalCapital <= 0.0) {
            return empty("投入资金必须为正数")
        }

        val range = highPrice - lowPrice
        val step = range / grids
        val stepPercent = step / basePrice * 100.0

        // 各档价格（从下界出发，每 +step 一档），剔除与基准价重合的档（偏离 < 半步视为基准）
        val halfStep = step / 2.0
        val rawPrices = (0..grids).map { lowPrice + step * it }
            .filter { p -> abs(p - basePrice) >= halfStep } // 剔除基准价本身

        // 资金权重：买入档（价低）权重大，卖出档（价高）权重小。
        // 采用 1/price 反比加权（价越低权重越高），买/卖各自归一化后各分 totalCapital 的一半。
        val buyPrices = rawPrices.filter { it < basePrice }
        val sellPrices = rawPrices.filter { it > basePrice }

        val buyWeights = buyPrices.associateWith { 1.0 / it }
        val sellWeights = sellPrices.associateWith { 1.0 / it }
        val buyWeightSum = buyWeights.values.sum().takeIf { it > 0.0 } ?: 0.0
        val sellWeightSum = sellWeights.values.sum().takeIf { it > 0.0 } ?: 0.0
        // 买/卖各占总资金一半；若某侧无档（基准贴近边界），全额归另一侧
        val (buyCapital, sellCapital) = when {
            buyPrices.isEmpty() -> 0.0 to totalCapital
            sellPrices.isEmpty() -> totalCapital to 0.0
            else -> totalCapital / 2.0 to totalCapital / 2.0
        }

        fun buildLevel(price: Double, side: String, weightSum: Double, capital: Double): GridLevel {
            val weight = (1.0 / price) / weightSum
            val amount = capital * weight
            // 股数按 100 股整手取整（A 股最小交易单位），金额 / 价格 / 100 向下取整再 ×100
            val shares = if (amount > 0.0 && price > 0.0) {
                ((amount / price) / 100).toInt() * 100
            } else 0
            return GridLevel(
                price = round2(price),
                side = side,
                shares = shares,
                amount = round2(shares * price),
                deviation = round2((price - basePrice) / basePrice * 100.0)
            )
        }

        val buyLevels = buyPrices.map { buildLevel(it, "BUY", buyWeightSum, buyCapital) }
        val sellLevels = sellPrices.map { buildLevel(it, "SELL", sellWeightSum, sellCapital) }
        val levels = (buyLevels + sellLevels).sortedBy { it.price }

        // 下一档提示：基于 currentPrice
        val (nextBuy, nextSell) = hints(currentPrice, basePrice, buyLevels, sellLevels)

        return GridResult(
            levels = levels,
            stepPercent = round2(stepPercent),
            buyLevels = buyLevels,
            sellLevels = sellLevels,
            nextBuyHint = nextBuy,
            nextSellHint = nextSell,
            validationError = null
        )
    }

    /**
     * 计算下一档买卖提示。
     * - nextBuy：现价上方最近的买入档（价格再跌一档即触发买入）；
     * - nextSell：现价下方最近的卖出档（价格再涨一档即触发卖出）。
     * 现价为 null 或越界（超出网格区间）时对应项为 null。
     */
    private fun hints(
        currentPrice: Double?,
        basePrice: Double,
        buyLevels: List<GridLevel>,
        sellLevels: List<GridLevel>
    ): Pair<Double?, Double?> {
        if (currentPrice == null || currentPrice <= 0.0) return null to null
        // nextBuy：买入档中价格 < currentPrice 的最大者（现价在其上方，跌到该档即买）
        val nextBuy = buyLevels.filter { it.price < currentPrice }.maxByOrNull { it.price }?.price
        // nextSell：卖出档中价格 > currentPrice 的最小者（现价在其下方，涨到该档即卖）
        val nextSell = sellLevels.filter { it.price > currentPrice }.minByOrNull { it.price }?.price
        return nextBuy to nextSell
    }

    private fun empty(error: String) = GridResult(
        levels = emptyList(),
        stepPercent = 0.0,
        buyLevels = emptyList(),
        sellLevels = emptyList(),
        nextBuyHint = null,
        nextSellHint = null,
        validationError = error
    )

    private fun round2(v: Double): Double =
        kotlin.math.round(v * 100.0) / 100.0
}
