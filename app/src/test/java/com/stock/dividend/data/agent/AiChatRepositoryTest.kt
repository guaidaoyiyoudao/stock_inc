package com.stock.dividend.data.agent

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.agent.tools.AddStockTool
import com.stock.dividend.data.agent.tools.GetHoldingsTool
import com.stock.dividend.data.repository.LlmConfig
import com.stock.dividend.data.repository.LlmConfigSource
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.StockSearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AiChatRepositoryTest {

    /** 脚本化假 Model：按调用次数依次返回预置响应。 */
    private class ScriptedModel(
        private val script: MutableList<() -> LlmResponse>,
    ) : Model {
        override val name: String = "fake"
        private var calls = 0

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
            val index = calls++
            emit(script[index]())
        }
    }

    private fun configSource(config: LlmConfig) = object : LlmConfigSource {
        override fun observeConfig(): Flow<LlmConfig> = flowOf(config)
    }

    @Test
    fun `只读工具回路：tool_calls 后文本，事件含 ToolStatus 与 Final`() = runTest {
        val stockRepository = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepository.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { stockRepository.getCachedPrices(emptyList()) } returns emptyMap()
        val model = ScriptedModel(
            mutableListOf(
                {
                    LlmResponse(
                        content = Content(
                            role = Role.MODEL,
                            parts = listOf(
                                Part(functionCall = FunctionCall(name = "get_holdings", args = emptyMap(), id = "c1"))
                            )
                        ),
                        finishReason = FinishReason.STOP
                    )
                },
                {
                    LlmResponse(content = Content(role = Role.MODEL, parts = listOf(Part(text = "共 0 只"))))
                }
            )
        )
        val factory = mockk<AiAgentFactory>(relaxed = true)
        coEvery { factory.create(any()) } returns LlmAgent(
            name = "ai_tab_agent",
            model = model,
            instruction = Instruction("test"),
            tools = listOf(GetHoldingsTool(stockRepository))
        )
        val repository = AiChatRepository(
            configSource(LlmConfig("http://x", "k", "m")),
            factory,
            com.google.adk.kt.sessions.InMemorySessionService(),
            mockk<AiTitleGenerator>(relaxed = true)
        )
        val sessionId = repository.createSession()
        val events = repository.send(sessionId, "我的持仓").toList()
        assertThat(events).contains(AiChatEvent.ToolStatus("get_holdings"))
        assertThat(events.filterIsInstance<AiChatEvent.Final>().single().text).isEqualTo("共 0 只")
    }

    @Test
    fun `会话创建后列表返回默认标题`() = runTest {
        val repository = AiChatRepository(
            configSource(LlmConfig("http://x", "k", "m")),
            mockk(relaxed = true),
            com.google.adk.kt.sessions.InMemorySessionService(),
            mockk(relaxed = true)
        )
        val id = repository.createSession()
        assertThat(id).isNotEmpty()
        val sessions = repository.listSessions()
        assertThat(sessions.single().id).isEqualTo(id)
        assertThat(sessions.single().title).isEqualTo("新会话")
    }

    @Test
    fun `loadMessages 还原用户与助手消息`() = runTest {
        val stockRepository = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepository.observeAllStocksForSnapshot() } returns emptyList()
        coEvery { stockRepository.getCachedPrices(emptyList()) } returns emptyMap()
        val model = ScriptedModel(
            mutableListOf(
                {
                    LlmResponse(
                        content = Content(
                            role = Role.MODEL,
                            parts = listOf(
                                Part(functionCall = FunctionCall(name = "get_holdings", args = emptyMap(), id = "c1"))
                            )
                        )
                    )
                },
                { LlmResponse(content = Content(role = Role.MODEL, parts = listOf(Part(text = "共 0 只")))) }
            )
        )
        val factory = mockk<AiAgentFactory>(relaxed = true)
        coEvery { factory.create(any()) } returns LlmAgent(
            name = "ai_tab_agent",
            model = model,
            instruction = Instruction("test"),
            tools = listOf(GetHoldingsTool(stockRepository))
        )
        val sessionService = com.google.adk.kt.sessions.InMemorySessionService()
        val repository = AiChatRepository(
            configSource(LlmConfig("http://x", "k", "m")),
            factory,
            sessionService,
            mockk<AiTitleGenerator>(relaxed = true)
        )
        val sessionId = repository.createSession()
        repository.send(sessionId, "我的持仓").toList()
        val messages = repository.loadMessages(sessionId)
        assertThat(messages.map { it.text }).containsExactly("我的持仓", "共 0 只").inOrder()
        assertThat(messages[0].isUser).isTrue()
        assertThat(messages[1].isUser).isFalse()
    }

    @Test
    fun `ensureTitle 用 LLM 标题更新会话`() = runTest {
        val titleGenerator = mockk<AiTitleGenerator>(relaxed = true)
        coEvery { titleGenerator.generate(any(), "你好", "回复") } returns "持仓分析"
        val sessionService = com.google.adk.kt.sessions.InMemorySessionService()
        val repository = AiChatRepository(
            configSource(LlmConfig("http://x", "k", "m")),
            mockk(relaxed = true),
            sessionService,
            titleGenerator
        )
        val sessionId = repository.createSession()
        repository.ensureTitle(sessionId, "你好", "回复")
        assertThat(repository.listSessions().single().title).isEqualTo("持仓分析")
    }

    @Test
    fun `写工具确认门：先 ConfirmationRequest，确认后执行并 Final`() = runTest {
        val stockRepository = mockk<StockRepository>(relaxed = true)
        coEvery { stockRepository.resolveStock("600519") } returns
            StockSearchResult(code = "sh.600519", name = "贵州茅台", marketCode = "1")
        coEvery { stockRepository.addStock(any(), any(), any(), any()) } returns Result.success(Unit)

        val model = ScriptedModel(
            mutableListOf(
                {
                    LlmResponse(
                        content = Content(
                            role = Role.MODEL,
                            parts = listOf(
                                Part(functionCall = FunctionCall(name = "add_stock", args = mapOf("code" to "600519"), id = "c1"))
                            )
                        )
                    )
                }
            )
        )
        val factory = mockk<AiAgentFactory>(relaxed = true)
        coEvery { factory.create(any()) } returns LlmAgent(
            name = "ai_tab_agent",
            model = model,
            instruction = Instruction("test"),
            tools = listOf(AddStockTool(stockRepository))
        )
        val repository = AiChatRepository(
            configSource(LlmConfig("http://x", "k", "m")),
            factory,
            com.google.adk.kt.sessions.InMemorySessionService(),
            mockk<AiTitleGenerator>(relaxed = true)
        )

        val sessionId = repository.createSession()
        val first = repository.send(sessionId, "加自选 600519").toList()
        val confirmation = first.filterIsInstance<AiChatEvent.ConfirmationRequest>().single()
        assertThat(confirmation.toolName).isEqualTo("add_stock")
        assertThat(confirmation.summary).contains("600519")
        coVerify(exactly = 0) { stockRepository.addStock(any(), any(), any(), any()) }

        val model2 = ScriptedModel(
            mutableListOf(
                { LlmResponse(content = Content(role = Role.MODEL, parts = listOf(Part(text = "已添加")))) }
            )
        )
        coEvery { factory.create(any()) } returns LlmAgent(
            name = "ai_tab_agent",
            model = model2,
            instruction = Instruction("test"),
            tools = listOf(AddStockTool(stockRepository))
        )
        val second = repository.confirm(sessionId, confirmation.requestId, confirmed = true).toList()
        assertThat(second.filterIsInstance<AiChatEvent.Final>().single().text).isEqualTo("已添加")
        coVerify { stockRepository.addStock(any(), 0, 0.0, any()) }
    }

    @Test
    fun `未配置 LLM 时 send 直接返回错误事件`() = runTest {
        val factory = mockk<AiAgentFactory>(relaxed = true)
        val repository = AiChatRepository(
            configSource(LlmConfig(baseUrl = "", apiKey = "", model = "")),
            factory,
            com.google.adk.kt.sessions.InMemorySessionService(),
            mockk<AiTitleGenerator>(relaxed = true)
        )
        val events = repository.send("s1", "hi").toList()
        assertThat(events.single()).isInstanceOf(AiChatEvent.Error::class.java)
        coVerify(exactly = 0) { factory.create(any()) }
    }
}
