package com.stock.dividend.data.repository

import com.stock.dividend.data.local.AppDatabase
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.remote.QuoteApi
import com.stock.dividend.data.remote.SearchApi
import androidx.room.withTransaction
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

/** 一条待导入的持仓（OCR/手动输入）。 */
data class ImportRow(
    val rawCodeOrName: String,
    val shares: Int,
    val costPerShare: Double
)

/** 批量导入结果。 */
data class ImportSummary(
    val succeeded: List<String>,   // 成功导入的股票 code（sh./sz. 格式）
    val failed: List<ImportRow>    // 解析/匹配失败的原始行
)

@Singleton
class StockRepository @Inject constructor(
    private val api: SearchApi,
    private val quoteApi: QuoteApi,
    private val stockDao: StockDao,
    private val transactionDao: TransactionDao,
    private val appDatabase: AppDatabase
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

    suspend fun updateTargetWeight(code: String, weight: Double) {
        stockDao.updateTargetWeight(code, weight.coerceIn(0.0, 100.0))
    }

    suspend fun updateLastUpdated(code: String, timestamp: Long) {
        stockDao.updateLastUpdated(code, timestamp)
    }

    suspend fun updateAllLastUpdated(codes: List<String>, timestamp: Long) {
        codes.forEach { code -> stockDao.updateLastUpdated(code, timestamp) }
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

    /**
     * 通过 OCR/手动输入的代码或名称解析为 [StockSearchResult]。
     * - 若输入是 6 位代码，优先精确匹配 code；
     * - 否则取首个 A 股结果。
     * 解析失败返回 null，由上层标错并允许用户修正。
     */
    suspend fun resolveStock(rawCodeOrName: String): StockSearchResult? {
        val trimmed = rawCodeOrName.trim()
        if (trimmed.isEmpty()) return null
        val results = searchStocks(trimmed).getOrDefault(emptyList())
        if (results.isEmpty()) return null
        val isNumericCode = trimmed.matches(Regex("\\d{6}"))
        return if (isNumericCode) {
            results.firstOrNull { it.code.substringAfter(".") == trimmed } ?: results.first()
        } else {
            results.first()
        }
    }

    /**
     * 重新计算某股票的 denormalized shares / costPerShare。
     * 逻辑与 EditHoldingViewModel.calculateHolding 一致（BUY 减 SELL 得净持仓；
     * 成本按买入加权平均）。用于批量导入后修正已存在股票的缓存字段。
     */
    suspend fun recomputeHolding(stockCode: String) {
        val transactions = transactionDao.getByStock(stockCode)
        if (transactions.isEmpty()) {
            stockDao.updateShares(stockCode, 0)
            stockDao.updateCostPerShare(stockCode, 0.0)
            return
        }
        val totalShares = transactions.sumOf {
            if (it.type == "BUY") it.shares.toLong() else -it.shares.toLong()
        }.toInt().coerceAtLeast(0)
        val buyTransactions = transactions.filter { it.type == "BUY" }
        val buyShares = buyTransactions.sumOf { it.shares.toLong() }.toInt()
        val totalCost = buyTransactions.sumOf { it.price * it.shares }
        val avgCost = if (buyShares > 0) totalCost / buyShares else 0.0
        stockDao.updateShares(stockCode, totalShares)
        stockDao.updateCostPerShare(stockCode, avgCost)
    }

    /**
     * 批量导入持仓。每行先 [resolveStock]，成功则 [addStock]（创建股票 + BUY 交易），
     * 再 [recomputeHolding] 修正缓存字段。整个过程在单个 Room 事务内，失败行不阻塞其他行。
     */
    suspend fun importHoldings(rows: List<ImportRow>): ImportSummary {
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<ImportRow>()
        val today = LocalDate.now().toString()
        appDatabase.withTransaction {
            rows.forEach { row ->
                val resolved = try {
                    resolveStock(row.rawCodeOrName)
                } catch (_: Exception) {
                    null
                }
                if (resolved == null) {
                    failed.add(row)
                    return@forEach
                }
                try {
                    addStock(resolved, row.shares, row.costPerShare, today)
                    recomputeHolding(resolved.code)
                    succeeded.add(resolved.code)
                } catch (_: Exception) {
                    failed.add(row)
                }
            }
        }
        return ImportSummary(succeeded = succeeded, failed = failed)
    }
}
