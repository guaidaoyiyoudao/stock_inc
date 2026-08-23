package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.GRID_TYPE_GEOM
import com.stock.dividend.data.local.entity.GRID_TYPE_YIELD

/**
 * 网格档位分布类型。
 *
 * - [ARITHMETIC] 等差（默认）：买入区间按**绝对价差**均分——低价股/窄区间直观。
 * - [GEOMETRIC] 等比：按**百分比步长**均分（相邻档位价之比恒定）——高价股/宽区间下
 *   各档相对跌幅一致，避免高价区档位过密、低价区过疏。
 * - [YIELD] 按股息率：**股息率**等差分档（如 5.5%/6.0%/6.5%），每档买入价 =
 *   年度每股分红(DPS) ÷ 该档股息率（P = DPS/(yield/100)）——收息视角直接以
 *   「跌到多少股息率买多少」定义网格，越便宜的档股息率越高。
 */
enum class GridType(val raw: String) {
    ARITHMETIC("ARITH"),
    GEOMETRIC("GEOM"),
    YIELD("YIELD");

    companion object {
        /** 解析持久化字符串；未知/缺失回退等差（旧数据兼容）。 */
        fun fromRaw(raw: String?): GridType = when (raw) {
            GRID_TYPE_GEOM -> GEOMETRIC
            GRID_TYPE_YIELD -> YIELD
            else -> ARITHMETIC
        }
    }
}

/**
 * 网格（分批买入计划）档位计算（纯函数，无 Android 依赖，便于单测）。
 *
 * **定位（重要）**：本计算器生成的是**买入档位表**——从买入起点 [basePrice]
 * 往下到资金用完位 [lowPrice] 之间分档，档位全部对应**买入**操作（越跌越买）。
 * - **纯买入模式（默认，swingMode=false）**：买入后持有吃股息，不因价格涨过基准
 *   就提示卖出（避免把收息仓做成短线高抛低吸）。
 * - **波段模式（swingMode=true，2026-08-23）**：每档买入拆为**底仓 + 波段**两部分
 *   （波段仓位比例 [GridResult.swingRatioPercent]，默认 30%）。底仓只买不卖、永续持有
 *   收息；波段部分涨到**股息率卖出锚**即减仓——卖出锚价 = DPS ÷ (该档买入股息率 −
 *   波段步长百分点)，默认步长 = 网格等效股息率档距（「回落一档」），估值语义为
 *   「股息率回落一档就高抛」。卖出后波段部分释放（跌回档位可再买回、资金回流弹药库），
 *   形成「低吸—高抛—再低吸」的可循环回合，且**底仓股数始终不变**。每回合利润按
 *   **计划口径**（卖出锚价 − 档位价）× 波段股数计，不含费用。
 *
 * - **买入起点 [basePrice]**：第一档（最贵）。通常由 BOLL 中轨锚定（回到震荡中枢开始建仓）。
 * - **资金用完位 [lowPrice]**：最后一档（最便宜）。通常由目标股息率对应价锚定
 *   （跌到此价股息率达到目标，资金全部打完）。
 * - **参考上界 [highPrice]**：仅展示「超过此价不追买」（BOLL 上轨），不参与分档。
 * - 档位在 `[lowPrice, basePrice]` 等分 [grids] 档（含两端），资金默认按 `1/price`
 *   反比分配——越便宜的档位买越多（低吸）；可传 [generate] 的 `levelWeights`
 *   改为逐档自定义比例（相对权重，归一化后分配）。
 * - **下一档提示**：现价下方最近的**未触发**档（已买入的档不再提示，每档只买一次）；
 *   现价 ≤ 资金用完位或下方档全部已买时无下一档。
 *
 * **说明**：本计算器仅生成计划档位表与提示，**不联网下单、不记账**——实际执行
 * （挂单/成交）由用户在券商端手动完成。
 *
 * @property price     档位价格（元）。
 * @property side      恒为 BUY（买入档）。
 * @property shares    该档分配股数（按资金权重，越便宜越多；A 股 100 股整手）= 底仓 + 波段。
 * @property amount    该档分配金额（元）。
 * @property deviation 相对买入起点 [basePrice] 的偏离（%，负=更低）。
 * @property yieldPercent 该档对应股息率（%）——[GridType.YIELD] 模式与波段模式填充
 *   （= DPS ÷ 档位价 × 100，波段卖出锚的换算基准）；纯买入价格步长模式为 null。
 * @property pairedSellPrice 配对卖出档价（元，**股息率锚** = DPS ÷ 卖出股息率）——仅波段模式
 *   填充；涨到此价减仓**波段部分**（[swingShares]），底仓不动。
 * @property pairedSellYieldPercent 卖出锚股息率（% = 买入股息率 − 波段步长百分点）；仅波段模式。
 * @property swingShares 波段股数（该档做高抛低吸的部分 = shares × 波段仓位比例，整手取整）；
 *   仅波段模式填充，纯买入模式 0。
 * @property baseHeld  运行时标记：该档**底仓部分已建**（只买不卖、永续持有）；默认 false。
 */
