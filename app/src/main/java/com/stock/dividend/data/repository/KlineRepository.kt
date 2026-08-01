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

/**
 * 单根 K 线（前复权，OHLCV）。由 [parseKlineBars] 解析腾讯 fqkline 数组行得到。
 *
 * @property date      日期（YYYY-MM-DD）。
 * @property open      开盘价（元）。
 * @property close     收盘价（元）。
 * @property high      最高价（元）。
 * @property low       最低价（元）。
 * @property volume    成交量（手）。
 */
data class KlineBar(
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double
)

/**
 * 腾讯 fqkline 数组行 → [KlineBar] 列表（纯函数，无 Android 依赖）。
 *
 * 数组下标（实测 2026-08，腾讯 `appstock/app/fqkline/get`）：
 * `[0]date [1]open [2]close [3]high [4]low [5]volume`（均为字符串）。
 * 第 7 元素（index 6）是分红对象，仅除权除息日有——本函数只取 K 线，分红由 [com.stock.dividend.data.repository.DividendRepository] 单独解析。
 *
 * 过滤规则（与 [KlineRepository.fetchCloses] 一致）：close 缺失/≤0/非数字的行整体丢弃，
 * 因为 BOLL 等技术指标依赖连续有效收盘价。OHLC 任一缺失无法构成完整 K 线，亦丢弃。
 *
 * @param rows 接口返回的 qfq{day|week|month} 数组
 * @return 升序（旧→新）的有效 K 线；rows 为空返回空表
 */
fun parseKlineBars(rows: List<List<*>>): List<KlineBar> = rows.mapNotNull { row ->
    val date = row.getOrNull(0) as? String
    val open = row.parseDouble(1)
    val close = row.parseDouble(2)
    val high = row.parseDouble(3)
    val low = row.parseDouble(4)
    val volume = row.parseDouble(5) ?: 0.0   // volume 缺失视为 0（不丢整根 K 线）
    if (date.isNullOrBlank() || close == null || close <= 0.0) return@mapNotNull null
    KlineBar(date = date, open = open ?: close, close = close, high = high ?: close, low = low ?: close, volume = volume)
}

/** 取下标 [index] 的字符串并转 Double；越界/非数字/null 返回 null。 */
private fun List<*>.parseDouble(index: Int): Double? =
    (getOrNull(index) as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() }

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
        val klines = response.klineRows(period) ?: return emptyList()
        // 仅取有效收盘价（>0），与历史行为一致
        return klines.mapNotNull { row ->
            (row.getOrNull(CLOSE_INDEX) as? String)?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
        }
    }

    /**
     * 拉取 [stockCode] 指定 [period] 的完整 OHLCV K 线（前复权）。网络失败返回空表（红线 #2）。
     *
     * 与 [fetchCloses] 共享同一次请求，只是解析时保留 open/high/low/volume——这些字段原本就被接口返回，
     * 只是 [fetchCloses] 丢弃了。故不增加任何网络成本。
     */
    suspend fun fetchKlines(
        stockCode: String,
        period: KlinePeriod,
        bars: Int = DEFAULT_BARS
    ): List<KlineBar> {
        val tencentCode = stockCode.toTencentCode() ?: return emptyList()
        val param = buildParam(tencentCode, period, bars)
        val response = try {
            tencentApi.getKline(param)
        } catch (_: Exception) {
            return emptyList()
        }
        val klines = response.klineRows(period) ?: return emptyList()
        return parseKlineBars(klines)
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

/** 按周期取对应键的 K 线数组；优先周期键，缺失时日线→周线降级，周线→日线降级。 */
private fun com.stock.dividend.data.remote.dto.TencentKlineResponse.klineRows(
    period: KlinePeriod
): List<List<*>>? {
    val stockData = data?.values?.firstOrNull() ?: return null
    return when (period) {
        KlinePeriod.DAILY -> stockData.qfqday
        KlinePeriod.WEEKLY -> stockData.qfqweek ?: stockData.qfqday
        KlinePeriod.MONTHLY -> stockData.qfqmonth ?: stockData.qfqweek
    }
}

private fun String.toTencentCode(): String? = when {
    startsWith("sh.", ignoreCase = true) -> "sh" + substringAfter(".")
    startsWith("sz.", ignoreCase = true) -> "sz" + substringAfter(".")
    else -> null
}

private fun LocalDate.iso(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)
