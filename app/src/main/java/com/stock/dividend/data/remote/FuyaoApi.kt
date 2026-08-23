package com.stock.dividend.data.remote

import com.google.gson.JsonObject
import com.stock.dividend.data.remote.dto.FuyaoAdjustmentFactorsData
import com.stock.dividend.data.remote.dto.FuyaoAssetAllocationData
import com.stock.dividend.data.remote.dto.FuyaoConstituentsData
import com.stock.dividend.data.remote.dto.FuyaoDragonTigerData
import com.stock.dividend.data.remote.dto.FuyaoEnvelope
import com.stock.dividend.data.remote.dto.FuyaoFundDrawdownsData
import com.stock.dividend.data.remote.dto.FuyaoFundDividendsData
import com.stock.dividend.data.remote.dto.FuyaoFundHoldingsData
import com.stock.dividend.data.remote.dto.FuyaoFundHoldersData
import com.stock.dividend.data.remote.dto.FuyaoFundIndustryData
import com.stock.dividend.data.remote.dto.FuyaoFundNavData
import com.stock.dividend.data.remote.dto.FuyaoFundProfileData
import com.stock.dividend.data.remote.dto.FuyaoFundReportDatesData
import com.stock.dividend.data.remote.dto.FuyaoFundReturnsData
import com.stock.dividend.data.remote.dto.FuyaoHistoricalData
import com.stock.dividend.data.remote.dto.FuyaoHotStockData
import com.stock.dividend.data.remote.dto.FuyaoIndicatorsData
import com.stock.dividend.data.remote.dto.FuyaoLimitPoolData
import com.stock.dividend.data.remote.dto.FuyaoStatementsData
import com.stock.dividend.data.remote.dto.FuyaoTickerSearchData
import com.stock.dividend.data.remote.dto.FuyaoThsIndexListData
import com.stock.dividend.data.remote.dto.FuyaoTradingDaysData
import com.stock.dividend.data.remote.dto.FuyaoValuationData
import com.stock.dividend.data.remote.dto.FuyaoBalanceSheetItem
import com.stock.dividend.data.remote.dto.FuyaoCashFlowItem
import com.stock.dividend.data.remote.dto.FuyaoIncomeItem
import com.stock.dividend.data.remote.dto.FuyaoSnapshotData
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 同花顺扶摇金融数据 API（https://fuyao.aicubes.cn，官方文档仓库 HiThink-Tech/Financial-API）。
 *
 * **通用约定**（与东财/腾讯差异点，接入实测 2026-08-23）：
 * - 认证：请求头 `X-api-key`（[com.stock.dividend.di.FuyaoClient] 拦截器统一注入，缺 key 源禁用）；
 * - 所有响应（含业务错误）HTTP 200，统一信封 [FuyaoEnvelope]，`code != 0` 即业务失败；
 * - **全部字段真实值口径**（价格元 / 百分比 % 原值 / 金额元），无东财 push2 的 ÷100/÷1000 规则；
 * - 时间戳统一毫秒 Unix，时区 Asia/Shanghai；
 * - thscode 格式 `600519.SH`（映射见 [com.stock.dividend.data.remote.dto.fuyaoThscodeToAppCode]）。
 *
 * **已知限制（实测）**：ETF 代码传入 A 股端点会让**整批**报 1002（调用方必须按标的类型拆分请求）；
 * 基金日K恒为未复权（adjust 不支持），故 K 线域仅股票走扶摇，基金保持腾讯。
 */
interface FuyaoApi {

    /** A 股行情快照（批量，逗号分隔 thscodes；只能传股票，混入基金会整批 1002）。 */
    @GET("api/a-share/prices/snapshot")
    suspend fun getPriceSnapshot(
        @Query("thscodes") thscodes: String
    ): FuyaoEnvelope<FuyaoSnapshotData>

    /** 指数行情快照（批量；沪深指数 thscode 如 000001.SH，同花顺指数另有 .TI 后缀本期不用）。 */
    @GET("api/a-share-index/prices/snapshot")
    suspend fun getIndexSnapshot(
        @Query("thscodes") thscodes: String
    ): FuyaoEnvelope<FuyaoSnapshotData>

