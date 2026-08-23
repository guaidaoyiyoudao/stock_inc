package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** grid_plans.gridType 取值：等差网格（绝对价差均分，默认）。 */
const val GRID_TYPE_ARITH = "ARITH"

/** grid_plans.gridType 取值：等比网格（百分比步长，高价股适用）。 */
const val GRID_TYPE_GEOM = "GEOM"

/** grid_plans.gridType 取值：按股息率网格（股息率等差分档，如 5.5%/6.0%/6.5%）。 */
const val GRID_TYPE_YIELD = "YIELD"

/**
 * 网格交易计划：用户为某只股票设定的网格参数（买入起点/资金用完位/档数/总资金），
 * 用于生成**纯买入**网格档位表（越跌越买、持有收息，无卖出档）。**仅做计划与提示，不联网下单**。
 *
 * - [stockCode] 关联标的；删除股票时级联删除其网格计划（外键）。
 * - [basePrice] 买入起点（第一档/最贵档，通常为 BOLL 中轨锚定）。
 * - [lowPrice] 资金用完位（最后一档/最便宜档，通常为目标股息率对应价）。
 * - [highPrice] 参考上界（超过不追买，仅展示，不参与分档）。
 * - [grids] 买入档数（[lowPrice, basePrice] 等分份数，≥ 2）。
 * - [gridType] 档位分布：ARITH 等差（绝对价差均分，默认）/ GEOM 等比（百分比步长，高价股适用）/
 *   YIELD 按股息率（股息率等差分档，档位价 = [dpsPerShare] ÷ 股息率）。
 * - [totalCapital] 投入总资金（元），按 1/price 反比加权分配到各档（越便宜买越多）。
 * - [targetYieldPercent] 建计划时的目标股息率（%，「到达即资金用完位」的用户意图）；
 *   一键重锚定用；手填参数或旧数据可能为 null（此时由现 lowPrice 反推）。
 *   YIELD 模式 = 结束股息率（资金用完位股息率）。
 * - [dpsPerShare] 建计划时的年度每股现金分红快照（元）——**仅 YIELD 模式**的档位价
 *   换算基准（P = dps ÷ yield）。存快照而非实时拉取：分红变化不使已建计划档位漂移，
 *   需要跟进时走「一键重锚定」用最新 DPS 重算。
 * - [levelWeights] 自定义档位资金比例（JSON 数组字符串，如 "[20.0,30.0,50.0]"，
 *   与档位同序、从最便宜档起，**相对权重**无需合计 100）；null = 默认 1/price 反比分配
 *   （越便宜买越多）。编解码与合法性校验见 [GridLevelWeights]。
 * - [notifyEnabled] 到档提醒开关：价格到达下一买入档时推送本地通知。
 * - [lastNotifiedLevelPrice] 上次已提醒的档位价（去重用；现价回升超过该档后清空，
 *   再次跌破可重新提醒）。仅通知检查回写，**不随 updatedAt 变动**。
 * - [swingMode] 波段模式开关（2026-08-23，DB v29）：每档买入拆为**底仓 + 波段**——
 *   底仓只买不卖收息；波段部分（[swingRatioPercent]，默认 30%）涨到**股息率卖出锚**
 *   （卖出锚价 = DPS ÷ (买入股息率 − [swingStepPercent] 百分点)）减仓、跌回档位再买回，
 *   回合滚动；false = 纯买入收息（默认，行为不变）。
 * - [swingStepPercent] 波段步长（**股息率百分点**，卖出锚 = 买入股息率 − 步长）；
 *   null/非正 = 默认取网格等效股息率档距（「回落一档」）。仅波段模式生效。
 * - [swingRatioPercent] 波段仓位比例（%，该档股数中做波段的部分；其余为底仓）。默认 30。
 * - [lastNotifiedSellLevelPrice] 上次已提醒的**卖出锚**价（波段模式到档提醒去重用；
 *   现价回落到该价之下后清空，再次涨到可重新提醒）。
 */
@Stable
@Entity(
    tableName = "grid_plans",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = StockEntity::class,
            parentColumns = ["code"],
            childColumns = ["stockCode"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [Index("stockCode")]
)
data class GridPlanEntity(
    @PrimaryKey
    val id: String,
    val stockCode: String,
    val stockName: String,
    val basePrice: Double,
    val lowPrice: Double,
    val highPrice: Double,
    val grids: Int,
    val totalCapital: Double,
    /** 档位分布：ARITH 等差 / GEOM 等比 / YIELD 按股息率（见 GridCalculator.GridType）。 */
    val gridType: String = GRID_TYPE_ARITH,
    /** 建计划时的目标股息率（%）；重锚定用，旧数据/手填可能为 null。YIELD 模式 = 结束股息率。 */
    val targetYieldPercent: Double? = null,
    /** 建计划时的年度每股分红快照（元）；YIELD 模式的档位价换算基准，其余模式 null。 */
    val dpsPerShare: Double? = null,
    /** 自定义档位资金比例（JSON 数组字符串，见 GridLevelWeights）；null = 反比默认分配。 */
    val levelWeights: String? = null,
    /** 到档提醒开关（价格到达下一买入档时推送通知）。 */
    val notifyEnabled: Boolean = true,
    /** 上次已提醒的档位价（每档只提醒一次；现价回升超过该档后清空）。 */
    val lastNotifiedLevelPrice: Double? = null,
    /** 波段模式开关：每档买入附带配对卖出档（涨到提示减仓、卖出后重挂）。 */
    val swingMode: Boolean = false,
    /** 波段步长（股息率百分点，卖出锚 = 买入股息率 − 步长）；null = 默认回落一档。 */
    val swingStepPercent: Double? = null,
    /** 波段仓位比例（%，该档股数中做波段的部分；其余为底仓只买不卖）。 */
    val swingRatioPercent: Double = 30.0,
    /** 上次已提醒的卖出档配对价（波段模式去重；现价回落到其下后清空）。 */
    val lastNotifiedSellLevelPrice: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * [GridPlanEntity.levelWeights] 列的编解码（纯函数，无 Android 依赖）。
 *
 * 语义：各档**相对资金权重**（无需合计 100，计算时归一化），与档位列表同序
 * （从最便宜档起）。null = 反比默认（1/price，越便宜买越多）。
 *
 * 解析容错：格式损坏 / 含 0、负数 / 空数组一律返回 null（回退反比默认），
 * 绝不让脏数据炸档位计算；档数一致性由 [com.stock.dividend.data.repository.GridCalculator]
 * 校验（权重档数 ≠ grids → 参数错误）。
 */
object GridLevelWeights {
    /** 序列化为 JSON 数组字符串（如 "[20.0,30.0,50.0]"）。 */
    fun toJson(weights: List<Double>): String = weights.joinToString(",", "[", "]")

    /**
     * 解析 levelWeights 列；null/空白/格式非法/含非正数/空数组 → null（反比默认）。
     */
    fun parse(raw: String?): List<Double>? {
        val trimmed = raw?.trim()
            ?.takeIf { it.length >= 2 && it.startsWith("[") && it.endsWith("]") }
            ?: return null
        val inner = trimmed.substring(1, trimmed.length - 1)
        if (inner.isBlank()) return null  // "[]" 空数组
        val weights = mutableListOf<Double>()
        for (part in inner.split(",")) {
            weights += part.trim().toDoubleOrNull() ?: return null
        }
        return weights.takeIf { it.all { w -> w > 0.0 } }
    }
}
