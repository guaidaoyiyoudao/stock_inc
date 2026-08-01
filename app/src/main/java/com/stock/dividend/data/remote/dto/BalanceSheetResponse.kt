package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 东方财富 datacenter-web「资产负债表」(reportName=RPT_DMSK_FN_BALANCE) 响应壳。
 *
 * 用途：补全 [FundamentalResponse] 缺失的资产负债率（[Item.debtAssetRatio]）。
 * 响应壳结构与 [FundamentalResponse] 同构。
 */
data class BalanceSheetResponse(
    val success: Boolean?,
    val result: BalanceSheetResult?
) {
    data class BalanceSheetResult(
        val data: List<Item>?
    )

    /**
     * 资产负债表项（仅取关心的字段）。字段名已实测确认（见设计文档 §3.2）。
     */
    data class Item(
        @SerializedName("REPORT_DATE")
        val reportDate: String?,
        @SerializedName("DEBT_ASSET_RATIO")
        val debtAssetRatio: Double?        // 资产负债率 %
    )
}