data class GridLevel(
    val price: Double,
    val side: String,           // 恒为 "BUY"
    val shares: Int,
    val amount: Double,
    val deviation: Double,      // (price - base) / base * 100
    /** 运行时标记：波段部分在持（满持仓待卖）；纯买入模式 = 该档已买入。计算器默认 false。 */
    val triggered: Boolean = false,
    /** 该档对应股息率（%）；按股息率（YIELD）模式与波段模式填充，纯买入价格步长模式 null。 */
    val yieldPercent: Double? = null,
    /** 配对卖出档价（元，股息率锚）；仅波段模式填充——涨到此价减仓波段部分。 */
    val pairedSellPrice: Double? = null,
    /** 卖出锚股息率（%）；仅波段模式填充。 */
    val pairedSellYieldPercent: Double? = null,
    /** 波段股数（高抛低吸部分）；仅波段模式填充。 */
    val swingShares: Int = 0,
    /** 运行时标记：底仓部分已建（只买不卖）。 */
    val baseHeld: Boolean = false
) {
    val isBuy: Boolean get() = side == "BUY"

    /** 底仓股数（只买不卖、永续持有的部分）= 总计划股数 − 波段股数。 */
    val baseShares: Int get() = shares - swingShares
}

/**
 * 网格档位的实际成交事件（[GridCalculator.markTriggeredLevels] 重放交易流产出）。
 *
 * @property levelPrice 命中的档位价（元）。BUY 事件 = 成交价落在该档触发区间；
 *   SELL 事件 = 成交价落在该档**配对卖出价**触发区间（仅波段模式）。
 * @property price      实际成交价（元）。
 * @property shares     成交股数。
 * @property date       成交日期（yyyy-MM-dd）。
 */
data class GridLevelTrade(
    val levelPrice: Double,
    val price: Double,
    val shares: Int,
    val date: String?
)

/**
 * 网格计算结果。
 *
 * @property levels         买入档位列表（价格从低到高：最便宜档在前）。
 * @property stepPercent    相邻档位步长幅度（%）：等差=价差/起点，等比=每档恒定比值；
 *   按股息率模式下价格步长不恒定，取等效等比步长（仅供参考）。
 * @property buyLevels      买入档（= levels，档位全部为买入）。
 * @property sellLevels     恒为空（卖出以 [GridLevel.pairedSellPrice] 配对表达，非独立档）。
 * @property highPrice      参考上界（超过不追买，展示用）。
 * @property currentPrice   计算时现价（元），可空；驱动 [nextBuyHint] / [nextSellHint]。
 * @property yieldStepPercent 相邻档**股息率**步长（百分点，如每档 +0.5）——仅
 *   [GridType.YIELD] 模式填充；等差/等比模式为 null。
 * @property validationError 参数非法时的提示；非 null 表示无有效档位。
 * @property swingMode      是否波段模式（股息率卖出锚 + 底仓/波段拆分 + 回合滚动）。
 * @property swingStepPercent 波段步长（**股息率百分点**，卖出锚股息率 = 买入股息率 − 步长）；
 *   仅波段模式非空——用户显式指定，或默认取网格的等效股息率档距（「回落一档」）。
 * @property swingRatioPercent 波段仓位比例（%，每档股数中做波段的部分；其余为底仓只买不卖）。
 * @property roundTrips     已完成的波段回合数（买入→涨到卖出锚→卖出波段部分）；
 *   仅运行时由 [markTriggeredLevels] 重放交易流标记，计算器默认 0。
 * @property swingProfit    已完成回合的波段利润（元，**计划口径** =
 *   Σ (卖出锚价 − 档位价) × 波段股数，不含费用/印花税）；运行时标记，默认 0。
 * @property buyFills       命中买入档的实际成交事件（运行时标记；波段模式含已释放档的历史买入）。
 * @property sellFills      命中卖出锚的实际卖出事件（仅波段模式，运行时标记）。
 */
