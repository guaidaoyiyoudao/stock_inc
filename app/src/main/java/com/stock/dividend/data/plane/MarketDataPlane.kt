package com.stock.dividend.data.plane

import androidx.annotation.VisibleForTesting
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.BollCalculator
import com.stock.dividend.data.repository.CapitalFlow
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.DividendMetricsCalculator
import com.stock.dividend.data.repository.DragonTigerItem
import com.stock.dividend.data.repository.DragonTigerBoard
import com.stock.dividend.data.repository.ErrorLogRepository
import com.stock.dividend.data.repository.FinancialStatements
import com.stock.dividend.data.repository.FinancialStatementsRepository
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.Fundamentals
import com.stock.dividend.data.repository.FundamentalsCacheRepository
import com.stock.dividend.data.repository.FundDataRepository
import com.stock.dividend.data.repository.IndexQuote
import com.stock.dividend.data.repository.KlineBar
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.KlineRepository
import com.stock.dividend.data.repository.MarketDataRepository
import com.stock.dividend.data.repository.MarketListItem
import com.stock.dividend.data.repository.DividendMetrics
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.data.repository.ResearchRepository
import com.stock.dividend.data.repository.ResearchReport
import com.stock.dividend.data.repository.StockAnnouncement
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.StockSearchResult
import com.stock.dividend.data.repository.TreasuryYields
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.enrichPayoutRatio
import com.google.gson.JsonObject
import com.stock.dividend.data.remote.dto.FuyaoAssetAllocationItem
import com.stock.dividend.data.remote.dto.FuyaoFundDrawdownItem
import com.stock.dividend.data.remote.dto.FuyaoFundHolderItem
import com.stock.dividend.data.remote.dto.FuyaoFundHoldingsData
import com.stock.dividend.data.remote.dto.FuyaoFundIndustryItem
import com.stock.dividend.data.remote.dto.FuyaoFundNavItem
import com.stock.dividend.data.remote.dto.FuyaoFundProfileItem
import com.stock.dividend.data.remote.dto.FuyaoFundReportDateItem
import com.stock.dividend.data.remote.dto.FuyaoFundReturnItem
import com.stock.dividend.data.remote.dto.FuyaoHotStockItem
import com.stock.dividend.data.remote.dto.FuyaoTickerItem
import com.stock.dividend.data.remote.dto.FuyaoThsIndexItem
import com.stock.dividend.data.remote.dto.FuyaoValuationItem
import com.stock.dividend.data.remote.dto.toFuyaoThscodeOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # 数据平面（Market Data Plane）——股市数据获取的唯一入口
 *
 * 所有消费方（ViewModel / Agent 工具 / 通知 Worker / Widget / 编排协调器）获取外部股市数据
 * 一律经本门面，禁止直接注入行情类 Repository 或 Api（红线，见 AGENTS.md §数据平面）。
 *
 * 分层（自上而下）：
 * 1. **内存会话缓存 + 并发去重**：行情 10s 新鲜度窗口、BOLL/市场数据 60s TTL、
 *    [InFlightMap] 合并同 key 并发请求——挡 combine 风暴与多页面/工具同时取数；
 * 2. **持久缓存**：price_cache / kline_cache / fundamentals_cache / dividends / search_cache 等
 *    （由下层各网络源 Repository 自行编排，本平面只补齐写透语义）；
 * 3. **真实网络请求**：现有行情类 Repository 收编为内部网络源。
 *
 * 职责边界：本平面只管「读 + 缓存」，不做任何业务写操作（加股/改持仓/交易/网格计划等留在原 Repository）。
 *
 * 统一语义（收敛此前多路径的不一致）：
 * - 行情：任何获取路径都**写透 price_cache**（修复主 UI 走 snapshots 不写缓存 → Widget 旧价）；
 * - 股息：`getDps` 自动 `ensureDividendsFresh`（表空/超 7 天自动拉网——修复网格页拿不到股息率的根因）；
 * - 当前股息率：唯一口径 = 最新年度 DPS ÷ 本平面现价（`getCurrentDividendYield`）；
 *   历史股息率曲线仍用 dividends 表除权时点快照，不受影响；
 * - BOLL：单一路径（K线仓 + BollCalculator），内置 Semaphore(3) 限流与内存缓存；
 * - 基本面：`getFundamentals` 返回**已补派息率**的产物（收敛 VM/工具/装配器 3 处重复 enrich）。
 */
