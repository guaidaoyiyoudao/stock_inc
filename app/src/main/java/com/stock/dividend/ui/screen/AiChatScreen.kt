package com.stock.dividend.ui.screen

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.ViewTreeObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.scan.bitmapToJpegDataUrl
import com.stock.dividend.data.scan.loadSampledBitmap
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.viewmodel.AiChatUiState
import com.stock.dividend.viewmodel.AiChatViewModel
import com.stock.dividend.viewmodel.ChatMessageUi
import com.stock.dividend.viewmodel.ChatRole
import com.stock.dividend.viewmodel.ConfirmationUi
import com.stock.dividend.viewmodel.ToolCallStatus
import com.stock.dividend.viewmodel.ToolCallUi
import com.stock.dividend.viewmodel.canRenderMarkdown
import java.text.DateFormat
import java.util.Date
import dev.jeziellago.compose.markdowntext.MarkdownText
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SUGGESTIONS = listOf(
    "我的持仓怎么样？",
    "600519 现在能买吗？",
    "把 600519 加进自选",
    "记一笔每月 3000 元房租支出"
)

@Composable
fun AiChatScreen(
    onGoSettings: () -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: AiChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSessions by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val messages = state.messages
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    // 选图即下采样 + JPEG 压缩成 data URL（最长边 1600，单张约 150-400KB），
                    // 与截图导入链路同一套编解码工具
                    val bitmap = loadSampledBitmap(context, uri)
                    bitmapToJpegDataUrl(bitmap)
                }.onSuccess { viewModel.onImagePicked(it) }
                    .onFailure { viewModel.onImageLoadFailed() }
            }
        }
    }
    val onAttachImage: () -> Unit = {
        if (state.modelSupportsImages) {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            viewModel.onImageUnsupported()
        }
    }

    ChatAutoScrollEffect(messages, listState)

    if (!state.llmConfigured) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(16.dp))
            Text(
                text = "AI 助手待启用",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "需要先配置一个大模型（DeepSeek / 智谱 / 通义 / 自定义），\n配置后即可查询持仓、估值、行情并自动记账",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.size(20.dp))
            AppButton(
                onClick = onGoSettings,
                text = "去配置大模型",
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SessionBar(
            currentTitle = state.sessions.firstOrNull { it.id == state.currentSessionId }?.title ?: "AI 助手",
            onOpenSessions = { showSessions = true },
            onNewSession = viewModel::onNewSession,
            onOpenAiSettings = onOpenAiSettings,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (messages.isEmpty()) {
                item { ChatGreeting(onSuggestionClick = viewModel::onInputChanged) }
            }
            itemsIndexed(messages, key = { index, _ -> index }) { _, message ->
                MessageBubble(message)
            }
        }

        state.pendingConfirmation?.let { confirmation ->
            ConfirmationCard(
                confirmation = confirmation,
                onConfirm = { viewModel.onConfirm(confirmation) },
                onReject = { viewModel.onReject(confirmation) },
                confirmBusy = state.isSending
            )
        }

        ChatComposer(
            input = state.input,
            isSending = state.isSending,
            pendingImages = state.pendingImages,
            onInputChanged = viewModel::onInputChanged,
            onSend = viewModel::onSend,
            onAttachImage = onAttachImage,
            onRemovePendingImage = viewModel::onRemovePendingImage
        )
    }

    if (showSessions) {
        SessionSheet(
            sessions = state.sessions,
            currentSessionId = state.currentSessionId,
            onSelect = { sessionId ->
                showSessions = false
                viewModel.onSelectSession(sessionId)
            },
            onNewSession = {
                showSessions = false
                viewModel.onNewSession()
            },
            onDelete = { sessionId -> viewModel.onDeleteSession(sessionId) },
            onDismiss = { showSessions = false }
        )
    }
}

