package com.stock.dividend.data.remote

import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmChatResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * OpenAI 兼容 chat completions。baseUrl 由用户配置、动态变化，故用 @Url 传全路径，
 * Retrofit 实例的 baseUrl 仅作占位（http://localhost/）。
 */
interface LlmApi {
    @POST
    suspend fun chatCompletions(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Body body: LlmChatRequest,
    ): LlmChatResponse
}
