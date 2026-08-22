package com.stock.dividend.ui.screen

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
            onOpenAiSettings = onOpenAiSettings,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
    onOpenAiSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = currentTitle,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onOpenSessions) {
            Icon(Icons.Filled.Chat, contentDescription = "会话列表")
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
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "我是你的股息投资助手",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "可以查持仓、看行情、估值、买入线，也可以帮你记账和改持仓；\n多模态模型支持发送持仓/成交截图直接识别导入",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        SUGGESTIONS.forEach { suggestion ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                onClick = { onSuggestionClick(suggestion) }
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                                .clip(MaterialTheme.shapes.small)
                        )
                    }
                }
            }
            if (message.text.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
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
            // 最终回复
            if (message.text.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    // 流式半成品或语法不完整的 Markdown 只显示纯文本，完整后再渲染
                    if (!message.streaming && canRenderMarkdown(message.text)) {
                        MarkdownText(
                            markdown = message.text,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    } else {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
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
 * 思考过程区：推理模型/联网搜索时实时展示模型的 reasoning。
 * - 流式接收中（streaming=true）：默认展开 + 标题「思考中…」+ 转圈，让用户知道没卡住。
 * - 接收完成（streaming=false）：默认折叠，标题「思考过程」，用户可点开查看。
 * 文本用浅色小字纯文本展示（不渲染 Markdown，避免流式闪烁）。
 */
@Composable
private fun ThinkingSection(thinking: String, streaming: Boolean) {
    // 流式时强制展开；完成后默认折叠。用 remember 持久化用户的折叠操作。
    var expanded by remember(streaming) { mutableStateOf(streaming) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 32.dp) // 留出与用户消息对称的右边距
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
                text = if (streaming) "思考中…" else "思考过程",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
            Text(
                text = if (expanded) "▲" else "▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded && thinking.isNotBlank()) {
            Text(
                text = thinking,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 工具调用胶囊：居中、淡背景圆角，左侧状态图标（进行中转圈 / 完成 ✓ / 失败 ✗）。
 * 完成态整体淡化（alpha 0.6），让历史记录里多个工具调用不打扰阅读。
 */
@Composable
private fun ToolCallPill(toolCall: ToolCallUi) {
    val isRunning = toolCall.status == ToolCallStatus.RUNNING
    val alpha = if (isRunning) 1f else 0.6f
    val trailing = when (toolCall.status) {
        ToolCallStatus.RUNNING -> "…"
        ToolCallStatus.DONE -> ""
        ToolCallStatus.FAILED -> "（失败）"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = alpha * 0.7f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.alpha(alpha)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val iconTint = MaterialTheme.colorScheme.onSecondaryContainer
            when (toolCall.status) {
                ToolCallStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = iconTint
                )
                ToolCallStatus.DONE -> Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = iconTint
                )
                ToolCallStatus.FAILED -> Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = toolCall.displayName + trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
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

@Composable
private fun ChatInputBar(
    input: String,
    isSending: Boolean,
    hasPendingImages: Boolean,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        IconButton(onClick = onAttachImage, enabled = !isSending) {
            Icon(Icons.Filled.ImageIcon, contentDescription = "发送图片")
        }
        Spacer(Modifier.width(4.dp))
        AppTextField(
            value = input,
            onValueChange = onInputChanged,
            modifier = Modifier.weight(1f),
            placeholder = { Text("问点什么…") },
            maxLines = 4,
            enabled = !isSending
        )
        Spacer(Modifier.width(8.dp))
        Box(contentAlignment = Alignment.Center) {
            FilledIconButton(
                onClick = onSend,
                enabled = (input.isNotBlank() || hasPendingImages) && !isSending
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
