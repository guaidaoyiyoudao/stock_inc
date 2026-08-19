package com.stock.dividend.data.repository

import kotlin.math.round

/**
 * 一条股息率网格水平线（个股详情页 K 线图叠加层）。
 *
 * @property yieldPercent 股息率档位（%，如 6.5）。
 * @property price 对应价格 = 年度每股分红 ÷ 股息率（元）。
 * @property belowCurrent true=现价下方（股息率更高/买点侧），false=现价上方；现价缺失为 null。
 */
data class DividendYieldLine(
    val yieldPercent: Double,
    val price: Double,
    val belowCurrent: Boolean?
)

/**
 * 股息率网格线计算器（纯函数，无 Android 依赖，便于单测）。
 *
 * 个股详情页 30 日 K 线图上叠加「股息率网格」：每条水平线代表一个股息率档位，
 * 对应价格 P = 年度每股分红(DPS) ÷ 该股息率（与 [GridAnchorCalculator] 股息底、
 * [DividendPriceScale] 股息率价位同公式）。价格越高 → 股息率越低；价格越低 → 股息率越高。
 *
 * 档位策略：以现价隐含股息率（DPS÷现价，现价缺失用区间中点）**对齐到
 * [DEFAULT_STEP_PERCENT] 步长网格**，向两侧展开，仅保留落在 K 线价格区间
 * [lowPrice, highPrice] 内的档位（典型 3~7 条），与 App 内股息率刻度尺（±3 档 × 0.5%）口径一致。
 */
object DividendYieldGridCalculator {

    /** 默认档位步长（%）。 */
    const val DEFAULT_STEP_PERCENT = 0.5

    /** 锚点向两侧最多展开的档数（0.5% 步长下覆盖 ±10%，足够任何 A 股股息率区间）。 */
    private const val MAX_STEPS_PER_SIDE = 20

    /** 股息率下限（%），低于此值的档位无意义（价格发散）。 */
    private const val MIN_YIELD_PERCENT = 0.1

    /** 价格边界比较容差（浮点误差防护，如 0.6/0.075 ≠ 精确 8.0）。 */
    private const val PRICE_EPS = 1e-9

    /**
     * 计算价格区间内的股息率网格线。
     *
     * @param dps 年度每股现金分红（>0 才有效；null/≤0 返回空表，由调用方降级为不画线）。
     * @param lowPrice 区间最低价（K 线最小 low）。
     * @param highPrice 区间最高价（K 线最大 high）。
     * @param currentPrice 现价（档位锚定与买卖侧分类参考；缺失/非法时用区间中点锚定，belowCurrent 全为 null）。
     * @param stepPercent 档位步长（%），默认 [DEFAULT_STEP_PERCENT]。
     * @return 按价格降序（= 股息率升序）排列的网格线，图表自上而下绘制；无有效档位返回空表。
     */
    fun computeLines(
        dps: Double?,
        lowPrice: Double,
        highPrice: Double,
        currentPrice: Double?,
        stepPercent: Double = DEFAULT_STEP_PERCENT
    ): List<DividendYieldLine> {
        if (dps == null || dps <= 0.0 || stepPercent <= 0.0) return emptyList()
        if (!lowPrice.isFinite() || !highPrice.isFinite() ||
            lowPrice <= 0.0 || highPrice <= lowPrice
        ) return emptyList()

        // 现价非法时退到区间中点；隐含股息率四舍五入对齐到步长网格（隐含 6.33% → 6.5%）
        val current = currentPrice?.takeIf { it.isFinite() && it > 0.0 }
        val referencePrice = current ?: (lowPrice + highPrice) / 2.0
        val impliedYield = dps / referencePrice * 100.0
        val anchorIndex = round(impliedYield / stepPercent).toInt()

        val lines = (-MAX_STEPS_PER_SIDE..MAX_STEPS_PER_SIDE).mapNotNull { offset ->
            val yieldPercent = (anchorIndex + offset) * stepPercent
            if (yieldPercent < MIN_YIELD_PERCENT) return@mapNotNull null
            val price = dps / (yieldPercent / 100.0)
            // 落在 K 线区间内的档位才有绘制意义（边界等值保留，EPS 防浮点误伤）
            if (price < lowPrice - PRICE_EPS || price > highPrice + PRICE_EPS) return@mapNotNull null
            DividendYieldLine(
                yieldPercent = round2(yieldPercent),
                price = round2(price),
                belowCurrent = current?.let { price < it }
            )
        }
        return lines.sortedByDescending { it.price }
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
