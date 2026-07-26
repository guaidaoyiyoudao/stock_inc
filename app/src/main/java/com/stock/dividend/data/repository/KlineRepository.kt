package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.TencentDividendApi
import com.stock.dividend.di.TencentDividendSource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** 腾讯 fqkline 周期。paramType 进请求 param，responseKey 对应响应 JSON 键。 */
enum class KlinePeriod(val paramType: String, val responseKey: String) {
    DAILY("day", "qfqday"),
    WEEKLY("week", "qfqweek"),
    MONTHLY("month", "qfqmonth");

    /** 取 [bars] 根所需的回看日历天数（含余量）。 */
    internal fun lookbackDays(bars: Int): Int = when (this) {
        DAILY -> bars * 7 / 5 + 30   // 交易日→日历，+buffer
        WEEKLY -> bars * 7
        MONTHLY -> bars * 31
    }
}

@Singleton
class KlineRepository @Inject constructor(
    @TencentDividendSource private val tencentApi: TencentDividendApi
) {
    suspend fun fetchCloses(
        stockCode: String,
        period: KlinePeriod,
        bars: Int = DEFAULT_BARS
    ): List<Double> {
        val tencentCode = stockCode.toTencentCode() ?: return emptyList()
        val param = buildParam(tencentCode, period, bars)
        val response = try {
            tencentApi.getKline(param)
        } catch (_: Exception) {
            return emptyList()
        }
        val stockData = response.data?.values?.firstOrNull() ?: return emptyList()
        val klines = when (period) {
            KlinePeriod.DAILY -> stockData.qfqday
            KlinePeriod.WEEKLY -> stockData.qfqweek ?: stockData.qfqday
            KlinePeriod.MONTHLY -> stockData.qfqmonth ?: stockData.qfqweek
        } ?: return emptyList()
        return klines.mapNotNull { row ->
            (row.getOrNull(CLOSE_INDEX) as? String)?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
        }
    }

    /** 周线兼容封装（旧调用点不改）。 */
    suspend fun fetchWeeklyCloses(stockCode: String, weeks: Int = DEFAULT_BARS): List<Double> =
        fetchCloses(stockCode, KlinePeriod.WEEKLY, weeks)

    internal fun buildParam(tencentCode: String, period: KlinePeriod, bars: Int): String {
        val today = LocalDate.now()
        val start = today.minusDays((period.lookbackDays(bars) * BUFFER_FACTOR).toLong())
        return "$tencentCode,${period.paramType},${start.iso()},${today.iso()},$KLINE_COUNT,$ADJUST_QFQ"
    }

    companion object {
        const val DEFAULT_BARS = 40
        const val KLINE_COUNT = 640
        const val CLOSE_INDEX = 2
        const val BUFFER_FACTOR = 2
        const val ADJUST_QFQ = "qfq"
    }
}

private fun String.toTencentCode(): String? = when {
    startsWith("sh.", ignoreCase = true) -> "sh" + substringAfter(".")
    startsWith("sz.", ignoreCase = true) -> "sz" + substringAfter(".")
    else -> null
}

private fun LocalDate.iso(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)
