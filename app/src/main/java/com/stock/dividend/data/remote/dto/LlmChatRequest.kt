package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

data class LlmMessage(val role: String, val content: String)

data class LlmResponseFormat(val type: String = "json_object")

data class LlmChatRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val temperature: Double = 0.3,
    @SerializedName("response_format") val responseFormat: LlmResponseFormat = LlmResponseFormat(),
)
