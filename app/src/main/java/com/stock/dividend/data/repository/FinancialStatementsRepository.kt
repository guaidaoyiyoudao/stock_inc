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
 * 刷新时按报告期 [mergeByReportDate] 合并——历史期次不可变，远端窗口没返回的旧期次从缓存续接，不随刷新丢失。
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
            // 历史期次不可变：远端窗口没返回的旧期次从缓存续接，不随刷新丢失；
            // 同期远端子表为 null（三表各自 runCatching，某表失败降级空）时回退缓存已有子表，防字段级回退
            val cachedPeriods = cached?.let { parse(it.payload)?.periods }.orEmpty()
            val merged = FinancialStatements(
                mergeByReportDate(cachedPeriods, remote.periods, { it.reportDate }) { r, c ->
                    // 三表任一子接口失败时该表全部字段为 null，逐字段回退缓存（防字段级回退被持久化）
                    r.copy(
                        totalOperateIncome = r.totalOperateIncome ?: c.totalOperateIncome,
                        operateCost = r.operateCost ?: c.operateCost,
                        saleExpense = r.saleExpense ?: c.saleExpense,
                        manageExpense = r.manageExpense ?: c.manageExpense,
                        financeExpense = r.financeExpense ?: c.financeExpense,
                        operateProfit = r.operateProfit ?: c.operateProfit,
                        totalProfit = r.totalProfit ?: c.totalProfit,
                        incomeTax = r.incomeTax ?: c.incomeTax,
                        parentNetProfit = r.parentNetProfit ?: c.parentNetProfit,
                        deductParentNetProfit = r.deductParentNetProfit ?: c.deductParentNetProfit,
                        netcashOperate = r.netcashOperate ?: c.netcashOperate,
                        netcashInvest = r.netcashInvest ?: c.netcashInvest,
                        netcashFinance = r.netcashFinance ?: c.netcashFinance,
                        endCce = r.endCce ?: c.endCce,
                        totalAssets = r.totalAssets ?: c.totalAssets,
                        totalLiabilities = r.totalLiabilities ?: c.totalLiabilities,
                        totalEquity = r.totalEquity ?: c.totalEquity,
                        monetaryFunds = r.monetaryFunds ?: c.monetaryFunds,
                        accountsRece = r.accountsRece ?: c.accountsRece,
                        inventory = r.inventory ?: c.inventory,
                        accountsPayable = r.accountsPayable ?: c.accountsPayable,
                        fixedAsset = r.fixedAsset ?: c.fixedAsset
                    )
                }
            )
            runCatching {
                financialStatementsCacheDao.upsert(
                    FinancialStatementsCacheEntity(
                        stockCode = stockCode,
                        payload = gson.toJson(merged),
                        fetchedAt = System.currentTimeMillis()
                    )
                )
            }
            return merged
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
