package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.remote.QuoteApi
import com.stock.dividend.data.remote.SearchApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class StockSearchResult(
    val code: String,
    val name: String,
    val marketCode: String,
    val currentPrice: Double? = null
)

@Singleton
class StockRepository @Inject constructor(
    private val api: SearchApi,
    private val quoteApi: QuoteApi,
    private val stockDao: StockDao,
    private val transactionDao: TransactionDao
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

            // Batch fetch prices for search results
            val pricedItems = if (items.isNotEmpty()) {
                try {
                    val secids = items.joinToString(",") { "${it.marketCode}.${it.code.substringAfter(".")}" }
                    val quoteResponse = quoteApi.getQuotes(secids = secids)
                    val priceMap = quoteResponse.data?.diff?.associate {
                        "${it.market}.${it.code}" to (it.price?.div(100.0))
                    } ?: emptyMap()
                    items.map { item ->
                        val key = "${item.marketCode}.${item.code.substringAfter(".")}"
                        item.copy(currentPrice = priceMap[key])
                    }
                } catch (_: Exception) {
                    items // Return without prices if quote fetch fails
                }
            } else items

            Result.success(pricedItems)
        } catch (e: Exception) {
            Result.failure(Exception(e.toUserMessage(), e))
        }
    }

    suspend fun addStock(
        searchResult: StockSearchResult,
        shares: Int = 0,
        costPerShare: Double = 0.0,
        buyDate: String = LocalDate.now().toString()
    ): Result<Unit> {
        return try {
            val entity = StockEntity(
                code = searchResult.code,
                name = searchResult.name,
                marketCode = searchResult.marketCode,
                shares = shares,
                costPerShare = costPerShare
            )
            stockDao.insert(entity)

            if (shares > 0) {
                transactionDao.insert(
                    TransactionEntity(
                        stockCode = searchResult.code,
                        type = "BUY",
                        shares = shares,
                        price = costPerShare,
                        date = buyDate
                    )
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(e.toUserMessage(), e))
        }
    }

    suspend fun removeStock(code: String) {
        stockDao.delete(code)
    }

    suspend fun restoreStock(stock: StockEntity) {
        stockDao.insert(stock)
    }

    fun observeAllStocks(): Flow<List<StockEntity>> {
        return stockDao.observeAll()
    }

    suspend fun observeAllStocksForSnapshot(): List<StockEntity> {
        return stockDao.observeAll().first()
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

    suspend fun updateCostPerShare(code: String, costPerShare: Double) {
        stockDao.updateCostPerShare(code, costPerShare.coerceAtLeast(0.0))
    }

    suspend fun getFirstBuyDate(code: String): String? =
        transactionDao.getFirstBuyDate(code)

    suspend fun fetchQuotes(stocks: List<StockEntity>): Map<String, Double> {
        if (stocks.isEmpty()) return emptyMap()
        return try {
            val secids = stocks.joinToString(",") { stock ->
                "${stock.marketCode}.${stock.code.substringAfter(".")}"
            }
            val response = quoteApi.getQuotes(secids = secids)
            val priceMap = mutableMapOf<String, Double>()
            response.data?.diff?.forEach { item ->
                val price = item.price
                if (price != null && price > 0) {
                    val prefix = if (item.market == 1) "sh" else "sz"
                    val appCode = "$prefix.${item.code}"
                    priceMap[appCode] = price / 100.0
                }
            }
            priceMap
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun formatStockCode(marketCode: String, code: String): String {
        val prefix = if (marketCode == "1") "sh" else "sz"
        return "$prefix.$code"
    }
}
