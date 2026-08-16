package com.stock.dividend.data.notification

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * A 股交易时段判断（纯函数，便于单测）。
 *
 * 窗口取周一至周五 9:15–15:15（含头含尾）：开盘前 15 分钟集合竞价即可能出现有效价格，
 * 收盘后留 15 分钟数据稳定。**午休（11:30–13:00）不细分**——网格检查每次仅一发批量
 * 行情请求，午休时段多查一次的成本可忽略，不值得引入额外复杂度。
 *
 * 法定节假日无法本地判断（无节假日表），节假日时段内的检查只会得到与节前收盘
 * 相同的价格，到档判定天然去重，不会误报。
 */
object AshareTradingTime {

    fun isTradingWindow(at: LocalDateTime): Boolean {
        if (at.dayOfWeek == DayOfWeek.SATURDAY || at.dayOfWeek == DayOfWeek.SUNDAY) return false
        val minutes = at.hour * 60 + at.minute
        return minutes in 9 * 60 + 15..15 * 60 + 15
    }
}
