package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 东方财富「中美国债收益率」接口响应（api/data/get, type=RPTA_WEB_TREASURYYIELD）。
 *
 * `result.data` 按日期倒序，首条为最新。各期限收益率单位为「%」（如 1.7337 表示 1.73%）。
 * 本 DTO 只解析 10 年期 [yield10Y] 字段。
 *
 * 真实响应示例（单条 data 元素）：
 * ```
 * {"SOLAR_DATE":"2026-07-27 00:00:00","EMM00588704":1.2688,"EMM00166462":1.4451,
 *  "EMM00166466":1.7337,"EMM00166469":2.193,"EMM01276014":0.4649, ...}
 * ```
 */
data class BondYieldResponse(
    val result: BondYieldResult?
) {
    data class BondYieldResult(
        val data: List<BondYieldItem>?
    )

    data class BondYieldItem(
        @SerializedName("SOLAR_DATE")
        val solarDate: String?,
        /** 中国 10 年期国债到期收益率（%）。可能为 null。 */
        @SerializedName("EMM00166466")
        val yield10Y: Double?
    )
}
