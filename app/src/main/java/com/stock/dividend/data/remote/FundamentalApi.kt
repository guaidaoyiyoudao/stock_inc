package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.BalanceSheetResponse
import com.stock.dividend.data.remote.dto.FundamentalResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 东方财富基本面接口集合。
 *
 * 复用 datacenter-web 的 `api/data/v1/get` 模式（与 [com.stock.dividend.data.remote.DividendApi] 同构）。
 * - [getFundamentals]：主要财务指标（ROE/营收净利同比/EPS），reportName=RPT_LICO_FN_CPD
 * - [getBalanceSheet]：资产负债表（负债率），reportName=RPT_DMSK_FN_BALANCE；补全前者缺失的 DEBT_ASSET_RATIO
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
}
