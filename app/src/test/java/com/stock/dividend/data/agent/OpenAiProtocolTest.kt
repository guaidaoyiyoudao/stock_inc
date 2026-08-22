package com.stock.dividend.data.agent

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class OpenAiProtocolTest {
    private val gson = Gson()

    @Test
    fun buildOpenAiRequest_putsSystemInstructionFirst() {
        val request = buildOpenAiRequest(
            LlmRequest(
                config = GenerateContentConfig(
                    systemInstruction = Content(role = Role.SYSTEM, parts = listOf(Part(text = "你是助手")))
                ),
                contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "你好"))))
            ),
            modelName = "deepseek-chat"
        )
        assertThat(request.model).isEqualTo("deepseek-chat")
        assertThat(request.messages[0].role).isEqualTo("system")
        assertThat(request.messages[0].content).isEqualTo("你是助手")
        assertThat(request.messages[1].role).isEqualTo("user")
        assertThat(request.messages[1].content).isEqualTo("你好")
    }

    @Test
    fun buildOpenAiRequest_convertsFunctionCallToToolCalls() {
        val request = buildOpenAiRequest(
            LlmRequest(
                contents = listOf(
                    Content(
                        role = Role.MODEL,
                        parts = listOf(
                            Part(functionCall = FunctionCall(name = "get_holdings", args = emptyMap(), id = "call-1"))
                        )
                    )
                )
            ),
            modelName = "m"
        )
        val msg = request.messages.single()
        assertThat(msg.role).isEqualTo("assistant")
        assertThat(msg.toolCalls!!.single().id).isEqualTo("call-1")
        assertThat(msg.toolCalls!!.single().function.name).isEqualTo("get_holdings")
        assertThat(gson.fromJson(msg.toolCalls!!.single().function.arguments, Map::class.java)).isEmpty()
    }

    @Test
    fun buildOpenAiRequest_convertsFunctionResponseToToolMessage() {
        val request = buildOpenAiRequest(
            LlmRequest(
                contents = listOf(
                    Content(
                        role = Role.USER,
                        parts = listOf(
                            Part(
                                functionResponse = FunctionResponse(
                                    name = "get_holdings",
                                    id = "call-1",
                                    response = mapOf("holdings" to emptyList<String>())
                                )
                            )
                        )
                    )
                )
            ),
            modelName = "m"
        )
        val msg = request.messages.single()
        assertThat(msg.role).isEqualTo("tool")
        assertThat(msg.toolCallId).isEqualTo("call-1")
        assertThat(msg.content as String).contains("holdings")
    }

    @Test
    fun buildOpenAiRequest_convertsToolsDeclaration() {
        val request = buildOpenAiRequest(
            LlmRequest(
                config = GenerateContentConfig(
                    tools = listOf(
                        Tool(
                            functionDeclarations = listOf(
                                FunctionDeclaration(
                                    name = "get_stock_info",
                                    description = "查询个股",
                                    parameters = Schema(
                                        type = Type.OBJECT,
                                        properties = mapOf("code" to Schema(type = Type.STRING, description = "代码")),
                                        required = listOf("code")
                                    )
                                )
                            )
                        )
                    )
                ),
                contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "x"))))
            ),
            modelName = "m"
        )
        val tool = request.tools!!.single()
        assertThat(tool.function.name).isEqualTo("get_stock_info")
        assertThat(tool.function.parameters).containsEntry("type", "object")
        assertThat(tool.function.parameters!!["required"]).isEqualTo(listOf("code"))
        val props = tool.function.parameters!!["properties"] as Map<*, *>
        assertThat((props["code"] as Map<*, *>)["type"]).isEqualTo("string")
    }

    @Test
    fun buildOpenAiRequest_userContentWithImage_becomesMultimodalPartsArray() {
        val request = buildOpenAiRequest(
            LlmRequest(
                contents = listOf(
                    Content(
                        role = Role.USER,
                        parts = listOf(
                            Part(text = "识别这张持仓截图"),
                            imageDataUrlToPart("data:image/png;base64,QUJD")!!,
                        )
                    )
                )
            ),
            modelName = "glm-4.6v-flash"
        )
        val msg = request.messages.single()
        assertThat(msg.role).isEqualTo("user")
        val parts = msg.content as List<*>
        assertThat(parts).hasSize(2)
        val textPart = parts[0] as OpenAiContentPart
        assertThat(textPart.type).isEqualTo("text")
        assertThat(textPart.text).isEqualTo("识别这张持仓截图")
        val imagePart = parts[1] as OpenAiContentPart
        assertThat(imagePart.type).isEqualTo("image_url")
        assertThat(imagePart.imageUrl!!.url).isEqualTo("data:image/png;base64,QUJD")
        // 序列化后符合 OpenAI 多模态协议（content 数组 + image_url 字段）
        assertThat(Gson().toJson(request)).contains("\"image_url\"")
    }

    @Test
    fun buildOpenAiRequest_imageOnlyUserContent_stillEmitsMessage() {
        val request = buildOpenAiRequest(
            LlmRequest(
                contents = listOf(
                    Content(
                        role = Role.USER,
                        parts = listOf(imageDataUrlToPart("data:image/jpeg;base64,QUJD")!!)
                    )
                )
            ),
            modelName = "gpt-4o"
        )
        val msg = request.messages.single()
        assertThat(msg.role).isEqualTo("user")
        val parts = msg.content as List<*>
        assertThat(parts).hasSize(1)
        assertThat((parts[0] as OpenAiContentPart).type).isEqualTo("image_url")
    }

    @Test
    fun buildOpenAiRequest_textOnlyUserContent_staysPlainString() {
        val request = buildOpenAiRequest(
            LlmRequest(
                contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "你好"))))
            ),
            modelName = "glm-4-flash"
        )
        assertThat(request.messages.single().content).isEqualTo("你好")
    }

    @Test
    fun toLlmResponse_mapsTextAndToolCalls() {
        val response = toLlmResponse(
            OpenAiChatResponse(
                choices = listOf(
                    OpenAiChoice(
                        message = OpenAiMessage(
                            role = "assistant",
                            content = "好的",
                            toolCalls = listOf(
                                OpenAiToolCall(
                                    id = "call-9",
                                    function = OpenAiFunctionCall(name = "add_stock", arguments = """{"code":"600519"}""")
                                )
                            )
                        ),
                        finishReason = "tool_calls"
                    )
                )
            )
        )
        val parts = response.content!!.parts
        assertThat(parts.mapNotNull { it.text }).containsExactly("好的")
        val fc = parts.mapNotNull { it.functionCall }.single()
        assertThat(fc.name).isEqualTo("add_stock")
        assertThat(fc.id).isEqualTo("call-9")
        assertThat(fc.args).containsEntry("code", "600519")
        assertThat(response.finishReason).isEqualTo(FinishReason.STOP)
    }

    @Test
    fun toLlmResponse_mapsLengthToMaxTokens() {
        val response = toLlmResponse(
            OpenAiChatResponse(
                choices = listOf(
                    OpenAiChoice(message = OpenAiMessage(role = "assistant", content = "x"), finishReason = "length")
                )
            )
        )
        assertThat(response.finishReason).isEqualTo(FinishReason.MAX_TOKENS)
    }
}
