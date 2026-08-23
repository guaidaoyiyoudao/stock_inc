package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.MarketApi
import com.stock.dividend.data.remote.dto.FuyaoPriceItem
import com.stock.dividend.data.remote.dto.IndexQuoteResponse
import com.stock.dividend.data.remote.dto.MarketClistResponse
import com.stock.dividend.data.remote.dto.fuyaoMsToDateStringOrNull
import com.stock.dividend.data.remote.dto.fuyaoThscodeToAppCodeOrNull
import com.stock.dividend.data.remote.dto.toFuyaoThscodeOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 个股资金流向快照（已转单位）。净额为「元」真实值；占比为「%」真实值（如 6.04 = 6.04%）。
 * 数据源 clist（fltt=2），单位规则：全部真实值不 ÷100。
 * 各字段可空——停牌/异常时缺失，调用方按可空处理（红线 #2）。
 */
data class CapitalFlow(
    val mainNetInflow: Double?,
    val mainNetInflowPct: Double?,
    val superLargeNetInflow: Double?,
    val largeNetInflow: Double?,
    val mediumNetInflow: Double?,
    val smallNetInflow: Double?,
    val superLargeNetInflowPct: Double?,
    val largeNetInflowPct: Double?,
    val mediumNetInflowPct: Double?,
    val smallNetInflowPct: Double?
)

/**
 * 行业板块或行业内个股的行情指标项（已转单位）。
 * 数据源 clist（fltt=2）：价格/百分比/PE/PB/资金净额均为真实值，不 ÷100。
 */
data class MarketListItem(
    val code: String?,       // 板块代码 BKxxxx / 股票 6 位代码
    val name: String?,
    val price: Double?,
    val changePct: Double?,
    val pe: Double?,
    val pb: Double?,
    val totalMarketCap: Double?,
    val turnoverRate: Double?,
    val industry: String?,
    val mainNetInflow: Double?,
    val mainNetInflowPct: Double?,
    val leaderName: String?,
    val leaderCode: String?,
    val leaderChangePct: Double?,
    /** 股息率（%，clist f133 真实值）。仅全市场榜单请求带该字段，其余场景 null。 */
    val dividendYield: Double? = null
)

/**
 * 指数现价快照（已 ÷100 转单位）。
 * 数据源 stock/get：f43/f44/f45/f46/f60/f170 为 ×100 整数，已 ÷100；f48 成交额（元）原值。
 */
data class IndexQuote(
    val code: String,
    val name: String?,
    val price: Double?,
    val changePct: Double?,
    val prevClose: Double?,
    val high: Double?,
    val low: Double?,
    val open: Double?,
    val amount: Double?
)

/** 龙虎榜个股明细。netBuy/billboardDealAmt 单位「元」。 */
data class DragonTigerItem(
    val tradeDate: String?,
    val securityCode: String?,
    val securityName: String?,
    val explain: String?,
    val netBuy: Double?,
    val billboardDealAmt: Double?
)

/**
 * 扶摇龙虎榜榜单（域模型，2026-08-23）。
 * ⚠️ 换算：扶摇 `change`/`net_rate` 为**小数分数**（全 API 唯一非百分比原值的比率字段，
 * 实测 -0.10022 = -10.022%），此处 ×100 转为 App 百分比口径，配 FuyaoDtoParseTest fixture 锁定。
 */
data class DragonTigerBoard(
    val tradeDate: String?,
    val boardType: String?,
    val entries: List<DragonTigerBoardEntry>
)

data class DragonTigerBoardEntry(
    val securityCode: String?,
    val securityName: String?,
    /** 涨跌幅 %（小数分数 ×100 后）。 */
    val changePct: Double?,
    /** 龙虎榜净买入额（元）。 */
    val netBuy: Double?,
    /** 净买入占比 %（小数分数 ×100 后）。 */
    val netBuyPct: Double?,
    val buyValue: Double?,
    val sellValue: Double?,
    val concepts: List<String>,
    /** 上榜原因（涨停原因/题材）。 */
    val reason: String?,
    val hotRank: Int?,
    val rangeDays: Int?,
    /** 游资净买入额（元，仅全部榜返回）。 */
    val hotMoneyNetBuy: Double?
)

/** 扶摇龙虎榜 DTO → 域模型（change/net_rate 小数分数 ×100；纯函数，配解析测试）。 */
internal fun com.stock.dividend.data.remote.dto.FuyaoDragonTigerData.toDragonTigerBoard(): DragonTigerBoard =
    DragonTigerBoard(
        tradeDate = tradeDate,
        boardType = boardType,
        entries = stockItems.orEmpty().map { item ->
            DragonTigerBoardEntry(
                securityCode = item.ticker ?: item.thscode?.substringBefore("."),
                securityName = item.name,
                changePct = item.change?.takeIfFinite()?.let { it * 100.0 },
                netBuy = item.netValue.takeIfFinite(),
                netBuyPct = item.netRate?.takeIfFinite()?.let { it * 100.0 },
                buyValue = item.buyValue.takeIfFinite(),
                sellValue = item.sellValue.takeIfFinite(),
                concepts = item.conceptList.orEmpty().mapNotNull { it.name },
                reason = item.limitReason,
                hotRank = item.hotRank,
                rangeDays = item.rangeDays,
                hotMoneyNetBuy = item.hotMoneyNetValue.takeIfFinite()
            )
        }
    )

