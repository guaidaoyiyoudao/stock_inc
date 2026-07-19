package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 东方财富单股详情接口响应（api/qt/stock/get）。
 * 实际返回字段更多，这里只解析需要的：代码、名称、所属行业。
 */
data class StockInfoResponse(
    val data: StockInfoData?
)

data class StockInfoData(
    @SerializedName("f57")
    val code: String?,
    @SerializedName("f58")
    val name: String?,
    @SerializedName("f127")
    val industry: String?
)
