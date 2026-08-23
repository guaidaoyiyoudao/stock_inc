package com.stock.dividend.data.repository

import java.time.LocalDate
import java.time.temporal.WeekFields

/**
 * 日线 → 周/月线本地聚合（纯函数，无 Android 依赖）。
 *
 * 同花顺扶摇只有日线（interval=1d），周线/月线由日线聚合得到——聚合同时保证了
 * 日/周/月三个周期的前复权基准**同源一致**（若混用腾讯原生周线 + 扶摇日线，
 * 两家复权因子的舍入差异会在三周期 BOLL 共振判断中引入基准偏差）。
 *
 * 聚合规则（对交易日升序的日线序列）：
 * - 周线按 ISO 周分组（周一起始，跨年周按 ISO week-based-year 归属，不劈叉）；
 * - 周期 K 的 **date = 组内最后一个交易日**——与尾根当前性判定 [klineTailIsCurrent]
 *   兼容（本周部分 K 的日期自然落在本周内）；upsert 按日期 REPLACE，当期 K 随增量重算覆盖；
 * - open = 组内首日 open，close = 组内末日 close，high/low = 组内极值，volume 求和。
 *
 * 输入必须升序（扶摇日K天然升序）；乱序输入的 open/close 取值无意义，由调用方保证。
 */
object KlineAggregator {

    fun aggregate(dailyBars: List<KlineBar>, period: KlinePeriod): List<KlineBar> = when (period) {
        KlinePeriod.DAILY -> dailyBars
        KlinePeriod.WEEKLY -> aggregateBy(dailyBars) { date ->
            date.get(WeekFields.ISO.weekBasedYear()) * 100 + date.get(WeekFields.ISO.weekOfWeekBasedYear())
        }
        KlinePeriod.MONTHLY -> aggregateBy(dailyBars) { date -> date.year * 100 + date.monthValue }
    }

    private fun aggregateBy(bars: List<KlineBar>, keyOf: (LocalDate) -> Int): List<KlineBar> =
        bars.groupBy { keyOf(LocalDate.parse(it.date)) }
            .map { (_, group) ->
                val first = group.first()
                val last = group.last()
                KlineBar(
                    date = last.date,
                    open = first.open,
                    close = last.close,
                    high = group.maxOf { it.high },
                    low = group.minOf { it.low },
                    volume = group.sumOf { it.volume }
                )
            }
}