data class GridResult(
    val levels: List<GridLevel>,
    val stepPercent: Double,
    val buyLevels: List<GridLevel>,
    val sellLevels: List<GridLevel>,
    val highPrice: Double,
    val validationError: String?,
    val currentPrice: Double? = null,
    /** 相邻档股息率步长（百分点）；仅按股息率（YIELD）模式填充。 */
    val yieldStepPercent: Double? = null,
    /** 波段模式开关：每档附带股息率锚卖出价，底仓只买不卖、波段部分回合滚动。 */
    val swingMode: Boolean = false,
    /** 波段步长（股息率百分点）；仅波段模式非空（显式指定或默认回落一档）。 */
    val swingStepPercent: Double? = null,
    /** 波段仓位比例（%）；仅波段模式非空。 */
    val swingRatioPercent: Double? = null,
    /** 已完成波段回合数（运行时由 markTriggeredLevels 标记）。 */
    val roundTrips: Int = 0,
    /** 已完成回合的波段利润（元，计划口径，不含费用；运行时标记）。 */
    val swingProfit: Double = 0.0,
    /** 命中买入档的实际成交事件（运行时标记）。 */
    val buyFills: List<GridLevelTrade> = emptyList(),
    /** 命中配对卖出档的实际卖出事件（仅波段模式，运行时标记）。 */
    val sellFills: List<GridLevelTrade> = emptyList()
) {
    /**
     * 下一档买入提示：现价下方最近的**未触发**档——已买入的档不再提示（纯买入网格
     * 每档只买一次；现价回升到已买档之上时不重复指向它）。波段模式下已卖出（波段
     * 部分释放）的档可再次提示（底仓已建则该次买入只补波段股数）。
     *
     * 为 null 的三种情形：无现价；现价 ≤ 资金用完位（资金已/将用完）；
     * 现价下方档位**全部已买**（等跌破更低的未买档）。
     *
     * 动态基于 [levels] 的 triggered 标记计算——消费方须先经
     * [GridCalculator.markTriggeredLevels] 关联实际交易，否则已买档不会被排除。
     */
    val nextBuyHint: Double?
        get() {
            val price = currentPrice ?: return null
            if (price <= 0.0) return null
            val low = levels.firstOrNull()?.price ?: return null
            if (price <= low) return null  // 已跌破资金用完位
            // 现价下方最近的未触发档（跳过已买入的档）
            return levels.filter { !it.triggered && it.price < price }
                .maxByOrNull { it.price }?.price
        }

    /**
     * 下一卖出档提示（仅波段模式）：现价**上方**最近、尚未达成的配对卖出价——
     * 来自当前已持有（triggered）的档位；与 [nextBuyHint] 对称的「预警视角」。
     *
     * 为 null 的情形：非波段模式；无可卖档（尚无已持有档位）；现价已越过全部
     * 配对卖出价（此时应立即减仓，由通知/信号层表达，不在此提示）。
     * 已达成的卖出目标（配对价 ≤ 现价）不出现在提示里，避免「下一卖」指着一个
     * 已经该卖的档。
     */
    val nextSellHint: Double?
        get() {
            if (!swingMode) return null
            val price = currentPrice ?: return null
            if (price <= 0.0) return null
            return levels
                .filter { it.triggered }
                .mapNotNull { it.pairedSellPrice }
                .filter { it > price }
                .minOrNull()
        }

    /** 已达成（现价 ≥ 配对卖出价）的已持有档数——波段模式下「现在就该减仓」的档数。 */
    val reachedSellCount: Int
        get() {
            val price = currentPrice ?: return 0
            if (!swingMode || price <= 0.0) return 0
            return levels.count { it.triggered && it.pairedSellPrice != null && price >= it.pairedSellPrice }
        }
}

