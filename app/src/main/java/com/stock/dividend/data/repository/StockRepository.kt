package com.stock.dividend.data.repository

import com.stock.dividend.data.local.AppDatabase
import com.stock.dividend.data.local.dao.IndustryTargetDao
import com.stock.dividend.data.local.dao.PriceCacheDao
import com.stock.dividend.data.local.dao.SearchCacheDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.dao.StockTagDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.IndustryTargetEntity
import com.stock.dividend.data.local.entity.PriceCacheEntity
import com.stock.dividend.data.local.entity.SearchCacheEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.StockTagEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.remote.QuoteApi
import com.stock.dividend.data.remote.SearchApi
import com.stock.dividend.data.remote.FuyaoApi
import com.stock.dividend.data.remote.dto.FuyaoIndicatorIds
import com.stock.dividend.data.remote.dto.fuyaoMsToDateStringOrNull
import com.stock.dividend.data.remote.dto.indicatorValueOf
import com.stock.dividend.data.remote.dto.toFuyaoReportParamOrNull
import com.stock.dividend.data.remote.dto.toFuyaoThscodeOrNull
import com.stock.dividend.data.remote.dto.fuyaoThscodeToAppCodeOrNull
import com.stock.dividend.di.EastMoneyFundamentalApi
import androidx.room.withTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

/** 一条待导入的交易记录（截图/AI 视觉解析的行）。 */
data class TransactionImportRow(
    val rawCodeOrName: String,
    val type: String,       // "BUY" / "SELL"
    val shares: Int,
    val price: Double,
    val date: String        // yyyy-MM-dd
)

/** 交易记录批量导入结果。 */
data class TransactionImportSummary(
    val insertedCount: Int,     // 成功插入的笔数
    val duplicatesSkipped: Int, // 去重跳过的笔数（同股同日同向同价同股数）
    val failedRows: List<TransactionImportRow> // 解析/匹配失败的原始行
)

