package com.stock.dividend.data.remote.dto

data class LlmChatResponse(val choices: List<Choice> = emptyList()) {
    data class Choice(val message: LlmMessage? = null)
    // content 声明为 Any（请求/响应共用 [LlmMessage]），响应侧恒为 String，as? 兜底
    val content: String? get() = choices.firstOrNull()?.message?.content as? String
}
