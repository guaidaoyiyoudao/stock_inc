package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.TencentDividendApi
import com.stock.dividend.di.TencentDividendSource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 拉取周线 K 线收盘价，用于计算周线 BOLL 带。
 *
 * 复用腾讯 fqkline 接口（与 [DividendRepository] 同源），param 用 `week` 周期。
 * 接口单次上限约 640 根，周线足够覆盖 BOLL 所需的 20+ 根。
 *
 * 网络异常或解析失败均返回空 list（不抛），由调用方据此判定 BOLL 数据不可用。
 */
@Singleton
class KlineRepository @Inject constructor(
    @TencentDividendSource private val tencentApi: TencentDividendApi
) {
    /**
     * 拉取 [stockCode]（`sh.600036` / `sz.000001`）最近周线收盘价。
     *
     * @param weeks 期望返回的周线根数（默认 40，远大于 BOLL period=20，留足余量）。
     * @return 按时间**升序**排列的收盘价；失败返回空 list。
     */
    suspend fun fetchWeeklyCloses(stockCode: String, weeks: Int = DEFAULT_WEEKS): List<Double> {
        val tencentCode = stockCode.toTencentCode() ?: return emptyList()
        val param = buildParam(tencentCode, weeks)
        val response = try {
            tencentApi.getKline(param)
        } catch (_: Exception) {
            return emptyList()
        }
        val stockData = response.data?.values?.firstOrNull() ?: return emptyList()
        // 周线请求时数据在 qfqweek 键下（字段名随周期变化）；日线索引留作回退。
        val klines = stockData.qfqweek ?: stockData.qfqday ?: return emptyList()
        // 每条: [date, open, close, high, low, volume, {dividend?}]，close 在 index 2
        return klines.mapNotNull { row ->
            (row.getOrNull(CLOSE_INDEX) as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        }
    }

    /**
     * 构造 fqkline param：`{code},week,{start},{end},{count},qfq`。
     * start 取 today - (weeks×7×WEEK_BUFFER_FACTOR) 天，保证覆盖足够多的周。
     */
    internal fun buildParam(tencentCode: String, weeks: Int): String {
        val today = LocalDate.now()
        // 多取一倍天数，避免节假日导致周线根数不足
        val start = today.minusDays((weeks * 7 * WEEK_BUFFER_FACTOR).toLong())
        return "$tencentCode,$KLINE_TYPE,${start.iso()},${today.iso()},$KLINE_COUNT,$ADJUST_QFQ"
    }

    companion object {
        const val DEFAULT_WEEKS = 40
        const val KLINE_TYPE = "week"
        const val ADJUST_QFQ = "qfq"
        const val KLINE_COUNT = 640
        const val CLOSE_INDEX = 2
        const val WEEK_BUFFER_FACTOR = 2
    }
}

/** `sh.600036` → `sh600036`（腾讯 fqkline 代码格式，与 DividendRepository 一致）。 */
private fun String.toTencentCode(): String? = when {
    startsWith("sh.", ignoreCase = true) -> "sh" + substringAfter(".")
    startsWith("sz.", ignoreCase = true) -> "sz" + substringAfter(".")
    else -> null
}

private fun LocalDate.iso(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)
