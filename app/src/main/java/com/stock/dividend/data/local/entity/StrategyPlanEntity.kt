package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * strategy_plans.strategyType 取值：年线定投（红利 ETF 经典策略——
 * 250 日均线下方开启定投买入窗口；高于年线 [sellHalfPercent]% 卖出一半、
 * [sellAllPercent]% 全部卖出）。首版唯一策略类型，留字段供后续扩展。
 */
const val STRATEGY_TYPE_MA_DCA = "MA_DCA"

/**
 * 交易策略计划：用户为某只股票/ETF 设定的策略参数。**仅做信号提示与记账辅助，
 * 不联网下单**。同一标的可配置多条策略（与网格计划同语义）。
 *
 * - [stockCode] 关联标的；删除股票时级联删除其策略（外键）。
 * - [strategyType] 策略类型（首版仅 MA_DCA 年线定投，见 [STRATEGY_TYPE_MA_DCA]）。
 * - [maPeriod] 均线周期（日，默认 250 = 年线）。
 * - [sellHalfPercent] 高于年线该百分比 → 卖出一半（默认 7.5）。
 * - [sellAllPercent] 高于年线该百分比 → 全部卖出（默认 15）。
 * - [dcaAmount] 每期定投金额（元，定投窗口内一键记账预填的买入金额基准）。
 * - [notifyEnabled] 卖出阈值推送开关（定投窗口按约定只展示不推送）。
 * - [lastNotifiedSellTier] 上次已提醒的卖出档（"HALF"/"ALL"；偏离回落到卖半阈值
 *   下方后清空，可重新提醒）。仅通知检查回写，**不随 updatedAt 变动**。
 */
@Stable
@Entity(
    tableName = "strategy_plans",
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
data class StrategyPlanEntity(
    @PrimaryKey
    val id: String,
    val stockCode: String,
    val stockName: String,
    /** 策略类型：MA_DCA 年线定投（见 [STRATEGY_TYPE_MA_DCA]）。 */
    val strategyType: String = STRATEGY_TYPE_MA_DCA,
    /** 均线周期（日），默认 250 = 年线。 */
    val maPeriod: Int = 250,
    /** 高于年线该百分比（%）→ 卖出一半。 */
    val sellHalfPercent: Double = 7.5,
    /** 高于年线该百分比（%）→ 全部卖出。 */
    val sellAllPercent: Double = 15.0,
    /** 每期定投金额（元）。 */
    val dcaAmount: Double = 1000.0,
    /** 备注。 */
    val note: String? = null,
    /** 卖出阈值推送开关（边沿触发：每档只提醒一次，回落自动复位）。 */
    val notifyEnabled: Boolean = true,
    /** 上次已提醒的卖出档（"HALF"/"ALL"，null = 未提醒；偏离回落后清空）。 */
    val lastNotifiedSellTier: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
