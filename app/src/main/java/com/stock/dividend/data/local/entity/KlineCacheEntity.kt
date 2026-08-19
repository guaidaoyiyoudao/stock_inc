package com.stock.dividend.data.local.entity

import androidx.room.Entity

/** 单根 K 线缓存行（前复权）。主键 (stockCode, period, date)。历史 K 线不可变，仅尾部最新一根盘中会变（增量请求覆盖更新）。 */
@Entity(tableName = "kline_cache", primaryKeys = ["stockCode", "period", "date"])
data class KlineCacheEntity(
    val stockCode: String,
    val period: String,   // KlinePeriod.name：DAILY / WEEKLY / MONTHLY
    val date: String,     // YYYY-MM-DD
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

/** K 线缓存同步状态（每股每周期一行）：fetchedAt=最近写缓存时间（新鲜窗口判定）；lastExDividendDate=写入时该股最新除权日——出现更新除权日说明前复权全历史漂移，需全量重建。 */
@Entity(tableName = "kline_cache_meta", primaryKeys = ["stockCode", "period"])
data class KlineCacheMetaEntity(
    val stockCode: String,
    val period: String,
    val fetchedAt: Long,
    val lastExDividendDate: String?
)
