package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

data class StockSearchResponse(
    @SerializedName("QuotationCodeTable")
    val quotationCodeTable: QuotationCodeTable?
) {
    data class QuotationCodeTable(
        val Data: List<StockItem>?
    )

    data class StockItem(
        val Code: String,
        val Name: String,
        val MktNum: String,
        val SecurityTypeName: String
    )
}
