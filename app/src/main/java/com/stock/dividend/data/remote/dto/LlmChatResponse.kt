package com.stock.dividend.data.remote.dto

data class LlmChatResponse(val choices: List<Choice> = emptyList()) {
    data class Choice(val message: LlmMessage? = null)
    val content: String? get() = choices.firstOrNull()?.message?.content
}
