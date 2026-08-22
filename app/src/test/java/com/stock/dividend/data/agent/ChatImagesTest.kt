package com.stock.dividend.data.agent

import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatImagesTest {

    @Test
    fun dataUrlToPart_roundtripsBackToSameUrl() {
        val url = "data:image/jpeg;base64,QUJDREVG"
        val part = imageDataUrlToPart(url)!!
        assertThat(part.inlineData!!.mimeType).isEqualTo("image/jpeg")
        assertThat(part.inlineData!!.data).isEqualTo("ABCDEF".toByteArray())
        assertThat(part.imageDataUrl()).isEqualTo(url)
    }

    @Test
    fun dataUrlToPart_preservesPngMime() {
        val url = "data:image/png;base64,QUJD"
        assertThat(imageDataUrlToPart(url)!!.imageDataUrl()).isEqualTo(url)
    }

    @Test
    fun dataUrlToPart_rejectsInvalidInputs() {
        assertThat(imageDataUrlToPart("https://x/y.png")).isNull()
        assertThat(imageDataUrlToPart("data:audio/mp3;base64,QUJD")).isNull()
        assertThat(imageDataUrlToPart("data:image/jpeg;base64,")).isNull()
        assertThat(imageDataUrlToPart("data:image/jpeg;base64,!!!非法!!!")).isNull()
        assertThat(imageDataUrlToPart("")).isNull()
    }

    @Test
    fun partToDataUrl_rejectsNonImageParts() {
        assertThat(Part(text = "纯文本").imageDataUrl()).isNull()
        assertThat(Part(inlineData = Blob(mimeType = null, displayName = null, data = byteArrayOf(1))).imageDataUrl()).isNull()
        assertThat(Part(inlineData = Blob(mimeType = "application/pdf", displayName = null, data = byteArrayOf(1))).imageDataUrl()).isNull()
    }

    @Test
    fun detector_matchesKnownMultimodalFamilies() {
        listOf(
            "glm-4.6v-flash", "GLM-4V-Plus", "glm-4.5v",
            "gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-5-turbo", "chatgpt-4o-latest",
            "qwen-vl-plus", "qwen2.5-vl-32b-instruct", "QVQ-Max",
            "claude-3-5-sonnet", "claude-sonnet-4", "claude-opus-4-1",
            "gemini-2.0-flash",
            "deepseek-vl2",
            "deepseek-v4-flash-vision-exp", // DeepSeek 官方多模态（2026-08 上线）
            "doubao-1.5-vision-pro",   // 命中通用 vision 兜底
            "moonshot-v1-8k-vision-preview",
            "Pixtral-12B",
        ).forEach { assertThat(MultimodalModelDetector.isMultimodal(it)).isTrue() }
    }

    @Test
    fun detector_rejectsTextOnlyModels() {
        listOf(
            "glm-4-flash", "glm-4.6-flash", "glm-4-plus",
            "deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash", "deepseek-v4-pro",
            "qwen-plus", "qwen-max", "qwen3-235b",
            "kimi-k2-0905-preview",
            "hunyuan-lite", "ernie-4.0-8k",
            "",
            "   ",
        ).forEach { assertThat(MultimodalModelDetector.isMultimodal(it)).isFalse() }
    }
}
