package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.MarketApi
import com.stock.dividend.data.remote.dto.IndexQuoteResponse
import com.stock.dividend.data.remote.dto.MarketClistResponse
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
    val leaderChangePct: Double?
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
    @com.stock.dividend.di.EastMoneyFundamentalApi
    private val fundamentalApi: com.stock.dividend.data.remote.FundamentalApi,
) {

    /**
     * 个股资金流向（主力/超大/大/中/小单净流入额+占比）。
     *
     * ⚠️ 数据源用 clist（`fs=m:{market}+t:{board}+s:{code}`）而非 stock/get——
     * 实测 stock/get 对资金流字段（f66/f69/f72/f75 等）返回不完整，clist 才有全套。
     * clist 单位：净额（元）原值不除；占比（%）真实值不除（如 6.04 表示 6.04%）。
     *
     * @param stockCode `sh.600036` / `sz.000001`。失败返回 null。
     */
    suspend fun fetchCapitalFlow(stockCode: String): CapitalFlow? {
        val fs = toClistFs(stockCode) ?: return null
        return runCatching {
            val item = marketApi.getClist(
                pz = "1", fid = "f3", fs = fs,
                fields = "f62,f184,f66,f69,f72,f75,f78,f81,f84,f87"
            ).data?.diff?.firstOrNull() ?: return@runCatching null
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
        }.getOrDefault(emptyList())
    }

    /**
     * 查询某只股票所属东财板块代码（BKxxxx）。
     * 东财 secid 详情接口 f128/f127 返回的是行业「名称」而非 BK 代码，无法直接拿板块代码。
     * 故采用：先查行业名，再用 clist 按板块名反查 BK 代码。简化为：用 [stockRepository] 缓存的 industry 名称。
     * 若取不到 industry 返回 null。
     */
    private suspend fun resolveIndustryCode(codeOrBk: String): String? {
        // 直接传 BK 代码
        if (codeOrBk.startsWith("BK", ignoreCase = true)) return codeOrBk.uppercase()
        // 传股票 code：取其缓存的行业名，再反查 BK 代码
        val stockCode = normalizeStockCode(codeOrBk) ?: return null
        val saved = stockRepository.observeStock(stockCode).first()
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
        val results = MAIN_INDICES.map { (code, secid) ->
            runCatching { marketApi.getIndexQuote(secid).toIndexQuote(code) }.getOrNull()
        }
        return results.filterNotNull()
    }

    /** 查询单只指数/ETF 行情（传 6 位代码，自动判市场）。 */
    suspend fun fetchIndexOrEtfQuote(code6: String): IndexQuote? {
        val secid = guessSecidByCode6(code6) ?: return null
        return runCatching { marketApi.getIndexQuote(secid).toIndexQuote(code6) }.getOrNull()
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
        }.getOrDefault(emptyList())
    }

    // ── 辅助：secid / fs / 单位换算 ──

    /** `sh.600036` / `sz.000001` → `1.600036` / `0.000001`（push2 secid，stock/get 用）。 */
    private fun toSecid(stockCode: String): String? {
        val code6 = stockCode.substringAfter(".", missingDelimiterValue = stockCode)
        val market = when {
            stockCode.startsWith("sh.", ignoreCase = true) -> 1
            stockCode.startsWith("sz.", ignoreCase = true) -> 0
            else -> guessMarketByCode6(code6)
        }
        return market?.let { "$it.$code6" }
    }

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

    /** 按 6 位代码规则猜市场：6xxxxx→沪(1)、其余→深(0)。 */
    private fun guessMarketByCode6(code6: String): Int? {
        if (!code6.matches(Regex("\\d{6}"))) return null
        return if (code6.startsWith("6")) 1 else 0
    }

    /** 按 6 位代码猜 push2 secid（指数/ETF/股票统一处理）。 */
    private fun guessSecidByCode6(code6: String): String? {
        // 主要指数（List<Pair>，name→secid）
        MAIN_INDICES.firstOrNull { it.second.endsWith(".$code6") }?.second?.let { return it }
        // ETF / 股票：6 开头沪市 1，其余深市 0；ETF 5xxxxx 也在沪市
        return guessMarketByCode6(code6)?.let { "$it.$code6" }
    }

    /**
     * clist 响应 → [MarketListItem] 列表。
     * ⚠️ clist（fltt=2）返回真实值：价格/百分比/资金净额均不 ÷100（与 ulist/stock/get 不同）。
     */
    private fun MarketClistResponse.toMarketList(): List<MarketListItem> =
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
                leaderChangePct = it.leaderChangePct.takeIfFinite()
            )
        }.orEmpty()

    private fun IndexQuoteResponse.toIndexQuote(fallbackCode: String): IndexQuote {
        val d = data
        return IndexQuote(
            code = d?.code ?: fallbackCode,
            name = d?.name,
            price = d?.price?.div100OrNull(),
            changePct = d?.changePct?.div100OrNull(),
            prevClose = d?.prevClose?.div100OrNull(),
            high = d?.high?.div100OrNull(),
            low = d?.low?.div100OrNull(),
            open = d?.open?.div100OrNull(),
            amount = d?.amount?.takeIfFinite()
        )
    }

    /** 行业列表排序维度。 */
    enum class SortBy { CHANGE, INFLOW, TURNOVER }

    /** 行业内个股排序维度。 */
    enum class PeerSortBy { CHANGE, MARKET_CAP, PE, PB }

    companion object {
        /** 主要指数：显示名 → push2 secid。 */
        val MAIN_INDICES: List<Pair<String, String>> = listOf(
            "上证指数" to "1.000001",
            "深证成指" to "0.399001",
            "沪深300" to "1.000300",
            "创业板指" to "0.399006",
            "科创50" to "1.000688",
            "中证500" to "0.000905",
            "中证1000" to "0.000852"
        )
    }
}

private fun Double.div100OrNull(): Double? = takeIf { it.isFinite() }?.div(100.0)

/** 可空 Double 取有限值；null/NaN/Infinity → null。clist 字段可空，统一用本扩展。 */
private fun Double?.takeIfFinite(): Double? = this?.takeIf { it.isFinite() }
