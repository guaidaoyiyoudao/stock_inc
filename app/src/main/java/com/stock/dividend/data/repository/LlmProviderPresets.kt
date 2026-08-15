package com.stock.dividend.data.repository

/** 国内可用的 OpenAI 兼容厂商预设。用户选定后自动填 baseUrl + 默认 model。 */
enum class LlmProviderPreset(
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1/", "deepseek-v4-flash"),
    ZHIPU("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/", "glm-4-flash"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/", "qwen-turbo"),
    CUSTOM("自定义", "", "");

    companion object {
        fun apply(provider: LlmProviderPreset, current: LlmConfig): LlmConfig =
            if (provider == CUSTOM) current
            else current.copy(baseUrl = provider.baseUrl, model = provider.defaultModel)
    }
}