    /** 场内基金行情快照（仅单只 thscode；ETF/LOF 均实测可用，比 A 股快照多振幅/换手率）。 */
    @GET("api/fund/market/snapshot")
    suspend fun getFundSnapshot(
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<FuyaoSnapshotData>

    /** 股票日K（单只；窗口 ≤10 年；adjust=forward 前复权，与腾讯 qfq 语义对应）。 */
    @GET("api/a-share/prices/historical")
    suspend fun getDailyBars(
        @Query("thscode") thscode: String,
        @Query("interval") interval: String = "1d",
        @Query("start") startMs: Long,
        @Query("end") endMs: Long,
        @Query("adjust") adjust: String = "forward"
    ): FuyaoEnvelope<FuyaoHistoricalData>

    /** 股票分红事件流（已除权事件，单只；一次返回全部历史，每股现金口径）。 */
    @GET("api/a-share/corporate-actions/adjustment-factors")
    suspend fun getAdjustmentFactors(
        @Query("thscode") thscode: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null
    ): FuyaoEnvelope<FuyaoAdjustmentFactorsData>

    /** 场内基金分红记录（fund_type=exchange 同时覆盖 ETF 与 LOF，实测 2026-08-23）。 */
    @GET("api/fund/corporate-actions/dividends")
    suspend fun getFundDividends(
        @Query("fund_type") fundType: String = "exchange",
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<FuyaoFundDividendsData>

    /** 标的检索（A 股/指数/基金；asset_type 过滤场外；同 thscode 可能重复行需去重）。 */
    @GET("api/meta/tickers/search")
    suspend fun searchTickers(
        @Query("q") query: String,
        @Query("asset_type") assetType: String? = null,
        @Query("limit") limit: Int = 20
    ): FuyaoEnvelope<FuyaoTickerSearchData>

    /** 利润表（多期，period=quarterly 含 Q4 累计口径年报；报告期取 period_end_ms，report_date_ms 是公告日）。 */
    @GET("api/a-share/financials/income-statements")
    suspend fun getIncomeStatements(
        @Query("thscode") thscode: String,
        @Query("period") period: String = "quarterly",
        @Query("limit") limit: Int = 8
    ): FuyaoEnvelope<FuyaoStatementsData<FuyaoIncomeItem>>

    /** 资产负债表（缺存货/应付账款/固定资产科目，由东财并行补齐）。 */
    @GET("api/a-share/financials/balance-sheets")
    suspend fun getBalanceSheets(
        @Query("thscode") thscode: String,
        @Query("period") period: String = "quarterly",
        @Query("limit") limit: Int = 8
    ): FuyaoEnvelope<FuyaoStatementsData<FuyaoBalanceSheetItem>>

    /** 现金流量表（缺期末现金余额科目，由东财并行补齐）。 */
    @GET("api/a-share/financials/cash-flow-statements")
    suspend fun getCashFlowStatements(
        @Query("thscode") thscode: String,
        @Query("period") period: String = "quarterly",
        @Query("limit") limit: Int = 8
    ): FuyaoEnvelope<FuyaoStatementsData<FuyaoCashFlowItem>>

    /** 财务指标（单报告期，report 格式 "2024-4" = 年-季；未披露期返回 5003 调用方跳过）。 */
    @GET("api/a-share/financials/indicators")
    suspend fun getIndicators(
        @Query("thscode") thscode: String,
        @Query("report") report: String
    ): FuyaoEnvelope<FuyaoIndicatorsData>

    // ════════════════════════════════════════════════════════
    // 以下为「数据平面全量接入」（2026-08-23）：估值/日历/特色数据/指数目录/代码表/基金域。
    // 这些能力为扶摇**独有**（东财/腾讯无对应），禁用时整体不可用、无降级路径。
    // 未 typed 的端点以 FuyaoEnvelope<JsonObject> 原始透传（字段无单位换算，面向 Agent/后续 UI）。
    // ════════════════════════════════════════════════════════

    /** A 股估值快照（批量 PE_TTM/PE_MRQ/PB/PS/PCF）。 */
    @GET("api/a-share/valuations/snapshot")
    suspend fun getValuations(
        @Query("thscodes") thscodes: String
    ): FuyaoEnvelope<FuyaoValuationData>

    /** 交易日历：近一年交易日序列（固定窗口，无入参）。 */
    @GET("api/a-share/calendar/trading-days")
    suspend fun getTradingDays(): FuyaoEnvelope<FuyaoTradingDaysData>

    /** 龙虎榜榜单（board_type=all/org/hot_money；date 缺省取最近交易日，非交易日显式传入报 1002）。 */
    @GET("api/a-share/special-data/dragon-tiger-list")
    suspend fun getDragonTigerList(
        @Query("board_type") boardType: String = "all",
        @Query("date") date: String? = null
    ): FuyaoEnvelope<FuyaoDragonTigerData>

    /** 涨停股票池（连板+四类板块；分页排序）。 */
    @GET("api/a-share/special-data/limit-up-pool")
    suspend fun getLimitUpPool(
        @Query("date_ms") dateMs: Long? = null,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 50,
        @Query("sort_field") sortField: String = "last_price",
        @Query("sort_dir") sortDir: String = "desc"
    ): FuyaoEnvelope<FuyaoLimitPoolData>

    /** 跌停股票池。 */
    @GET("api/a-share/special-data/limit-down-pool")
    suspend fun getLimitDownPool(
        @Query("date_ms") dateMs: Long? = null,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 50,
        @Query("sort_field") sortField: String = "last_price",
        @Query("sort_dir") sortDir: String = "desc"
    ): FuyaoEnvelope<FuyaoLimitPoolData>

    /** 炸板股票池。 */
    @GET("api/a-share/special-data/limit-break-pool")
    suspend fun getLimitBreakPool(
        @Query("date_ms") dateMs: Long? = null,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 50,
        @Query("sort_field") sortField: String = "last_price",
        @Query("sort_dir") sortDir: String = "desc"
    ): FuyaoEnvelope<FuyaoLimitPoolData>

    /** 连板天梯（近 30 交易日 ×6 板梯队矩阵；嵌套结构原始透传）。 */
    @GET("api/a-share/special-data/limit-up-ladder")
    suspend fun getLimitUpLadder(): FuyaoEnvelope<JsonObject>

    /** 个股异动原因列表（tag_codes=LIMIT_UP/LIMIT_DOWN/SHARP_RISE/SHARP_FALL/RAPID_RALLY/RAPID_DECLINE，OR）。 */
    @GET("api/a-share/special-data/anomaly-analysis-list")
    suspend fun getAnomalyList(
        @Query("tag_codes") tagCodes: String? = null
    ): FuyaoEnvelope<JsonObject>

    /** 按股票批量查当日异动原因（≤50 个 thscode；当日无异动的代码被忽略）。 */
    @GET("api/a-share/special-data/anomaly-analysis-stock")
    suspend fun getAnomalyByStock(
        @Query("thscodes") thscodes: String
    ): FuyaoEnvelope<JsonObject>

    /** 热度飙升榜 Top30（period=day/hour）。 */
    @GET("api/a-share/special-data/skyrocket-list")
    suspend fun getSkyrocketList(
        @Query("period") period: String = "day"
    ): FuyaoEnvelope<FuyaoHotStockData>

    /** A 股热股榜 Top30（period=day=24小时级/hour=小时级）。 */
    @GET("api/a-share/special-data/hot-stock-list")
    suspend fun getHotStockList(
        @Query("period") period: String = "day"
    ): FuyaoEnvelope<FuyaoHotStockData>

    /** 历史热股榜（按自然日，一年内）。 */
    @GET("api/a-share/special-data/hot-stock-list-history")
    suspend fun getHotStockListHistory(
        @Query("date") date: String
    ): FuyaoEnvelope<FuyaoHotStockData>

    /** 个股热度排名走势（点位序列，原始透传）。 */
    @GET("api/a-share/special-data/hot-stock-rank-trend")
    suspend fun getHotStockRankTrend(
        @Query("thscode") thscode: String,
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String
    ): FuyaoEnvelope<JsonObject>

    /** 集合竞价快照（stage=live 实时/final 终态；原始透传）。 */
    @GET("api/a-share/auction/snapshot")
    suspend fun getAuctionSnapshot(
        @Query("thscodes") thscodes: String,
        @Query("stage") stage: String = "final"
    ): FuyaoEnvelope<JsonObject>

    /** 短线风向标竞价基准（date 缺省当日；原始透传）。 */
    @GET("api/a-share/auction/short-term-benchmark")
    suspend fun getShortTermBenchmark(
        @Query("date") date: String? = null
    ): FuyaoEnvelope<JsonObject>

    /** 同花顺指数清单（tag=cn_concept/region/tszs/industry，单 tag 全量无分页）。 */
    @GET("api/a-share-index/catalog/ths-index-list")
    suspend fun getThsIndexList(
        @Query("tag") tag: String = "cn_concept"
    ): FuyaoEnvelope<FuyaoThsIndexListData>

    /** 指数成分股（同花顺板块指数 .TI 与标准指数 000300.SH 等均支持，单指数）。 */
    @GET("api/a-share-index/constituents/ths-stock-list")
    suspend fun getIndexConstituents(
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<FuyaoConstituentsData>

    /** 指数日K（无复权语义；窗口 ≤10 年；item 形态与股票日K一致）。 */
    @GET("api/a-share-index/prices/historical")
    suspend fun getIndexDailyBars(
        @Query("thscode") thscode: String,
        @Query("interval") interval: String = "1d",
        @Query("start") startMs: Long,
        @Query("end") endMs: Long
    ): FuyaoEnvelope<FuyaoHistoricalData>

    /** 全量代码表（asset_type 过滤，分页 limit≤10000）。 */
    @GET("api/meta/tickers/list")
    suspend fun getTickerList(
        @Query("asset_type") assetType: String? = null,
        @Query("limit") limit: Int = 1000,
        @Query("offset") offset: Int = 0
    ): FuyaoEnvelope<FuyaoTickerSearchData>

    // ── 基金域（fund_type=exchange 覆盖 ETF/LOF；manager/company 用 id）──

    /** 基金基本资料（规模/成立/管理人/经理任职/费率）。 */
    @GET("api/fund/profile/detail")
    suspend fun getFundProfile(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<FuyaoFundProfileData>

    /** 基金重仓持仓（定期披露；含股票仓位/集中度/主营行业汇总）。 */
    @GET("api/fund/portfolio/holdings")
    suspend fun getFundHoldings(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<FuyaoFundHoldingsData>

    /** 基金行业配置（部分基金无数据返回 5003，调用方按空处理）。 */
    @GET("api/fund/portfolio/industry-allocation")
    suspend fun getFundIndustryAllocation(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<FuyaoFundIndustryData>

    /** 基金资产配置（股票/债券/存款/其他比例）。 */
    @GET("api/fund/portfolio/asset-allocation")
    suspend fun getFundAssetAllocation(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<FuyaoAssetAllocationData>

    /** 历史股票持仓（原始透传）。 */
    @GET("api/fund/portfolio/stock-history")
    suspend fun getFundStockHistory(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<JsonObject>

    /** 历史债券持仓（原始透传）。 */
    @GET("api/fund/portfolio/bond-history")
    suspend fun getFundBondHistory(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<JsonObject>

    /** 股票持仓披露报告期列表。 */
    @GET("api/fund/portfolio/stock-report-dates")
    suspend fun getFundStockReportDates(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<FuyaoFundReportDatesData>

    /** 债券持仓披露报告期列表。 */
    @GET("api/fund/portfolio/bond-report-dates")
    suspend fun getFundBondReportDates(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<FuyaoFundReportDatesData>

    /** 多周期最大回撤矩阵。 */
    @GET("api/fund/performance/drawdowns")
    suspend fun getFundDrawdowns(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<FuyaoFundDrawdownsData>

    /** 区间收益（含同类平均）。 */
    @GET("api/fund/performance/returns")
    suspend fun getFundReturns(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<FuyaoFundReturnsData>

    /** 基金净值（range 缺省返回最新一条；nav_type=unit/adj/unit,adj）。 */
    @GET("api/fund/performance/nav")
    suspend fun getFundNav(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String,
        @Query("range") range: String? = null,
        @Query("nav_type") navType: String = "unit,adj"
    ): FuyaoEnvelope<FuyaoFundNavData>

    /** 历史业绩指标序列（RSI/唐奇安通道/跟踪指数估值分位等；start/end 必填）。 */
    @GET("api/fund/performance/indicators-historical")
    suspend fun getFundPerformanceIndicators(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String,
        @Query("start") startMs: Long,
        @Query("end") endMs: Long
    ): FuyaoEnvelope<JsonObject>

    /** 持有人结构（merge_scope=all/merged/separate）。 */
    @GET("api/fund/holders/detail")
    suspend fun getFundHoldersDetail(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String,
        @Query("merge_scope") mergeScope: String = "all"
    ): FuyaoEnvelope<FuyaoFundHoldersData>

    /** 前十大持有人（原始透传）。 */
    @GET("api/fund/holders/top")
    suspend fun getFundHoldersTop(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<JsonObject>

    /** 基金经理详情（原始透传）。 */
    @GET("api/fund/managers/detail")
    suspend fun getFundManagerDetail(
        @Query("manager_id") managerId: String
    ): FuyaoEnvelope<JsonObject>

    /** 基金经理投资风格（原始透传）。 */
    @GET("api/fund/managers/investment-style")
    suspend fun getFundManagerStyle(
        @Query("manager_id") managerId: String
    ): FuyaoEnvelope<JsonObject>

    /** 基金经理业绩（原始透传）。 */
    @GET("api/fund/managers/performance")
    suspend fun getFundManagerPerformance(
        @Query("manager_id") managerId: String
    ): FuyaoEnvelope<JsonObject>

    /** 基金经理从业经历（原始透传）。 */
    @GET("api/fund/managers/experience")
    suspend fun getFundManagerExperience(
        @Query("manager_id") managerId: String
    ): FuyaoEnvelope<JsonObject>

    /** 基金公司详情（原始透传）。 */
    @GET("api/fund/companies/detail")
    suspend fun getFundCompanyDetail(
        @Query("company_id") companyId: String
    ): FuyaoEnvelope<JsonObject>

    /** 基金诊断（多维评分，原始透传）。 */
    @GET("api/fund/diagnostics/detail")
    suspend fun getFundDiagnostics(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<JsonObject>

    /** 基金资讯列表（原始透传）。 */
    @GET("api/fund/news/article-list")
    suspend fun getFundNews(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<JsonObject>

    /** 基金募集列表（subscribe=active 当前募集/upcoming 即将募集，必填）。 */
    @GET("api/fund/offerings/list")
    suspend fun getFundOfferings(
        @Query("subscribe") subscribe: String = "active"
    ): FuyaoEnvelope<JsonObject>

    /** 基金利润表（原始透传）。 */
    @GET("api/fund/financials/income-statements")
    suspend fun getFundIncomeStatements(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<JsonObject>

    /** 基金资产负债表（原始透传）。 */
    @GET("api/fund/financials/balance-sheets")
    suspend fun getFundBalanceSheets(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<JsonObject>

    /** 基金财务指标（原始透传）。 */
    @GET("api/fund/financials/indicators")
    suspend fun getFundFinancialIndicators(
        @Query("fund_type") fundType: String,
        @Query("thscode") thscode: String
    ): FuyaoEnvelope<JsonObject>
}
