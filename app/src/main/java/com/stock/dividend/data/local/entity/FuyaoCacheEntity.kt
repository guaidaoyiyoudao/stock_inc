package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 扶摇数据持久缓存（DB v28，2026-08-23）：payload 为 Gson JSON，key 形如
 * `fundHoldings|sh.510880` / `tradingDays` / `dragonTiger|all|2026-08-21`。
 *
 * 语义（离线优先，与 K线/财报的「历史不可变数据永久缓存」一致）：
 * - **合并式**（交易日历/指数日K/基金持仓/净值/报告期等）：远端覆盖同期、缓存独有
 *   历史永续保留（mergeByReportDate 同思想）；
 * - **覆盖式**（指数目录/成分/基金资料等慢变数据）：成功整体替换，失败/禁用回退缓存；
 * - **按日缓存优先**（龙虎榜/热股历史/涨跌停池）：过去日期命中缓存零网络。
 */
@Entity(tableName = "fuyao_cache")
data class FuyaoCacheEntity(
    @PrimaryKey
    val key: String,
    val payload: String,
    val fetchedAt: Long
)
