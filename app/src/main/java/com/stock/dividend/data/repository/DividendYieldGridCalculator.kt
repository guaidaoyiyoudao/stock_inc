package com.stock.dividend.data.repository

import kotlin.math.ceil
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
 * 档位策略：
 * 1. 以现价隐含股息率（DPS÷现价，现价缺失用区间中点）**对齐到 [DEFAULT_STEP_PERCENT] 步长网格**，
 *    区间内档位全部保留（典型 3~7 条）；
 * 2. **最低 3 档保证**：无论 K 线价格区间多窄，至少返回「离现价最近的档 + 上下各一档」
 *    （区间外的档位也返回，由图表扩展 Y 轴容纳）——避免窄区间时网格线缩水到 1~2 条；
 *    某侧越界（股息率下限）时向另一侧补足至 3 档。
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
     * 计算股息率网格线（区间内档位 + 最低 3 档保证）。
     *
     * @param dps 年度每股现金分红（>0 才有效；null/≤0 返回空表，由调用方降级为不画线）。
     * @param lowPrice 区间最低价（K 线最小 low）。
     * @param highPrice 区间最高价（K 线最大 high）。
     * @param currentPrice 现价（档位锚定与买卖侧分类参考；缺失/非法时用区间中点锚定，belowCurrent 全为 null）。
     * @param stepPercent 档位步长（%），默认 [DEFAULT_STEP_PERCENT]。
     * @return 按价格降序（= 股息率升序）排列的网格线，图表自上而下绘制；dps 无效返回空表。
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

        // 网格档下标 n → 股息率 n×step；minIndex 为满足股息率下限的最小档
        val minIndex = ceil(MIN_YIELD_PERCENT / stepPercent).toInt()

        fun priceAt(n: Int): Double = dps / (n * stepPercent / 100.0)

        // 1) 区间内档位（EPS 容差防浮点误伤，边界等值保留）
        val indices = mutableSetOf<Int>()
        for (n in maxOf(anchorIndex - MAX_STEPS_PER_SIDE, minIndex)..(anchorIndex + MAX_STEPS_PER_SIDE)) {
            val price = priceAt(n)
            if (price >= lowPrice - PRICE_EPS && price <= highPrice + PRICE_EPS) indices += n
        }

        // 2) 最低 3 档保证：最近档（锚点，不低于最小有效档）± 1 档；某侧越界（股息率下限）
        //    时向另一侧补足——区间外档位也保留，由图表扩展 Y 轴容纳
        val nearest = anchorIndex.coerceAtLeast(minIndex)
        var lo = (nearest - 1).coerceAtLeast(minIndex)
        var hi = (nearest + 1).coerceAtMost(anchorIndex + MAX_STEPS_PER_SIDE)
        while (hi - lo + 1 < 3) {
            if (lo > minIndex) lo-- else hi++
        }
        indices.addAll(lo..hi)

        return indices.map { n ->
            val price = priceAt(n)
            DividendYieldLine(
                yieldPercent = round2(n * stepPercent),
                price = round2(price),
                belowCurrent = current?.let { price < it }
            )
        }.sortedByDescending { it.price }
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