@Singleton
class StockRepository @Inject constructor(
    private val api: SearchApi,
    private val quoteApi: QuoteApi,
    private val fuyaoApi: FuyaoApi,
    private val fuyaoConfig: FuyaoConfig,
    @EastMoneyFundamentalApi private val fundamentalApi: com.stock.dividend.data.remote.FundamentalApi,
    private val stockDao: StockDao,
    private val transactionDao: TransactionDao,
    private val industryTargetDao: IndustryTargetDao,
    private val priceCacheDao: PriceCacheDao,
    private val searchCacheDao: SearchCacheDao,
    private val stockTagDao: StockTagDao,
    private val klineRepository: KlineRepository,
    private val appDatabase: AppDatabase,
    private val errorLogRepository: ErrorLogRepository,
) {
    suspend fun searchStocks(query: String): Result<List<StockSearchResult>> {
        return try {
            // 缓存优先：同一关键词命中则直接返回缓存（不发任何网络请求），用 price_cache 补价
            val queryKey = query.trim().lowercase()
            val cached = searchCacheDao.getByQuery(queryKey)
            if (cached.isNotEmpty()) {
                val codes = cached.map { it.code }
                val priceMap = getCachedPrices(codes)
                val results = cached.map { entity ->
                    StockSearchResult(
                        code = entity.code,
                        name = entity.name,
                        marketCode = entity.marketCode,
                        currentPrice = priceMap[entity.code]
                    )
                }
                return Result.success(results)
            }

            // 搜索主源：同花顺扶摇（asset_type 精确过滤场外基金）；失败或结果为空时东财兜底
            val fuyaoItems = if (fuyaoConfig.enabled) {
                runCatching { searchFromFuyao(query) }.getOrNull()
            } else null
            val items = if (!fuyaoItems.isNullOrEmpty()) fuyaoItems else searchFromEastMoney(query)

            // 写入搜索缓存（以小写 queryKey 为复用键），失败不阻塞主流程
            if (items.isNotEmpty()) {
                try {
                    val now = System.currentTimeMillis()
                    searchCacheDao.upsertAll(items.map {
                        SearchCacheEntity(
                            code = it.code,
                            queryKey = queryKey,
                            name = it.name,
                            marketCode = it.marketCode,
                            updatedAt = now
                        )
                    })
                } catch (_: Exception) { /* 缓存写入失败不影响搜索 */ }
            }

            // Batch fetch prices for search results（扶摇主源，东财 ulist 兜底）
            val pricedItems = if (items.isNotEmpty()) {
                try {
                    // 扶摇快照（股票批量 + 基金逐只），真实值口径无 ÷100/÷1000 规则
                    val fuyaoPrices = if (fuyaoConfig.enabled) {
                        runCatching {
                            fetchSnapshotsFromFuyao(items.map { it.code }).mapValues { it.value.price }
                        }.getOrNull()
                    } else null
                    // 扶摇失败/未配置 → 东财 ulist 兜底（价格除数随标的类型变：股票 ÷100、基金 ÷1000，§4.9）
                    val priceMap = fuyaoPrices ?: runCatching {
                        val secids = items.joinToString(",") { "${it.marketCode}.${it.code.substringAfter(".")}" }
                        quoteApi.getQuotes(secids = secids).data?.diff?.associate {
                            "${it.market}.${it.code}" to
                                it.price.divPriceScaleOrNull(FundDividendParser.isExchangeTradedFundCode(it.code))
                        } ?: emptyMap()
                    }.getOrDefault(emptyMap())
                    val priced = items.map { item ->
                        item.copy(currentPrice = priceMap[item.code])
                    }
                    // 同步把搜到的现价写入 price_cache（key 统一用 sh./sz. 格式）
                    cachePrices(priced.associate { it.code to it.currentPrice })
                    priced
                } catch (_: Exception) {
                    items // Return without prices if quote fetch fails
                }
            } else items

            Result.success(pricedItems)
        } catch (e: Exception) {
            Result.failure(Exception(e.toUserMessage(), e))
        }
    }

    /**
     * 扶摇标的检索（主源）。asset_type 限定 A股+ETF+LOF（场外基金不出结果），
     * 结果限沪深（BJ 与现有能力一致不支持）；同 thscode 偶有重复行
     * （fund-etf/fund-otc 并存，实测 2026-08-23）按代码去重后取前 10 条（与东财 count=10 同容量）。
     */
    private suspend fun searchFromFuyao(query: String): List<StockSearchResult> {
        val envelope = fuyaoApi.searchTickers(
            query = query,
            assetType = "a-share,fund-etf,fund-lof",
            limit = 20
        )
        check(envelope.isOk) { "扶摇搜索失败: code=${envelope.code} ${envelope.message}" }
        return envelope.data?.item.orEmpty()
            .filter { it.exchange == "SH" || it.exchange == "SZ" }
            .mapNotNull { item ->
                val appCode = item.thscode?.fuyaoThscodeToAppCodeOrNull() ?: return@mapNotNull null
                StockSearchResult(
                    code = appCode,
                    name = item.name.orEmpty(),
                    marketCode = if (item.exchange == "SH") "1" else "0"
                )
            }
            .distinctBy { it.code }
            .take(10)
    }

    /** 东财搜索（候补源，原主源逻辑）。A 股 + 场内基金（Classify 规则见函数内注释历史）。 */
    private suspend fun searchFromEastMoney(query: String): List<StockSearchResult> {
        val response = api.searchStocks(input = query)
        // A 股 + 场内基金（ETF/LOF）。场内基金实测口径（2026-08-22）：Classify="Fund" 且
        // MktNum 为 "1"(沪)/"0"(深)——与 A 股同市场规则（510300/159915/161907 均如此）；
        // 场外基金 Classify="OTCFUND"（MktNum="150"）不可行情交易，继续排除。
        return response.quotationCodeTable?.Data
            ?.filter { it.Classify == "AStock" || (it.Classify == "Fund" && (it.MktNum == "1" || it.MktNum == "0")) }
            ?.map { item ->
                StockSearchResult(
                    code = formatStockCode(item.MktNum, item.Code),
                    name = item.Name,
                    marketCode = item.MktNum
                )
            } ?: emptyList()
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

    /** 按代码读自选股实体（数据平面按代码取行情时解析用；不存在返回 null）。 */
    suspend fun getStock(code: String): StockEntity? {
        return try {
            stockDao.getByCode(code)
        } catch (_: Exception) {
            null
        }
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

    /**
     * 更新买入阈值倍数（股息率达到「10Y 国债 × 该倍数」时提示买入）。
     * 合法范围 [0.1, 20.0]，过小或过大无意义。
     */
    suspend fun updateBuyThresholdMultiplier(code: String, multiplier: Double) {
        stockDao.updateBuyThresholdMultiplier(code, multiplier.coerceIn(0.1, 20.0))
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
        // 数据平面收敛：与 fetchQuoteSnapshots 共用同一次请求（含写透 price_cache），只取现价
        return fetchQuoteSnapshots(stocks).mapNotNull { (code, snap) ->
            snap.price?.takeIf { it > 0.0 }?.let { code to it }
        }.toMap()
    }

    /**
     * 拉取 [stocks] 的完整行情快照（价格 + 涨跌 + PE/PB + 市值 + 换手/量比等）。
     *
     * 与 [fetchQuotes] 共用同一次请求（成本不变），只是把原本丢弃的 f3-f23 字段一并解析出来，
     * 供持仓评估、LLM 解读、卡片展示等场景使用（见 [QuoteSnapshot]）。网络失败返回空 map（红线 #2）。
     *
     * **写透缓存**（数据平面语义）：拉到的现价同步写入 price_cache——任何行情获取路径都更新缓存，
     * 保证 Widget/通知/Agent 回退路径读到的冷启动兜底价与主 UI 一致。
     *
     * @return key 为 App 内 `sh.XXXXXX`/`sz.XXXXXX` 格式，value 为 [QuoteSnapshot]；
     *         现价无效（null/≤0）的条目仍保留（price=null），调用方按可空处理。
     */
    suspend fun fetchQuoteSnapshots(stocks: List<StockEntity>): Map<String, QuoteSnapshot> {
        if (stocks.isEmpty()) return emptyMap()
        return try {
            // 同花顺扶摇（权威主源）与东财（候补）并行发起，不增加刷新时延：
            // - 扶摇成功：扶摇价格为权威值，东财仅回填扶摇缺失字段（市值/换手率/量比/振幅/PE/PB，
            //   见 QuoteSnapshot.supplementedFrom）；东财失败不影响主结果（缺失字段暂为 null）。
            // - 扶摇失败：东财结果直接作为降级结果（请求早已在飞，零额外等待），落日志可感知。
            // - 扶摇未配置 key：只走东财（现状路径）。
            coroutineScope {
                val fuyaoDeferred = if (fuyaoConfig.enabled) {
                    async {
                        runCatching { fetchSnapshotsFromFuyao(stocks.map { it.code }) }.getOrNull()
                    }
                } else null
                val emDeferred = async {
                    runCatching { fetchSnapshotsFromEastMoney(stocks) }.getOrNull()
                }

                val fuyao = fuyaoDeferred?.await()
                val em = emDeferred.await()

                val merged = when {
                    fuyao != null -> {
                        if (em == null) {
                            // 主源成功、补齐失败：不降级不报错，仅低噪埋点（60s 防抖）
                            errorLogRepository.record(
                                source = "行情",
                                message = "东财补齐失败（扶摇主源不受影响，缺失字段暂不展示）"
                            )
                        }
                        // 扶摇条目字段级补齐 + 扶摇未覆盖的代码用东财整条兜底（退市/新上市边界）
                        buildMap {
                            putAll(fuyao.mapValues { it.value.supplementedFrom(em?.get(it.key)) })
                            em?.forEach { (code, snap) -> if (!containsKey(code)) put(code, snap) }
                        }
                    }
                    em != null -> {
                        if (fuyaoConfig.enabled) {
                            errorLogRepository.record(
                                source = "同花顺",
                                message = "行情主源失败，已降级东财（${stocks.size} 只标的）"
                            )
                        }
                        em
                    }
                    else -> {
                        errorLogRepository.record(
                            source = "行情",
                            message = "行情获取失败（${stocks.size} 只标的）"
                        )
                        emptyMap()
                    }
                }
                // 拉到新价后写入 price_cache（后台刷新覆盖，缓存作冷启动兜底）
                cachePrices(merged.mapValues { it.value.price })
                merged
            }
        } catch (e: Exception) {
            // 静默失败落日志（设置 → 数据 → 失败日志）；返回空 map 走缓存兜底
            errorLogRepository.record(
                source = "行情",
                message = "行情获取失败（${stocks.size} 只标的）",
                throwable = e,
            )
            emptyMap()
        }
    }

    /** 东财 ulist 批量快照（候补源/未配置扶摇时的现状路径）。失败抛异常由调用方处理。 */
    private suspend fun fetchSnapshotsFromEastMoney(stocks: List<StockEntity>): Map<String, QuoteSnapshot> {
        val secids = stocks.joinToString(",") { stock ->
            "${stock.marketCode}.${stock.code.substringAfter(".")}"
        }
        val response = quoteApi.getQuotes(secids = secids)
        return response.data?.diff?.associateBy(
            keySelector = { item ->
                val prefix = if (item.market == 1) "sh" else "sz"
                "$prefix.${item.code}"
            },
            valueTransform = { toQuoteSnapshot(it) }
        ).orEmpty()
    }

    /**
     * 扶摇快照（权威主源）。股票走 A 股批量接口（一次），场内基金逐只走基金快照接口——
     * **必须拆分**：基金代码传入 A 股批量会让整批报 1002（实测 2026-08-23）。
     * 任一请求失败/信封非 0 抛异常 → 调用方整体降级东财。结果为空同样视为失败
     * （全部标的均未取到时让候补源接管）。
     */
    private suspend fun fetchSnapshotsFromFuyao(codes: List<String>): Map<String, QuoteSnapshot> =
        coroutineScope {
            val isFund = { code: String -> FundDividendParser.isExchangeTradedFundCode(code.substringAfter(".")) }
            val shareCodes = codes.filterNot(isFund)
            val fundCodes = codes.filter(isFund)

            val snapshots = mutableMapOf<String, QuoteSnapshot>()
            if (shareCodes.isNotEmpty()) {
                val thscodes = shareCodes.mapNotNull { it.toFuyaoThscodeOrNull() }
                    .takeIf { it.size == shareCodes.size }
                    ?: throw IllegalArgumentException("存在无法映射扶摇 thscode 的股票代码: $shareCodes")
                val envelope = fuyaoApi.getPriceSnapshot(thscodes = thscodes.joinToString(","))
                check(envelope.isOk) { "扶摇A股快照失败: code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty().forEach { item ->
                    toQuoteSnapshotFromFuyao(item)?.let { snapshots[it.stockCode] = it }
                }
            }
            fundCodes.map { fundCode ->
                async {
                    val thscode = fundCode.toFuyaoThscodeOrNull()
                        ?: throw IllegalArgumentException("基金代码无法映射扶摇 thscode: $fundCode")
                    val envelope = fuyaoApi.getFundSnapshot(thscode = thscode)
                    check(envelope.isOk) { "扶摇基金快照失败: $fundCode code=${envelope.code} ${envelope.message}" }
                    envelope.data?.item.orEmpty().forEach { item ->
                        toQuoteSnapshotFromFuyao(item)?.let { snapshots[it.stockCode] = it }
                    }
                }
            }.awaitAll()
            check(snapshots.isNotEmpty()) { "扶摇快照结果为空（${codes.size} 只标的）" }
            snapshots
        }

    /**
     * 读取 [codes] 的缓存价（冷启动兜底用）。仅返回有缓存的项。
     * key 格式与 [fetchQuotes] 一致：`sh.600036` / `sz.000001`。
     */
    suspend fun getCachedPrices(codes: List<String>): Map<String, Double> {
        if (codes.isEmpty()) return emptyMap()
        return try {
            priceCacheDao.getByCodes(codes).associate { it.code to it.price }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 把现价写入 price_cache（REPLACE 更新）。null 值跳过。
     * 供 [fetchQuotes] / [searchStocks] 在拉到新价后调用。
     */
    private suspend fun cachePrices(prices: Map<String, Double?>) {
        val valid = prices.filterValues { it != null && it > 0 }.mapValues { it.value!! }
        if (valid.isEmpty()) return
        try {
            val now = System.currentTimeMillis()
            priceCacheDao.upsertAll(valid.map { (code, price) ->
                PriceCacheEntity(code = code, price = price, updatedAt = now)
            })
        } catch (_: Exception) { /* 缓存写入失败不影响主流程 */ }
    }

    private fun formatStockCode(marketCode: String, code: String): String {
        val prefix = if (marketCode == "1") "sh" else "sz"
        return "$prefix.$code"
    }

    /**
     * 拉取单只股票的所属行业（东财一级行业，f127）并缓存到 [StockEntity.industry]。
     * 网络失败或返回空时不写入。
     */
    suspend fun fetchAndCacheIndustry(code: String) {
        val stock = stockDao.getByCode(code) ?: return
        val secid = "${stock.marketCode}.${stock.code.substringAfter(".")}"
        val response = try {
            quoteApi.getStockInfo(secid = secid)
        } catch (_: Exception) {
            return
        }
        val industry = response.data?.industry?.trim().orEmpty()
        if (industry.isNotEmpty()) {
            stockDao.updateIndustry(code, industry)
        }
    }

    // ---------- BOLL 带 ----------

    /**
     * 拉取 [stockCode] 指定 [period] 的收盘价并计算 BOLL 带（MA20 ± 2σ）。
     * 网络失败或收盘价不足 20 根返回 null。
     */
    suspend fun fetchBoll(stockCode: String, period: KlinePeriod = KlinePeriod.WEEKLY): BollBand? {
        val closes = try {
            klineRepository.fetchCloses(stockCode, period)
        } catch (_: Exception) {
            return null
        }
        return BollCalculator.calculate(closes)
    }

    /**
     * 拉取单股近 5 期主要财务指标（ROE / 负债率 / 营收净利同比）。
     *
     * 并发拉两个接口：主要财务指标（RPT_LICO_FN_CPD）+ 资产负债表（RPT_DMSK_FN_BALANCE），
     * 后者补全前者缺失的负债率（按报告期对齐，见 [FundamentalsBuilder]）。
     *
     * 职责分离：只解析财务摘要，**不**补全派息率（派息率需股息数据，由 VM 经 [enrichPayoutRatio] 补全，
     * 避免 Repository 间交叉注入）。任一接口失败降级为空（红线 #2），绝不崩 UI——财务指标接口失败则整体 null，
     * 资产负债表接口失败则仅负债率为 null。
     */
    suspend fun fetchFundamentals(stockCode: String): Fundamentals? {
        return try {
            val securityCode = stockCode.substringAfter(".")
            val filter = """(SECURITY_CODE="$securityCode")"""
            // 扶摇主源（股票）+ 东财并行补齐（dividendPlan 分红方案 / announceYield 公告股息率
            // 仅东财有）；扶摇失败整体降级东财；基金扶摇不覆盖直走东财。两源并发不增加时延。
            val fuyaoEnabled = fuyaoConfig.enabled &&
                !FundDividendParser.isExchangeTradedFund(stockCode)
            coroutineScope {
                val fuyaoDeferred = if (fuyaoEnabled) {
                    async { runCatching { fetchFundamentalsFromFuyao(stockCode) }.getOrNull() }
                } else null
                val emDeferred = async {
                    runCatching {
                        // 并发拉两接口；任一失败用 runCatching 兜底为空，不阻塞另一个
                        val finDeferred = async {
                            runCatching { fundamentalApi.getFundamentals(filter = filter) }.getOrNull()
                        }
                        val balDeferred = async {
                            runCatching { fundamentalApi.getBalanceSheet(filter = filter) }.getOrNull()
                        }
                        FundamentalsBuilder.build(
                            finDeferred.await()?.result?.data.orEmpty(),
                            balDeferred.await()?.result?.data.orEmpty()
                        )
                    }.getOrNull()
                }
                val fuyao = fuyaoDeferred?.await()
                val em = emDeferred.await()
                when {
                    fuyao != null -> Fundamentals(mergeFundamentalsPeriods(fuyao.periods, em?.periods.orEmpty()))
                    else -> em
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 扶摇财务摘要（主源）：利润表（quarterly 最新 5 期）提供报告期与基本 EPS，
     * 财务指标（单期接口）逐期并发提供 ROE/资产负债率/营收同比/归母净利同比。
     * 未披露期指标返回 5003 → 该期指标字段 null（东财补齐通道回填），期次保留（有 EPS）。
     */
    private suspend fun fetchFundamentalsFromFuyao(stockCode: String): Fundamentals {
        val thscode = stockCode.toFuyaoThscodeOrNull()
            ?: throw IllegalArgumentException("代码无法映射扶摇 thscode: $stockCode")
        val incomeEnvelope = fuyaoApi.getIncomeStatements(thscode = thscode, limit = 5)
        check(incomeEnvelope.isOk) { "扶摇利润表失败: code=${incomeEnvelope.code} ${incomeEnvelope.message}" }
        val incomeItems = incomeEnvelope.data?.item.orEmpty()
        check(incomeItems.isNotEmpty()) { "扶摇利润表无数据: $stockCode" }
        return Fundamentals(
            periods = coroutineScope {
                incomeItems.map { item ->
                    async {
                        val date = item.periodEndMs.fuyaoMsToDateStringOrNull()
                            ?: return@async null
                        val report = date.toFuyaoReportParamOrNull()
                        val indicators = report?.let {
                            runCatching {
                                fuyaoApi.getIndicators(thscode = thscode, report = it)
                            }.getOrNull()
                                ?.takeIf { envelope -> envelope.isOk }
                                ?.data
                        }
                        Fundamentals.Period(
                            reportDate = date,
                            roe = indicators.indicatorValueOf(FuyaoIndicatorIds.WEIGHTED_ROE),
                            debtToAssetRatio = indicators.indicatorValueOf(FuyaoIndicatorIds.ASSETS_DEBT_RATIO),
                            revenueYoy = indicators.indicatorValueOf(FuyaoIndicatorIds.REVENUE_YOY),
                            netProfitYoy = indicators.indicatorValueOf(FuyaoIndicatorIds.PARENT_NET_PROFIT_YOY),
                            basicEps = item.basicEps,
                            payoutRatio = null,   // 由 enrichPayoutRatio 补全
                            announceYield = null, // 东财补齐
                            dividendPlan = null   // 东财补齐
                        )
                    }
                }.awaitAll().filterNotNull().sortedBy { it.reportDate }
            }
        )
    }

    // ---------- 行业目标配比 ----------

    fun observeIndustryTargets(): Flow<List<IndustryTargetEntity>> =
        industryTargetDao.observeAll()

    suspend fun getIndustryTargets(): List<IndustryTargetEntity> =
        industryTargetDao.getAll()

    suspend fun updateIndustryTarget(industry: String, weight: Double) {
        industryTargetDao.upsert(
            IndustryTargetEntity(industry = industry, targetWeight = weight.coerceIn(0.0, 100.0))
        )
    }

    suspend fun deleteIndustryTarget(industry: String) {
        industryTargetDao.deleteByIndustry(industry)
    }

    // ---------- 股票标签 ----------

    /** 全量订阅所有 (code, tag)，ViewModel 据此算 tagsByCode 映射。 */
    fun observeAllStockTags(): Flow<List<StockTagEntity>> = stockTagDao.observeAll()

    /** 全局所有出现过的标签（去重排序），供 EditHolding 输入建议。 */
    fun observeAllTags(): Flow<List<String>> = stockTagDao.observeAllTags()

    /** 某只股票当前的所有标签（Flow，编辑页订阅用）。 */
    fun observeTagsForStock(code: String): Flow<List<String>> =
        stockTagDao.observeByStock(code).map { list -> list.map { it.tag } }

    /**
     * 全量覆盖某只股票的标签集合：事务内先 clear，再批量 insert。
     * 标签去空白并去重；空标签自动忽略。
     */
    suspend fun setStockTags(code: String, tags: List<String>) {
        val normalized = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        appDatabase.withTransaction {
            stockTagDao.clearForStock(code)
            normalized.forEach { tag ->
                stockTagDao.insert(StockTagEntity(stockCode = code, tag = tag))
            }
        }
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
        // sh.600519 / SH600519 / sz.000001 → 600519；名称与纯 6 位代码原样
        val normalized = PREFIXED_CODE_REGEX.matchEntire(trimmed)?.groupValues?.get(2) ?: trimmed
        val results = searchStocks(normalized).getOrDefault(emptyList())
        if (results.isEmpty()) return null
        val isNumericCode = normalized.matches(Regex("\\d{6}"))
        return if (isNumericCode) {
            results.firstOrNull { it.code.substringAfter(".") == normalized } ?: results.first()
        } else {
            results.first()
        }
    }

    private companion object {
        /** 带交易所前缀的 A 股代码：sh.600519 / SH600519 / sz 000001 等。 */
        val PREFIXED_CODE_REGEX = Regex("(?i)^(sh|sz)[.\\s]?(\\d{6})$")
    }

    /**
     * 重新计算某股票的 denormalized shares / costPerShare。
     * 统一调用 [HoldingCalculator.calculate]（移动加权平均），确保 AI 录入、
     * UI 编辑、备份恢复三条路径的成本算法永远一致。用于批量导入后修正已存在股票的缓存字段。
     */
    suspend fun recomputeHolding(stockCode: String) {
        val holding = HoldingCalculator.calculate(transactionDao.getByStock(stockCode))
        stockDao.updateShares(stockCode, holding.totalShares)
        stockDao.updateCostPerShare(stockCode, holding.avgCostPerShare)
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
        // 事务外补充行业信息（网络调用，失败不阻塞导入结果）
        succeeded.forEach { code ->
            try { fetchAndCacheIndustry(code) } catch (_: Exception) { /* 行业缺失不影响导入 */ }
        }
        return ImportSummary(succeeded = succeeded, failed = failed)
    }

    /**
     * 批量导入交易记录（截图/AI 视觉解析的行）。与 [importHoldings] 同风格：
     * 单 Room 事务内按日期升序逐行 [resolveStock] → 股票不存在则建自选（0 股，不产生初始交易）
     * → **五元组去重**（同股同日同向同价同股数已存在则跳过）→ 插入交易；涉及的股票最后
     * 统一 [recomputeHolding]。失败行不阻塞其他行。
     *
     * 按日期升序插入保证 FIFO 已实现盈亏与「同日按插入顺序」的口径和真实时间一致。
     */
    suspend fun importTransactions(rows: List<TransactionImportRow>): TransactionImportSummary {
        val inserted = mutableListOf<String>()          // 成功插入的股票 code（去重前口径，仅计数用）
        var duplicatesSkipped = 0
        val failedRows = mutableListOf<TransactionImportRow>()
        val touchedStocks = mutableSetOf<String>()
        appDatabase.withTransaction {
            rows.sortedBy { it.date }.forEach { row ->
                val resolved = try {
                    resolveStock(row.rawCodeOrName)
                } catch (_: Exception) {
                    null
                }
                if (resolved == null) {
                    failedRows.add(row)
                    return@forEach
                }
                try {
                    // FK 要求股票先存在：建自选（0 股、不插初始 BUY 交易），持仓完全由交易记录表达
                    if (stockDao.getByCode(resolved.code) == null) {
                        addStock(resolved, shares = 0, costPerShare = 0.0)
                    }
                    val existing = transactionDao.getByStock(resolved.code)
                    val isDuplicate = existing.any {
                        it.date == row.date && it.type == row.type &&
                            it.shares == row.shares && it.price == row.price
                    }
                    if (isDuplicate) {
                        duplicatesSkipped++
                    } else {
                        transactionDao.insert(
                            TransactionEntity(
                                stockCode = resolved.code,
                                type = row.type,
                                shares = row.shares,
                                price = row.price,
                                date = row.date,
                                note = "截图导入"
                            )
                        )
                        inserted.add(resolved.code)
                        touchedStocks.add(resolved.code)
                    }
                } catch (_: Exception) {
                    failedRows.add(row)
                }
            }
            touchedStocks.forEach { code -> recomputeHolding(code) }
        }
        return TransactionImportSummary(
            insertedCount = inserted.size,
            duplicatesSkipped = duplicatesSkipped,
            failedRows = failedRows
        )
    }
}
