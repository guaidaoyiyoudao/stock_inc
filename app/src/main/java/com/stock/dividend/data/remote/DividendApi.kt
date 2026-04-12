package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.DividendResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface DividendApi {

    @GET("api/data/v1/get")
    suspend fun getDividends(
        @Query("reportName") reportName: String = "RPT_SHAREBONUS_DET",
        @Query("columns") columns: String = "ALL",
        @Query("filter") filter: String,
        @Query("sortColumns") sortColumns: String = "REPORT_DATE",
        @Query("sortTypes") sortTypes: String = "-1",
        @Query("pageSize") pageSize: String = "500",
        @Query("pageNumber") pageNumber: String = "1",
        @Query("source") source: String = "WEB",
        @Query("client") client: String = "WEB"
    ): DividendResponse
}
