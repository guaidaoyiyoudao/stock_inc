package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.QuoteResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface QuoteApi {

    @GET("api/qt/ulist.np/get")
    suspend fun getQuotes(
        @Query("secids") secids: String,
        @Query("fields") fields: String = "f2,f12,f13",
        @Query("ut") ut: String = "fa5fd1943c7b386f172d6893dbfba10b"
    ): QuoteResponse
}
