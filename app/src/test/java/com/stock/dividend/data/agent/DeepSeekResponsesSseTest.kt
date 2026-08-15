package com.stock.dividend.data.agent

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Test

class DeepSeekResponsesSseTest {

    @Test
    fun feed_pairsEventAndDataAcrossLines() {
        val parser = ResponsesSseLineParser()
        assertThat(parser.feed("event: response.output_text.delta")).isNull()
        val event = parser.feed("""data: {"delta":"你好"}""")
        assertThat(event).isNotNull()
        assertThat(event!!.type).isEqualTo("response.output_text.delta")
        assertThat(event.data).contains("你好")
    }

    @Test
    fun feed_blankLineResetsPendingEvent() {
        val parser = ResponsesSseLineParser()
        parser.feed("event: response.created")
        assertThat(parser.feed("")).isNull() // 空行复位
        // 之后来 data 行，因 event 已复位，应返回 null（无配对）
        assertThat(parser.feed("""data: {}""")).isNull()
    }

    @Test
    fun feed_commentAndUnknownLinesIgnored() {
        val parser = ResponsesSseLineParser()
        assertThat(parser.feed(": keep-alive")).isNull()
        assertThat(parser.feed("id: 123")).isNull()
        assertThat(parser.feed("event: response.output_text.delta")).isNull()
        val event = parser.feed("""data: {"delta":"x"}""")
        assertThat(event).isNotNull()
    }

    @Test
    fun feed_dataWithoutEventIsIgnored() {
        val parser = ResponsesSseLineParser()
        assertThat(parser.feed("""data: {"delta":"x"}""")).isNull()
    }

    @Test
    fun feed_multipleEventsParsedSequentially() {
        val parser = ResponsesSseLineParser()
        val events = mutableListOf<ResponsesSseEvent?>()
        listOf(
            "event: response.created",
            """data: {"id":"resp_1"}""",
            "", // 分隔
            "event: response.output_text.delta",
            """data: {"delta":"你"}""",
            "", // 分隔
            "event: response.output_text.delta",
            """data: {"delta":"好"}""",
        ).forEach { events += parser.feed(it) }
        val nonNull = events.filterNotNull()
        assertThat(nonNull.map { it.type }).containsExactly(
            "response.created", "response.output_text.delta", "response.output_text.delta"
        ).inOrder()
        assertThat(nonNull[1].data).contains("你")
        assertThat(nonNull[2].data).contains("好")
    }

    @Test
    fun accumulator_outputDeltasReturnOutputType() {
        val acc = ResponsesSseAccumulator(Gson())
        assertThat(acc.onEvent(ResponsesSseEvent("response.output_text.delta", """{"delta":"你好"}""")))
            .isEqualTo(SseTextDelta.Output("你好"))
        assertThat(acc.onEvent(ResponsesSseEvent("response.output_text.delta", """{"delta":"世界"}""")))
            .isEqualTo(SseTextDelta.Output("世界"))
        // web_search 事件不产生文本增量
        assertThat(acc.onEvent(ResponsesSseEvent("response.web_search_call.in_progress", """{}"""))).isNull()
    }

    @Test
    fun accumulator_reasoningDeltasReturnReasoningType() {
        val acc = ResponsesSseAccumulator(Gson())
        assertThat(acc.onEvent(ResponsesSseEvent("response.reasoning_text.delta", """{"delta":"我需要"}""")))
            .isEqualTo(SseTextDelta.Reasoning("我需要"))
        assertThat(acc.onEvent(ResponsesSseEvent("response.reasoning_text.delta", """{"delta":"搜索行情"}""")))
            .isEqualTo(SseTextDelta.Reasoning("搜索行情"))
    }

    @Test
    fun accumulator_reasoningTextDoneReturnsReasoningDoneSignal() {
        val acc = ResponsesSseAccumulator(Gson())
        // 先来个思考增量
        acc.onEvent(ResponsesSseEvent("response.reasoning_text.delta", """{"delta":"思考中"}"""))
        // reasoning_text.done 应返回 ReasoningDone 信号（UI 据此停转圈）
        val done = acc.onEvent(ResponsesSseEvent("response.reasoning_text.done", """{"text":"思考中"}"""))
        assertThat(done).isEqualTo(SseTextDelta.ReasoningDone)
    }

