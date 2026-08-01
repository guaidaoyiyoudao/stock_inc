package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.BondYieldResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 10 年期国债到期收益率（东方财富数据中心，与 AKShare `bond_zh_us_rate` 同源）。
 *
 * - 接口：`datacenter.eastmoney.com/api/data/get`，type=`RPTA_WEB_TREASURYYIELD`
 * - 10 年期字段：`EMM00166466`，数值直接为「%」（如 1.7337 表示 1.73%）。
 * - 返回 `result.data` 按日期倒序，取首条即最新。
 */
interface BondYieldApi {

    @GET("api/data/get")
    suspend fun getTreasuryYield(
        @Query("type") type: String = "RPTA_WEB_TREASURYYIELD",
        @Query("sty") sty: String = "ALL",
        @Query("st") st: String = "SOLAR_DATE",
        @Query("sr") sr: String = "-1",
        @Query("token") token: String = "894050c76af8597a853f5b408b759f5d",
        @Query("p") p: String = "1",
        @Query("ps") ps: String = "5",
        @Query("pageNo") pageNo: String = "1",
        @Query("pageNum") pageNum: String = "1"
    ): BondYieldResponse
}
