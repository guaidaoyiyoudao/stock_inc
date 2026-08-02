package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 东方财富「个股研报」接口（`reportapi.eastmoney.com/report/list`）响应。
 *
 * 返回券商对个股的盈利预测与评级。字段名已实测确认（2026-08，茅台）。
 * 金额/比率单位见各字段注释；EPS 为「元」、PE 为倍数。
 */
data class ResearchReportResponse(
    val hits: Int? = null,
    val data: List<Item>? = null
) {
    data class Item(
        @SerializedName("title")
        val title: String?,
        @SerializedName("stockName")
        val stockName: String?,
        @SerializedName("stockCode")
        val stockCode: String?,
        @SerializedName("orgSName")
        val orgSName: String?,                // 研究机构简称
        @SerializedName("publishDate")
        val publishDate: String?,             // "2026-07-23 00:00:00.000"
        @SerializedName("predictThisYearEps")
        val predictThisYearEps: String?,      // 今年预测 EPS（字符串，可能空）
        @SerializedName("predictThisYearPe")
        val predictThisYearPe: String?,       // 今年预测 PE
        @SerializedName("predictNextYearEps")
        val predictNextYearEps: String?,
        @SerializedName("predictNextYearPe")
        val predictNextYearPe: String?,
        @SerializedName("emRatingName")
        val emRatingName: String?,            // 东财综合评级（如「买入」「增持」）
        @SerializedName("infoCode")
        val infoCode: String?                 // 报告 infoCode（可拼详情链接）
    )
}
