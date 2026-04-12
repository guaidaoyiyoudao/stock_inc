package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.StockSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface SearchApi {

    @GET("api/suggest/get")
    suspend fun searchStocks(
        @Query("input") input: String,
        @Query("type") type: String = "14",
        @Query("token") token: String = "D43BF722C8E33BDC906FB84D85E326E8",
        @Query("count") count: String = "10"
    ): StockSearchResponse
}