/**
 * 聊天列表自动跟底。key 必须是**末条消息对象**而非 text.length：
 * `Final` 事件定稿时流式增量拼接长度常与全文等长、仅 streaming 翻转 false，
 * 此时纯文本→MarkdownText 切换 + 复制行出现会让末条变高——按长度键控会漏掉
 * 这次高度增长，回复尾部滞留视口外（internal 供 Robolectric 单测复用）。
 * 滚动方式：流式中（streaming/thinkingStreaming）高频增量会不断取消动画，用
 * snap 确定性跟底；非流式变化（发送消息/定稿/切换会话）用动画平滑滚底。
 *
 * ⚠️ 定稿后内容仍可能**异步变高**（MarkdownText 为 AndroidView+Markwon，代码块
 * 语法高亮等在后续帧才完成），此时没有任何消息事件可触发——故额外观察
 * 「末条已测量高度 + 视口高度」的派生状态：只要还在贴底意图内（末条底部仍在
 * 视口内、未滚出多屏），布局一变就 snap 补滚一次。键盘开合导致的视口变化
 * 同样被覆盖。用户主动上滑翻历史（末条已滚出视口）则不追赶。
 */
@Composable
internal fun ChatAutoScrollEffect(
    messages: List<ChatMessageUi>,
    listState: LazyListState,
) {
    val last = messages.lastOrNull()
    // 派生状态：末条测量高度 / 列表视口高度（读 layoutInfo 不会触发重组，只有值变才重组）
    val lastMeasuredSize by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: -1 }
    }
    val viewportSize by remember {
        derivedStateOf { listState.layoutInfo.viewportSize.height }
    }
    LaunchedEffect(messages.size, last) {
        if (last != null) {
            val streaming = last.streaming || last.thinkingStreaming
            listState.scrollToAbsoluteBottom(messages.lastIndex, animate = !streaming)
        }
    }
    // 异步变高/视口变化的补偿滚动（无消息事件路径）
    LaunchedEffect(lastMeasuredSize, viewportSize) {
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        val isLast = lastVisible.index == info.totalItemsCount - 1
        // 仅当末条仍（部分）可见（=贴底意图未被打断）且底部存在缺口时补滚
        if (isLast && listState.canScrollForward) {
            listState.scrollToAbsoluteBottom(info.totalItemsCount - 1, animate = false)
        }
    }
}

/**
 * 滚到列表绝对底部（可选动画）：
 * - 近距离（末条已在视口内，如定稿变高）：原位 [scrollToItem] 强制 remeasure——
 *   定稿的高度增长发生在本帧 layout 之前，直接读 layoutInfo 会拿到旧值；
 * - 远距离（末条不可见，如发送消息/切换会话）：animate 先平滑滚到末条顶对齐
 *   （末条矮于视口时会被自动钳到底），snap 直接顶对齐；
 * - 收尾统一按「末条底边 − 视口底边」缺口补齐（末条高于视口时为正）。缺口因
 *   viewportEndOffset 的 padding 语义只可能偏大，偏大被滚动边界 clamp 到绝对
 *   底部，不会冲过头。
 * 不用 `animateScrollToItem(末条, 大 scrollOffset)` 一把梭：其动画目标取自起始帧
 * 测量，定稿同帧变高时落点会差一截。
 */
internal suspend fun LazyListState.scrollToAbsoluteBottom(lastIndex: Int, animate: Boolean = false) {
    if (lastIndex < 0) return
    val info = layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull()
    when {
        lastVisible != null && lastVisible.index == lastIndex ->
            scrollToItem(firstVisibleItemIndex, firstVisibleItemScrollOffset)
        animate -> animateScrollToItem(lastIndex)
        else -> scrollToItem(lastIndex)
    }
    val last = layoutInfo.visibleItemsInfo.lastOrNull()
        ?.takeIf { it.index == lastIndex && it.size > 0 } ?: return
    val viewportBottom = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    val gap = (last.offset + last.size) - viewportBottom
    if (gap > 0) {
        if (animate) animateScrollBy(gap.toFloat()) else scrollBy(gap.toFloat())
    }
}