/**
 * 网格股息展望：这套网格**全部打完后**的年股息收入与资金收益率（收息定位的终极答案）。
 *
 * @property annualDividend  预计年股息（元）= Σ(各档分配股数 × 每股年分红)。
 * @property yieldOnCapitalPct 占总资金收益率（%，= 年股息 / totalCapital × 100）——
 *   全部资金打完时的「成本股息率」口径。
 */
data class GridDividendOutlook(
    val annualDividend: Double,
    val yieldOnCapitalPct: Double?
)

object GridCalculator {

    /** 波段仓位比例默认值（%）：每档股数中 30% 做波段高抛低吸，其余 70% 为底仓只买不卖。 */
    const val DEFAULT_SWING_RATIO_PERCENT = 30.0

    /**
     * 生成纯买入网格档位表。
     *
     * @param basePrice     买入起点（第一档，最贵；通常为 BOLL 中轨）。
     * @param lowPrice      资金用完位（最后一档，最便宜；通常为目标股息率对应价）。
     * @param highPrice     参考上界（超过不追买，仅展示；可不参与分档）。
     * @param grids         买入档数（[lowPrice, basePrice] 等分份数，≥ 2）。
     * @param totalCapital  投入总资金（元，> 0）。
     * @param currentPrice  当前价（元），可选；用于「下一档买入」提示。
     * @param gridType      档位分布：等差（默认）/ 等比（百分比步长）/ 按股息率。
     * @param dps           年度每股现金分红（元）；**仅 [GridType.YIELD] 模式必填**——
     *   股息率档位价 = dps ÷ 股息率（首档 yield = dps/basePrice、末档 yield = dps/lowPrice
     *   由两端价格反推，中间档股息率等差）。缺失/非正时 YIELD 模式返回参数错误。
     * @param levelWeights  自定义档位资金比例（**相对权重**，与档位同序、从最便宜档起，
     *   无需合计 100，计算时归一化）；null = 默认 1/price 反比分配（越便宜买越多）。
     *   长度 ≠ [grids] 或含非正数时返回参数错误（档位价不受影响，只改资金分配）。
     * @param swingMode     波段模式：每档买入拆为**底仓 + 波段**两部分——底仓只买不卖
 *   （永续持有收息），波段部分涨到**股息率卖出锚**（买入股息率 − 步长百分点对应的
 *   价格，P = DPS ÷ 卖出股息率）减仓、跌回档位再买回，回合滚动；false = 纯买入收息
 *   （默认，行为不变）。波段模式**必须有 [dps]**（卖出锚按股息率换算）。
     * @param swingStepPercent 波段步长（**股息率百分点**，卖出锚股息率 = 买入股息率 − 步长）；
 *   null/非正 = 默认取网格等效股息率档距（「回落一档」，YIELD 模式即 [GridResult.yieldStepPercent]）。
     * @param swingRatioPercent 波段仓位比例（%，该档股数中做波段的部分，整手取整）；
 *   其余股数为底仓。默认 30。须在 (0, 100]。100 = 无底仓纯波段。
     */
    fun generate(
        basePrice: Double,
        lowPrice: Double,
        highPrice: Double,
        grids: Int,
        totalCapital: Double,
        currentPrice: Double? = null,
        gridType: GridType = GridType.ARITHMETIC,
        dps: Double? = null,
        levelWeights: List<Double>? = null,
        swingMode: Boolean = false,
        swingStepPercent: Double? = null,
        swingRatioPercent: Double = DEFAULT_SWING_RATIO_PERCENT
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
        if (levelWeights != null &&
            (levelWeights.size != grids || levelWeights.any { it <= 0.0 })
        ) {
            return empty("各档资金比例须为正数且与档数一致", highPrice)
        }
        if (gridType == GridType.YIELD && (dps == null || dps <= 0.0)) {
            return empty("按股息率网格需要分红数据（每股年分红）", highPrice)
        }
        if (swingMode && (dps == null || dps <= 0.0)) {
            return empty("波段模式需要分红数据（卖出锚按股息率换算）", highPrice)
        }
        if (swingMode && (swingRatioPercent <= 0.0 || swingRatioPercent > 100.0)) {
            return empty("波段仓位比例须在 0~100 之间", highPrice)
        }
        // YIELD / 波段模式已在上方保证 dps 非空；局部收窄供分支使用（其余模式不读）
        val dpsValue = dps ?: 0.0

        // 档位在 [lowPrice, basePrice] 分布 grids 档（含两端），从低到高排列。
        // 等差：绝对价差均分；等比：相邻档位价之比恒定（百分比步长）；
        // 按股息率：股息率等差递增（价格双曲线递减），档位价 = dps ÷ 该档股息率。
        val prices: List<Double> = when (gridType) {
            GridType.GEOMETRIC -> {
                val ratio = Math.pow(basePrice / lowPrice, 1.0 / (grids - 1))
                (0 until grids).map { lowPrice * Math.pow(ratio, it.toDouble()) }
            }
            GridType.ARITHMETIC -> {
                val step = (basePrice - lowPrice) / (grids - 1)
                (0 until grids).map { lowPrice + step * it }
            }
            GridType.YIELD -> {
                // 股息率由两端价格反推：末档（最贵）= dps/basePrice，首档（最便宜）= dps/lowPrice；
                // 序列仍从低价到高价，i=0 → dps/(dps/lowPrice) = lowPrice、i=grids-1 → basePrice（精确）。
                val startYield = dpsValue / basePrice
                val endYield = dpsValue / lowPrice
                val yStep = (endYield - startYield) / (grids - 1)
                (0 until grids).map { dpsValue / (endYield - yStep * it) }
            }
        }
        // stepPercent 语义：相邻档的步长幅度（%）——等差按价差/起点，等比按每档恒定比值；
        // 按股息率模式价格步长不恒定，取等效等比步长（价格区间几何均分）仅供参考。
        val stepPercent = when (gridType) {
            GridType.GEOMETRIC -> (Math.pow(basePrice / lowPrice, 1.0 / (grids - 1)) - 1.0) * 100.0
            GridType.ARITHMETIC ->
                ((basePrice - lowPrice) / (grids - 1)) / basePrice * 100.0
            GridType.YIELD ->
                (Math.pow(basePrice / lowPrice, 1.0 / (grids - 1)) - 1.0) * 100.0
        }
        // 按股息率模式：相邻档股息率步长（百分点，如每档 +0.5）
        val yieldStepPercent = if (gridType == GridType.YIELD) {
            round2((dpsValue / lowPrice - dpsValue / basePrice) * 100.0 / (grids - 1))
        } else null

        // 波段步长（股息率百分点）：显式指定优先；缺省取网格等效股息率档距
        // （最贵档与最便宜档股息率之差 ÷ 档距数——YIELD 模式即 yieldStepPercent，
        //  语义「卖出锚 = 股息率回落一档」）。
        val effectiveSwingStep = if (swingMode) {
            (swingStepPercent?.takeIf { it > 0.0 })
                ?: (dpsValue / lowPrice - dpsValue / basePrice) / (grids - 1) * 100.0
        } else null
        // 卖出锚股息率 = 买入股息率 − 步长；最贵档买入股息率最小，最先不保——要求全部档位为正
        if (swingMode && dpsValue / basePrice * 100.0 - effectiveSwingStep!! <= 0.0) {
            return empty("波段步长过大：买入起点档的卖出股息率 ≤ 0", highPrice)
        }

        // 资金分配：默认 1/price 反比加权（越便宜买越多）；自定义权重时按相对比例归一化
        val weights = levelWeights ?: prices.map { 1.0 / it }
        val weightSum = weights.sum()
        val levels = prices.mapIndexed { index, price ->
            val weight = weights[index] / weightSum
            val amount = totalCapital * weight
            // A 股 100 股整手向下取整
            val shares = if (amount > 0.0 && price > 0.0) {
                ((amount / price) / 100).toInt() * 100
            } else 0
            // 波段拆分：波段股数 = 总股数 × 比例（整手取整），其余为底仓（只买不卖）
            val swingShares = if (swingMode) {
                ((shares * swingRatioPercent / 100.0) / 100).toInt() * 100
            } else 0
            // 卖出锚（股息率锚）：卖出股息率 = 买入股息率 − 步长百分点 → P = DPS ÷ 卖出股息率
            val sellYield = if (swingMode) dpsValue / price - effectiveSwingStep!! / 100.0 else 0.0
            GridLevel(
                price = round2(price),
                side = "BUY",
                shares = shares,
                amount = round2(shares * price),
                deviation = round2((price - basePrice) / basePrice * 100.0),
                yieldPercent = if (gridType == GridType.YIELD || swingMode) {
                    round2(dpsValue / price * 100.0)
                } else null,
                pairedSellPrice = if (swingMode && sellYield > 0.0) round2(dpsValue / sellYield) else null,
                pairedSellYieldPercent = if (swingMode && sellYield > 0.0) round2(sellYield * 100.0) else null,
                swingShares = swingShares
            )
        }

        return GridResult(
            levels = levels,
            stepPercent = round2(stepPercent),
            buyLevels = levels,
            sellLevels = emptyList(),
            highPrice = round2(highPrice),
            validationError = null,
            currentPrice = currentPrice,
            yieldStepPercent = yieldStepPercent,
            swingMode = swingMode,
            swingStepPercent = effectiveSwingStep?.let(::round2),
            swingRatioPercent = if (swingMode) round2(swingRatioPercent) else null
        )
    }