    @Test
    fun accumulator_reasoningAndOutputAreClassifiedSeparately() {
        val acc = ResponsesSseAccumulator(Gson())
        // reasoning 先到（web_search 思考阶段），output 后到（最终答案）
        val r = acc.onEvent(ResponsesSseEvent("response.reasoning_text.delta", """{"delta":"思考中"}"""))
        val o = acc.onEvent(ResponsesSseEvent("response.output_text.delta", """{"delta":"答案"}"""))
        assertThat(r).isInstanceOf(SseTextDelta.Reasoning::class.java)
        assertThat(o).isInstanceOf(SseTextDelta.Output::class.java)
        assertThat((r as SseTextDelta.Reasoning).text).isEqualTo("思考中")
        assertThat((o as SseTextDelta.Output).text).isEqualTo("答案")
    }

    @Test
    fun accumulator_completedCapturesFullOutput() {
        val acc = ResponsesSseAccumulator(Gson())
        acc.onEvent(ResponsesSseEvent("response.output_text.delta", """{"delta":"你好"}"""))
        acc.onEvent(ResponsesSseEvent("response.output_text.delta", """{"delta":"世界"}"""))
        acc.onEvent(
            ResponsesSseEvent(
                "response.completed",
                """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"你好世界"}]}]}"""
            )
        )
        val final = acc.finish()
        assertThat(final.status).isEqualTo("completed")
        assertThat(final.output).hasSize(1)
        assertThat(final.output[0].getSafeString("type")).isEqualTo("message")
    }

    @Test
    fun accumulator_fallbackBuildsMessageFromAccumulatedTextWhenNoOutput() {
        val acc = ResponsesSseAccumulator(Gson())
        acc.onEvent(ResponsesSseEvent("response.output_text.delta", """{"delta":"兜底文本"}"""))
        // 没有 response.completed，finish 应回退构造 message
        val final = acc.finish()
        assertThat(final.output).hasSize(1)
        val msg = final.output[0]
        assertThat(msg.getSafeString("type")).isEqualTo("message")
        val text = msg.getAsJsonArray("content")[0].asJsonObject.getSafeString("text")
        assertThat(text).isEqualTo("兜底文本")
    }

    @Test
    fun accumulator_ignoresUnrelatedEvents() {
        val acc = ResponsesSseAccumulator(Gson())
        // in_progress 等非文本事件返回 null
        assertThat(acc.onEvent(ResponsesSseEvent("response.in_progress", """{}"""))).isNull()
        val final = acc.finish()
        assertThat(final.output).isEmpty() // 无文本无 completed
    }

    @Test
    fun accumulator_malformedDeltaJsonDoesNotCrash() {
        val acc = ResponsesSseAccumulator(Gson())
        assertThat(acc.onEvent(ResponsesSseEvent("response.output_text.delta", """not-json"""))).isNull()
        assertThat(acc.onEvent(ResponsesSseEvent("response.output_text.delta", """{"delta":""}"""))).isNull()
    }

    @Test
    fun accumulator_capturesFunctionCallFromStreamingEvents() {
        // 真实流式工具调用序列：output_item.added（function_call）→ function_call_arguments.delta × N → output_item.done
        val acc = ResponsesSseAccumulator(Gson())
        val itemId = "fc_001"
        acc.onEvent(
            ResponsesSseEvent(
                "response.output_item.added",
                """{"type":"response.output_item.added","item":{"type":"function_call","id":"$itemId","call_id":"call_1","name":"get_stock_info","arguments":"","status":"in_progress"},"output_index":1}"""
            )
        )
        // 参数分块到达
        acc.onEvent(ResponsesSseEvent("response.function_call_arguments.delta", """{"type":"response.function_call_arguments.delta","item_id":"$itemId","delta":"{\"code\":"}"""))
        acc.onEvent(ResponsesSseEvent("response.function_call_arguments.delta", """{"type":"response.function_call_arguments.delta","item_id":"$itemId","delta":"\"600519\"}"}"""))
        acc.onEvent(
            ResponsesSseEvent(
                "response.output_item.done",
                """{"type":"response.output_item.done","item":{"type":"function_call","id":"$itemId","call_id":"call_1","name":"get_stock_info","arguments":"{\"code\":\"600519\"}","status":"completed"},"output_index":1}"""
            )
        )
        // completed 只带 message（实测流式如此），function_call 须由累积器自行保留
        acc.onEvent(
            ResponsesSseEvent(
                "response.completed",
                """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"正在查询"}]}]}"""
            )
        )
        val final = acc.finish()
        val types = final.output.map { it.getSafeString("type") }
        assertThat(types).containsExactly("message", "function_call")
        val fc = final.output.first { it.getSafeString("type") == "function_call" }
        assertThat(fc.getSafeString("call_id")).isEqualTo("call_1")
        assertThat(fc.getSafeString("name")).isEqualTo("get_stock_info")
        assertThat(fc.getSafeString("arguments")).isEqualTo("""{"code":"600519"}""")
    }
}
