package com.stock.dividend.data.agent

import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Part
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 聊天图片编解码：data URL ↔ ADK [Part]（inlineData）。
 *
 * 纯 JVM（kotlin.io.encoding.Base64，无 Android 依赖），AI 会话经 ADK SessionService
 * 持久化后历史图片以 Blob 字节往返，重新打开会话仍可渲染缩略图。
 */

/** `data:image/jpeg;base64,xxx` → `Part(inlineData=Blob)`；格式非法返回 null。 */
@OptIn(ExperimentalEncodingApi::class)
internal fun imageDataUrlToPart(dataUrl: String): Part? {
    val header = dataUrl.substringBefore(",", "")
    if (!header.startsWith("data:") || !header.endsWith(";base64")) return null
    val mime = header.removePrefix("data:").removeSuffix(";base64")
    if (!mime.startsWith("image/")) return null
    val payload = dataUrl.substringAfter(",", "")
    if (payload.isEmpty()) return null
    val bytes = runCatching { Base64.Default.decode(payload) }.getOrNull() ?: return null
    return Part(inlineData = Blob(mimeType = mime, displayName = null, data = bytes))
}

/** 含图片 inlineData 的 Part → data URL；非图片（无 inlineData / 非 image mime）返回 null。 */
@OptIn(ExperimentalEncodingApi::class)
internal fun Part.imageDataUrl(): String? {
    val blob = inlineData ?: return null
    val mime = blob.mimeType ?: return null
    if (!mime.startsWith("image/")) return null
    val data = blob.data ?: return null
    return "data:$mime;base64," + Base64.Default.encode(data)
}

/**
 * 按**模型名启发式**判断聊天模型是否多模态（支持图片输入）。
 *
 * OpenAI 兼容协议没有能力协商接口，这里用主流多模态家族的命名特征匹配
 * （匹配前统一小写）。误判兜底：UI 层据此只控制「加图」入口的可解释行为，
 * 真不支持时服务端 400 会经 Error 事件透出，不会静默。
 */
object MultimodalModelDetector {

    private val patterns = listOf(
        // OpenAI：gpt-4o / gpt-4.1 / gpt-4-turbo / gpt-4v / gpt-5 / chatgpt-4o
        "gpt-4o", "gpt-4.1", "gpt-4-turbo", "gpt-4v", "gpt-5", "chatgpt-4o",
        // 智谱 GLM 视觉系：glm-4v / glm-4.5v / glm-4.6v-flash 等
        "glm-4v", "glm-4.1v", "glm-4.5v", "glm-4.6v", "glm-5v",
        // 通义 Qwen 视觉系 + QVQ
        "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen3-vl", "qvq",
        // Anthropic（3/4/5 代全系多模态）
        "claude-3", "claude-4", "claude-5", "claude-opus", "claude-sonnet", "claude-haiku",
        // Google
        "gemini",
        // DeepSeek：官方多模态 deepseek-v4-flash-vision(-exp)（显式列出，防通用兜底被收紧后漏判）
        // + 视觉开源系 deepseek-vl / deepseek-vl2；chat / v4-flash / v4-pro 纯文本不匹配
        "deepseek-v4-flash-vision", "deepseek-vl",
        // Moonshot 多模态（vision 系 + kimi-latest）
        "kimi-vision", "kimi-latest", "moonshot-vision",
        // 通用兜底：多数厂商视觉模型命名含 vision / -vl / pixtral
        "vision", "-vl", "vl-", "pixtral",
    )

    fun isMultimodal(model: String): Boolean {
        val name = model.trim().lowercase()
        if (name.isEmpty()) return false
        return patterns.any { name.contains(it) }
    }
}