    private fun empty(error: String, highPrice: Double) = GridResult(
        levels = emptyList(),
        stepPercent = 0.0,
        buyLevels = emptyList(),
        sellLevels = emptyList(),
        highPrice = highPrice,
        validationError = error
    )

    private fun round2(v: Double): Double =
        kotlin.math.round(v * 100.0) / 100.0

    /**
     * 网格股息展望：全部档位打完后的年股息收入（Σ 档位股数 × 每股年分红）与占总资金收益率。
     *
     * @param result       网格计算结果（档位表含各档分配股数）。
     * @param dps          最新年度每股现金分红（元）；null/非正 → 返回 null（不臆造）。
     * @param totalCapital 计划总资金（元），用于成本收益率。
     */
    fun dividendOutlook(
        result: GridResult,
        dps: Double?,
        totalCapital: Double
    ): GridDividendOutlook? {
        if (dps == null || dps <= 0.0 || result.levels.isEmpty()) return null
        val totalShares = result.levels.sumOf { it.shares }
        if (totalShares <= 0) return null
        val annualDividend = totalShares * dps
        return GridDividendOutlook(
            annualDividend = round2(annualDividend),
            yieldOnCapitalPct = if (totalCapital > 0.0) round2(annualDividend / totalCapital * 100.0) else null
        )
    }