@Singleton
class MarketDataPlane @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
    private val klineRepository: KlineRepository,
    private val fundamentalsCacheRepository: FundamentalsCacheRepository,
    private val financialStatementsRepository: FinancialStatementsRepository,
    private val marketDataRepository: MarketDataRepository,
    private val fundDataRepository: FundDataRepository,
    private val bondYieldRepository: BondYieldRepository,
    private val researchRepository: ResearchRepository,
    private val dividendFreshnessStore: DividendFreshnessStore,
    private val errorLogRepository: ErrorLogRepository,
) {
    /** 测试可替换的时钟（默认真实时间）。 */
    @VisibleForTesting
    @Volatile
    var nowProvider: () -> Long = System::currentTimeMillis

    // ── 会话缓存与并发设施 ────────────────────────────────

    /** 带时间戳的会话缓存条目。 */
    private class Timed<T>(val at: Long, val value: T)

    private val quoteSession = ConcurrentHashMap<String, Timed<QuoteSnapshot>>()
    private val quoteFlights = InFlightMap<Map<String, QuoteSnapshot>>()

    private val bollSession = ConcurrentHashMap<String, Timed<BollBand?>>()
    private val bollFlights = InFlightMap<BollBand?>()
    private val bollSemaphore = Semaphore(3)

    private val marketSession = ConcurrentHashMap<String, Timed<Any?>>()
    private val marketFlights = InFlightMap<Any?>()

    private val dividendFlights = InFlightMap<Unit>()

    // ══ 行情 ═══════════════════════════════════════════════

    /**
     * 批量获取行情快照（价格+涨跌+PE/PB+市值等）。内存窗口内复用、并发合并，
     * 任何成功获取都写透 price_cache（冷启动/Widget/通知兜底一致）。
     *
     * @param force true 绕过内存会话窗口强制发网（用户显式下拉刷新）；持久缓存仍只写不读——
     *              行情是实时数据，缓存价仅由 [cachedPrices] 显式读取
     */
    suspend fun getQuoteSnapshots(
        stocks: List<StockEntity>,
        force: Boolean = false
    ): Map<String, QuoteSnapshot> {
        if (stocks.isEmpty()) return emptyMap()
        val now = nowProvider()
        val stale = stocks.filter { s ->
            force || quoteSession[s.code]?.let { now - it.at > PlanePolicy.QUOTE_FRESH_MS } ?: true
        }.distinctBy { it.code }
        if (stale.isNotEmpty()) {
            // key 用排序后的代码串：不同页面同一批标的的并发获取合并为一次请求
            val ordered = stale.sortedBy { it.code }
            val key = ordered.joinToString(",") { it.code }
            val fetched = quoteFlights.run(key) {
                stockRepository.fetchQuoteSnapshots(ordered)
            }
            val fetchedAt = nowProvider()
            fetched.forEach { (code, snap) -> quoteSession[code] = Timed(fetchedAt, snap) }
        }
        return stocks.mapNotNull { s ->
            quoteSession[s.code]?.let { s.code to it.value }
        }.toMap()
    }

    /** 单股行情快照便捷入口（自选股不存在返回 null）。 */
    suspend fun getQuoteSnapshot(stockCode: String, force: Boolean = false): QuoteSnapshot? {
        val stock = stockRepository.getStock(stockCode) ?: return null
        return getQuoteSnapshots(listOf(stock), force)[stockCode]
    }

    /** 只要现价（>0 过滤；与原 fetchQuotes 语义一致，key 为 `sh.XXXXXX`/`sz.XXXXXX`）。 */
    suspend fun getPrices(stocks: List<StockEntity>, force: Boolean = false): Map<String, Double> =
        getQuoteSnapshots(stocks, force).mapNotNull { (code, snap) ->
            snap.price?.takeIf { it > 0.0 }?.let { code to it }
        }.toMap()

    /** 按代码批量取现价（内部解析自选股实体；非自选股跳过）。网格/通知等只持有代码的场景用。 */
    suspend fun getPricesForCodes(codes: List<String>, force: Boolean = false): Map<String, Double> {
        if (codes.isEmpty()) return emptyMap()
        val stocks = codes.distinct().mapNotNull { stockRepository.getStock(it) }
        return getPrices(stocks, force)
    }

    /** 纯读 price_cache（冷启动兜底/Widget 渲染），绝不发网络。 */
    suspend fun cachedPrices(codes: List<String>): Map<String, Double> =
        stockRepository.getCachedPrices(codes)

    /** 拉取单股所属行业并写入 [StockEntity.industry]（东财一级行业 f127）。 */
    suspend fun ensureIndustry(stockCode: String) {
        stockRepository.fetchAndCacheIndustry(stockCode)
    }

    // ══ 股息 ═══════════════════════════════════════════════

    /**
     * 确保该股分红数据新鲜：dividends 表空或距上次**成功**拉取超 [PlanePolicy.DIVIDEND_FRESH_MS]
     * → 自动发网络刷新（腾讯主源+东财回退，见 [DividendRepository.fetchAndCacheDividends]）。
     * 失败进入 [PlanePolicy.DIVIDEND_RETRY_BACKOFF_MS] 退避，防 combine 风暴反复打接口。
     * 同股并发调用经 [InFlightMap] 合并为一次请求。
     */
    suspend fun ensureDividendsFresh(stockCode: String) {
        dividendFlights.run(stockCode) {
            val now = nowProvider()
            val rows = runCatching { dividendRepository.getDividends(stockCode) }
                .getOrDefault(emptyList())
            val successAt = dividendFreshnessStore.lastSuccessAt(stockCode)
            val fresh = rows.isNotEmpty() && successAt > 0L &&
                now - successAt < PlanePolicy.DIVIDEND_FRESH_MS
            if (fresh) return@run
            if (now - dividendFreshnessStore.lastAttemptAt(stockCode) <
                PlanePolicy.DIVIDEND_RETRY_BACKOFF_MS
            ) return@run
            dividendFreshnessStore.markAttempt(stockCode, now)
            val result = dividendRepository.fetchAndCacheDividends(
                stockCode, stockCode.substringAfter(".")
            )
            if (result.isSuccess) {
                dividendFreshnessStore.markSuccess(stockCode, nowProvider())
            } else {
                // 静默失败落日志（设置 → 数据 → 失败日志），60s 防抖不刷屏
                errorLogRepository.record(
                    source = "分红",
                    message = "分红数据刷新失败（$stockCode）",
                    throwable = result.exceptionOrNull(),
                )
            }
        }
    }

    /**
     * 强制刷新分红（详情页手动刷新入口）：无条件发网，成功/失败都记账（成功刷新 7 天时钟，
     * 失败进入退避窗口）。
     */
    suspend fun refreshDividends(stockCode: String): Result<Unit> {
        dividendFreshnessStore.markAttempt(stockCode, nowProvider())
        val result = dividendRepository.fetchAndCacheDividends(
            stockCode, stockCode.substringAfter(".")
        )
        if (result.isSuccess) {
            dividendFreshnessStore.markSuccess(stockCode, nowProvider())
        } else {
            errorLogRepository.record(
                source = "分红",
                message = "分红数据刷新失败（$stockCode）",
                throwable = result.exceptionOrNull(),
            )
        }
        return result
    }

    /** 订阅该股分红记录（Room 响应式，网络刷新后会自动重发射）。 */
    fun observeDividends(stockCode: String): Flow<List<DividendEntity>> =
        dividendRepository.observeDividends(stockCode)

    /** 一次性读取该股分红记录（非 Flow，不触发刷新——需刷新语义用 [getDps]/[ensureDividendsFresh]）。 */
    suspend fun getDividends(stockCode: String): List<DividendEntity> =
        dividendRepository.getDividends(stockCode)

    /** 该股最新一条分红记录（无记录返回 null）。 */
    suspend fun getLatestDividend(stockCode: String): DividendEntity? =
        dividendRepository.getLatestDividend(stockCode)

    /** 全表分红记录观察（股息日历等跨股场景用）。 */
    fun observeAllDividends(): Flow<List<DividendEntity>> =
        dividendRepository.observeAllDividends()

    /** 全表有除权日的分红记录（今日信号/简报的倒计时用）。 */
    suspend fun getAllDividendsWithExDate(): List<DividendEntity> =
        dividendRepository.getAllWithExDate()

    /**
     * 最近一年每股现金分红（DPS，TTM 口径：最近 12 个月已除权分红合计，无近期除权时回退最新报告年
     * 合计——修复半年派息股中期/末期跨日历年被劈半的问题）：先确保分红数据新鲜（空/过期自动拉网），
     * 再按 [ForecastCalculator.latestYearlyCashPerShare] 计算。无分红数据返回 null。
     */
    suspend fun getDps(stockCode: String): Double? {
        runCatching { ensureDividendsFresh(stockCode) }
        val dividends = runCatching { dividendRepository.getDividends(stockCode) }
            .getOrDefault(emptyList())
        return ForecastCalculator.latestYearlyCashPerShare(dividends)?.takeIf { it > 0.0 }
    }

    /**
     * 当前股息率（**全 App 唯一口径**）：最新年度 DPS ÷ 现价 × 100。
     * 现价优先取本平面行情快照，无自选股记录时回退 price_cache；两者皆无返回 null。
     */
    suspend fun getCurrentDividendYield(stockCode: String): Double? {
        val dps = getDps(stockCode) ?: return null
        val price = getQuoteSnapshot(stockCode)?.price?.takeIf { it > 0.0 }
            ?: cachedPrices(listOf(stockCode))[stockCode]?.takeIf { it > 0.0 }
        return price?.let { dps / it * 100.0 }
    }

    /** 分红深度指标（连续年数/CAGR/稳定性；纯本地计算，不发网络）。 */
    fun getDividendMetrics(dividends: List<DividendEntity>): DividendMetrics? =
        DividendMetricsCalculator.calculate(dividends)

    // ══ BOLL / K线 ═════════════════════════════════════════

    /**
     * 指定周期的 BOLL 带（MA20 ± 2σ）。**单一路径**：K线仓（Room 永久缓存+每日增量）→
     * [BollCalculator]。内置 Semaphore(3) 限流（腾讯接口高频拒连，红线 #5）与 60s 内存缓存
     * （今日页/组合页/简报/通知共享，收敛各消费方自建缓存）。收盘价不足 20 根返回 null。
     */
    suspend fun getBoll(
        stockCode: String,
        period: KlinePeriod = KlinePeriod.WEEKLY
    ): BollBand? {
        val key = "$stockCode|${period.name}"
        val cached = bollSession[key]
        if (cached != null && nowProvider() - cached.at < PlanePolicy.BOLL_TTL_MS) {
            return cached.value
        }
        return bollFlights.run(key) {
            val closes = bollSemaphore.withPermit {
                runCatching { klineRepository.fetchCloses(stockCode, period) }
                    .onFailure {
                        errorLogRepository.record(
                            source = "K线",
                            message = "${period.chineseName()}线数据获取失败（$stockCode）",
                            throwable = it,
                        )
                    }
                    .getOrDefault(emptyList())
            }
            BollCalculator.calculate(closes).also { bollSession[key] = Timed(nowProvider(), it) }
        }
    }

    /** 完整 OHLCV K 线（前复权；Room 永久缓存编排由 K线仓负责）。 */
    suspend fun getKlines(
        stockCode: String,
        period: KlinePeriod,
        bars: Int = KlineRepository.DEFAULT_BARS,
        forceRefresh: Boolean = false
    ): List<KlineBar> = klineRepository.fetchKlines(stockCode, period, bars, forceRefresh)

    // ══ 基本面 / 财务三表 ══════════════════════════════════

    /**
     * 单股主要财务指标（ROE/负债率/营收净利同比 + **已补派息率**）。
     * 7 天缓存 + 报告期合并由 [FundamentalsCacheRepository] 编排；派息率用本地分红按报告期
     * 对齐补全（原 VM/工具/装配器 3 处重复实现收敛于此）。
     */
    suspend fun getFundamentals(
        stockCode: String,
        forceRefresh: Boolean = false
    ): Fundamentals? {
        val raw = runCatching { fundamentalsCacheRepository.getFundamentals(stockCode, forceRefresh) }
            .onFailure {
                errorLogRepository.record(
                    source = "基本面",
                    message = "基本面数据获取失败（$stockCode）",
                    throwable = it,
                )
            }
            .getOrNull() ?: return null
        val dividends = runCatching { dividendRepository.getDividends(stockCode) }
            .getOrDefault(emptyList())
        return enrichPayoutRatio(raw, dividends.cashPerShareByDividendYear())
    }

    /** 财务三表全量（利润/现金流/资产负债；7 天缓存 + 报告期合并）。 */
    suspend fun getFinancialStatements(
        stockCode: String,
        forceRefresh: Boolean = false
    ): FinancialStatements? =
        runCatching { financialStatementsRepository.getFinancialStatements(stockCode, forceRefresh) }
            .onFailure {
                errorLogRepository.record(
                    source = "财务三表",
                    message = "财务报表获取失败（$stockCode）",
                    throwable = it,
                )
            }
            .getOrNull()

    /**
     * 用当前本地分红重算 [fundamentals] 各期派息率（幂等：可对已 enrich 的产物重复调用）。
     * 详情页订阅到分红更新时调用，保证派息率随分红数据响应式刷新，无需重读 7 天缓存。
     */
    suspend fun enrichFundamentals(fundamentals: Fundamentals, stockCode: String): Fundamentals {
        val dividends = runCatching { dividendRepository.getDividends(stockCode) }
            .getOrDefault(emptyList())
        return enrichPayoutRatio(fundamentals, dividends.cashPerShareByDividendYear())
    }

    // ══ 市场（指数/板块/榜单/资金流/龙虎榜；60s 内存缓存） ══

    /** 四大指数等指数现价快照（stock/get，÷100 已处理）。 */
    suspend fun getIndexQuotes(): List<IndexQuote> =
        cachedMarket("indexQuotes") { marketDataRepository.fetchIndexQuotes() }

    /** 指数/ETF 单标的现价（code6 = 6 位代码）。 */
    suspend fun getIndexOrEtfQuote(code6: String): IndexQuote? =
        cachedMarket("indexOrEtf|$code6") { marketDataRepository.fetchIndexOrEtfQuote(code6) }

    /** 板块列表（fs=m:90+t:2，按涨跌/主力净流入/换手排序）。 */
    suspend fun getIndustryList(
        sortBy: MarketDataRepository.SortBy = MarketDataRepository.SortBy.CHANGE,
        limit: Int = 15
    ): List<MarketListItem> =
        cachedMarket("industryList|${sortBy.name}|$limit") {
            marketDataRepository.fetchIndustryList(sortBy, limit)
        }

    /** 同行业个股（传板块代码 BKxxxx 或股票 code）。 */
    suspend fun getIndustryPeers(
        industryCodeOrStockCode: String,
        sortBy: MarketDataRepository.PeerSortBy = MarketDataRepository.PeerSortBy.MARKET_CAP,
        limit: Int = 15
    ): List<MarketListItem> =
        cachedMarket("industryPeers|$industryCodeOrStockCode|${sortBy.name}|$limit") {
            marketDataRepository.fetchIndustryPeers(industryCodeOrStockCode, sortBy, limit)
        }

    /** 全市场 A 股榜单（含 f133 股息率；候选集口径见 [MarketDataRepository.fetchMarketRanking] note）。 */
    suspend fun getMarketRanking(
        sortBy: MarketDataRepository.RankingSortBy = MarketDataRepository.RankingSortBy.DIVIDEND_YIELD,
        minDividendYield: Double? = null,
        maxPe: Double? = null,
        limit: Int = 20
    ): List<MarketListItem> =
        cachedMarket("ranking|${sortBy.name}|$minDividendYield|$maxPe|$limit") {
            marketDataRepository.fetchMarketRanking(sortBy, minDividendYield, maxPe, limit)
        }

    /** 个股资金流向（clist 全套净额/占比；stock/get 字段不全不可用，见 §4.9.3）。 */
    suspend fun getCapitalFlow(stockCode: String): CapitalFlow? =
        cachedMarket("capitalFlow|$stockCode") { marketDataRepository.fetchCapitalFlow(stockCode) }

    /** 龙虎榜明细。 */
    suspend fun getDragonTiger(
        stockCode: String? = null,
        limit: Int = 20
    ): List<DragonTigerItem> =
        cachedMarket("dragonTiger|$stockCode|$limit") {
            marketDataRepository.fetchDragonTiger(stockCode, limit)
        }

    // ══ 国债收益率 ═════════════════════════════════════════

    /** 10Y 国债收益率（%）：内存 → 24h prefs → 远程 → 旧缓存 → 默认值（原样透传，已是样板）。 */
    suspend fun get10YBondYield(forceRefresh: Boolean = false): Double =
        bondYieldRepository.fetch10YBondYield(forceRefresh)

    /** 多期限国债 + 中美利差 + LPR 全集（%）。 */
    suspend fun getAllYields(): TreasuryYields = bondYieldRepository.fetchAllYields()

    // ══ 研报 / 公告 ════════════════════════════════════════

    suspend fun getResearchReports(
        code6: String,
        limit: Int = 10,
        recentDays: Int = 1095
    ): List<ResearchReport> = researchRepository.fetchReports(code6, limit, recentDays)

    suspend fun getAnnouncements(code6: String, limit: Int = 10): List<StockAnnouncement> =
        researchRepository.fetchAnnouncements(code6, limit)

    // ══ 搜索 / 实体 ════════════════════════════════════════

    /** 搜索股票（search_cache 命中零网络；结果补现价并写 price_cache）。 */
    suspend fun searchStocks(query: String): Result<List<StockSearchResult>> =
        stockRepository.searchStocks(query)

    /** 解析原始代码/名称为股票（搜索缓存 + 网络）。 */
    suspend fun resolveStock(rawCodeOrName: String): StockSearchResult? =
        stockRepository.resolveStock(rawCodeOrName)

    /** 读自选股实体（行情按代码获取时内部解析用；不属于行情数据，仅为消费方免直注 StockRepository）。 */
    suspend fun getStock(stockCode: String): StockEntity? = stockRepository.getStock(stockCode)

    // ══ 本地库观察透传 ═════════════════════════════════════
    // 非网络数据（用户自选股/持仓的 Room 响应式流），透传仅为让只读页面免于同时注入
    // MarketDataPlane + StockRepository 两个入口；写操作（改持仓等）仍走 StockRepository。

    /** 观察全量自选股（含持仓 shares/costPerShare）。 */
    fun observeAllStocks(): Flow<List<StockEntity>> = stockRepository.observeAllStocks()

    /** 观察单只自选股。 */
    fun observeStock(stockCode: String): Flow<StockEntity?> = stockRepository.observeStock(stockCode)

    // ── 内部工具 ──────────────────────────────────────────

    /**
     * 清空全部内存会话缓存（行情/BOLL/市场）。缓存管理清理持久缓存后由
     * [com.stock.dividend.viewmodel.CacheManagementViewModel] 联动调用——
     * 否则 10s/60s 窗口内仍会读到已清库前的旧内存值。网络源与持久缓存不受影响。
     */
    // ══ 扶摇独有能力（2026-08-23 全量接入；东财/腾讯无对应，禁用时返回空/null）═══════
    // 市场类（榜单/池子/目录等）走 cachedMarket 60s 缓存；单实体类（基金资料/持仓等）
    // 直通（数据为定期披露的低频数据，无需会话缓存）。返回的 DTO 为扶摇真实值口径
    // （§4.9.5），唯一换算点在 DragonTigerBoard（change/net_rate 小数分数 ×100）。

    /** A 股估值快照（批量 PE_TTM/PE_MRQ/PB/PS/PCF）。key 为 App 代码。60s 缓存。 */
    suspend fun getValuations(codes: List<String>): Map<String, FuyaoValuationItem> =
        cachedMarket("valuations|${codes.sorted().joinToString(",")}") {
            marketDataRepository.fetchValuations(codes)
        }

    /** 交易日历（近一年 `yyyy-MM-dd` 升序；节假日守卫/回测对齐用）。60s 缓存。 */
    suspend fun getTradingDays(): List<String> =
        cachedMarket("tradingDays") { marketDataRepository.fetchTradingDays() }

    /** 扶摇龙虎榜榜单（board_type=all/org/hot_money；date 缺省最近交易日）。60s 缓存。 */
    suspend fun getDragonTigerBoard(boardType: String = "all", date: String? = null): DragonTigerBoard? =
        cachedMarket("dragonTigerBoard|$boardType|$date") {
            marketDataRepository.fetchDragonTigerBoard(boardType, date)
        }

    /** 涨停股票池（情绪温度计）。60s 缓存。 */
    suspend fun getLimitUpPool(dateMs: Long? = null, page: Int = 1, size: Int = 50) =
        cachedMarket("limitUp|$dateMs|$page|$size") {
            marketDataRepository.fetchLimitUpPool(dateMs, page, size)
        }

    /** 跌停股票池。60s 缓存。 */
    suspend fun getLimitDownPool(dateMs: Long? = null, page: Int = 1, size: Int = 50) =
        cachedMarket("limitDown|$dateMs|$page|$size") {
            marketDataRepository.fetchLimitDownPool(dateMs, page, size)
        }

    /** 炸板股票池。60s 缓存。 */
    suspend fun getLimitBreakPool(dateMs: Long? = null, page: Int = 1, size: Int = 50) =
        cachedMarket("limitBreak|$dateMs|$page|$size") {
            marketDataRepository.fetchLimitBreakPool(dateMs, page, size)
        }

    /** 连板天梯（近 30 交易日梯队矩阵，原始 JSON）。60s 缓存。 */
    suspend fun getLimitUpLadder(): JsonObject? =
        cachedMarket("limitLadder") { marketDataRepository.fetchLimitUpLadder() }

    /** A 股热股榜 Top30（period=day 24小时级/hour 小时级）。60s 缓存。 */
    suspend fun getHotStockList(period: String = "day"): List<FuyaoHotStockItem> =
        cachedMarket("hotStock|$period") { marketDataRepository.fetchHotStockList(period) }

    /** 热度飙升榜 Top30。60s 缓存。 */
    suspend fun getSkyrocketList(period: String = "day"): List<FuyaoHotStockItem> =
        cachedMarket("skyrocket|$period") { marketDataRepository.fetchSkyrocketList(period) }

    /** 历史热股榜（按自然日）。60s 缓存。 */
    suspend fun getHotStockHistory(date: String): List<FuyaoHotStockItem> =
        cachedMarket("hotHistory|$date") { marketDataRepository.fetchHotStockListHistory(date) }

    /** 个股热度排名走势（原始 JSON）。60s 缓存。 */
    suspend fun getHotStockRankTrend(thscode: String, startDate: String, endDate: String): JsonObject? =
        cachedMarket("rankTrend|$thscode|$startDate|$endDate") {
            marketDataRepository.fetchHotStockRankTrend(thscode, startDate, endDate)
        }

    /** 个股异动原因列表（原始 JSON；tag 过滤）。60s 缓存。 */
    suspend fun getAnomalyList(tagCodes: String? = null): JsonObject? =
        cachedMarket("anomaly|$tagCodes") { marketDataRepository.fetchAnomalyList(tagCodes) }

    /** 按股票批量查当日异动原因（原始 JSON，传 App 代码列表）。60s 缓存。 */
    suspend fun getAnomalyReasons(codes: List<String>): JsonObject? {
        val thscodes = codes.mapNotNull { it.toFuyaoThscodeOrNull() }
        if (thscodes.isEmpty()) return null
        return cachedMarket("anomalyStock|${thscodes.joinToString(",")}") {
            marketDataRepository.fetchAnomalyByStock(thscodes.joinToString(","))
        }
    }

    /** 集合竞价快照（原始 JSON；stage=live/final，传 App 代码列表）。60s 缓存。 */
    suspend fun getAuctionSnapshot(codes: List<String>, stage: String = "final"): JsonObject? {
        val thscodes = codes.mapNotNull { it.toFuyaoThscodeOrNull() }
        if (thscodes.isEmpty()) return null
        return cachedMarket("auction|$stage|${thscodes.joinToString(",")}") {
            marketDataRepository.fetchAuctionSnapshot(thscodes.joinToString(","), stage)
        }
    }

    /** 短线风向标竞价基准（原始 JSON）。60s 缓存。 */
    suspend fun getShortTermBenchmark(date: String? = null): JsonObject? =
        cachedMarket("benchmark|$date") { marketDataRepository.fetchShortTermBenchmark(date) }

    /** 同花顺指数清单（tag=cn_concept/region/tszs/industry）。60s 缓存。 */
    suspend fun getThsIndexList(tag: String = "cn_concept"): List<FuyaoThsIndexItem> =
        cachedMarket("thsIndex|$tag") { marketDataRepository.fetchThsIndexList(tag) }

    /** 指数成分股（thscode 如 886042.TI / 000300.SH）。60s 缓存。 */
    suspend fun getIndexConstituents(thscode: String): List<FuyaoTickerItem> =
        cachedMarket("constituents|$thscode") { marketDataRepository.fetchIndexConstituents(thscode) }

    /** 指数日K（无复权；大盘/行业走势图用）。60s 缓存。 */
    suspend fun getIndexDailyBars(thscode: String, days: Int = 400): List<KlineBar> =
        cachedMarket("indexBars|$thscode|$days") { marketDataRepository.fetchIndexDailyBars(thscode, days) }

    /** 全量代码表（asset_type=a-share/fund-etf/fund-lof 等）。60s 缓存。 */
    suspend fun getTickerList(assetType: String? = null, limit: Int = 1000, offset: Int = 0): List<FuyaoTickerItem> =
        cachedMarket("tickerList|$assetType|$limit|$offset") {
            marketDataRepository.fetchTickerList(assetType, limit, offset)
        }

    // ── 基金扩展数据（扶摇独有；输入 App 代码 sh.510880，fund_type 恒 exchange）──

    /** 扶摇基金域能否使用（key 已配置）。UI 可据此提示配置入口。 */
    fun isFundDataEnabled(): Boolean = fundDataRepository.isEnabled()

    /** 基金基本资料（规模/成立/管理人/经理任职/费率）。 */
    suspend fun getFundProfile(fundCode: String): FuyaoFundProfileItem? =
        fundDataRepository.getProfile(fundCode)

    /** 基金重仓持仓（定期披露；含股票仓位/集中度/主营行业汇总）。 */
    suspend fun getFundHoldings(fundCode: String): FuyaoFundHoldingsData? =
        fundDataRepository.getHoldings(fundCode)

    /** 基金行业配置（部分基金无数据返回空表）。 */
    suspend fun getFundIndustryAllocation(fundCode: String): List<FuyaoFundIndustryItem> =
        fundDataRepository.getIndustryAllocation(fundCode)

    /** 基金资产配置（股票/债券/存款/其他比例）。 */
    suspend fun getFundAssetAllocation(fundCode: String): List<FuyaoAssetAllocationItem> =
        fundDataRepository.getAssetAllocation(fundCode)

    /** 多周期最大回撤矩阵。 */
    suspend fun getFundDrawdowns(fundCode: String): FuyaoFundDrawdownItem? =
        fundDataRepository.getDrawdowns(fundCode)

    /** 区间收益（含同类平均）。 */
    suspend fun getFundReturns(fundCode: String): FuyaoFundReturnItem? =
        fundDataRepository.getReturns(fundCode)

    /** 净值序列（range 缺省最新一条）。 */
    suspend fun getFundNav(fundCode: String, range: String? = null): List<FuyaoFundNavItem> =
        fundDataRepository.getNav(fundCode, range)

    /** 持有人结构（机构/个人占比等）。 */
    suspend fun getFundHoldersDetail(fundCode: String, mergeScope: String = "all"): List<FuyaoFundHolderItem> =
        fundDataRepository.getHoldersDetail(fundCode, mergeScope)

    /** 股票持仓披露报告期。 */
    suspend fun getFundStockReportDates(fundCode: String): List<FuyaoFundReportDateItem> =
        fundDataRepository.getStockReportDates(fundCode)

    /** 历史股票持仓（原始 JSON）。 */
    suspend fun getFundStockHistory(fundCode: String): JsonObject? =
        fundDataRepository.getStockHistory(fundCode)

    /** 历史债券持仓（原始 JSON）。 */
    suspend fun getFundBondHistory(fundCode: String): JsonObject? =
        fundDataRepository.getBondHistory(fundCode)

    /** 债券持仓披露报告期。 */
    suspend fun getFundBondReportDates(fundCode: String): List<FuyaoFundReportDateItem> =
        fundDataRepository.getBondReportDates(fundCode)

    /** 历史业绩指标序列（RSI/唐奇安/估值分位；start/end 毫秒）。 */
    suspend fun getFundPerformanceIndicators(fundCode: String, startMs: Long, endMs: Long): JsonObject? =
        fundDataRepository.getPerformanceIndicators(fundCode, startMs, endMs)

    /** 前十大持有人（原始 JSON）。 */
    suspend fun getFundHoldersTop(fundCode: String): JsonObject? =
        fundDataRepository.getHoldersTop(fundCode)

    /** 基金经理详情/风格/业绩/从业经历（原始 JSON，manager_id 来自资料或持仓数据）。 */
    suspend fun getFundManagerDetail(managerId: String): JsonObject? =
        fundDataRepository.getManagerDetail(managerId)

    suspend fun getFundManagerStyle(managerId: String): JsonObject? =
        fundDataRepository.getManagerStyle(managerId)

    suspend fun getFundManagerPerformance(managerId: String): JsonObject? =
        fundDataRepository.getManagerPerformance(managerId)

    suspend fun getFundManagerExperience(managerId: String): JsonObject? =
        fundDataRepository.getManagerExperience(managerId)

    /** 基金公司详情（原始 JSON）。 */
    suspend fun getFundCompanyDetail(companyId: String): JsonObject? =
        fundDataRepository.getCompanyDetail(companyId)

    /** 基金诊断（多维评分，原始 JSON）。 */
    suspend fun getFundDiagnostics(fundCode: String): JsonObject? =
        fundDataRepository.getDiagnostics(fundCode)

    /** 基金资讯列表（原始 JSON）。 */
    suspend fun getFundNews(fundCode: String): JsonObject? =
        fundDataRepository.getNews(fundCode)

    /** 基金募集列表（subscribe=active/upcoming，原始 JSON）。 */
    suspend fun getFundOfferings(subscribe: String = "active"): JsonObject? =
        fundDataRepository.getOfferings(subscribe)

    /** 基金利润表/资产负债表/财务指标（原始 JSON）。 */
    suspend fun getFundIncomeStatements(fundCode: String): JsonObject? =
        fundDataRepository.getIncomeStatements(fundCode)

    suspend fun getFundBalanceSheets(fundCode: String): JsonObject? =
        fundDataRepository.getBalanceSheets(fundCode)

    suspend fun getFundFinancialIndicators(fundCode: String): JsonObject? =
        fundDataRepository.getFinancialIndicators(fundCode)

    fun clearSessionCaches() {
        quoteSession.clear()
        bollSession.clear()
        marketSession.clear()
    }

    /** 市场类数据的短 TTL 内存缓存 + 并发合并（key 含全部参数保证不同参数互不串）。 */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cachedMarket(key: String, fetch: suspend () -> T): T {
        val cached = marketSession[key]
        if (cached != null && nowProvider() - cached.at < PlanePolicy.MARKET_TTL_MS) {
            return cached.value as T
        }
        // 缓存值按 Any? 存取（fetch 可能返回可空类型），出口统一非受检转回 T
        return marketFlights.run(key) {
            fetch().also { marketSession[key] = Timed(nowProvider(), it) }
        } as T
    }
}

/**
 * 分红年度（reportDate 前 4 位）→ 该年每股分红**合计**。
 * 半年派息股中期+末期必须合计后才能对年报 EPS 算派息率（见 [enrichPayoutRatio] 口径说明）；
 * 腾讯源 `nd` 与东财真实报告期按年份归一后口径统一。无效年份/非正金额剔除（不臆造）。
 */
internal fun List<DividendEntity>.cashPerShareByDividendYear(): Map<Int, Double> =
    mapNotNull { d ->
        val year = d.reportDate.take(4).toIntOrNull() ?: return@mapNotNull null
        if (d.cashPerShare <= 0.0 || !d.cashPerShare.isFinite()) return@mapNotNull null
        year to d.cashPerShare
    }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.sum() }

/** K 线周期中文名（失败日志 message 用）。 */
private fun KlinePeriod.chineseName(): String = when (this) {
    KlinePeriod.DAILY -> "日"
    KlinePeriod.WEEKLY -> "周"
    KlinePeriod.MONTHLY -> "月"
}