@Composable
private fun SessionBar(
    currentTitle: String,
    onOpenSessions: () -> Unit,
    onNewSession: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenSessions) {
            Icon(Icons.Filled.Chat, contentDescription = "会话列表")
        }
        Text(
            text = currentTitle,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onNewSession) {
            Icon(Icons.Filled.Add, contentDescription = "新建会话")
        }
        IconButton(onClick = onOpenAiSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "AI 设置")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSheet(
    sessions: List<com.stock.dividend.data.agent.AiSessionSummary>,
    currentSessionId: String?,
    onSelect: (String) -> Unit,
    onNewSession: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "历史会话",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            if (sessions.isEmpty()) {
                Text(
                    text = "暂无会话",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(sessions, key = { it.id }) { session ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = session.id != currentSessionId) {
                                onSelect(session.id)
                            }
                            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = formatSessionTime(session.updatedAtMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(onClick = { onDelete(session.id) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除会话",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            AppTextButton(
                onClick = onNewSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                text = "新建会话",
                leadingIcon = Icons.Filled.Add,
            )
        }
    }
}

private fun formatSessionTime(updatedAtMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updatedAtMs))

@Composable
private fun ChatGreeting(onSuggestionClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "我是你的股息投资助手",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "可以查持仓、看行情、估值、买入线，也可以帮你记账和改持仓；\n多模态模型支持发送持仓/成交截图直接识别导入",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        // ChatGPT 式建议胶囊：全圆角 + 细边框，点击即填入输入框
        SUGGESTIONS.forEach { suggestion ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                ),
                onClick = { onSuggestionClick(suggestion) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessageUi) {
    when (message.role) {
        // 用户消息强制 LTR 布局方向：Alignment.End 是方向敏感对齐，个别 ROM/系统
        // 布局方向判定异常时会把用户气泡翻到左侧——聊天语义上用户消息永远贴右。
        ChatRole.USER -> CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Ltr
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 用户消息携带的图片（多模态输入）：缩略图置于文字上方
                if (message.images.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.images.forEach { url ->
                            DataUrlImage(
                                dataUrl = url,
                                contentDescription = "用户发送的图片",
                                modifier = Modifier
                                    .size(width = 110.dp, height = 150.dp)
                                    .clip(MaterialTheme.shapes.medium)
                            )
                        }
                    }
                }
                if (message.text.isNotEmpty()) {
                    // ChatGPT 式用户气泡：浅色大圆角（28dp）、内容自适应宽度靠右
                    // （widthIn 上限防长文占满整行），内边距舒展
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.widthIn(max = 300.dp)
                    ) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
        ChatRole.AGENT -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 思考过程（联网搜索/推理时，先于答案流式到达；让用户知道没卡住）
            message.thinking?.let { ThinkingSection(it, message.thinkingStreaming) }
            // 最终回复：ChatGPT 式无气泡全宽排版（正文即界面），长文完整展示不折叠
            if (message.text.isNotEmpty()) {
                // 流式半成品或语法不完整的 Markdown 只显示纯文本，完整后再渲染；
                // 流式期间文本尾部带 ▍ 光标，示意正在生成
                val displayText = if (message.streaming) message.text + " ▍" else message.text
                if (!message.streaming && canRenderMarkdown(message.text)) {
                    MarkdownTextSynced(markdown = message.text)
                } else {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                // 完成态回复底部操作行（ChatGPT 式）：一键复制全文
                if (!message.streaming) {
                    CopyActionRow(text = message.text)
                }
            }
        }
        ChatRole.SYSTEM -> Text(
            text = message.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        ChatRole.TOOL -> message.toolCall?.let { ToolCallPill(it) }
    }
}

/**
 * 高度同步版 MarkdownText（修复「回复尾部划不到底」的真机问题）。
 *
 * MarkdownText 内部是 AndroidView + Markwon TextView：个别 ROM / 系统字体缩放下，
 * TextView 布局完成后的真实高度没有（或迟滞地）传播回 Compose 测量，导致
 * LazyList 以偏小高度封顶滚动——回复尾部永远滚不出来、也不可见（被裁切）。
 *
 * 双保险：
 * 1. `afterSetMarkdown` 里主动 requestLayout 踢一脚迟滞的自测量；
 * 2. GlobalLayoutListener 观察 TextView 真实高度，经 `Modifier.layout` 强制
 *    Compose 节点高度 ≥ 真实值（两者一致时无副作用），高度变化再由
 *    ChatAutoScrollEffect 的布局补偿拉回绝对底部。
 */
@Composable
private fun MarkdownTextSynced(markdown: String) {
    var realHeightPx by remember(markdown) { mutableStateOf(0) }
    MarkdownText(
        markdown = markdown,
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val height = maxOf(placeable.height, realHeightPx)
            layout(placeable.width, height) { placeable.place(0, 0) }
        },
        afterSetMarkdown = { tv ->
            tv.post { tv.requestLayout() }
            // 同一 TextView 重复 setMarkdown 时只挂一次高度监听（防每次更新累积监听器；
            // 高度回填逻辑本身幂等——只增不减，多挂无益只有开销）
            if (tv.tag == null) {
                tv.tag = true
                tv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (tv.height > realHeightPx) realHeightPx = tv.height
                    }
                })
            }
        },
    )
}

