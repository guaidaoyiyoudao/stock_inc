package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.remote.DividendApi
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DividendRepository @Inject constructor(
    private val api: DividendApi,
    private val dividendDao: DividendDao
) {
    suspend fun fetchAndCacheDividends(stockCode: String, securityCode: String): Result<Unit> {
        return try {
            val expectedSecuCode = stockCode.toEastmoneySecuCode()
            val filter = expectedSecuCode
                ?.let { "(SECUCODE=\"$it\")" }
                ?: "(SECURITY_CODE=\"$securityCode\")"
            val response = api.getDividends(filter = filter)
            val items = response.result?.data ?: emptyList()

            val entities = items.mapNotNull { item ->
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
}

private fun String?.toDateOnlyOrNull(): String? =
    this
        ?.substringBefore("T")
        ?.substringBefore(" ")
        ?.takeIf { it.isNotBlank() }

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
