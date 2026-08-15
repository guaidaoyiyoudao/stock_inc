package com.stock.dividend.data.agent

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
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
import com.google.gson.JsonParser
import org.junit.Test

class DeepSeekResponsesProtocolTest {
    private val gson = Gson()

    @Test
    fun buildResponsesRequest_systemInstructionGoesToInstructionsNotInput() {
        val request = buildResponsesRequest(
            LlmRequest(
                config = GenerateContentConfig(
                    systemInstruction = Content(role = Role.SYSTEM, parts = listOf(Part(text = "你是助手")))
                ),
                contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "你好"))))
            ),
            modelName = "deepseek-v4-flash"
        )
        assertThat(request.model).isEqualTo("deepseek-v4-flash")
        assertThat(request.instructions).isEqualTo("你是助手")
        val input = request.input as List<*>
        assertThat(input).hasSize(1)
        val msg = input.single() as ResponsesMessageItem
        assertThat(msg.role).isEqualTo("user")
        assertThat(msg.content).isEqualTo("你好")
    }

    @Test
    fun buildResponsesRequest_functionCallBecomesFunctionCallItemWithCallId() {
        val request = buildResponsesRequest(
            LlmRequest(
                contents = listOf(
                    Content(
                        role = Role.MODEL,
                        parts = listOf(
                            Part(functionCall = FunctionCall(name = "get_holdings", args = mapOf("x" to 1), id = "call-1"))
                        )
                    )
                )
            ),
            modelName = "m"
        )
        val item = (request.input as List<*>).single() as ResponsesFunctionCallItem
        assertThat(item.type).isEqualTo("function_call")
        assertThat(item.callId).isEqualTo("call-1")
        assertThat(item.name).isEqualTo("get_holdings")
        assertThat(gson.fromJson(item.arguments, Map::class.java)).containsEntry("x", 1.0)
    }

    @Test
    fun buildResponsesRequest_functionResponseBecomesFunctionCallOutput() {
        val request = buildResponsesRequest(
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
        val item = (request.input as List<*>).single() as ResponsesFunctionCallOutputItem
        assertThat(item.type).isEqualTo("function_call_output")
        assertThat(item.callId).isEqualTo("call-1")
        assertThat(item.output).contains("holdings")
    }

    @Test
    fun buildResponsesRequest_declaresFunctionToolsWithTopLevelName() {
        val request = buildResponsesRequest(
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
                                        properties = mapOf("code" to Schema(type = Type.STRING)),
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
        val tool = request.tools!!.filterIsInstance<ResponsesFunctionTool>().single()
        assertThat(tool.type).isEqualTo("function")
        assertThat(tool.name).isEqualTo("get_stock_info")
        assertThat(tool.parameters).containsEntry("type", "object")
        assertThat(tool.parameters!!["required"]).isEqualTo(listOf("code"))
    }

    @Test
    fun buildResponsesRequest_includesWebSearchToolWhenEnabled() {
        val request = buildResponsesRequest(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "x"))))),
            modelName = "m",
            includeWebSearch = true
        )
        val toolTypes = request.tools!!.map { it::class.simpleName }
        assertThat(toolTypes).contains("ResponsesWebSearchTool")
        val webSearch = request.tools!!.filterIsInstance<ResponsesWebSearchTool>().single()
        assertThat(webSearch.type).isEqualTo("web_search")
    }

    @Test
    fun buildResponsesRequest_noWebSearchByDefault() {
        val request = buildResponsesRequest(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "x"))))),
            modelName = "m",
            includeWebSearch = false
        )
        assertThat(request.tools).isNull()
    }

    @Test
    fun buildResponsesRequest_toolsAbsentWhenNoDeclarationsAndNoWebSearch() {
        val request = buildResponsesRequest(
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "x"))))),
            modelName = "m"
        )
        assertThat(request.tools).isNull()
    }

    @Test
    fun toLlmResponse_messageOutputBecomesTextPart() {
        val response = ResponsesResponse(
            status = "completed",
            output = listOf(
                JsonParser.parseString(
                    """{"type":"message","content":[{"type":"output_text","text":"你好"}]}"""
                ).asJsonObject
            )
        )
        val llm = toLlmResponse(response, gson)
        assertThat(llm.content!!.parts.mapNotNull { it.text }).containsExactly("你好")
    }

    @Test
    fun toLlmResponse_functionCallOutputBecomesFunctionCallPart() {
        val response = ResponsesResponse(
            output = listOf(
                JsonParser.parseString(
                    """{"type":"function_call","call_id":"call-9","name":"add_stock","arguments":"{\"code\":\"600519\"}"}"""
                ).asJsonObject
            )
        )
        val llm = toLlmResponse(response, gson)
        val fc = llm.content!!.parts.mapNotNull { it.functionCall }.single()
        assertThat(fc.name).isEqualTo("add_stock")
        assertThat(fc.id).isEqualTo("call-9")
        assertThat(fc.args).containsEntry("code", "600519")
    }

    @Test
    fun toLlmResponse_webSearchCallIgnored() {
        val response = ResponsesResponse(
            output = listOf(
                JsonParser.parseString(
                    """{"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","query":"x"}}"""
                ).asJsonObject,
                JsonParser.parseString(
                    """{"type":"message","content":[{"type":"output_text","text":"结果"}]}"""
                ).asJsonObject
            )
        )
        val llm = toLlmResponse(response, gson)
        // web_search_call 不产生 Part，只 message 的文本进 Part
        assertThat(llm.content!!.parts).hasSize(1)
        assertThat(llm.content!!.parts.single().text).isEqualTo("结果")
    }

    @Test
    fun toLlmResponse_emptyOutputReturnsErrorMessage() {
        val llm = toLlmResponse(ResponsesResponse(output = emptyList()), gson)
        assertThat(llm.errorMessage).isNotNull()
    }

    @Test
    fun toLlmResponse_incompleteStatusMapsToMaxTokens() {
        val llm = toLlmResponse(
            ResponsesResponse(status = "incomplete", output = listOf(
                JsonParser.parseString("""{"type":"message","content":[{"type":"output_text","text":"x"}]}""").asJsonObject
            )),
            gson
        )
        assertThat(llm.finishReason?.name).isEqualTo("MAX_TOKENS")
    }
}
