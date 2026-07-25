package com.stock.dividend.data.repository

import com.google.gson.Gson
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.remote.DividendApi
import com.stock.dividend.data.remote.TencentDividendApi
import com.stock.dividend.data.remote.dto.TencentDividendItem
import com.stock.dividend.di.EastMoneyDividendApi
import com.stock.dividend.di.TencentDividendSource
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DividendRepository @Inject constructor(
    @TencentDividendSource private val tencentApi: TencentDividendApi,
    @EastMoneyDividendApi private val eastMoneyApi: DividendApi,
    private val dividendDao: DividendDao
) {
    suspend fun fetchAndCacheDividends(stockCode: String, securityCode: String): Result<Unit> {
        return try {
            // 腾讯为主源；若失败或拿不到数据，用东方财富回退补充
            val entities = fetchFromTencent(stockCode, securityCode)
                .takeIf { it.isNotEmpty() }
                ?: fetchFromEastMoney(stockCode, securityCode)

            dividendDao.deleteByStockCode(stockCode)
            dividendDao.insertAll(entities)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(e.toUserMessage(), e))
        }
    }

    fun observeDividends(stockCode: String): Flow<List<DividendEntity>> {
        return dividendDao.observeByStock(stockCode)
    }

    suspend fun getLatestDividend(stockCode: String): DividendEntity? {
        return dividendDao.getLatestByStock(stockCode)
    }

    // ── 腾讯（主源）──────────────────────────────────────────────

    private suspend fun fetchFromTencent(
        stockCode: String,
        securityCode: String
    ): List<DividendEntity> {
        val tencentCode = stockCode.toTencentCode() ?: "$securityCode"
        val dividends = try {
            fetchAllDividendsFromTencent(tencentCode)
        } catch (_: Exception) {
            // 腾讯失败不中断，交由东方财富回退
            return emptyList()
        }

        return dividends.mapNotNull { item ->
            val cashPerShare = item.fhSh?.toDoubleOrNull()?.let { it / 10.0 }
            if (cashPerShare == null || cashPerShare <= 0.0) return@mapNotNull null
            val exDate = item.cqr?.takeDateOnlyOrNull() ?: return@mapNotNull null
            val reportDate = item.nd?.takeYearEndDateOrNull() ?: exDate

            DividendEntity(
                id = "${stockCode}_${exDate}",
                stockCode = stockCode,
                reportDate = reportDate,
                cashPerShare = cashPerShare,
                dividendYield = null, // 腾讯无此字段，由本地按现价计算
                exDividendDate = exDate,
                recordDate = item.djr?.takeDateOnlyOrNull(),
                planNoticeDate = null,
                planStatus = null
            )
        }
            // 按除权除息日去重（分块合并可能出现重复）
            .distinctBy { it.id }
    }

    /**
     * 腾讯 fqkline 单次上限约 640 个交易日（≈2.5 年）。为覆盖近 5 年分红，
     * 按日期窗口分两块请求并合并：块1=最近 ~3 年，块2=再往前 ~3 年。
     */
    private suspend fun fetchAllDividendsFromTencent(tencentCode: String): List<TencentDividendItem> {
        val today = LocalDate.now()
        val block1End = today
        val block1Start = today.minusYears(3)
        val block2End = block1Start.minusDays(1)
        val block2Start = block2End.minusYears(3)

        val merged = LinkedHashMap<String, TencentDividendItem>()
        merged.putAll(fetchTencentWindow(tencentCode, block1Start, block1End))
        merged.putAll(fetchTencentWindow(tencentCode, block2Start, block2End))
        return merged.values.toList()
    }

    private suspend fun fetchTencentWindow(
        tencentCode: String,
        start: LocalDate,
        end: LocalDate
    ): Map<String, TencentDividendItem> {
        val param = "$tencentCode,day,${start.iso()},${end.iso()},$KLINE_COUNT,qfq"
        val response = tencentApi.getKline(param)
        val qfqday = response.data?.values?.firstOrNull()?.qfqday ?: return emptyMap()

        val gson = Gson()
        val result = LinkedHashMap<String, TencentDividendItem>()
        qfqday.forEach { day ->
            // 第 7 个元素（index 6）是分红对象，仅有除权除息日当天才有
            val seventh = day.getOrNull(DIVIDEND_ELEMENT_INDEX) ?: return@forEach
            if (seventh !is com.google.gson.JsonObject) return@forEach
            val item = gson.fromJson(seventh, TencentDividendItem::class.java)
            val key = item.cqr ?: item.djr ?: return@forEach
            if (item.fhSh?.toDoubleOrNull()?.let { it > 0.0 } == true) {
                result[key] = item
            }
        }
        return result
    }

    // ── 东方财富（补充/回退）──────────────────────────────────────

    private suspend fun fetchFromEastMoney(
        stockCode: String,
        securityCode: String
    ): List<DividendEntity> {
        val expectedSecuCode = stockCode.toEastmoneySecuCode()
        val filter = expectedSecuCode
            ?.let { "(SECUCODE=\"$it\")" }
            ?: "(SECURITY_CODE=\"$securityCode\")"
        val response = eastMoneyApi.getDividends(filter = filter)
        val items = response.result?.data ?: emptyList()

        return items.mapNotNull { item ->
            if (expectedSecuCode != null &&
                item.secuCode != null &&
                !item.secuCode.equals(expectedSecuCode, ignoreCase = true)
            ) {
                return@mapNotNull null
            }
            val reportDate = item.reportDate.toDateOnlyOrNull() ?: return@mapNotNull null
            val cashRatio = item.pretaxBonusRmb ?: return@mapNotNull null
            if (cashRatio <= 0) return@mapNotNull null

            DividendEntity(
                id = "${stockCode}_${reportDate}",
                stockCode = stockCode,
                reportDate = reportDate,
                cashPerShare = cashRatio / 10.0,
                dividendYield = item.dividentRatio?.let { it * 100.0 },
                exDividendDate = item.exDividendDate.toDateOnlyOrNull(),
                recordDate = item.equityRecordDate.toDateOnlyOrNull(),
                planNoticeDate = item.planNoticeDate.toDateOnlyOrNull(),
                planStatus = item.assignProgress
            )
        }
    }

    companion object {
        private const val KLINE_COUNT = 640
        private const val DIVIDEND_ELEMENT_INDEX = 6
    }
}

