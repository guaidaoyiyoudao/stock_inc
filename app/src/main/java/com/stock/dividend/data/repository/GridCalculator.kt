package com.stock.dividend.data.repository

import com.stock.dividend.data.local.entity.GRID_TYPE_GEOM
import com.stock.dividend.data.local.entity.GRID_TYPE_YIELD
import kotlin.math.abs

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
 * - **下一档提示**：现价下方最近的**未触发**档（已买入的档不再提示，每档只买一次）；
 *   现价 ≤ 资金用完位或下方档全部已买时无下一档。
 *
 * **说明**：本计算器仅生成计划档位表与提示，**不联网下单、不记账**——实际执行
 * （挂单/成交）由用户在券商端手动完成。
 *
 * @property price     档位价格（元）。
 * @property side      恒为 BUY（纯买入模型）。
 * @property shares    该档分配股数（按资金权重，越便宜越多；A 股 100 股整手）。
 * @property amount    该档分配金额（元）。
 * @property deviation 相对买入起点 [basePrice] 的偏离（%，负=更低）。
 * @property yieldPercent 该档对应股息率（%）——仅 [GridType.YIELD] 模式填充
 *   （= DPS ÷ 档位价 × 100）；等差/等比模式为 null。
 */
data class GridLevel(
    val price: Double,
    val side: String,           // 恒为 "BUY"
    val shares: Int,
    val amount: Double,
    val deviation: Double,      // (price - base) / base * 100
    /** 该档是否已被实际成交触发（价格跌到该档并发生买入交易）；仅运行时标记，计算器默认 false。 */
    val triggered: Boolean = false,
    /** 该档对应股息率（%）；仅按股息率（YIELD）模式填充，其余模式 null。 */
    val yieldPercent: Double? = null
) {
    val isBuy: Boolean get() = side == "BUY"
}

/**
 * 网格计算结果。
 *
 * @property levels         买入档位列表（价格从低到高：最便宜档在前）。
 * @property stepPercent    相邻档位步长幅度（%）：等差=价差/起点，等比=每档恒定比值；
 *   按股息率模式下价格步长不恒定，取等效等比步长（仅供参考）。
 * @property buyLevels      买入档（= levels，纯买入模型下恒等）。
 * @property sellLevels     恒为空（纯买入，无卖出档）。
 * @property highPrice      参考上界（超过不追买，展示用）。
 * @property currentPrice   计算时现价（元），可空；驱动 [nextBuyHint]。
 * @property yieldStepPercent 相邻档**股息率**步长（百分点，如每档 +0.5）——仅
 *   [GridType.YIELD] 模式填充；等差/等比模式为 null。
 * @property validationError 参数非法时的提示；非 null 表示无有效档位。
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
    val yieldStepPercent: Double? = null
) {
    /**
     * 下一档买入提示：现价下方最近的**未触发**档——已买入的档不再提示（纯买入网格
     * 每档只买一次；现价回升到已买档之上时不重复指向它）。
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

    /** 恒为 null（纯买入，无卖出语义）。 */
    val nextSellHint: Double? get() = null
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
     */
    fun generate(
        basePrice: Double,
        lowPrice: Double,
        highPrice: Double,
        grids: Int,
        totalCapital: Double,
        currentPrice: Double? = null,
        gridType: GridType = GridType.ARITHMETIC,
        dps: Double? = null
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
        if (gridType == GridType.YIELD && (dps == null || dps <= 0.0)) {
            return empty("按股息率网格需要分红数据（每股年分红）", highPrice)
        }
        // YIELD 模式已在上方保证 dps 非空；局部收窄供分支使用（其余模式不读）
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
                deviation = round2((price - basePrice) / basePrice * 100.0),
                yieldPercent = if (gridType == GridType.YIELD) round2(dpsValue / price * 100.0) else null
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
            yieldStepPercent = yieldStepPercent
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
     * **语义**：某档位被触发 = 该股票存在一笔 **BUY 交易**，成交价落在该档位的
     * 「触发区间」内。触发区间以档位价为中心、半径 = 相邻档位价差的一半（半步长）：
     * 价格跌进此区间即认为该档被执行（网格分档的合理容差，避免因价格微小抖动漏判）。
     *
     * 匹配只针对买入交易（纯买入模型，卖出记录不参与档位触发判定——卖出是独立的
     * 持仓管理动作，不是网格买入档的执行）。
     *
     * 返回带 [GridLevel.triggered] 标记的 [GridResult] 副本；不修改原对象。
     *
     * @param result       网格计算结果（[GridCalculator.generate] 产出）。
     * @param transactions 该股票的全部交易记录（BUY/SELL 均可，内部只取 BUY）。
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

        // 所有 BUY 成交价
        val buyPrices = transactions
            .filter { it.type == "BUY" && it.price > 0.0 }
            .map { it.price }

        val triggeredByPrice = result.levels.associate { level ->
            val hit = buyPrices.any { buy -> abs(buy - level.price) <= halfStep }
            level.price to hit
        }

        val updatedLevels = result.levels.map { level ->
            if (triggeredByPrice[level.price] == true) level.copy(triggered = true) else level
        }
        return result.copy(levels = updatedLevels, buyLevels = updatedLevels)
    }
}
