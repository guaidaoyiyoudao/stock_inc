package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 东方财富「个股公告」接口（`np-anotice-stock.eastmoney.com/api/security/ann`）响应。
 *
 * 返回上市公司公告列表。字段名已实测确认（2026-08，茅台）。
 */
data class StockAnnouncementResponse(
    val data: AnnouncementData? = null
) {
    data class AnnouncementData(
        val list: List<Item>? = null
    )

    data class Item(
        @SerializedName("art_code")
        val artCode: String?,
        @SerializedName("title")
        val title: String?,
        @SerializedName("notice_date")
        val noticeDate: String?,              // "2026-07-18 00:00:00"
        @SerializedName("display_time")
        val displayTime: String?
    )
}