/**
 * 回复底部操作行（ChatGPT 式）：复制全文到剪贴板，小图标弱化不抢正文视线。
 */
@Composable
private fun CopyActionRow(text: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    // 复制成功态 2 秒后自动复位（✓ 回到复制图标）
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000L)
            copied = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        IconButton(
            onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                copied = true
                android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = "复制回复",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 思考过程区（ChatGPT「已深度思考」样式）：
 * - 流式接收中：标题「思考中…」+ 转圈，默认展开；
 * - 接收完成：折叠为「已深度思考」细字标签（无重容器），点开查看斜体浅色正文 + 左侧竖线。
 * 文本用浅色小字纯文本展示（不渲染 Markdown，避免流式闪烁）。
 */@Composable
private fun ThinkingSection(thinking: String, streaming: Boolean) {
    // 流式时强制展开；完成后默认折叠。用 remember 持久化用户的折叠操作。
    var expanded by remember(streaming) { mutableStateOf(streaming) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (streaming) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = if (streaming) "思考中…" else "已深度思考",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        if (expanded && thinking.isNotBlank()) {
            // 左侧竖线 + 斜体浅色（ChatGPT 推理摘要的呈现方式）
            Row {
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp, end = 10.dp)
                        .width(2.dp)
                        .heightIn(min = 1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                ) { }
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        }
    }
}

/**
 * 工具调用行（ChatGPT「已搜索」样式）：全宽浅底圆角行 + 状态图标（进行中转圈 /
 * 完成 ✓ / 失败 ✗）。完成态整体淡化，历史里多个工具调用不打扰阅读。
 */
@Composable
private fun ToolCallPill(toolCall: ToolCallUi) {
    val isRunning = toolCall.status == ToolCallStatus.RUNNING
    val alpha = if (isRunning) 1f else 0.66f
    val trailing = when (toolCall.status) {
        ToolCallStatus.RUNNING -> "…"
        ToolCallStatus.DONE -> ""
        ToolCallStatus.FAILED -> "（失败）"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
            when (toolCall.status) {
                ToolCallStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = iconTint
                )
                ToolCallStatus.DONE -> Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = iconTint
                )
                ToolCallStatus.FAILED -> Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = toolCall.displayName + trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConfirmationCard(
    confirmation: ConfirmationUi,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    confirmBusy: Boolean,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "需要确认",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = confirmation.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppTextButton(
                    onClick = onReject,
                    enabled = !confirmBusy,
                    text = "取消",
                )
                Spacer(Modifier.width(4.dp))
                AppButton(
                    onClick = onConfirm,
                    enabled = !confirmBusy,
                    text = "确认",
                )
            }
        }
    }
}

