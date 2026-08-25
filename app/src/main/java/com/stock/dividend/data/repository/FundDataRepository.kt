package com.stock.dividend.data.repository

import com.google.gson.JsonObject
import com.stock.dividend.data.remote.FuyaoApi
import com.stock.dividend.data.remote.dto.FuyaoAssetAllocationData
import com.stock.dividend.data.remote.dto.FuyaoAssetAllocationItem
import com.stock.dividend.data.remote.dto.FuyaoFundDrawdownItem
import com.stock.dividend.data.remote.dto.FuyaoFundHolderItem
import com.stock.dividend.data.remote.dto.FuyaoFundHoldingsData

import com.stock.dividend.data.remote.dto.FuyaoFundIndustryItem
import com.stock.dividend.data.remote.dto.FuyaoFundNavItem
import com.stock.dividend.data.remote.dto.FuyaoFundProfileItem
import com.stock.dividend.data.remote.dto.FuyaoFundReportDateItem
import com.stock.dividend.data.remote.dto.FuyaoFundReturnItem
import com.stock.dividend.data.remote.dto.toFuyaoThscodeOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基金扩展数据仓库（同花顺扶摇独有能力，2026-08-23 全量接入）：
 * 基本资料（规模/经理/费率）、重仓持仓与行业/资产配置、多周期收益与最大回撤、净值、
 * 持有人结构、基金经理/公司、诊断、资讯、募集、基金财务三表。
 *
 * **持久缓存（DB v28，离线优先）**：历史不可变数据（持仓/行业配置/净值/持有人/报告期）
 * 合并式永久缓存——远端覆盖同期、缓存独有旧期次永续保留；慢变数据（资料/收益/回撤等）
 * 覆盖式缓存 + 失败回退。断网或未配置 key 时历史数据依然可读。
 *
 * 这些能力东财/腾讯无对应、无降级路径——网络失败返回缓存或 null（红线 #2），
 * 落 ErrorLog（source="同花顺"）。数据平面统一经 [com.stock.dividend.data.plane.MarketDataPlane]。
 * 输入为 App 代码（`sh.510880`）；App 只管理场内基金，fund_type 恒 `exchange`。
 */