/**
 * 市场行情数据（资金流、板块、行业内个股、指数、龙虎榜、市场情绪）。
 *
 * 所有网络失败一律吞异常返回空（红线 #2）。⚠️ 单位规则按数据源区分：
 * - clist（板块/个股/资金流）：全部真实值，不 ÷100
 * - stock/get（指数/ETF）：价格百分比 ÷100；成交额原值不除
 */
@Singleton
class MarketDataRepository @Inject constructor(
    private val marketApi: MarketApi,
    private val stockRepository: StockRepository,
    private val fuyaoApi: com.stock.dividend.data.remote.FuyaoApi,
    private val fuyaoConfig: FuyaoConfig,
    private val cacheStore: FuyaoCacheStore,
    @com.stock.dividend.di.EastMoneyFundamentalApi
    private val fundamentalApi: com.stock.dividend.data.remote.FundamentalApi,
    private val errorLogRepository: ErrorLogRepository,
) {

    /**
     * 个股资金流向（主力/超大/大/中/小单净流入额+占比）。
     *
     * ⚠️ 数据源用 clist（`fs=m:{market}+t:{board}+s:{code}`）而非 stock/get——
     * 实测 stock/get 对资金流字段（f66/f69/f72/f75 等）返回不完整，clist 才有全套。
     * clist 单位：净额（元）原值不除；占比（%）真实值不除（如 6.04 表示 6.04%）。
     *
     * ⚠️ **行归属校验（2026-08-20 审计实测）**：clist 的 `s:` 单股筛选**实际不生效**
     * （`fs=m:1+t:2+s:600941` 返回 total=1628 全沪市列表按 fid 排序），必须请求 f12
     * 并按代码精确匹配，否则拿到的是「当日涨幅第一名」的资金流（张冠李戴，比崩溃更隐蔽）。
     *
     * @param stockCode `sh.600036` / `sz.000001`。失败或响应不含该股返回 null。
     */
    suspend fun fetchCapitalFlow(stockCode: String): CapitalFlow? {
        val fs = toClistFs(stockCode) ?: return null
        val code6 = stockCode.substringAfter(".")
        return runCatching {
            val item = marketApi.getClist(
                pz = "5", fid = "f3", fs = fs,
                fields = "f12,f62,f184,f66,f69,f72,f75,f78,f81,f84,f87"
            ).data?.diff
                // 行归属校验：只认 f12 与请求代码一致的记录（s: 筛选失效，见上）
                ?.firstOrNull { it.code == code6 }
                ?: return@runCatching null
            CapitalFlow(
                mainNetInflow = item.mainNetInflow.takeIfFinite(),
                mainNetInflowPct = item.mainNetInflowPct.takeIfFinite(),
                superLargeNetInflow = item.superLargeNetInflow.takeIfFinite(),
                superLargeNetInflowPct = item.superLargeNetInflowPct.takeIfFinite(),
                largeNetInflow = item.largeNetInflow.takeIfFinite(),
                largeNetInflowPct = item.largeNetInflowPct.takeIfFinite(),
                mediumNetInflow = item.mediumNetInflow.takeIfFinite(),
                mediumNetInflowPct = item.mediumNetInflowPct.takeIfFinite(),
                smallNetInflow = item.smallNetInflow.takeIfFinite(),
                smallNetInflowPct = item.smallNetInflowPct.takeIfFinite()
            )
        }.onFailure {
            errorLogRepository.record(
                source = "市场数据",
                message = "个股资金流获取失败（$stockCode）",
                throwable = it,
            )
        }.getOrNull()
    }

    /**
     * 行业板块列表（东财一级行业，fs=m:90+t:2）。
     * @param sortBy 排序：CHANGE(涨跌幅) / INFLOW(主力净流入) / TURNOVER(换手率)
     * @param limit 返回条数
     */
    suspend fun fetchIndustryList(
        sortBy: SortBy = SortBy.CHANGE,
        limit: Int = 15
    ): List<MarketListItem> = runCatching {
        val fid = when (sortBy) {
            SortBy.CHANGE -> "f3"
            SortBy.INFLOW -> "f62"
            SortBy.TURNOVER -> "f8"
        }
        marketApi.getClist(
            pz = limit.toString(),
            fid = fid,
            fs = "m:90+t:2",
            fields = "f2,f3,f8,f12,f14,f62,f128,f140,f136,f184"
        ).toMarketList()
    }.onFailure {
        errorLogRepository.record(
            source = "市场数据",
            message = "板块列表获取失败",
            throwable = it,
        )
    }.getOrDefault(emptyList())

    /**
     * 同行业个股。传入 [industryCode]（板块代码 BKxxxx，如「白酒Ⅱ」=BK1277）或股票 code（取其行业）。
     * @param sortBy 排序：CHANGE/MARKET_CAP(市值)/PE/PB
     * @param limit 返回条数
     * @return 个股列表；无法解析行业返回空
     */
    suspend fun fetchIndustryPeers(
        industryCodeOrStockCode: String,
        sortBy: PeerSortBy = PeerSortBy.MARKET_CAP,
        limit: Int = 15
    ): List<MarketListItem> {
        val bkCode = resolveIndustryCode(industryCodeOrStockCode) ?: return emptyList()
        return runCatching {
            val fid = when (sortBy) {
                PeerSortBy.CHANGE -> "f3"
                PeerSortBy.MARKET_CAP -> "f20"
                PeerSortBy.PE -> "f9"
                PeerSortBy.PB -> "f23"
            }
            marketApi.getClist(
                pz = limit.toString(),
                fid = fid,
                fs = "b:$bkCode",
                fields = "f2,f3,f8,f9,f12,f14,f20,f23,f100"
            ).toMarketList()
        }.onFailure {
            errorLogRepository.record(
                source = "市场数据",
                message = "行业内个股获取失败（$industryCodeOrStockCode）",
                throwable = it,
            )
        }.getOrDefault(emptyList())
    }

    /**
     * 全市场 A 股榜单（fs 覆盖沪深全市场约 5500 只，按 [sortBy] 降序取前 N）。
     *
     * ⚠️ **过滤是客户端行为**：clist 仅支持单字段排序，不支持条件过滤。传过滤条件时
     * 先按排序拉 [RANKING_SCAN_SIZE] 条候选再过滤——**满足条件的股票若排在候选集之外会漏掉**
     * （如按股息率榜前 200 中过滤 PE，低 PE 但股息率排 200 名外的股票不会出现）。
     * 调用方（工具层）需向用户如实说明该口径。
     * 过滤时字段缺失（null）的股票被剔除（停牌/无数据不臆造）。
     *
     * @param minDividendYield 可选：股息率下限（%，含）
     * @param maxPe 可选：PE(TTM) 上限（%，含）
     * @param limit 返回条数（1-50）
     */
    suspend fun fetchMarketRanking(
        sortBy: RankingSortBy = RankingSortBy.DIVIDEND_YIELD,
        minDividendYield: Double? = null,
        maxPe: Double? = null,
        limit: Int = 20
    ): List<MarketListItem> = runCatching {
        val fid = when (sortBy) {
            RankingSortBy.DIVIDEND_YIELD -> "f133"
            RankingSortBy.CHANGE -> "f3"
            RankingSortBy.MARKET_CAP -> "f20"
            RankingSortBy.PE -> "f9"
            RankingSortBy.PB -> "f23"
            RankingSortBy.TURNOVER -> "f8"
        }
        val fetchSize = if (minDividendYield != null || maxPe != null) RANKING_SCAN_SIZE else limit
        marketApi.getClist(
            pz = fetchSize.toString(),
            fid = fid,
            fs = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23",
            fields = "f2,f3,f8,f9,f12,f14,f20,f23,f133"
        ).toMarketList()
            .filter { item ->
                (minDividendYield == null || (item.dividendYield != null && item.dividendYield >= minDividendYield)) &&
                    (maxPe == null || (item.pe != null && item.pe <= maxPe))
            }
            .take(limit.coerceIn(1, 50))
    }.onFailure {
        errorLogRepository.record(
            source = "市场数据",
            message = "全市场榜单获取失败",
            throwable = it,
        )
    }.getOrDefault(emptyList())

    /**
     * 查询某只股票所属东财板块代码（BKxxxx）。
     * 东财 secid 详情接口 f128/f127 返回的是行业「名称」而非 BK 代码，无法直接拿板块代码。
     * 故采用：先查行业名，再用 clist 按板块名反查 BK 代码。简化为：用 [stockRepository] 缓存的 industry 名称。
     * 若取不到 industry 返回 null。
     */
    private suspend fun resolveIndustryCode(codeOrBk: String): String? {
        // 直接传 BK 代码
        if (codeOrBk.startsWith("BK", ignoreCase = true)) return codeOrBk.uppercase()
        // 传股票 code：取其缓存的行业名，再反查 BK 代码（DB 读也吞异常，红线 #2）
        val stockCode = normalizeStockCode(codeOrBk) ?: return null
        val saved = runCatching { stockRepository.observeStock(stockCode).first() }.getOrNull()
        val industryName = saved?.industry?.takeIf { it.isNotBlank() } ?: return null
        return findBkCodeByName(industryName)
    }

    /** 用板块名反查 BK 代码：拉行业列表匹配名称。 */
    private suspend fun findBkCodeByName(name: String): String? = runCatching {
        marketApi.getClist(
            pz = "100",
            fid = "f3",
            fs = "m:90+t:2",
            fields = "f12,f14"
        ).data?.diff?.firstOrNull { it.name == name }?.code
    }.getOrNull()

    /** 主要指数行情（上证/深证/沪深300/创业板/科创50/中证500/中证1000）。失败返回空。 */
    suspend fun fetchIndexQuotes(): List<IndexQuote> {
        // 同花顺扶摇主源：一次批量拉全部主要指数（东财为 7 次单查）；失败降级东财
        if (fuyaoConfig.enabled) {
            runCatching { fetchIndexQuotesFromFuyao() }
                .onSuccess { return it }
                .onFailure {
                    errorLogRepository.record(
                        source = "同花顺",
                        message = "指数主源失败，已降级东财",
                        throwable = (it as? Exception)
                    )
                }
        }
        val results = MAIN_INDICES.map { (name, secid, _) ->
            runCatching { marketApi.getIndexQuote(secid).toIndexQuote(name) }
                .onFailure {
                    errorLogRepository.record(
                        source = "市场数据",
                        message = "指数行情获取失败（$name）",
                        throwable = it,
                    )
                }
                .getOrNull()
        }
        return results.filterNotNull()
    }

    /** 扶摇指数批量快照 → [IndexQuote]（真实值无换算；名称由本地清单带出，快照响应无名称字段）。 */
    private suspend fun fetchIndexQuotesFromFuyao(): List<IndexQuote> {
        val thscodes = MAIN_INDICES.joinToString(",") { it.third }
        val envelope = fuyaoApi.getIndexSnapshot(thscodes = thscodes)
        check(envelope.isOk) { "扶摇指数快照失败: code=${envelope.code} ${envelope.message}" }
        val byThscode = envelope.data?.item.orEmpty()
            .associateBy { it.thscode }
        return MAIN_INDICES.mapNotNull { (name, _, thscode) ->
            byThscode[thscode]?.toIndexQuoteFromFuyao(
                code6 = thscode.substringBefore("."),
                name = name
            )
        }
    }

    /** 查询单只指数/ETF 行情（传 6 位代码，自动判市场）。 */
    suspend fun fetchIndexOrEtfQuote(code6: String): IndexQuote? {
        // 扶摇主源：快照（指数/基金按代码形态选接口）+ 并行 ticker-search 补名称（快照无名称）；
        // 任一失败降级东财 stock/get（自带 f58 名称）。
        if (fuyaoConfig.enabled) {
            runCatching { fetchIndexOrEtfQuoteFromFuyao(code6) }
                .getOrNull()
                ?.let { return it }
        }
        val secid = guessSecidByCode6(code6) ?: return null
        return runCatching { marketApi.getIndexQuote(secid).toIndexQuote(code6) }
            .onFailure {
                errorLogRepository.record(
                    source = "市场数据",
                    message = "指数/ETF行情获取失败（$code6）",
                    throwable = it,
                )
            }
            .getOrNull()
    }

    /** 扶摇单只指数/基金快照 + 名称检索。基金走基金接口；000/399 走指数接口；其余按 A 股快照。 */
    private suspend fun fetchIndexOrEtfQuoteFromFuyao(code6: String): IndexQuote? {
        if (!code6.matches(Regex("\\d{6}"))) return null
        val isFund = FundDividendParser.isExchangeTradedFundCode(code6)
        // 市场判定与 guessSecidByCode6 一致：6 开头沪股票 / 5 开头沪基金 → 沪，其余深
        val thscode = if (code6.startsWith("6") || code6.startsWith("5")) "$code6.SH" else "$code6.SZ"
        return kotlinx.coroutines.coroutineScope {
            val quoteDeferred = async {
                val envelope = when {
                    isFund -> fuyaoApi.getFundSnapshot(thscode = thscode)
                    code6.startsWith("000") || code6.startsWith("399") ->
                        fuyaoApi.getIndexSnapshot(thscodes = thscode)
                    else -> fuyaoApi.getPriceSnapshot(thscodes = thscode)
                }
                check(envelope.isOk) { "扶摇快照失败: $code6 code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty().firstOrNull()
                    ?: throw IllegalStateException("扶摇快照无数据: $code6")
            }
            val nameDeferred = async {
                // 名称补充（快照响应无名称）；失败仅降级为 null，不拖垮行情
                runCatching {
                    val search = fuyaoApi.searchTickers(query = code6, limit = 5)
                    search.data?.item.orEmpty()
                        .firstOrNull { it.thscode == thscode }?.name
                }.getOrNull()
            }
            quoteDeferred.await().toIndexQuoteFromFuyao(code6 = code6, name = nameDeferred.await())
        }
    }

    /** 龙虎榜。传 code 过滤单股，不传返回当日全市场。失败返回空。 */
    suspend fun fetchDragonTiger(stockCode: String? = null, limit: Int = 20): List<DragonTigerItem> {
        val filter = if (stockCode != null) {
            val securityCode = stockCode.substringAfter(".")
            """(SECURITY_CODE="$securityCode")"""
        } else {
            ""  // 不传 filter：东财 datacenter 不传 filter 即返回全表
        }
        return runCatching {
            fundamentalApi.getDragonTiger(filter = filter, pageSize = limit.toString()).result?.data
                .orEmpty()
                .map {
                    DragonTigerItem(
                        tradeDate = it.tradeDate?.substringBefore(" "),
                        securityCode = it.securityCode,
                        securityName = it.securityName,
                        explain = it.explain,
                        netBuy = it.netBuy?.takeIfFinite(),
                        billboardDealAmt = it.billboardDealAmt?.takeIfFinite()
                    )
                }
        }.onFailure {
            errorLogRepository.record(
                source = "市场数据",
                message = "龙虎榜获取失败${stockCode?.let { "（$it）" } ?: ""}",
                throwable = it,
            )
        }.getOrDefault(emptyList())
    }

    // ── 扶摇独有能力（东财/腾讯无对应；2026-08-23 数据平面全量接入）──────────

    /** 扶摇独有能力统一执行器：禁用返回 null（调用方给默认值）；失败记日志返回 null（无降级源）。 */
    private suspend fun <T> fetchFuyaoOnly(message: String, block: suspend () -> T): T? {
        if (!fuyaoConfig.enabled) return null
        return runCatching { block() }
            .onFailure {
                errorLogRepository.record(source = "同花顺", message = message, throwable = (it as? Exception))
            }
            .getOrNull()
    }

    /** A 股估值快照（批量 PE_TTM/PB 等）。key 为 App 代码（sh.600519）。 */
    suspend fun fetchValuations(stockCodes: List<String>): Map<String, com.stock.dividend.data.remote.dto.FuyaoValuationItem> {
        if (stockCodes.isEmpty()) return emptyMap()
        return fetchFuyaoOnly("估值快照获取失败") {
            val thscodes = stockCodes.mapNotNull { it.toFuyaoThscodeOrNull() }
            val envelope = fuyaoApi.getValuations(thscodes = thscodes.joinToString(","))
            check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
            envelope.data?.item.orEmpty().mapNotNull { item ->
                item.thscode?.fuyaoThscodeToAppCodeOrNull()?.let { it to item }
            }.toMap()
        } ?: emptyMap()
    }

    /** 交易日历（近一年，`yyyy-MM-dd` 升序；合并式永久缓存——历史交易日不可变，新日期追加）。 */
    suspend fun fetchTradingDays(): List<String> =
        cacheStore.fetchFirstMerge(
            "tradingDays",
            fuyaoCacheTypeOf<List<String>>(),
            merge = { cached, fresh ->
                if (cached == null) fresh
                else (fresh + cached).distinct().sorted()
            }
        ) {
            fetchFuyaoOnly("交易日历获取失败") {
                val envelope = fuyaoApi.getTradingDays()
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty().mapNotNull { it.date?.let { d ->
                    // yyyyMMdd → yyyy-MM-dd
                    if (d.length == 8) "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}" else null
                } }
            } ?: emptyList()
        } ?: emptyList()

    /** 扶摇龙虎榜榜单（过去日期按日缓存优先——历史榜单不可变零网络；缺省日期恒拉最新）。 */
    suspend fun fetchDragonTigerBoard(
        boardType: String = "all",
        date: String? = null
    ): DragonTigerBoard? {
        val isPast = date != null && date < java.time.LocalDate.now().toString()
        return cacheStore.cacheFirstForDate(
            key = "dragonTiger|$boardType|${date ?: "auto"}",
            typeOfT = fuyaoCacheTypeOf<DragonTigerBoard>(),
            isPastDate = isPast
        ) {
            fetchFuyaoOnly("龙虎榜（扶摇）获取失败") {
                val envelope = fuyaoApi.getDragonTigerList(boardType = boardType, date = date)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.toDragonTigerBoard()
            }
        }
    }

    /** 涨停股票池（过去日期按日缓存优先；当日实时恒拉网，失败回退最近一次缓存）。 */
    suspend fun fetchLimitUpPool(
        dateMs: Long? = null,
        page: Int = 1,
        size: Int = 50,
        sortField: String = "last_price",
        sortDir: String = "desc"
    ): com.stock.dividend.data.remote.dto.FuyaoLimitPoolData? =
        limitPoolCached("limitUp", dateMs, page, size) {
            fuyaoApi.getLimitUpPool(dateMs, page, size, sortField, sortDir)
        }

    /** 跌停股票池。 */
    suspend fun fetchLimitDownPool(
        dateMs: Long? = null,
        page: Int = 1,
        size: Int = 50
    ): com.stock.dividend.data.remote.dto.FuyaoLimitPoolData? =
        limitPoolCached("limitDown", dateMs, page, size) {
            fuyaoApi.getLimitDownPool(dateMs, page, size)
        }

    /** 炸板股票池。 */
    suspend fun fetchLimitBreakPool(
        dateMs: Long? = null,
        page: Int = 1,
        size: Int = 50
    ): com.stock.dividend.data.remote.dto.FuyaoLimitPoolData? =
        limitPoolCached("limitBreak", dateMs, page, size) {
            fuyaoApi.getLimitBreakPool(dateMs, page, size)
        }

    /** 池子类按日缓存：显式过去日期命中即零网络；缺省（当日）拉网+缓存兜底。 */
    private suspend fun limitPoolCached(
        kind: String,
        dateMs: Long?,
        page: Int,
        size: Int,
        fetch: suspend () -> com.stock.dividend.data.remote.dto.FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoLimitPoolData>
    ): com.stock.dividend.data.remote.dto.FuyaoLimitPoolData? {
        val todayStartMs = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return cacheStore.cacheFirstForDate(
            key = "$kind|${dateMs ?: "auto"}|$page|$size",
            typeOfT = fuyaoCacheTypeOf<com.stock.dividend.data.remote.dto.FuyaoLimitPoolData>(),
            isPastDate = dateMs != null && dateMs < todayStartMs
        ) {
            fetchFuyaoOnly("$kind 池获取失败") {
                val envelope = fetch()
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data
            }
        }
    }

    /** 连板天梯（近 30 交易日梯队矩阵，原始 JSON）。 */
    suspend fun fetchLimitUpLadder(): com.google.gson.JsonObject? =
        fetchFuyaoOnly("连板天梯获取失败") {
            fuyaoApi.getLimitUpLadder().takeIf { it.isOk }?.data
        }

    /** 热股榜 Top30（覆盖式缓存：实时榜过期即无意义，缓存仅作断网兜底）。 */
    suspend fun fetchHotStockList(period: String = "day"): List<com.stock.dividend.data.remote.dto.FuyaoHotStockItem> =
        cacheStore.fetchFirstReplace("hotStock|$period", fuyaoCacheTypeOf<List<com.stock.dividend.data.remote.dto.FuyaoHotStockItem>>()) {
            fetchFuyaoOnly("热股榜获取失败") {
                val envelope = fuyaoApi.getHotStockList(period)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty()
            } ?: emptyList()
        } ?: emptyList()

    /** 热度飙升榜 Top30（覆盖式缓存兜底）。 */
    suspend fun fetchSkyrocketList(period: String = "day"): List<com.stock.dividend.data.remote.dto.FuyaoHotStockItem> =
        cacheStore.fetchFirstReplace("skyrocket|$period", fuyaoCacheTypeOf<List<com.stock.dividend.data.remote.dto.FuyaoHotStockItem>>()) {
            fetchFuyaoOnly("飙升榜获取失败") {
                val envelope = fuyaoApi.getSkyrocketList(period)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty()
            } ?: emptyList()
        } ?: emptyList()

    /** 历史热股榜（按自然日不可变：过去日期命中缓存零网络）。 */
    suspend fun fetchHotStockListHistory(date: String): List<com.stock.dividend.data.remote.dto.FuyaoHotStockItem> =
        cacheStore.cacheFirstForDate(
            key = "hotHistory|$date",
            typeOfT = fuyaoCacheTypeOf<List<com.stock.dividend.data.remote.dto.FuyaoHotStockItem>>(),
            isPastDate = date < java.time.LocalDate.now().toString()
        ) {
            fetchFuyaoOnly("历史热股榜获取失败（$date）") {
                val envelope = fuyaoApi.getHotStockListHistory(date)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty()
            } ?: emptyList()
        } ?: emptyList()

    /** 个股热度排名走势（原始 JSON 点位序列）。 */
    suspend fun fetchHotStockRankTrend(
        thscode: String,
        startDate: String,
        endDate: String
    ): com.google.gson.JsonObject? =
        fetchFuyaoOnly("个股热度走势获取失败（$thscode）") {
            fuyaoApi.getHotStockRankTrend(thscode, startDate, endDate).takeIf { it.isOk }?.data
        }

    /** 个股异动原因列表（原始 JSON；tag 过滤）。 */
    suspend fun fetchAnomalyList(tagCodes: String? = null): com.google.gson.JsonObject? =
        fetchFuyaoOnly("异动列表获取失败") {
            fuyaoApi.getAnomalyList(tagCodes).takeIf { it.isOk }?.data
        }

    /** 按股票批量查当日异动原因（原始 JSON）。 */
    suspend fun fetchAnomalyByStock(thscodes: String): com.google.gson.JsonObject? =
        fetchFuyaoOnly("个股异动原因获取失败") {
            fuyaoApi.getAnomalyByStock(thscodes).takeIf { it.isOk }?.data
        }

    /** 集合竞价快照（原始 JSON；stage=live/final）。 */
    suspend fun fetchAuctionSnapshot(thscodes: String, stage: String = "final"): com.google.gson.JsonObject? =
        fetchFuyaoOnly("集合竞价快照获取失败") {
            fuyaoApi.getAuctionSnapshot(thscodes, stage).takeIf { it.isOk }?.data
        }

    /** 短线风向标竞价基准（原始 JSON）。 */
    suspend fun fetchShortTermBenchmark(date: String? = null): com.google.gson.JsonObject? =
        fetchFuyaoOnly("短线风向标获取失败") {
            fuyaoApi.getShortTermBenchmark(date).takeIf { it.isOk }?.data
        }

    /** 同花顺指数清单（覆盖式缓存：目录慢变，断网/禁用回退缓存）。 */
    suspend fun fetchThsIndexList(tag: String = "cn_concept"): List<com.stock.dividend.data.remote.dto.FuyaoThsIndexItem> =
        cacheStore.fetchFirstReplace("thsIndex|$tag", fuyaoCacheTypeOf<List<com.stock.dividend.data.remote.dto.FuyaoThsIndexItem>>()) {
            fetchFuyaoOnly("同花顺指数清单获取失败（$tag）") {
                val envelope = fuyaoApi.getThsIndexList(tag)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty()
            } ?: emptyList()
        } ?: emptyList()

    /** 指数成分股（覆盖式缓存兜底；成分半年调整一次）。 */
    suspend fun fetchIndexConstituents(thscode: String): List<com.stock.dividend.data.remote.dto.FuyaoTickerItem> =
        cacheStore.fetchFirstReplace("constituents|$thscode", fuyaoCacheTypeOf<List<com.stock.dividend.data.remote.dto.FuyaoTickerItem>>()) {
            fetchFuyaoOnly("指数成分股获取失败（$thscode）") {
                val envelope = fuyaoApi.getIndexConstituents(thscode)
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty()
            } ?: emptyList()
        } ?: emptyList()

    /**
     * 指数日K（无复权——历史永不漂移，比股票 K 线更不可变）。
     * 合并式永久缓存 + 当日新鲜短路：尾根=今天或今日已同步过 → 零网络直读；
     * 否则拉增量合并（远端覆盖同期、缓存独有旧日期永续保留）。
     */
    suspend fun fetchIndexDailyBars(thscode: String, days: Int = 400): List<KlineBar> {
        val key = "indexBars|$thscode|$days"
        val cached = cacheStore.loadEntry<List<KlineBar>>(key, fuyaoCacheTypeOf<List<KlineBar>>())
        val today = java.time.LocalDate.now().toString()
        val syncedToday = cached != null && java.time.Instant.ofEpochMilli(cached.fetchedAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() == today
        if (cached != null && cached.value.isNotEmpty() &&
            (cached.value.last().date >= today || syncedToday)
        ) {
            return cached.value
        }
        return cacheStore.fetchFirstMerge(
            key,
            fuyaoCacheTypeOf<List<KlineBar>>(),
            merge = { c, fresh ->
                if (c == null) fresh
                else (fresh.associateBy { it.date } + c.associateBy { it.date })
                    .values.toList().sortedBy { it.date }
            }
        ) {
            fetchFuyaoOnly("指数日K获取失败（$thscode）") {
                val endMs = System.currentTimeMillis()
                val startMs = java.time.LocalDate.now().minusDays(days.toLong())
                    .atStartOfDay(com.stock.dividend.data.remote.dto.FUYAO_ZONE)
                    .toInstant().toEpochMilli()
                val envelope = fuyaoApi.getIndexDailyBars(
                    thscode = thscode, startMs = startMs, endMs = endMs
                )
                check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
                envelope.data?.item.orEmpty().mapNotNull { bar ->
                    val date = bar.dateMs.fuyaoMsToDateStringOrNull() ?: return@mapNotNull null
                    val close = bar.close?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
                    KlineBar(
                        date = date,
                        open = bar.open?.takeIf { it.isFinite() } ?: close,
                        close = close,
                        high = bar.high?.takeIf { it.isFinite() } ?: close,
                        low = bar.low?.takeIf { it.isFinite() } ?: close,
                        // 指数成交量扶摇为股（上证 8/21 实测 446.9 亿股与成交额/均价互洽），÷100 对齐 KlineBar 手口径
                        volume = bar.volume?.takeIf { it.isFinite() }?.div(100.0) ?: 0.0
                    )
                }
            } ?: emptyList()
        } ?: cached?.value ?: emptyList()
    }

    /** 全量代码表（asset_type=a-share/fund-etf/fund-lof/...，分页）。 */
    suspend fun fetchTickerList(
        assetType: String? = null,
        limit: Int = 1000,
        offset: Int = 0
    ): List<com.stock.dividend.data.remote.dto.FuyaoTickerItem> =
        fetchFuyaoOnly("代码表获取失败") {
            val envelope = fuyaoApi.getTickerList(assetType, limit, offset)
            check(envelope.isOk) { "code=${envelope.code} ${envelope.message}" }
            envelope.data?.item.orEmpty()
        } ?: emptyList()

    // ── 辅助：secid / fs / 单位换算 ──

    /**
     * `sh.600036` → clist 的 `fs` 筛选串：`m:1+t:2+s:600036`（沪主板）/ `m:0+t:2+s:000001`（深主板）。
     * t:2 = 主板（含 600/000）；创业板 300 用 t:0，科创 688 用 t:1，但 clist 资金流接口对 t 不敏感，
     * 统一用 t:2 即可命中（实测 600519/000001 均能返回）。无法识别返回 null。
     */
    private fun toClistFs(stockCode: String): String? {
        val code6 = stockCode.substringAfter(".", missingDelimiterValue = stockCode)
        val market = when {
            stockCode.startsWith("sh.", ignoreCase = true) -> 1
            stockCode.startsWith("sz.", ignoreCase = true) -> 0
            else -> guessMarketByCode6(code6)
        } ?: return null
        if (!code6.matches(Regex("\\d{6}"))) return null
        return "m:$market+t:2+s:$code6"
    }

    /** 规范化股票 code：纯 6 位数字按前缀猜市场，带前缀原样返回。 */
    private fun normalizeStockCode(raw: String): String? {
        if (raw.startsWith("sh.", ignoreCase = true) || raw.startsWith("sz.", ignoreCase = true)) {
            return raw.lowercase()
        }
        val market = guessMarketByCode6(raw) ?: return null
        return "${if (market == 1) "sh" else "sz"}.$raw"
    }

    /** 按 6 位代码规则猜市场：6 开头（沪股票）/5 开头（沪基金 ETF·LOF，2026-08-22 修正，此前误判深市）→ 沪(1)，其余→深(0)。 */
    private fun guessMarketByCode6(code6: String): Int? {
        if (!code6.matches(Regex("\\d{6}"))) return null
        return if (code6.startsWith("6") || code6.startsWith("5")) 1 else 0
    }

    /** 按 6 位代码猜 push2 secid（指数/ETF/股票统一处理）。 */
    private fun guessSecidByCode6(code6: String): String? {
        // 主要指数（List<Pair>，name→secid）
        MAIN_INDICES.firstOrNull { it.second.endsWith(".$code6") }?.second?.let { return it }
        // ETF / 股票：6 开头沪市 1，其余深市 0；ETF 5xxxxx 也在沪市
        return guessMarketByCode6(code6)?.let { "$it.$code6" }
    }

    /** 行业列表排序维度。 */
    enum class SortBy { CHANGE, INFLOW, TURNOVER }

    /** 行业内个股排序维度。 */
    enum class PeerSortBy { CHANGE, MARKET_CAP, PE, PB }

    /** 全市场榜单排序维度。 */
    enum class RankingSortBy { DIVIDEND_YIELD, CHANGE, MARKET_CAP, PE, PB, TURNOVER }

    companion object {
        /** 全市场榜单带过滤条件时的候选集大小（客户端过滤仅作用于榜单前列）。 */
        private const val RANKING_SCAN_SIZE = 200

        /** 主要指数：显示名 → (push2 secid, 扶摇 thscode)。扶摇 thscode 实测全部可用（2026-08-23）。 */
        val MAIN_INDICES: List<Triple<String, String, String>> = listOf(
            Triple("上证指数", "1.000001", "000001.SH"),
            Triple("深证成指", "0.399001", "399001.SZ"),
            Triple("沪深300", "1.000300", "000300.SH"),
            Triple("创业板指", "0.399006", "399006.SZ"),
            Triple("科创50", "1.000688", "000688.SH"),
            Triple("中证500", "0.000905", "000905.SH"),
            Triple("中证1000", "0.000852", "000852.SH")
        )
    }
}

/**
 * clist 响应 → [MarketListItem] 列表（顶层 internal 纯函数，配 MarketDataRepositoryParseTest）。
 * ⚠️ clist（fltt=2）返回真实值：价格/百分比/资金净额均不 ÷100（与 ulist/stock/get 相反，§4.9.1）。
 * 数值脏值（null/NaN/Infinity，以及容错 Gson 把 "-" 读成的 null）一律降 null，不臆造。
 */
internal fun MarketClistResponse.toMarketList(): List<MarketListItem> =
    data?.diff?.map {
        MarketListItem(
            code = it.code,
            name = it.name,
            price = it.price.takeIfFinite(),
            changePct = it.changePct.takeIfFinite(),
            pe = it.pe.takeIfFinite(),
            pb = it.pb.takeIfFinite(),
            totalMarketCap = it.totalMarketCap.takeIfFinite(),
            turnoverRate = it.turnoverRate.takeIfFinite(),
            industry = it.industry,
            mainNetInflow = it.mainNetInflow.takeIfFinite(),
            mainNetInflowPct = it.mainNetInflowPct.takeIfFinite(),
            leaderName = it.leaderName,
            leaderCode = it.leaderCode,
            leaderChangePct = it.leaderChangePct.takeIfFinite(),
            dividendYield = it.dividendYield.takeIfFinite()
        )
    }.orEmpty()

/**
 * stock/get 指数响应 → [IndexQuote]（顶层 internal 纯函数，配 MarketDataRepositoryParseTest）。
 * ⚠️ 价格类字段（f43/f44/f45/f46/f60）除数随标的类型变：指数/股票 ×100 ÷100，
 * **场内基金（ETF/LOF）×1000 ÷1000**（实测 2026-08-22：510880 f43=3387 → 3.387，腾讯同刻 3.387）；
 * f170 涨跌幅两类均 ×100；f48 成交额（元）原值不除（§4.9.1）。
 */
internal fun IndexQuoteResponse.toIndexQuote(fallbackCode: String): IndexQuote {
    val d = data
    val isFund = FundDividendParser.isExchangeTradedFundCode(d?.code ?: fallbackCode)
    return IndexQuote(
        code = d?.code ?: fallbackCode,
        name = d?.name,
        price = d?.price.divPriceScaleOrNull(isFund),
        changePct = d?.changePct?.div100OrNull(),
        prevClose = d?.prevClose.divPriceScaleOrNull(isFund),
        high = d?.high.divPriceScaleOrNull(isFund),
        low = d?.low.divPriceScaleOrNull(isFund),
        open = d?.open.divPriceScaleOrNull(isFund),
        amount = d?.amount?.takeIfFinite()
    )
}

/** 可空 Double 取有限值；null/NaN/Infinity → null。clist 字段可空，统一用本扩展（internal 供解析测试共用）。 */
internal fun Double?.takeIfFinite(): Double? = this?.takeIf { it.isFinite() }

/**
 * 扶摇快照 item → [IndexQuote]（真实值口径无换算；金额=turnover 元）。
 * 名称与代码由调用方提供（快照响应无名称；扶摇指数 ticker 为内部码如 1A0001，非 6 位代码）。
 */
internal fun FuyaoPriceItem.toIndexQuoteFromFuyao(code6: String, name: String?): IndexQuote = IndexQuote(
    code = code6,
    name = name,
    price = lastPrice.takeIfFinite(),
    changePct = changePct.takeIfFinite(),
    prevClose = prevClose.takeIfFinite(),
    high = high.takeIfFinite(),
    low = low.takeIfFinite(),
    open = open.takeIfFinite(),
    amount = turnover.takeIfFinite()
)
