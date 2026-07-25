package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.TencentKlineResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 腾讯财经 fqkline 接口（用作股息数据源）。
 *
 * baseUrl = https://web.ifzq.gtimg.cn/
 * param 格式：`{code},day,{start},{end},{count},qfq`，例如 `sh600036,day,2021-01-01,2026-12-31,640,qfq`。
 * 单次最多返回约 640 个交易日的历史。
 */
interface TencentDividendApi {

    @GET("appstock/app/fqkline/get")
    suspend fun getKline(@Query("param") param: String): TencentKlineResponse
}
