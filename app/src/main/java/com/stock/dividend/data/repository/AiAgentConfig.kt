package com.stock.dividend.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * AI Tab agent 专属配置（仅作用于 AI 助手，不影响「一键评估」链路）。
 *
 * - [systemPrompt]：用户自定义的附加系统提示词，追加到默认 BASE_INSTRUCTION 之后（非替换，
 *   避免破坏工具调用/数据准确性契约）。空串表示用默认。
 * - [temperature]：回答随机性，null 表示用模型默认；有效范围 0.0~2.0。
 * - [maxTokens]：单轮最大输出长度，null 表示用模型默认；必须 > 0。
 */
data class AiAgentConfig(
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val maxTokens: Int? = null,
)

/** 只读 AI agent 配置源（抽象出来便于单测用 fake 绕开 SharedPreferences）。 */
interface AiAgentConfigSource {
    fun observe(): Flow<AiAgentConfig>
}
