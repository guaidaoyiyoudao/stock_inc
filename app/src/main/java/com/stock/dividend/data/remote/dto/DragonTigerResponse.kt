package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 东方财富「龙虎榜」接口（datacenter-web `reportName=RPT_DAILYBILLBOARD_DETAILS`）响应壳。
 * 字段名已实测确认（2026-08）。金额单位「元」。
 */
data class DragonTigerResponse(
    val success: Boolean?,
    val result: DragonTigerResult?
) {
    data class DragonTigerResult(
        val data: List<Item>?
    )

    data class Item(
        @SerializedName("TRADE_DATE")
        val tradeDate: String?,               // "2026-07-31 00:00:00"
        @SerializedName("SECURITY_CODE")
        val securityCode: String?,
        @SerializedName("SECURITY_NAME_ABBR")
        val securityName: String?,
        @SerializedName("EXPLAIN")
        val explain: String?,                 // 上榜原因（如「日跌幅偏离值达7%的证券」）
        @SerializedName("BILLBOARD_DEAL_AMT")
        val billboardDealAmt: Double?,        // 龙虎榜成交额（元）
        @SerializedName("ACCUM_AMOUNT")
        val accumAmount: Double?,             // 当日总成交额（元）
        @SerializedName("NET_BUY")
        val netBuy: Double?,                  // 买入净额（元）
        @SerializedName("REASON")
        val reason: String?                   // 分类（如「跌幅偏离值」）
    )
}
