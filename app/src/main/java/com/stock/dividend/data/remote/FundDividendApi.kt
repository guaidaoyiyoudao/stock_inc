package com.stock.dividend.data.remote

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * 东方财富基金 f10「分红送配」页（fundf10.eastmoney.com/fhsp_{code}.html）。
 *
 * 场内基金（ETF/LOF）分红的**唯一数据源**（2026-08-22 实测）：
 * - 腾讯 fqkline 第 7 元素分红仅覆盖股票，ETF 的 K 线数组无分红行（640 行实测 0 条）；
 * - 东财 datacenter RPT_SHAREBONUS_DET 对 ETF 返回「返回数据为空」；
 * - 本页为服务端渲染 HTML 表（class='cfxq'），数据完整且结构稳定，解析见
 *   [com.stock.dividend.data.repository.FundDividendParser]。
 *
 * 响应为 HTML 原文（String），由 ScalarsConverter 处理（不走 Gson）。
 */
interface FundDividendApi {

    @GET("fhsp_{code}.html")
    suspend fun getFundDividendHtml(@Path("code") code: String): String
}
