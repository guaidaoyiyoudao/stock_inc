package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

data class QuoteResponse(
    val data: QuoteData?
)

data class QuoteData(
    val diff: List<QuoteItem>?
)

data class QuoteItem(
    @SerializedName("f2")
    val price: Double?,
    @SerializedName("f12")
    val code: String,
    @SerializedName("f13")
    val market: Int
)
