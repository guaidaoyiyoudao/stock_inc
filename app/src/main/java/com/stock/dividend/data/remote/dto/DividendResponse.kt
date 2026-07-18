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
        @SerializedName("SECUCODE")
        val secuCode: String? = null,
        @SerializedName("SECURITY_NAME_ABBR")
        val securityNameAbbr: String?,
        @SerializedName("REPORT_DATE")
        val reportDate: String?,
        @SerializedName("PRETAX_BONUS_RMB")
        val pretaxBonusRmb: Double?,
        @SerializedName("DIVIDENT_RATIO")
        val dividentRatio: Double?,
        @SerializedName("EX_DIVIDEND_DATE")
        val exDividendDate: String?,
        @SerializedName("EQUITY_RECORD_DATE")
        val equityRecordDate: String?,
        @SerializedName("PLAN_NOTICE_DATE")
        val planNoticeDate: String? = null,
        @SerializedName("ASSIGN_PROGRESS")
        val assignProgress: String?
    )
}
