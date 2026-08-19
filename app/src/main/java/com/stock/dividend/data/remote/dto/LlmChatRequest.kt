package com.stock.dividend.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * OpenAI 兼容消息。content 声明为 Any：
 * - 文本路径传 String（Gson 序列化为 JSON 字符串，与历史行为完全一致）；
 * - 视觉路径传 [List]<[LlmContentPart]>（Gson 序列化为 content parts 数组）。
 * 本 DTO 仅作为请求体序列化发送、从不反序列化，故 Any 无解析风险。
 */
data class LlmMessage(val role: String, val content: Any)

/** 多模态 content part：文本或图片（image_url 值为 data:image/jpeg;base64,… 形式的 data URL）。 */
data class LlmContentPart(
    val type: String, // "text" | "image_url"
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: LlmImageUrl? = null
)

data class LlmImageUrl(val url: String)

data class LlmResponseFormat(val type: String = "json_object")

data class LlmChatRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val temperature: Double = 0.3,
    // null 时 Gson 跳过该字段——视觉模型对 response_format 支持不稳，视觉请求省略
    @SerializedName("response_format") val responseFormat: LlmResponseFormat? = LlmResponseFormat(),
    // 可选输出长度上限；视觉解析长表格时传大值防 JSON 被截断
    @SerializedName("max_tokens") val maxTokens: Long? = null,
)
