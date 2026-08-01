package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 东方财富 datacenter-web「主要财务指标」(reportName=RPT_LICO_FN_CPD) 响应壳。
 * 结构与 [DividendResponse] 同构。
 */
data class FundamentalResponse(
    val success: Boolean?,
    val result: FundamentalResult?
) {
    data class FundamentalResult(
        val data: List<Item>?
    )

    /**
     * 单期财务摘要项。字段名已交叉验证（见设计文档 §3.2）。
     * 均可空：部分字段在某些报告期可能缺失。
     */
    data class Item(
        @SerializedName("REPORTDATE")
        val reportDate: String?,
        @SerializedName("WEIGHTAVG_ROE")
        val weightedAvgRoe: Double?,        // 加权净资产收益率 %
        @SerializedName("DEBT_ASSET_RATIO")
        val debtAssetRatio: Double?,        // 资产负债率 %
        @SerializedName("YSTZ")
        val revenueYoy: Double?,            // 营收同比 %
        @SerializedName("SJLTZ")
        val netProfitYoy: Double?,          // 归母净利同比 %
        @SerializedName("BASIC_EPS")
        val basicEps: Double?,              // 基本每股收益（元），用于算派息率
        @SerializedName("ZXGXL")
        val announceYield: Double? = null,  // 公告股息率 %（东财按公告日股价算，通常仅年报期有值）
        @SerializedName("ASSIGNDSCRPT")
        val dividendPlan: String? = null    // 分红方案文本，如「10派3.60元(含税)」
    )
}
