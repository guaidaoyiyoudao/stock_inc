package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.QuoteResponse
import com.stock.dividend.data.remote.dto.StockInfoResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface QuoteApi {

    @GET("api/qt/ulist.np/get")
    suspend fun getQuotes(
        @Query("secids") secids: String,
        @Query("fields") fields: String = "f2,f12,f13",
        @Query("ut") ut: String = "fa5fd1943c7b386f172d6893dbfba10b"
    ): QuoteResponse

    /** 单股详情：f57 代码、f58 名称、f127 所属行业（东财一级行业）。 */
    @GET("api/qt/stock/get")
    suspend fun getStockInfo(
        @Query("secid") secid: String,
        @Query("fields") fields: String = "f57,f58,f127",
        @Query("ut") ut: String = "fa5fd1943c7b386f172d6893dbfba10b"
    ): StockInfoResponse
}
