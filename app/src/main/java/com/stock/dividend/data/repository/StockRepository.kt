package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.remote.SearchApi
import com.stock.dividend.data.remote.dto.StockSearchResponse
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class StockSearchResult(
    val code: String,
    val name: String,
    val marketCode: String
)

@Singleton
class StockRepository @Inject constructor(
    private val api: SearchApi,
    private val stockDao: StockDao
) {
    suspend fun searchStocks(query: String): Result<List<StockSearchResult>> {
        return try {
            val response = api.searchStocks(input = query)
            val items = response.quotationCodeTable?.Data
                ?.filter { it.Classify == "AStock" }
                ?.map { item ->
                    StockSearchResult(
                        code = formatStockCode(item.MktNum, item.Code),
                        name = item.Name,
                        marketCode = item.MktNum
                    )
                } ?: emptyList()
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(Exception(e.toUserMessage(), e))
        }
    }

    suspend fun addStock(searchResult: StockSearchResult, shares: Int = 0): Result<Unit> {
        return try {
            val entity = StockEntity(
                code = searchResult.code,
                name = searchResult.name,
                marketCode = searchResult.marketCode,
                shares = shares
            )
            stockDao.insert(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(e.toUserMessage(), e))
        }
    }

    suspend fun removeStock(code: String) {
        stockDao.delete(code)
    }

    fun observeAllStocks(): Flow<List<StockEntity>> {
        return stockDao.observeAll()
    }

    fun observeStock(code: String): Flow<StockEntity?> {
        return stockDao.observeByCode(code)
    }

    suspend fun updateShares(code: String, shares: Int) {
        stockDao.updateShares(code, shares.coerceAtLeast(0))
    }

    suspend fun updateYieldPeriod(code: String, period: String) {
        stockDao.updateYieldPeriod(code, period)
    }

    private fun formatStockCode(marketCode: String, code: String): String {
        val prefix = if (marketCode == "1") "sh" else "sz"
        return "$prefix.$code"
    }
}
