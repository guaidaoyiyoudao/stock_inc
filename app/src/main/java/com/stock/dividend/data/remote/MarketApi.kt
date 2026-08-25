package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.IndexQuoteResponse
import com.stock.dividend.data.remote.dto.MarketClistResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 东方财富 push2 行情接口集合（板块/个股列表、个股资金流、指数行情）。
 *
 * 复用 [QuoteApi] 同一 base url（`push2.eastmoney.com`）。⚠️ 单位规则见各 DTO 文档——
 * `clist`（[MarketClistResponse]）返回真实值不除；`stock/get`（[IndexQuoteResponse]）价格百分比需 ÷100。
 * 网络失败由 Repository 统一吞异常返回空（红线 #2）。
 */
interface MarketApi {

    /**
     * 通用列表查询（板块/行业内个股/资金流向/全市场 等）。
     * - 行业板块：`fs=m:90+t:2`，按 f3 涨跌幅 / f62 主力净流入 排序
     * - 行业内个股：`fs=b:BK1277`（板块代码），按 f20 市值 / f9 PE / f23 PB 排序
     * - 个股资金流：`fs=m:1+t:2+s:600519`（沪市主板 + 代码），取 f62/f184/f66/f69/... 资金字段
     * - 全市场个股：`fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23`
     *
     * @param fid 排序字段（如 f3 涨跌幅、f6 成交额、f20 市值、f62 主力净流入）
     * @param po  排序方向（1 降序 / 0 升序）
     * @param fs  筛选范围
     * @param fields 返回字段（逗号分隔的 f 编号）
     */
    @GET("api/qt/clist/get")
    suspend fun getClist(
        @Query("pn") pn: String = "1",
        @Query("pz") pz: String,
        @Query("po") po: String = "1",
        @Query("np") np: String = "1",
        @Query("fltt") fltt: String = "2",
        @Query("invt") invt: String = "2",
        @Query("fid") fid: String,
        @Query("fs") fs: String,
        @Query("fields") fields: String,
        @Query("ut") ut: String = "fa5fd1943c7b386f172d6893dbfba10b"
    ): MarketClistResponse

    /**
     * 指数现价（上证 1.000001 / 深证 0.399001 / 沪深300 1.000300 等）。
     * 复用 stock/get；f43 现价、f44 高、f45 低、f60 昨收、f170 涨跌幅 均为 ×100 整数需 ÷100；
     * f48 成交额（元）原值不除。
     */
    @GET("api/qt/stock/get")
    suspend fun getIndexQuote(
        @Query("secid") secid: String,
        @Query("fields") fields: String = "f43,f44,f45,f46,f48,f57,f58,f60,f170",
        @Query("ut") ut: String = "fa5fd1943c7b386f172d6893dbfba10b"
    ): IndexQuoteResponse

    /**
     * 个股资金流（按 secid 精确拉取，fltt=2 全真实值——净额元原值、占比 % 原值，单位规则
     * 见 [CapitalFlowResponse]）。2026-08-24 接入：替代此前 clist 单股方案（clist 的 `s:` 单股
     * 筛选实际不生效，按 fid 拉前 N 条再客户端匹配对普通个股恒 miss）。
     */
    @GET("api/qt/ulist.np/get")
    suspend fun getCapitalFlow(
        @Query("secids") secids: String,
        @Query("fields") fields: String = "f12,f62,f184,f66,f69,f72,f75,f78,f81,f84,f87",
        @Query("fltt") fltt: String = "2",
        @Query("invt") invt: String = "2",
        @Query("ut") ut: String = "fa5fd1943c7b386f172d6893dbfba10b"
    ): com.stock.dividend.data.remote.dto.CapitalFlowResponse
}
