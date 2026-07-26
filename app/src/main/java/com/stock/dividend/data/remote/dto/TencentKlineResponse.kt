package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 腾讯财经 fqkline（前复权 K 线）接口响应。
 *
 * 分红数据并不单独返回，而是嵌在每日 K 线数组的第 7 个元素（index 6）里：
 * `[date, open, close, high, low, volume, {分红对象?}]`，仅有除权除息日当天才有该对象。
 * 因此 qfqday 用 List<List<Any>> 接收，运行时再解析第 7 元素。
 *
 * 周线请求时数据落在 `qfqweek` 键下（字段名随周期变化），故新增该字段；
 * Gson 反序列化时按实际返回的键填充，未命中的字段为 null。
 */
data class TencentKlineResponse(
    val code: Int?,
    val msg: String?,
    val data: Map<String, StockData?>?
) {
    data class StockData(
        val qfqday: List<List<*>>?,
        val qfqweek: List<List<*>>? = null,
        val qfqmonth: List<List<*>>? = null
    )
}

/**
 * 从 qfqday 单条记录中解析出的分红信息。
 * @param nd 报告年度（如 "2024"，对应 2024 年报分红）
 * @param fhSh 每 10 股派息（元），需 ÷10 得每股派息
 * @param djr 登记日
 * @param cqr 除权除息日
 */
data class TencentDividendItem(
    @SerializedName("nd") val nd: String?,
    @SerializedName("fh_sh") val fhSh: String?,
    @SerializedName("djr") val djr: String?,
    @SerializedName("cqr") val cqr: String?
)
