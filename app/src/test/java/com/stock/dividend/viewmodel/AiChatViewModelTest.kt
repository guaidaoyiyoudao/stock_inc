package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.agent.AiChatEvent
import com.stock.dividend.data.agent.AiChatRepository
import com.stock.dividend.data.agent.AiSessionMessage
import com.stock.dividend.data.agent.AiSessionSummary
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AiChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun send_appendsUserMessageAndResetsIsSending() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "会话1", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.send("s1", "你好") } returns flowOf(AiChatEvent.Final("你好呀"))
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        assertThat(vm.uiState.value.currentSessionId).isEqualTo("s1")
        vm.onInputChanged("你好")
        vm.onSend()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertThat(state.messages.map { it.text }).containsExactly("你好", "你好呀")
        assertThat(state.isSending).isFalse()
        assertThat(state.input).isEmpty()
    }

    @Test
    fun partial_accumulatesThenFinalOverwrites() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "会话1", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.send("s1", "hi") } returns flowOf(
            AiChatEvent.Partial("你"),
            AiChatEvent.Partial("好"),
            AiChatEvent.Final("你好呀")
        )
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onInputChanged("hi")
        vm.onSend()
        advanceUntilIdle()
        assertThat(vm.uiState.value.messages.last().text).isEqualTo("你好呀")
    }

    @Test
    fun final_closesStreamingFlag() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "会话1", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.send("s1", "hi") } returns flowOf(
            AiChatEvent.Partial("## 你好"),
            AiChatEvent.Final("## 你好呀")
        )
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onInputChanged("hi")
        vm.onSend()
        advanceUntilIdle()
        assertThat(vm.uiState.value.messages.last().streaming).isFalse()
    }

    @Test
    fun confirmationCard_canConfirmAndClear() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "会话1", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.send(any(), any()) } returns flowOf(
            AiChatEvent.ConfirmationRequest("req-1", "add_stock", "添加自选：600519")
        )
        coEvery { repository.confirm("s1", "req-1", true) } returns flowOf(AiChatEvent.Final("已添加"))
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onInputChanged("加自选")
        vm.onSend()
        advanceUntilIdle()
        assertThat(vm.uiState.value.pendingConfirmation?.requestId).isEqualTo("req-1")
        vm.onConfirm(vm.uiState.value.pendingConfirmation!!)
        advanceUntilIdle()
        assertThat(vm.uiState.value.pendingConfirmation).isNull()
        assertThat(vm.uiState.value.messages.last().text).isEqualTo("已添加")
        assertThat(vm.uiState.value.isSending).isFalse()
    }

    @Test
    fun cancelConfirmation_setsConfirmFalse() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "会话1", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.send(any(), any()) } returns flowOf(
            AiChatEvent.ConfirmationRequest("req-2", "remove_stock", "删除自选：600519")
        )
        coEvery { repository.confirm("s1", "req-2", false) } returns flowOf(AiChatEvent.Final("已取消"))
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onInputChanged("删")
        vm.onSend()
        advanceUntilIdle()
        vm.onReject(vm.uiState.value.pendingConfirmation!!)
        advanceUntilIdle()
        assertThat(vm.uiState.value.pendingConfirmation).isNull()
        assertThat(vm.uiState.value.messages.last().text).isEqualTo("已取消")
    }

    @Test
    fun errorEvent_appendsSystemBubble() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "会话1", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.send(any(), any()) } returns flowOf(AiChatEvent.Error("网络失败"))
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onInputChanged("x")
        vm.onSend()
        advanceUntilIdle()
        assertThat(vm.uiState.value.messages.last().role).isEqualTo(ChatRole.SYSTEM)
        assertThat(vm.uiState.value.messages.last().text).isEqualTo("网络失败")
    }

    @Test
    fun withoutLlmConfig_llmConfiguredIsFalse() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(false)
        coEvery { repository.listSessions() } returns emptyList()
        coEvery { repository.createSession() } returns "s1"
        coEvery { repository.loadMessages("s1") } returns emptyList()
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        assertThat(vm.uiState.value.llmConfigured).isFalse()
    }

    @Test
    fun withoutSession_autoCreatesAndSelects() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns emptyList()
        coEvery { repository.createSession() } returns "s-new"
        coEvery { repository.loadMessages("s-new") } returns emptyList()
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        assertThat(vm.uiState.value.currentSessionId).isEqualTo("s-new")
    }

    @Test
    fun newSession_switchesAndClearsMessages() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "会话1", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.createSession() } returns "s2"
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onNewSession()
        advanceUntilIdle()
        assertThat(vm.uiState.value.currentSessionId).isEqualTo("s2")
        assertThat(vm.uiState.value.messages).isEmpty()
    }

    @Test
    fun thinking_accumulatesThenFinalClosesStreaming() = runTest {
        // 复现 web_search 真实时序：先思考（reasoning），再最终答案
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "会话1", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.send("s1", "今天大盘") } returns flowOf(
            AiChatEvent.Thinking("我需要搜索"),
            AiChatEvent.Thinking("今日A股行情"),
            AiChatEvent.Final("今天A股收涨，沪指 3878"),
        )
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onInputChanged("今天大盘")
        vm.onSend()
        advanceUntilIdle()
        val agent = vm.uiState.value.messages.last { it.role == ChatRole.AGENT }
        // 思考过程累积到 thinking 字段
        assertThat(agent.thinking).isEqualTo("我需要搜索今日A股行情")
        // Final 后思考结束（可折叠）
        assertThat(agent.thinkingStreaming).isFalse()
        // 最终答案在 text
        assertThat(agent.text).isEqualTo("今天A股收涨，沪指 3878")
    }

    @Test
    fun thinkingDone_stopsSpinnerBeforeFinal() = runTest {
        // reasoning_text.done 在 Final 之前到达 → 转圈应立即停（不等 Final）
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "会话1", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.send("s1", "hi") } returns flowOf(
            AiChatEvent.Thinking("思考中"),
            AiChatEvent.ThinkingDone,   // reasoning 段结束
            AiChatEvent.Final("答案是"),
        )
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onInputChanged("hi")
        vm.onSend()
        advanceUntilIdle()
        val agent = vm.uiState.value.messages.last { it.role == ChatRole.AGENT }
        assertThat(agent.thinking).isEqualTo("思考中")
        assertThat(agent.thinkingStreaming).isFalse()  // ThinkingDone 已停转圈
        assertThat(agent.text).isEqualTo("答案是")
    }

    @Test
    fun selectSession_loadsMessages() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns
            listOf(AiSessionSummary("s1", "会话1", 1000), AiSessionSummary("s2", "会话2", 2000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.loadMessages("s2") } returns listOf(
            AiSessionMessage(isUser = true, text = "历史问题"),
            AiSessionMessage(isUser = false, text = "历史回答")
        )
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onSelectSession("s2")
        advanceUntilIdle()
        assertThat(vm.uiState.value.currentSessionId).isEqualTo("s2")
        assertThat(vm.uiState.value.messages.map { it.text }).containsExactly("历史问题", "历史回答").inOrder()
    }

    @Test
    fun firstRoundInNewSession_triggersTitleGeneration() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "新会话", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.send("s1", "你好") } returns flowOf(AiChatEvent.Final("你好呀"))
        coEvery { repository.ensureTitle("s1", "你好", "你好呀") } returns Unit
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onInputChanged("你好")
        vm.onSend()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.ensureTitle("s1", "你好", "你好呀") }
    }

    @Test
    fun sessionWithTitle_skipsTitleGeneration() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "持仓分析", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.send("s1", "你好") } returns flowOf(AiChatEvent.Final("你好呀"))
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onInputChanged("你好")
        vm.onSend()
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.ensureTitle(any(), any(), any()) }
    }

    @Test
    fun deleteSession_callsRepoAndRefreshesList() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "会话1", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.deleteSession("s1") } returns Unit
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onDeleteSession("s1")
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.deleteSession("s1") }
    }

    @Test
    fun toolStatus_sequenceFinalizesPreviousAndAppendsChineseName() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "会话1", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        // 两个工具接连调用，最后回复：第一个工具应在第二个开始时被标记完成
        coEvery { repository.send(any(), any()) } returns flowOf(
            AiChatEvent.ToolStatus("get_holdings"),
            AiChatEvent.ToolStatus("get_kline"),
            AiChatEvent.Final("分析完成")
        )
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onInputChanged("分析下")
        vm.onSend()
        advanceUntilIdle()

        val toolMsgs = vm.uiState.value.messages.filter { it.role == ChatRole.TOOL }
        assertThat(toolMsgs).hasSize(2)
        // 中文动作名（非原始 snake_case）
        assertThat(toolMsgs[0].toolCall?.displayName).isEqualTo("查询持仓")
        assertThat(toolMsgs[1].toolCall?.displayName).isEqualTo("查询 K 线")
        // 第一个被后续工具标记完成，第二个被 Final 标记完成
        assertThat(toolMsgs[0].toolCall?.status).isEqualTo(ToolCallStatus.DONE)
        assertThat(toolMsgs[1].toolCall?.status).isEqualTo(ToolCallStatus.DONE)
    }

    @Test
    fun errorEvent_finalizesRunningToolsAsFailed() = runTest {
        val repository = mockk<AiChatRepository>()
        coEvery { repository.observeConfigured() } returns flowOf(true)
        coEvery { repository.listSessions() } returns listOf(AiSessionSummary("s1", "会话1", 1000))
        coEvery { repository.loadMessages("s1") } returns emptyList()
        coEvery { repository.send(any(), any()) } returns flowOf(
            AiChatEvent.ToolStatus("get_holdings"),
            AiChatEvent.Error("网络失败")
        )
        val vm = AiChatViewModel(repository)
        advanceUntilIdle()
        vm.onInputChanged("x")
        vm.onSend()
        advanceUntilIdle()

        val toolMsg = vm.uiState.value.messages.single { it.role == ChatRole.TOOL }
        assertThat(toolMsg.toolCall?.status).isEqualTo(ToolCallStatus.FAILED)
    }
}
