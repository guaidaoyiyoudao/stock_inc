package com.stock.dividend.data.repository

import com.google.gson.Gson
import com.stock.dividend.data.local.dao.FinancialStatementsCacheDao
import com.stock.dividend.data.local.entity.FinancialStatementsCacheEntity
import com.stock.dividend.data.remote.FundamentalApi
import com.stock.dividend.di.EastMoneyFundamentalApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单股财务三表缓存编排：新鲜（≤7 天）直接返回；过期/缺失走网络拉三表并写缓存；
 * 网络失败回退旧缓存、无缓存则 null。全程吞异常（红线 #2）。
 *
 * 与 [FundamentalsCacheRepository] 同构，区别在拉取三表（利润/现金流/资产负债）。
 */
@Singleton
class FinancialStatementsRepository @Inject constructor(
    private val financialStatementsCacheDao: FinancialStatementsCacheDao,
    @EastMoneyFundamentalApi private val fundamentalApi: FundamentalApi,
) {
    private val gson = Gson()

    suspend fun getFinancialStatements(stockCode: String, forceRefresh: Boolean = false): FinancialStatements? {
        val cached = runCatching { financialStatementsCacheDao.get(stockCode) }.getOrNull()
        if (!forceRefresh && cached != null && isFresh(cached.fetchedAt)) {
            return parse(cached.payload)
        }

        val remote = runCatching { fetchFromNetwork(stockCode) }.getOrNull()
        if (remote != null) {
            runCatching {
                financialStatementsCacheDao.upsert(
                    FinancialStatementsCacheEntity(
                        stockCode = stockCode,
                        payload = gson.toJson(remote),
                        fetchedAt = System.currentTimeMillis()
                    )
                )
            }
            return remote
        }
        return cached?.let { parse(it.payload) }
    }

    /** 并发拉三表（任一失败降级为空，不阻塞另两个），用 [FinancialStatementsBuilder] 对齐合并。 */
    private suspend fun fetchFromNetwork(stockCode: String): FinancialStatements? {
        val securityCode = stockCode.substringAfter(".")
        val filter = """(SECURITY_CODE="$securityCode")"""
        return coroutineScope {
            val incomeDeferred = async {
                runCatching { fundamentalApi.getIncomeStatement(filter = filter) }.getOrNull()
            }
            val cashDeferred = async {
                runCatching { fundamentalApi.getCashFlowStatement(filter = filter) }.getOrNull()
            }
            val balDeferred = async {
                runCatching { fundamentalApi.getBalanceSheetFull(filter = filter) }.getOrNull()
            }
            FinancialStatementsBuilder.build(
                income = incomeDeferred.await()?.result?.data.orEmpty(),
                cashFlow = cashDeferred.await()?.result?.data.orEmpty(),
                balance = balDeferred.await()?.result?.data.orEmpty()
            )
        }
    }

    private fun parse(payload: String): FinancialStatements? =
        runCatching { gson.fromJson(payload, FinancialStatements::class.java) }.getOrNull()

    private fun isFresh(fetchedAt: Long): Boolean =
        System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS

    companion object {
        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
