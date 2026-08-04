package com.stock.dividend.data.repository

/**
 * 网格（分批买入计划）参数智能锚定（纯函数，无 Android 依赖，便于单测）。
 *
 * 结合**技术面（日/周/月三周期 BOLL）+ 用户目标股息率**自动填充纯买入网格的
 * 买入起点/资金用完位/参考上界，让网格区间不再凭空手填，而是有据可依：
 *
 * - **买入起点（第一档）= min(日 BOLL 下轨, 周 BOLL 下轨, 月 BOLL 中轨)**：
 *   防守型建仓——**跌到日/周短期支撑或月线中枢之下才开始分批买入**，
 *   而不是一回到震荡中枢（现价附近）就建仓。保证起点 ≤ 月 BOLL 中轨（「中轨及以下」）。
 * - **参考上界 = 月 BOLL 上轨**：仅展示「超过此价不追买」（月线震荡上沿）。
 * - **资金用完位（最后一档）= min(三周期 BOLL 下轨最低者, 目标股息率底)**：用户设定的
 *   「资金用完位」。价格跌到此点时股息率达到 [targetYieldPercent]（若股息底更低则更早触达
 *   价值底），是用户愿意把网格剩余资金全部打满的位置。对应价 `P = D / (目标股息率/100)`，
 *   D = 最近一年每股分红（[ForecastCalculator.latestYearlyCashPerShare]）。
 *
 * 各周期数据缺失时**跳过该周期**（如日线数据不足），至少需一个有效周期；无分红或
 * 目标股息率非正返回 null，由调用方降级到手动填参。
 *
 * **「资金用完」语义**：资金用完位即最后一档——[GridCalculator] 的纯买入网格把资金按
 * 1/price 反比分配到 [lowPrice, basePrice] 的各买入档，触及资金用完位时买入档已全部打完。
 * 用户调高目标股息率 → 资金用完位更低 → 同样资金买到更多股数（更深的安全垫）。
 *
 * @property basePrice          建议买入起点（min(日下轨, 周下轨, 月BOLL中轨)，第一档）。
 * @property lowPrice           建议资金用完位（min(三周期下轨最低, 目标股息率底)，最后一档）。
 * @property highPrice          建议参考上界（月 BOLL 上轨，超过不追买）。
 * @property bollLower          三周期 BOLL 下轨最低者（解释资金用完位来源）。
 * @property monthlyMiddle      月 BOLL 中轨（买入起点的上限参考）。
 * @property dividendFloorPrice 目标股息率对应价（用户设定的价值底）。
 * @property targetYieldPercent 用户目标股息率（%，到达即资金用完）。
 * @property latestYearlyDividend 最近一年每股分红（元）。
 */
data class GridAnchor(
    val basePrice: Double,
    val lowPrice: Double,
    val highPrice: Double,
    val bollLower: Double,
    val monthlyMiddle: Double,
    val dividendFloorPrice: Double,
    val targetYieldPercent: Double,
    val latestYearlyDividend: Double
) {
    /** 资金用完位由哪一侧决定：目标股息率底更低 → 基本面主导（价值底先到）；否则技术面（BOLL 超卖）主导。 */
    val lowAnchoredByDividend: Boolean get() = dividendFloorPrice <= bollLower
}

object GridAnchorCalculator {

    /**
     * 由三周期 BOLL + 用户目标股息率计算网格锚定参数。
     *
     * 买入起点 = min(日 BOLL 下轨, 周 BOLL 下轨, 月 BOLL 中轨)——防守型建仓，
     * 跌到短期支撑或月线中枢之下才开始买。
     *
     * @param dailyBand            日线 BOLL（可空，缺失跳过）。
     * @param weeklyBand           周线 BOLL（可空，缺失跳过）。
     * @param monthlyBand          月线 BOLL（可空，缺失跳过）。
     * @param latestYearlyDividend 最近一年每股分红（元，>0）。
     * @param targetYieldPercent   用户目标股息率（%，>0）；到达此股息率 = 网格资金用完位。
     */
    fun anchor(
        dailyBand: BollBand?,
        weeklyBand: BollBand?,
        monthlyBand: BollBand?,
        latestYearlyDividend: Double,
        targetYieldPercent: Double
    ): GridAnchor? {
        if (latestYearlyDividend <= 0.0 || targetYieldPercent <= 0.0) return null

        // 各周期有效值（价格非正视为无效，跳过）
        val dailyLower = dailyBand?.takeIf { it.lower > 0.0 }?.lower
        val weeklyLower = weeklyBand?.takeIf { it.lower > 0.0 }?.lower
        val monthlyMiddle = monthlyBand?.takeIf { it.middle > 0.0 }?.middle
        val monthlyUpper = monthlyBand?.takeIf { it.upper > 0.0 }?.upper
        val monthlyLower = monthlyBand?.takeIf { it.lower > 0.0 }?.lower
        val dailyUpper = dailyBand?.takeIf { it.upper > 0.0 }?.upper
        val weeklyUpper = weeklyBand?.takeIf { it.upper > 0.0 }?.upper

        // 买入起点 = min(日下轨, 周下轨, 月BOLL中轨)；至少一个有效
        val start = listOfNotNull(dailyLower, weeklyLower, monthlyMiddle).minOrNull()
            ?: return null

        // 技术支撑参考 = 三周期下轨最低者（含月下轨）
        val techLower = listOfNotNull(dailyLower, weeklyLower, monthlyLower).minOrNull()

        // 目标股息率对应价：P = D / (yield/100)。目标收益率越高 → 对应价越低（更深的价值底）。
        val dividendFloor = latestYearlyDividend / (targetYieldPercent / 100.0)
        // 资金用完位取「技术下轨最低」与「股息底」的更保守（更低）者
        val low = minOf(techLower ?: dividendFloor, dividendFloor)

        // 参考上界：月 BOLL 上轨，缺失取各周期上轨最高者
        val high = monthlyUpper
            ?: listOfNotNull(dailyUpper, weeklyUpper).maxOrNull()
            ?: return null

        // 有效性：资金用完位必须低于买入起点，否则锚定结果不可用
        if (!(low < start)) return null

        return GridAnchor(
            basePrice = round2(start),
            lowPrice = round2(low),
            highPrice = round2(high),
            bollLower = round2(techLower ?: low),
            monthlyMiddle = round2(monthlyMiddle ?: start),
            dividendFloorPrice = round2(dividendFloor),
            targetYieldPercent = round2(targetYieldPercent),
            latestYearlyDividend = latestYearlyDividend
        )
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0
}
