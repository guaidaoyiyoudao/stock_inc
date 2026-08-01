package com.stock.dividend.data.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenAiSseParserTest {

    @Test
    fun parseSseDataLine_parsesJsonAfterDataPrefix() {
        val chunk = parseSseDataLine("""data: {"choices":[{"delta":{"content":"你"}}]}""")
        assertThat(chunk).isNotNull()
        assertThat(chunk!!.choices.single().delta!!.content).isEqualTo("你")
    }

    @Test
    fun parseSseDataLine_ignoresDoneAndNonDataLines() {
        assertThat(parseSseDataLine("data: [DONE]")).isNull()
        assertThat(parseSseDataLine(": keep-alive")).isNull()
        assertThat(parseSseDataLine("")).isNull()
        assertThat(parseSseDataLine("data: not-json{{{")).isNull()
    }

    @Test
    fun accumulator_accumulatesTextAndProducesFinalResponse() {
        val acc = SseAccumulator()
        assertThat(
            acc.onChunk(OpenAiSseChunk(choices = listOf(OpenAiSseChoice(delta = OpenAiDelta(content = "你好")))))
        ).isEqualTo("你好")
        assertThat(
            acc.onChunk(OpenAiSseChunk(choices = listOf(OpenAiSseChoice(delta = OpenAiDelta(content = "世界")))))
        ).isEqualTo("世界")
        val final = acc.finish()
        assertThat(final.choices.single().message!!.content).isEqualTo("你好世界")
        assertThat(final.choices.single().finishReason).isEqualTo("stop")
    }

    @Test
    fun accumulator_accumulatesToolCallsByIndex() {
        val acc = SseAccumulator()
        acc.onChunk(
            OpenAiSseChunk(
                choices = listOf(
                    OpenAiSseChoice(
                        delta = OpenAiDelta(
                            toolCalls = listOf(
                                OpenAiToolCallDelta(index = 0, id = "call-1", function = OpenAiFunctionCallDelta(name = "add_stock"))
                            )
                        )
                    )
                )
            )
        )
        acc.onChunk(
            OpenAiSseChunk(
                choices = listOf(
                    OpenAiSseChoice(
                        delta = OpenAiDelta(
                            toolCalls = listOf(
                                OpenAiToolCallDelta(index = 0, function = OpenAiFunctionCallDelta(arguments = """{"code":"60"""))
                            )
                        )
                    )
                )
            )
        )
        acc.onChunk(
            OpenAiSseChunk(
                choices = listOf(
                    OpenAiSseChoice(
                        delta = OpenAiDelta(
                            toolCalls = listOf(
                                OpenAiToolCallDelta(index = 0, function = OpenAiFunctionCallDelta(arguments = """0519"}"""))
                            )
                        )
                    )
                )
            )
        )
        val final = acc.finish()
        val tc = final.choices.single().message!!.toolCalls!!.single()
        assertThat(tc.id).isEqualTo("call-1")
        assertThat(tc.function.name).isEqualTo("add_stock")
        assertThat(tc.function.arguments).isEqualTo("""{"code":"600519"}""")
        assertThat(final.choices.single().finishReason).isEqualTo("tool_calls")
    }
}