    /**
     * 关联实际交易记录，标记每个买入档位是否已触发（纯函数，无 Android 依赖）。
     *
     * **触发语义**：某档位被触发 = 该股票存在一笔 **BUY 交易**，成交价落在该档位的
     * 「触发区间」内。触发区间以档位价为中心、半径 = 相邻档位价差的一半（半步长）：
     * 价格跌进此区间即认为该档被执行（网格分档的合理容差，避免因价格微小抖动漏判）。
     *
     * **重放语义（按交易日期升序逐笔重放）**：
     * - **BUY** 命中未持有档 → 占用该档（triggered=true）；命中已持有档则忽略（同档不重复占用）。
     * - **SELL**：纯买入模式不参与判定（卖出是独立的持仓管理动作，沿用历史语义）；
     *   **波段模式**下命中某档**卖出锚**触发区间（[GridLevel.pairedSellPrice] ± 半步长）
     *   且该档**波段部分在持** → 释放波段部分（triggered=false，跌回档位可再买回），
     *   并累计一个回合（[GridResult.roundTrips]）与其**计划口径**利润
     *   （(卖出锚价 − 档位价) × 波段股数，见 [GridResult.swingProfit]）。
     *   **底仓部分不受卖出影响**（[GridLevel.baseHeld] 保持，永续持有）。
     *   卖出未命中任何在持档的卖出锚则忽略。
     *
     * 返回带 triggered / baseHeld 标记 / 回合计数 / 成交事件（[GridResult.buyFills]·
     * [GridResult.sellFills]）的 [GridResult] 副本；不修改原对象。
     *
     * @param result       网格计算结果（[GridCalculator.generate] 产出）。
     * @param transactions 该股票的全部交易记录（按日期升序重放；同日多笔按列表顺序）。
     */
    fun markTriggeredLevels(
        result: GridResult,
        transactions: List<com.stock.dividend.data.local.entity.TransactionEntity>
    ): GridResult {
        if (result.levels.isEmpty()) return result

        // 相邻档位价差的一半作为触发区间半径；档位等分时价差一致
        val levelPrices = result.levels.map { it.price }
        val halfStep = levelPrices.zipWithNext().minOfOrNull { (a, b) -> (b - a) / 2.0 }
            ?: return result

        // 按日期重放（日期相同保持列表顺序——录入顺序即当天先后）
        val ordered = transactions
            .filter { it.price > 0.0 }
            .sortedBy { it.date }

        // 三态机（波段模式）：底仓已建（baseHeld，只买不卖）+ 波段在持（swingHeld，可卖可再买）。
        // 纯买入模式只有 held（等价于 swingHeld 语义：该档已买入）。
        val baseHeld = mutableSetOf<Double>()
        val swingHeld = mutableSetOf<Double>()
        val buyFills = mutableListOf<GridLevelTrade>()
        val sellFills = mutableListOf<GridLevelTrade>()
        var roundTrips = 0
        var swingProfit = 0.0

        for (tx in ordered) {
            if (tx.type == "BUY") {
                // 唯一阻断条件：波段部分已在持（全量或波段补买均不允许重复）；
                // 底仓已建、波段已释放的档可再买（只补波段股数，实盘由用户按提示执行）
                val level = result.levels
                    .filter {
                        it.price !in swingHeld &&
                            kotlin.math.abs(tx.price - it.price) <= halfStep
                    }
                    .minByOrNull { kotlin.math.abs(tx.price - it.price) }
                    ?: continue
                if (level.baseShares > 0) baseHeld += level.price
                swingHeld += level.price
                buyFills += GridLevelTrade(level.price, tx.price, tx.shares, tx.date)
            } else if (tx.type == "SELL" && result.swingMode) {
                val level = result.levels
                    .filter {
                        it.price in swingHeld && it.pairedSellPrice != null &&
                            kotlin.math.abs(tx.price - it.pairedSellPrice!!) <= halfStep
                    }
                    .minByOrNull { kotlin.math.abs(tx.price - it.pairedSellPrice!!) }
                    ?: continue
                // 只释放波段部分；底仓（baseHeld）不动
                swingHeld -= level.price
                if (level.baseShares <= 0) baseHeld -= level.price  // 无底仓档彻底复位
                roundTrips += 1
                swingProfit += (level.pairedSellPrice!! - level.price) * level.swingShares
                sellFills += GridLevelTrade(level.price, tx.price, tx.shares, tx.date)
            }
        }

        val updatedLevels = if (result.swingMode) {
            result.levels.map { level ->
                level.copy(
                    triggered = level.price in swingHeld,
                    baseHeld = level.price in baseHeld
                )
            }
        } else {
            // 纯买入：沿用「有买入即触发」语义（baseHeld 恒 false）
            result.levels.map { level ->
                level.copy(triggered = level.price in swingHeld)
            }
        }
        return result.copy(
            levels = updatedLevels,
            buyLevels = updatedLevels,
            roundTrips = roundTrips,
            swingProfit = round2(swingProfit),
            buyFills = buyFills,
            sellFills = sellFills
        )
    }
}
