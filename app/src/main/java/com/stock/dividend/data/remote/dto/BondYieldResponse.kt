package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 东方财富「中美国债收益率」接口响应（api/data/get, type=RPTA_WEB_TREASURYYIELD）。
 *
 * `result.data` 按日期倒序，首条为最新。各期限收益率单位为「%」（如 1.7337 表示 1.73%）。
 * 同时含中美利差与 LPR 字段。调用方按需取用，缺失即 null。
 *
 * 真实响应示例（单条 data 元素，2026-07-31）：
 * ```
 * {"SOLAR_DATE":"2026-07-31 00:00:00","EMM00588704":1.2606,"EMM00166462":1.411,
 *  "EMM00166466":1.7141,"EMM00166469":2.1876,"EMM01276014":0.4535,
 *  "EMG00001306":4.28,"EMG00001310":4.75}
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
        /** 中国 2 年期国债到期收益率（%）。 */
        @SerializedName("EMM00588704")
        val yield2Y: Double? = null,
        /** 中国 5 年期国债到期收益率（%）。 */
        @SerializedName("EMM00166462")
        val yield5Y: Double? = null,
        /** 中国 10 年期国债到期收益率（%）。可能为 null。 */
        @SerializedName("EMM00166466")
        val yield10Y: Double? = null,
        /** 中国 30 年期国债到期收益率（%）。 */
        @SerializedName("EMM00166469")
        val yield30Y: Double? = null,
        /** 中美 10 年期国债利差（%，中-美）。 */
        @SerializedName("EMM01276014")
        val cnUsSpread10Y: Double? = null,
        /** LPR 1 年期（%）。 */
        @SerializedName("EMG00001306")
        val lpr1Y: Double? = null,
        /** LPR 5 年期（%）。 */
        @SerializedName("EMG00001310")
        val lpr5Y: Double? = null
    )
}
