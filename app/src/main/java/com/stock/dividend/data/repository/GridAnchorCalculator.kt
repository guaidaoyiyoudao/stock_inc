package com.stock.dividend.data.repository

/**
 * 网格参数智能锚定（纯函数，无 Android 依赖，便于单测）。
 *
 * 结合**技术面（BOLL）+ 用户目标股息率**自动填充网格的基准价/上下界，
 * 让网格区间不再凭空手填，而是有据可依：
 *
 * - **基准价（中轴）= BOLL 中轨（MA20）**：当前震荡中枢。
 * - **上界 = BOLL 上轨**：网格顶部，触及意味超买（震荡区间上沿）。
 * - **下界 = 目标股息率对应价**：用户设定的「资金用完位」。价格跌到此点时股息率恰好达到
 *   [targetYieldPercent]，是用户愿意把网格剩余资金全部打满的位置（红利股的「价值底」）。
 *   对应价 `P = D / (目标股息率/100)`，D = 最近一年每股分红
 *   （[ForecastCalculator.latestYearlyCashPerShare]）。
 *
 * 当 BOLL 下轨与目标股息率底冲突时，取**更保守（更低）者作为下界**：既尊重技术超卖区，
 * 又守住用户的价值底——两者都满足才最稳。若目标股息率底高于 BOLL 下轨，说明「震荡超卖位
 * 的股息率还没到目标」，此时以 BOLL 下轨为界（避免网格区间过窄），但会在
 * [dividendFloorPrice] 中保留目标价供 UI 解释。
 *
 * 若任一关键数据缺失（无 BOLL / 无分红 / 目标股息率非正），返回 null，由调用方降级到手动填参。
 *
 * **「资金用完」语义**：下界即资金用完位——[GridCalculator] 的等差网格会把资金分配到
 * [lowPrice, basePrice) 的各买入档，触及下界时买入档已全部用完。用户调高目标股息率 →
 * 下界价更低 → 同样资金买到更多股数（更深的安全垫）。
 *
 * @property basePrice          建议基准价（BOLL 中轨）。
 * @property lowPrice           建议下界（min(BOLL 下轨, 目标股息率底)，即资金用完位）。
 * @property highPrice          建议上界（BOLL 上轨）。
 * @property bollLower          BOLL 下轨（解释下界来源）。
 * @property dividendFloorPrice 目标股息率对应价（用户设定的价值底）。
 * @property targetYieldPercent 用户目标股息率（%，到达即资金用完）。
 * @property latestYearlyDividend 最近一年每股分红（元）。
 */
data class GridAnchor(
    val basePrice: Double,
    val lowPrice: Double,
    val highPrice: Double,
    val bollLower: Double,
    val dividendFloorPrice: Double,
    val targetYieldPercent: Double,
    val latestYearlyDividend: Double
) {
    /** 下界由哪一侧决定：目标股息率底更低 → 基本面主导（价值底先到）；否则技术面（BOLL 超卖）主导。 */
    val lowAnchoredByDividend: Boolean get() = dividendFloorPrice <= bollLower
}

object GridAnchorCalculator {

    /**
     * 由 BOLL + 用户目标股息率计算网格锚定参数。
     *
     * @param band                 BOLL 带（建议周线，震荡区间参考）。
     * @param latestYearlyDividend 最近一年每股分红（元，>0）。
     * @param targetYieldPercent   用户目标股息率（%，>0）；到达此股息率 = 网格资金用完位。
     */
    fun anchor(
        band: BollBand,
        latestYearlyDividend: Double,
        targetYieldPercent: Double
    ): GridAnchor? {
        if (latestYearlyDividend <= 0.0 || targetYieldPercent <= 0.0) return null
        if (band.middle <= 0.0 || band.upper <= 0.0 || band.lower <= 0.0) return null

        // 目标股息率对应价：P = D / (yield/100)。目标收益率越高 → 对应价越低（更深的价值底）。
        val dividendFloor = latestYearlyDividend / (targetYieldPercent / 100.0)
        // 下界取 BOLL 下轨与目标股息率底的更保守（更低）者
        val low = minOf(band.lower, dividendFloor)

        // 有效性：需满足 low < base < high，否则锚定结果不可用
        if (!(low < band.middle && band.middle < band.upper)) return null

        return GridAnchor(
            basePrice = round2(band.middle),
            lowPrice = round2(low),
            highPrice = round2(band.upper),
            bollLower = round2(band.lower),
            dividendFloorPrice = round2(dividendFloor),
            targetYieldPercent = round2(targetYieldPercent),
            latestYearlyDividend = latestYearlyDividend
        )
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0
}