@Singleton
class FundDataRepository @Inject constructor(
    private val fuyaoApi: FuyaoApi,
    private val fuyaoConfig: FuyaoConfig,
    private val cacheStore: FuyaoCacheStore,
    private val errorLogRepository: ErrorLogRepository,
) {
    /** 扶摇独有能力统一执行器：禁用直接 null；失败记日志返回 null（无降级源）。 */
    private suspend fun <T> fetchFuyao(
        source: String,
        message: String,
        block: suspend () -> T
    ): T? = withContext(Dispatchers.IO) {
        if (!fuyaoConfig.enabled) return@withContext null
        try {
            block()
        } catch (e: Exception) {
            errorLogRepository.record(source = source, message = message, throwable = e)
            null
        }
    }

    private fun thscodeOf(fundCode: String): String? = fundCode.toFuyaoThscodeOrNull()

    /**
     * 合并式缓存的通用合并：key 非空行「远端覆盖同期、缓存独有旧期次永续保留」。
     * 注意 Kotlin Map `+` 是右侧覆盖左侧，必须缓存放左、远端放右（2026-08-24 评审修复：
     * 此前方向写反导致远端修正永远进不了缓存）。key 为 null 的行无法对齐去重
     * （associateBy 会把多条 null 键塌缩成一条），仅保留远端侧，防止跨次同步重复累积。
     */
    private inline fun <T, K : Any> mergeByKey(
        cached: List<T>,
        fresh: List<T>,
        key: (T) -> K?,
        sort: (List<T>) -> List<T>
    ): List<T> {
        val merged = buildMap {
            cached.forEach { v -> key(v)?.let { k -> put(k, v) } }
            fresh.forEach { v -> key(v)?.let { k -> put(k, v) } }   // 远端覆盖同期
        }.values.toList()
        return sort(merged + fresh.filter { key(it) == null })
    }

    fun isEnabled(): Boolean = fuyaoConfig.enabled

    suspend fun getProfile(fundCode: String): FuyaoFundProfileItem? =
        cacheStore.fetchFirstReplace("fundProfile|$fundCode", fuyaoCacheTypeOf<FuyaoFundProfileItem>()) {
            fetchFuyao("同花顺", "基金资料获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                val envelope = fuyaoApi.getFundProfile(fundType = "exchange", thscode = ths)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item?.firstOrNull()
            }
        }

    /** 重仓持仓（合并式：按报告期 endDateMs 对齐，远端覆盖同期、缓存独有旧报告期永续保留）。 */
    suspend fun getHoldings(fundCode: String): FuyaoFundHoldingsData? =
        cacheStore.fetchFirstMerge(
            "fundHoldings|$fundCode",
            fuyaoCacheTypeOf<FuyaoFundHoldingsData>(),
            merge = { cached, fresh ->
                if (cached == null) fresh
                else fresh.copy(
                    // 按报告期 endDateMs 对齐：远端覆盖同期、缓存独有旧报告期永续保留
                    item = mergeByKey(
                        cached = cached.item.orEmpty(),
                        fresh = fresh.item.orEmpty(),
                        key = { it.endDateMs },
                        sort = { list -> list.sortedByDescending { it.endDateMs } }
                    )
                )
            }
        ) {
            fetchFuyao("同花顺", "基金重仓持仓获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                val envelope = fuyaoApi.getFundHoldings(fundType = "exchange", thscode = ths)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data
            }
        }

    /** 行业配置（合并式按报告期；部分基金无数据返回空——业务错与空同语义）。 */
    suspend fun getIndustryAllocation(fundCode: String): List<FuyaoFundIndustryItem> =
        cacheStore.fetchFirstMerge(
            "fundIndustry|$fundCode",
            fuyaoCacheTypeOf<List<FuyaoFundIndustryItem>>(),
            merge = { cached, fresh ->
                if (cached == null) fresh
                else (fresh + cached.filter { c -> c.reportPeriod !in fresh.map { it.reportPeriod } })
                    .sortedByDescending { it.reportPeriod }
            }
        ) {
            fetchFuyao("同花顺", "基金行业配置获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                fuyaoApi.getFundIndustryAllocation(fundType = "exchange", thscode = ths)
                    .data?.item.orEmpty()   // 业务错（无数据）与成功但空同语义
            }
        } ?: emptyList()

    suspend fun getAssetAllocation(fundCode: String): List<FuyaoAssetAllocationItem> =
        cacheStore.fetchFirstReplace("fundAsset|$fundCode", fuyaoCacheTypeOf<List<FuyaoAssetAllocationItem>>()) {
            fetchFuyao("同花顺", "基金资产配置获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                val envelope = fuyaoApi.getFundAssetAllocation(fundType = "exchange", thscode = ths)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty()
            }
        } ?: emptyList()

    suspend fun getDrawdowns(fundCode: String): FuyaoFundDrawdownItem? =
        cacheStore.fetchFirstReplace("fundDrawdown|$fundCode", fuyaoCacheTypeOf<FuyaoFundDrawdownItem>()) {
            fetchFuyao("同花顺", "基金最大回撤获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                val envelope = fuyaoApi.getFundDrawdowns(fundType = "exchange", thscode = ths)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item?.firstOrNull()
            }
        }

    suspend fun getReturns(fundCode: String): FuyaoFundReturnItem? =
        cacheStore.fetchFirstReplace("fundReturns|$fundCode", fuyaoCacheTypeOf<FuyaoFundReturnItem>()) {
            fetchFuyao("同花顺", "基金区间收益获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                val envelope = fuyaoApi.getFundReturns(fundType = "exchange", thscode = ths)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item?.firstOrNull()
            }
        }

    /** 净值序列（合并式按 nav_date：历史净值不可变，新日期追加；range 进缓存 key）。 */
    suspend fun getNav(
        fundCode: String,
        range: String? = null
    ): List<FuyaoFundNavItem> =
        cacheStore.fetchFirstMerge(
            "fundNav|$fundCode|${range ?: "latest"}",
            fuyaoCacheTypeOf<List<FuyaoFundNavItem>>(),
            merge = { cached, fresh ->
                if (cached == null) fresh
                else mergeByKey(
                    cached = cached,
                    fresh = fresh,
                    key = { it.navDateMs },
                    sort = { list -> list.sortedBy { it.navDateMs } }
                )
            }
        ) {
            fetchFuyao("同花顺", "基金净值获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                val envelope = fuyaoApi.getFundNav(fundType = "exchange", thscode = ths, range = range)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty()
            }
        } ?: emptyList()

    /** 持有人结构（合并式按报告期：历史期次永续保留）。 */
    suspend fun getHoldersDetail(
        fundCode: String,
        mergeScope: String = "all"
    ): List<FuyaoFundHolderItem> =
        cacheStore.fetchFirstMerge(
            "fundHolders|$fundCode|$mergeScope",
            fuyaoCacheTypeOf<List<FuyaoFundHolderItem>>(),
            merge = { cached, fresh ->
                if (cached == null) fresh
                else {
                    val freshKeys = fresh.map { it.reportDateMs to it.mergeScope }
                    (fresh + cached.filter { it.reportDateMs to it.mergeScope !in freshKeys })
                }
            }
        ) {
            fetchFuyao("同花顺", "基金持有人结构获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                val envelope = fuyaoApi.getFundHoldersDetail(
                    fundType = "exchange", thscode = ths, mergeScope = mergeScope
                )
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty()
            }
        } ?: emptyList()

    private suspend fun reportDates(
        key: String,
        fundCode: String,
        fetch: suspend () -> List<FuyaoFundReportDateItem>?
    ): List<FuyaoFundReportDateItem> =
        cacheStore.fetchFirstMerge(
            key,
            fuyaoCacheTypeOf<List<FuyaoFundReportDateItem>>(),
            merge = { cached, fresh ->
                if (cached == null) fresh
                else mergeByKey(
                    cached = cached,
                    fresh = fresh,
                    key = { it.endDateMs },
                    sort = { list -> list.sortedByDescending { it.endDateMs } }
                )
            }
        ) { fetch() } ?: emptyList()

    /** 股票持仓披露报告期（合并式：历史报告期永续保留）。 */
    suspend fun getStockReportDates(fundCode: String): List<FuyaoFundReportDateItem> =
        reportDates("fundStockReportDates|$fundCode", fundCode) {
            fetchFuyao("同花顺", "基金持仓报告期获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                val envelope = fuyaoApi.getFundStockReportDates(fundType = "exchange", thscode = ths)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty()
                // 禁用/失败返回 null 走 fetchFirstMerge 回退缓存（勿 ?: emptyList() 伪装成成功空）
            }
        }

    /** 债券持仓披露报告期（合并式）。 */
    suspend fun getBondReportDates(fundCode: String): List<FuyaoFundReportDateItem> =
        reportDates("fundBondReportDates|$fundCode", fundCode) {
            fetchFuyao("同花顺", "基金债券报告期获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                val envelope = fuyaoApi.getFundBondReportDates(fundType = "exchange", thscode = ths)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty()
                // 禁用/失败返回 null 走 fetchFirstMerge 回退缓存（勿 ?: emptyList() 伪装成成功空）
            }
        }

    // ── 原始透传端点（覆盖式缓存 + 失败回退；JsonObject 直接消费）──

    private suspend fun rawCached(key: String, fetch: suspend () -> JsonObject?): JsonObject? =
        cacheStore.fetchFirstReplace(key, fuyaoCacheTypeOf<JsonObject>()) { fetch() }

    suspend fun getStockHistory(fundCode: String): JsonObject? =
        rawCached("fundStockHistory|$fundCode") {
            fetchFuyao("同花顺", "基金历史股票持仓获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                fuyaoApi.getFundStockHistory(fundType = "exchange", thscode = ths)
                    .takeIf { it.isOk }?.data
            }
        }

    suspend fun getBondHistory(fundCode: String): JsonObject? =
        rawCached("fundBondHistory|$fundCode") {
            fetchFuyao("同花顺", "基金历史债券持仓获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                fuyaoApi.getFundBondHistory(fundType = "exchange", thscode = ths)
                    .takeIf { it.isOk }?.data
            }
        }

    /** 历史业绩指标（RSI/唐奇安/估值分位序列；start/end 毫秒必填）。 */
    suspend fun getPerformanceIndicators(
        fundCode: String,
        startMs: Long,
        endMs: Long
    ): JsonObject? =
        rawCached("fundPerfInd|$fundCode|$startMs") {
            fetchFuyao("同花顺", "基金历史业绩指标获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                fuyaoApi.getFundPerformanceIndicators(
                    fundType = "exchange", thscode = ths, startMs = startMs, endMs = endMs
                ).takeIf { it.isOk }?.data
            }
        }

    suspend fun getHoldersTop(fundCode: String): JsonObject? =
        rawCached("fundHoldersTop|$fundCode") {
            fetchFuyao("同花顺", "基金前十大持有人获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                fuyaoApi.getFundHoldersTop(fundType = "exchange", thscode = ths)
                    .takeIf { it.isOk }?.data
            }
        }

    suspend fun getManagerDetail(managerId: String): JsonObject? =
        rawCached("fundManager|$managerId") {
            fetchFuyao("同花顺", "基金经理详情获取失败（$managerId）") {
                fuyaoApi.getFundManagerDetail(managerId = managerId).takeIf { it.isOk }?.data
            }
        }

    suspend fun getManagerStyle(managerId: String): JsonObject? =
        rawCached("fundManagerStyle|$managerId") {
            fetchFuyao("同花顺", "基金经理风格获取失败（$managerId）") {
                fuyaoApi.getFundManagerStyle(managerId = managerId).takeIf { it.isOk }?.data
            }
        }

    suspend fun getManagerPerformance(managerId: String): JsonObject? =
        rawCached("fundManagerPerf|$managerId") {
            fetchFuyao("同花顺", "基金经理业绩获取失败（$managerId）") {
                fuyaoApi.getFundManagerPerformance(managerId = managerId).takeIf { it.isOk }?.data
            }
        }

    suspend fun getManagerExperience(managerId: String): JsonObject? =
        rawCached("fundManagerExp|$managerId") {
            fetchFuyao("同花顺", "基金经理从业经历获取失败（$managerId）") {
                fuyaoApi.getFundManagerExperience(managerId = managerId).takeIf { it.isOk }?.data
            }
        }

    suspend fun getCompanyDetail(companyId: String): JsonObject? =
        rawCached("fundCompany|$companyId") {
            fetchFuyao("同花顺", "基金公司详情获取失败（$companyId）") {
                fuyaoApi.getFundCompanyDetail(companyId = companyId).takeIf { it.isOk }?.data
            }
        }

    suspend fun getDiagnostics(fundCode: String): JsonObject? =
        rawCached("fundDiag|$fundCode") {
            fetchFuyao("同花顺", "基金诊断获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                fuyaoApi.getFundDiagnostics(fundType = "exchange", thscode = ths)
                    .takeIf { it.isOk }?.data
            }
        }

    suspend fun getNews(fundCode: String): JsonObject? =
        rawCached("fundNews|$fundCode") {
            fetchFuyao("同花顺", "基金资讯获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                fuyaoApi.getFundNews(fundType = "exchange", thscode = ths).takeIf { it.isOk }?.data
            }
        }

    /** 基金募集（subscribe=active 当前募集 / upcoming 即将募集）。 */
    suspend fun getOfferings(subscribe: String = "active"): JsonObject? =
        rawCached("fundOfferings|$subscribe") {
            fetchFuyao("同花顺", "基金募集列表获取失败") {
                fuyaoApi.getFundOfferings(subscribe = subscribe).takeIf { it.isOk }?.data
            }
        }

    suspend fun getIncomeStatements(fundCode: String): JsonObject? =
        rawCached("fundIncome|$fundCode") {
            fetchFuyao("同花顺", "基金利润表获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                fuyaoApi.getFundIncomeStatements(fundType = "exchange", thscode = ths)
                    .takeIf { it.isOk }?.data
            }
        }

    suspend fun getBalanceSheets(fundCode: String): JsonObject? =
        rawCached("fundBalance|$fundCode") {
            fetchFuyao("同花顺", "基金资产负债表获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                fuyaoApi.getFundBalanceSheets(fundType = "exchange", thscode = ths)
                    .takeIf { it.isOk }?.data
            }
        }

    suspend fun getFinancialIndicators(fundCode: String): JsonObject? =
        rawCached("fundFinInd|$fundCode") {
            fetchFuyao("同花顺", "基金财务指标获取失败（$fundCode）") {
                val ths = thscodeOf(fundCode) ?: return@fetchFuyao null
                fuyaoApi.getFundFinancialIndicators(fundType = "exchange", thscode = ths)
                    .takeIf { it.isOk }?.data
            }
        }
}