/**
 * 底部 composer 区（ChatGPT 式）：不铺全宽色带，页面背景延伸到底；
 * 输入行为**大圆角轻底色胶囊**（surfaceContainerHigh，无边框无阴影不浮卡），
 * 左右留边距。待发图片缩略图与输入行同住此区。
 * 内容区（LazyColumn）底边直接贴住本区顶边。
 */
@Composable
private fun ChatComposer(
    input: String,
    isSending: Boolean,
    pendingImages: List<String>,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onRemovePendingImage: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)) {
        if (pendingImages.isNotEmpty()) {
            PendingImagesRow(
                images = pendingImages,
                onRemove = onRemovePendingImage,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(onClick = onAttachImage, enabled = !isSending) {
                    Icon(Icons.Filled.ImageIcon, contentDescription = "发送图片")
                }
                val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                val inputInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        // 整个输入区可点聚焦（无涟漪）：手机上点输入框空白/占位符区域也弹键盘
                        .clickable(
                            interactionSource = inputInteraction,
                            indication = null
                        ) { focusRequester.requestFocus() },
                    contentAlignment = Alignment.CenterStart
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = input,
                        onValueChange = onInputChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        enabled = !isSending,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(
                            MaterialTheme.colorScheme.primary
                        ),
                        maxLines = 5,
                    )
                    if (input.isEmpty()) {
                        Text(
                            text = "问点什么…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FilledIconButton(
                    onClick = onSend,
                    enabled = (input.isNotBlank() || pendingImages.isNotEmpty()) && !isSending,
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

/** 输入框上方的待发送图片行：64dp 缩略图 + 右上角移除按钮。 */
@Composable
private fun PendingImagesRow(
    images: List<String>,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        images.forEach { url ->
            Box {
                DataUrlImage(
                    dataUrl = url,
                    contentDescription = "待发送图片",
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.small)
                )
                // 外层 Box 撑起 48dp 触达区（Material 无障碍最小触控），视觉仍是 20dp 圆钮
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp)
                        .clickable { onRemove(url) }
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除图片",
                            modifier = Modifier.padding(3.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * data URL（`data:image/jpeg;base64,…`）→ Bitmap 渲染。
 * 会话历史重开时图片从 ADK SessionService 持久化字节还原，这里本地解码，不经网络。
 *
 * 性能：Base64 解码 + Bitmap 解码在 [Dispatchers.Default] 异步执行（不卡主线程），
 * 并按显示尺寸下采样（inJustDecodeBounds 先量边界 → inSampleSize 到目标像素约 2 倍上限，
 * 缩略场景目标 256px）——聊天缩略图无需全尺寸位图，避免大图 OOM 与解码抖动。
 * 解码中显示低透明度占位色块。
 */
@Composable
private fun DataUrlImage(
    dataUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    // produceState：dataUrl 变化时重新异步解码；null = 解码中或失败（统一占位）
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = dataUrl) {
        value = withContext(Dispatchers.Default) {
            decodeDataUrlBitmap(dataUrl, targetEdgePx = DATA_URL_IMAGE_TARGET_PX * 2)
        }
    }
    val bmp = bitmap   // 委托属性不能智能转换，接局部变量判空
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        // 解码中/失败兜底：低透明度占位块（历史会话数据损坏时不至于空白无解释）
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = modifier
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.ImageIcon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 缩略图显示目标边长（px）：最大用途 110x150dp 缩略，取 256 已足够清晰。 */
private const val DATA_URL_IMAGE_TARGET_PX = 256

private fun decodeDataUrlBitmap(dataUrl: String, targetEdgePx: Int): android.graphics.Bitmap? {
    if (!dataUrl.startsWith("data:image/")) return null
    return runCatching {
        val payload = dataUrl.substringAfter("base64,", "")
        if (payload.isEmpty()) return null
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        // 先只量边界不载像素，再按 2 的幂算 inSampleSize，长边降到目标（约 2×256px）以内即停
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > targetEdgePx ||
            bounds.outHeight / sample > targetEdgePx
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}
