package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.viewmodel.ChatMessageUi
import com.stock.dividend.viewmodel.ChatRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 聊天列表自动跟底契约（AiChatScreen 滚底 bug 的回归锁定）：
 * 用固定高度 Box 模拟「流式纯文本（矮）→ 定稿 Markdown（高）」的末条渲染高度变化，
 * 断言 [ChatAutoScrollEffect] 在**文本等长、仅 streaming 翻转**的定稿事件后仍滚到
 * 列表绝对底部（canScrollForward == false 即到底）。动画路径的滚动挂在测试时钟上，
 * 断言前用 waitUntil 等动画真正结束，不能用 runOnIdle（动画中途就会触发）。
 */
@RunWith(RobolectricTestRunner::class)
class AiChatScrollToBottomTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 复刻 AiChatScreen 的列表结构；末条高度按角色/streaming 模拟真实渲染高度差。 */
    private fun setup(
        messages: State<List<ChatMessageUi>>,
        listState: LazyListState,
        asyncExtraHeight: State<Int> = mutableStateOf(0),
    ) {
        composeRule.setContent {
            ChatAutoScrollEffect(messages.value, listState)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(12.dp),
            ) {
                itemsIndexed(messages.value, key = { index, _ -> index }) { _, message ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(messageHeight(message) + asyncExtraHeight.value.dp)
                    )
                }
            }
        }
    }

    private fun messageHeight(message: ChatMessageUi) = when {
        message.role == ChatRole.USER -> 100.dp
        message.streaming -> 2500.dp // 流式纯文本（已长高超过视口）
        else -> 3000.dp              // 定稿 Markdown（标题/间距/复制行）+ 超过任何测试视口
    }

    private fun assertAtAbsoluteBottom(listState: LazyListState) {
        // 绝对底部 = 不可再向前滚；末条已进入视口
        assertThat(listState.canScrollForward).isFalse()
        assertThat(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index)
            .isEqualTo(listState.layoutInfo.totalItemsCount - 1)
    }

    @Test
    fun `定稿等长变高时仍滚到绝对底部（近距离动画）`() {
        val listState = LazyListState()
        val longReply = "长回复正文".repeat(200)
        val messages = mutableStateOf(
            listOf(
                ChatMessageUi(ChatRole.USER, "问题"),
                ChatMessageUi(ChatRole.AGENT, longReply, streaming = true),
            )
        )
        setup(messages, listState)
        composeRule.waitForIdle()

        // Final 定稿：文本等长、仅 streaming 翻转 false —— 旧实现（key=text.length）不会重滚
        composeRule.runOnIdle { messages.value = messages.value.map { it.copy(streaming = false) } }
        composeRule.waitUntil(5_000) { !listState.canScrollForward }
        composeRule.runOnIdle { assertAtAbsoluteBottom(listState) }
    }

    @Test
    fun `追加超高长回复时滚到绝对底部（流式 snap）`() {
        val listState = LazyListState()
        val messages = mutableStateOf(listOf(ChatMessageUi(ChatRole.USER, "问题")))
        setup(messages, listState)
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            messages.value = messages.value + ChatMessageUi(ChatRole.AGENT, "流式到达的超高长回复", streaming = true)
        }
        composeRule.waitUntil(5_000) { !listState.canScrollForward }
        composeRule.runOnIdle { assertAtAbsoluteBottom(listState) }
    }

    @Test
    fun `切换会话加载长历史时动画滚到绝对底部（远距离动画）`() {
        val listState = LazyListState()
        val messages = mutableStateOf(listOf(ChatMessageUi(ChatRole.USER, "旧会话的问题")))
        setup(messages, listState)
        composeRule.waitForIdle()

        // 整体替换为长历史：末条是不可见的超高定稿回复，应走远距离动画路径
        composeRule.runOnIdle {
            messages.value = buildList {
                repeat(5) { i -> add(ChatMessageUi(ChatRole.USER, "历史消息 $i")) }
                add(ChatMessageUi(ChatRole.AGENT, "历史会话末尾的超高长回复"))
            }
        }
        composeRule.waitUntil(5_000) { !listState.canScrollForward }
        composeRule.runOnIdle { assertAtAbsoluteBottom(listState) }
    }

    /**
     * 定稿后内容异步变高（真机根因：MarkdownText 为 AndroidView+Markwon，代码块语法高亮
     * 在定稿滚动完成后的后续帧才撑高）——没有消息事件可触发跟底，必须由布局观察补偿。
     * 仅改末条渲染高度、不动 messages，复现「无事件路径」。
     */
    @Test
    fun `定稿后末条异步变高时补滚到绝对底部`() {
        val listState = LazyListState()
        val longReply = "长回复正文".repeat(200)
        val messages = mutableStateOf(
            listOf(
                ChatMessageUi(ChatRole.USER, "问题"),
                ChatMessageUi(ChatRole.AGENT, longReply, streaming = true),
            )
        )
        val asyncExtraHeight = mutableStateOf(0)
        setup(messages, listState, asyncExtraHeight)
        composeRule.waitForIdle()

        composeRule.runOnIdle { messages.value = messages.value.map { it.copy(streaming = false) } }
        composeRule.waitUntil(5_000) { !listState.canScrollForward }
        composeRule.runOnIdle { assertAtAbsoluteBottom(listState) }

        // 定稿滚动已完成后，代码块高亮才把末条撑高 600dp：尾部应沉到视口外后被补偿拉回
        composeRule.runOnIdle { asyncExtraHeight.value = 600 }
        composeRule.waitUntil(5_000) { !listState.canScrollForward }
        composeRule.runOnIdle { assertAtAbsoluteBottom(listState) }
    }
}
