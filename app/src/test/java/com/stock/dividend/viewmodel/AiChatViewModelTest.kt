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
    fun `发送后追加用户消息并复位 isSending`() = runTest {
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
    fun `流式 Partial 累积后 Final 覆盖`() = runTest {
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
    fun `Partial 消息标记流式中，Final 后关闭流式标记`() = runTest {
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
    fun `确认卡片出现后可确认并清空`() = runTest {
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
    fun `取消确认走 confirm false`() = runTest {
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
    fun `错误事件追加 SYSTEM 气泡`() = runTest {
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
    fun `未配置 LLM 时 llmConfigured 为 false`() = runTest {
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
    fun `无会话时自动创建并选中`() = runTest {
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
    fun `新建会话切换并清空消息`() = runTest {
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
    fun `选择历史会话加载消息`() = runTest {
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
    fun `新会话首轮完成后触发标题生成`() = runTest {
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
    fun `已有标题的会话不触发标题生成`() = runTest {
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
    fun `删除会话调用仓库并刷新列表`() = runTest {
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
}
