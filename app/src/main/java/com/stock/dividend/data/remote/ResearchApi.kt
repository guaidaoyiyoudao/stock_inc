package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.ResearchReportResponse
import com.stock.dividend.data.remote.dto.StockAnnouncementResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 东方财富「研报」接口（`reportapi.eastmoney.com/report/list`）。
 * 返回券商对个股的盈利预测与评级。base url 由 [com.stock.dividend.di.NetworkModule] 单独装配。
 */
interface ResearchApi {

    @GET("report/list")
    suspend fun getReports(
        @Query("code") code: String,
        @Query("pageSize") pageSize: String = "10",
        @Query("pageNo") pageNo: String = "1",
        @Query("industry") industry: String = "*",
        @Query("rating") rating: String = "*",
        @Query("ratingChange") ratingChange: String = "*",
        @Query("qType") qType: String = "0",
        @Query("beginTime") beginTime: String,
        @Query("endTime") endTime: String
    ): ResearchReportResponse
}

/**
 * 东方财富「个股公告」接口（`np-anotice-stock.eastmoney.com/api/security/ann`）。
 */
interface AnnouncementApi {

    @GET("api/security/ann")
    suspend fun getAnnouncements(
        @Query("stock_list") stockList: String,
        @Query("page_size") pageSize: String = "10",
        @Query("page_index") pageIndex: String = "1",
        @Query("ann_type") annType: String = "A",
        @Query("sr") sr: String = "-1"
    ): StockAnnouncementResponse
}