private fun LocalDate.iso(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)

private fun String?.toDateOnlyOrNull(): String? =
    this
        ?.substringBefore("T")
        ?.substringBefore(" ")
        ?.takeIf { it.isNotBlank() }

private fun String?.takeDateOnlyOrNull(): String? = toDateOnlyOrNull()

/** 报告年度补成 `YYYY-12-31` 作为 reportDate，便于按年份分组统计。 */
private fun String.takeYearEndDateOrNull(): String? =
    substringBefore("-").takeIf { it.length == 4 && it.toIntOrNull() != null }
        ?.let { "$it-12-31" }

/** `sh.600036` → `sh600036`（腾讯 fqkline 代码格式）。 */
private fun String.toTencentCode(): String? {
    return when {
        startsWith("sh.", ignoreCase = true) -> "sh" + substringAfter(".")
        startsWith("sz.", ignoreCase = true) -> "sz" + substringAfter(".")
        else -> null
    }
}

private fun String.toEastmoneySecuCode(): String? {
    val code = substringAfter(".").takeIf { it.isNotBlank() } ?: return null
    return when {
        startsWith("sz.", ignoreCase = true) -> "$code.SZ"
        startsWith("sh.", ignoreCase = true) -> "$code.SH"
        else -> null
    }
}

internal fun Exception.toUserMessage(): String {
    return when (this) {
        is SocketTimeoutException -> "网络连接超时，请重试"
        is UnknownHostException, is ConnectException -> "网络连接失败，请检查网络后重试"
        is HttpException -> {
            if (code() in 500..599) "服务器暂时无法响应，请稍后重试"
            else "网络请求失败，请重试"
        }
        else -> "操作失败，请重试"
    }
}
