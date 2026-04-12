package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

data class DividendResponse(
    val success: Boolean?,
    val result: DividendResult?
) {
    data class DividendResult(
        val data: List<DividendItem>?
    )

    data class DividendItem(
        @SerializedName("SECURITY_CODE")
        val securityCode: String?,
        @SerializedName("SECURITY_NAME_ABBR")
        val securityNameAbbr: String?,
        @SerializedName("REPORT_DATE")
        val reportDate: String?,
        @SerializedName("CASH_DIVIDEND_RATIO")
        val cashDividendRatio: Double?,
        @SerializedName("DIVIDEND_YIELD")
        val dividendYield: Double?,
        @SerializedName("EX_DIVIDEND_DATE")
        val exDividendDate: String?,
        @SerializedName("EQUITY_RECORD_DATE")
        val equityRecordDate: String?,
        @SerializedName("IMPL_PLAN")
        val implPlan: String?
    )
}
