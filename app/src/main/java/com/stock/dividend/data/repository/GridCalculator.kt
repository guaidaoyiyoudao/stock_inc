package com.stock.dividend.data.repository

import kotlin.math.abs

/**
 * 网格（分批买入计划）档位计算（纯函数，无 Android 依赖，便于单测）。
 *
 * **定位（重要）**：本计算器生成的是**纯买入档位表**——从买入起点 [basePrice]
 * 往下到资金用完位 [lowPrice] 之间分档，档位全部对应**买入**操作（越跌越买）。
 * **不含卖出档**：买入后持有吃股息，不因价格涨过基准就提示卖出（避免把收息仓
 * 做成短线高抛低吸）。
 *
 * - **买入起点 [basePrice]**：第一档（最贵）。通常由 BOLL 中轨锚定（回到震荡中枢开始建仓）。
 * - **资金用完位 [lowPrice]**：最后一档（最便宜）。通常由目标股息率对应价锚定
 *   （跌到此价股息率达到目标，资金全部打完）。
 * - **参考上界 [highPrice]**：仅展示「超过此价不追买」（BOLL 上轨），不参与分档。
 * - 档位在 `[lowPrice, basePrice]` 等分 [grids] 档（含两端），资金按 `1/price`
 *   反比分配——越便宜的档位买越多（低吸）。
 * - **下一档提示**：现价下方最近的买入档（现价高于某档 → 跌到该档即触发买入）；
 *   现价 ≤ 资金用完位时无下一档（资金已/将用完）。
 *
 * **说明**：本计算器仅生成计划档位表与提示，**不联网下单、不记账**——实际执行
 * （挂单/成交）由用户在券商端手动完成。
 *
 * @property price     档位价格（元）。
 * @property side      恒为 BUY（纯买入模型）。
 * @property shares    该档分配股数（按资金权重，越便宜越多；A 股 100 股整手）。
 * @property amount    该档分配金额（元）。
 * @property deviation 相对买入起点 [basePrice] 的偏离（%，负=更低）。
 */
data class GridLevel(
    val price: Double,
    val side: String,           // 恒为 "BUY"
    val shares: Int,
    val amount: Double,
    val deviation: Double       // (price - base) / base * 100
) {
    val isBuy: Boolean get() = side == "BUY"
}

/**
 * 网格计算结果。
 *
 * @property levels         买入档位列表（价格从低到高：最便宜档在前）。
 * @property stepPercent    相邻档位价差占买入起点的百分比（%）。
 * @property buyLevels      买入档（= levels，纯买入模型下恒等）。
 * @property sellLevels     恒为空（纯买入，无卖出档）。
 * @property nextBuyHint    现价下方最近的买入档价；现价 ≤ 资金用完位或无现价时为 null。
 * @property nextSellHint   恒为 null（无卖出语义）。
 * @property highPrice      参考上界（超过不追买，展示用）。
 * @property validationError 参数非法时的提示；非 null 表示无有效档位。
 */
data class GridResult(
    val levels: List<GridLevel>,
    val stepPercent: Double,
    val buyLevels: List<GridLevel>,
    val sellLevels: List<GridLevel>,
    val nextBuyHint: Double?,
    val nextSellHint: Double?,
    val highPrice: Double,
    val validationError: String?
)

object GridCalculator {

    /**
     * 生成纯买入网格档位表。
     *
     * @param basePrice     买入起点（第一档，最贵；通常为 BOLL 中轨）。
     * @param lowPrice      资金用完位（最后一档，最便宜；通常为目标股息率对应价）。
     * @param highPrice     参考上界（超过不追买，仅展示；可不参与分档）。
     * @param grids         买入档数（[lowPrice, basePrice] 等分份数，≥ 2）。
     * @param totalCapital  投入总资金（元，> 0）。
     * @param currentPrice  当前价（元），可选；用于「下一档买入」提示。
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
            return empty("价格必须为正数", highPrice.coerceAtLeast(0.0))
        }
        if (!(lowPrice < basePrice)) {
            return empty("需满足：资金用完位 < 买入起点", highPrice)
        }
        if (grids < 2) {
            return empty("买入档数至少为 2", highPrice)
        }
        if (totalCapital <= 0.0) {
            return empty("投入资金必须为正数", highPrice)
        }

        // 档位在 [lowPrice, basePrice] 等分 grids 档（含两端），从低到高排列
        val range = basePrice - lowPrice
        val step = range / (grids - 1)
        val stepPercent = step / basePrice * 100.0
        val prices = (0 until grids).map { lowPrice + step * it } // 最便宜档在前

        // 资金 1/price 反比加权：越便宜买越多
        val weightSum = prices.sumOf { 1.0 / it }
        val levels = prices.map { price ->
            val weight = (1.0 / price) / weightSum
            val amount = totalCapital * weight
            // A 股 100 股整手向下取整
            val shares = if (amount > 0.0 && price > 0.0) {
                ((amount / price) / 100).toInt() * 100
            } else 0
            GridLevel(
                price = round2(price),
                side = "BUY",
                shares = shares,
                amount = round2(shares * price),
                deviation = round2((price - basePrice) / basePrice * 100.0)
            )
        }

        val nextBuy = nextBuyHint(currentPrice, lowPrice, levels)

        return GridResult(
            levels = levels,
            stepPercent = round2(stepPercent),
            buyLevels = levels,
            sellLevels = emptyList(),
            nextBuyHint = nextBuy,
            nextSellHint = null,
            highPrice = round2(highPrice),
            validationError = null
        )
    }

    /**
     * 下一档买入提示：现价下方最近的买入档（现价高于该档，跌到即买）。
     * 现价 ≤ 资金用完位（lowPrice）时无下一档——资金已/将用完，不再提示买入。
     */
    private fun nextBuyHint(currentPrice: Double?, lowPrice: Double, levels: List<GridLevel>): Double? {
        if (currentPrice == null || currentPrice <= 0.0) return null
        if (currentPrice <= lowPrice) return null  // 已跌破资金用完位
        // 档位从低到高：取现价下方最近的档（价格 < currentPrice 的最大者）
        return levels.filter { it.price < currentPrice }.maxByOrNull { it.price }?.price
    }

    private fun empty(error: String, highPrice: Double) = GridResult(
        levels = emptyList(),
        stepPercent = 0.0,
        buyLevels = emptyList(),
        sellLevels = emptyList(),
        nextBuyHint = null,
        nextSellHint = null,
        highPrice = highPrice,
        validationError = error
    )

    private fun round2(v: Double): Double =
        kotlin.math.round(v * 100.0) / 100.0
}
