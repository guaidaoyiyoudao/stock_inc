package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.BalanceSheetResponse
import com.stock.dividend.data.remote.dto.CashFlowStatementResponse
import com.stock.dividend.data.remote.dto.DragonTigerResponse
import com.stock.dividend.data.remote.dto.FundamentalResponse
import com.stock.dividend.data.remote.dto.IncomeStatementResponse
import com.stock.dividend.data.remote.dto.BalanceSheetFullResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 东方财富基本面接口集合。
 *
 * 复用 datacenter-web 的 `api/data/v1/get` 模式（与 [com.stock.dividend.data.remote.DividendApi] 同构）。
 * - [getFundamentals]：主要财务指标（ROE/营收净利同比/EPS），reportName=RPT_LICO_FN_CPD
 * - [getBalanceSheet]：资产负债表（负债率），reportName=RPT_DMSK_FN_BALANCE；补全前者缺失的 DEBT_ASSET_RATIO
 * - [getIncomeStatement]：利润表（reportName=RPT_DMSK_FN_INCOME）
 * - [getCashFlowStatement]：现金流量表（reportName=RPT_DMSK_FN_CASHFLOW）
 * - [getBalanceSheetFull]：资产负债表全量字段（与 [getBalanceSheet] 同 reportName，DTO 取更多科目）
 * - [getDragonTiger]：龙虎榜明细（reportName=RPT_DAILYBILLBOARD_DETAILS）
 */
interface FundamentalApi {

    @GET("api/data/v1/get")
    suspend fun getFundamentals(
        @Query("reportName") reportName: String = "RPT_LICO_FN_CPD",
        @Query("columns") columns: String = "ALL",
        @Query("filter") filter: String,
        @Query("sortColumns") sortColumns: String = "REPORTDATE",
        @Query("sortTypes") sortTypes: String = "-1",
        @Query("pageSize") pageSize: String = "5",     // 近 5 期
        @Query("pageNumber") pageNumber: String = "1",
        @Query("source") source: String = "WEB",
        @Query("client") client: String = "WEB"
    ): FundamentalResponse

    @GET("api/data/v1/get")
    suspend fun getBalanceSheet(
        @Query("reportName") reportName: String = "RPT_DMSK_FN_BALANCE",
        @Query("columns") columns: String = "ALL",
        @Query("filter") filter: String,
        @Query("sortColumns") sortColumns: String = "REPORT_DATE",
        @Query("sortTypes") sortTypes: String = "-1",
        @Query("pageSize") pageSize: String = "5",     // 近 5 期
        @Query("pageNumber") pageNumber: String = "1",
        @Query("source") source: String = "WEB",
        @Query("client") client: String = "WEB"
    ): BalanceSheetResponse

    @GET("api/data/v1/get")
    suspend fun getIncomeStatement(
        @Query("reportName") reportName: String = "RPT_DMSK_FN_INCOME",
        @Query("columns") columns: String = "ALL",
        @Query("filter") filter: String,
        @Query("sortColumns") sortColumns: String = "REPORT_DATE",
        @Query("sortTypes") sortTypes: String = "-1",
        @Query("pageSize") pageSize: String = "8",     // 近 8 期
        @Query("pageNumber") pageNumber: String = "1",
        @Query("source") source: String = "WEB",
        @Query("client") client: String = "WEB"
    ): IncomeStatementResponse

    @GET("api/data/v1/get")
    suspend fun getCashFlowStatement(
        @Query("reportName") reportName: String = "RPT_DMSK_FN_CASHFLOW",
        @Query("columns") columns: String = "ALL",
        @Query("filter") filter: String,
        @Query("sortColumns") sortColumns: String = "REPORT_DATE",
        @Query("sortTypes") sortTypes: String = "-1",
        @Query("pageSize") pageSize: String = "8",
        @Query("pageNumber") pageNumber: String = "1",
        @Query("source") source: String = "WEB",
        @Query("client") client: String = "WEB"
    ): CashFlowStatementResponse

    @GET("api/data/v1/get")
    suspend fun getBalanceSheetFull(
        @Query("reportName") reportName: String = "RPT_DMSK_FN_BALANCE",
        @Query("columns") columns: String = "ALL",
        @Query("filter") filter: String,
        @Query("sortColumns") sortColumns: String = "REPORT_DATE",
        @Query("sortTypes") sortTypes: String = "-1",
        @Query("pageSize") pageSize: String = "8",
        @Query("pageNumber") pageNumber: String = "1",
        @Query("source") source: String = "WEB",
        @Query("client") client: String = "WEB"
    ): BalanceSheetFullResponse

    @GET("api/data/v1/get")
    suspend fun getDragonTiger(
        @Query("reportName") reportName: String = "RPT_DAILYBILLBOARD_DETAILS",
        @Query("columns") columns: String = "ALL",
        @Query("filter") filter: String,
        @Query("sortColumns") sortColumns: String = "TRADE_DATE",
        @Query("sortTypes") sortTypes: String = "-1",
        @Query("pageSize") pageSize: String = "20",
        @Query("pageNumber") pageNumber: String = "1",
        @Query("source") source: String = "WEB",
        @Query("client") client: String = "WEB"
    ): DragonTigerResponse
}
