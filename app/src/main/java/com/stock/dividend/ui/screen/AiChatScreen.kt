package com.stock.dividend.ui.screen

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch

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

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

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

        if (state.pendingImages.isNotEmpty()) {
            PendingImagesRow(
                images = state.pendingImages,
                onRemove = viewModel::onRemovePendingImage
            )
        }

        ChatInputBar(
            input = state.input,
            isSending = state.isSending,
            hasPendingImages = state.pendingImages.isNotEmpty(),
            onInputChanged = viewModel::onInputChanged,
            onSend = viewModel::onSend,
            onAttachImage = onAttachImage
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
        ChatRole.USER -> Column(
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
                // ChatGPT 式用户气泡：浅色大圆角（28dp）、限宽靠右、内边距舒展
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(0.82f)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                    )
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
                    MarkdownText(markdown = message.text)
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
 * 回复底部操作行（ChatGPT 式）：复制全文到剪贴板，小图标弱化不抢正文视线。
 */
@Composable
private fun CopyActionRow(text: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
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
 * ChatGPT 式输入栏：单个大圆角胶囊容器（细边框 + 轻阴影）内嵌
 * 「+ 附件按钮 · 无边框多行输入 · 圆形发送按钮」，focus/发送态随主题色。
 */
@Composable
private fun ChatInputBar(
    input: String,
    isSending: Boolean,
    hasPendingImages: Boolean,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 2.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = onAttachImage, enabled = !isSending) {
                Icon(Icons.Filled.ImageIcon, contentDescription = "发送图片")
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = input,
                    onValueChange = onInputChanged,
                    modifier = Modifier.fillMaxWidth(),
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
                enabled = (input.isNotBlank() || hasPendingImages) && !isSending,
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

/** 输入框上方的待发送图片行：64dp 缩略图 + 右上角移除按钮。 */
@Composable
private fun PendingImagesRow(
    images: List<String>,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
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
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(20.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "移除图片",
                        modifier = Modifier
                            .clickable { onRemove(url) }
                            .padding(3.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * data URL（`data:image/jpeg;base64,…`）→ Bitmap 渲染。
 * 会话历史重开时图片从 ADK SessionService 持久化字节还原，这里本地解码，不经网络。
 */
@Composable
private fun DataUrlImage(
    dataUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(dataUrl) { decodeDataUrlBitmap(dataUrl) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        // 解码失败兜底：占位块（历史会话数据损坏时不至于空白无解释）
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
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

private fun decodeDataUrlBitmap(dataUrl: String): android.graphics.Bitmap? {
    if (!dataUrl.startsWith("data:image/")) return null
    return runCatching {
        val payload = dataUrl.substringAfter("base64,", "")
        if (payload.isEmpty()) return null
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
