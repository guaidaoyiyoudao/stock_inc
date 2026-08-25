package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.agent.AiChatEvent
import com.stock.dividend.data.agent.AiChatRepository
import com.stock.dividend.data.agent.AiSessionMessage
import com.stock.dividend.data.agent.AiSessionSummary
import com.stock.dividend.data.agent.ToolDisplayName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ChatRole { USER, AGENT, SYSTEM, TOOL }

/** 工具调用气泡的状态：进行中转圈，完成打勾，失败打叉。 */
enum class ToolCallStatus { RUNNING, DONE, FAILED }

@Stable
data class ToolCallUi(
    val displayName: String,
    val status: ToolCallStatus,
)

@Stable
data class ChatMessageUi(
    val role: ChatRole,
    val text: String,
    /** true 表示流式半成品：UI 应显示纯文本，禁止 Markdown 渲染。 */
    val streaming: Boolean = false,
    /** 仅 [ChatRole.TOOL] 有效：工具调用的展示名与状态。 */
    val toolCall: ToolCallUi? = null,
    /** 仅 [ChatRole.AGENT] 有效：推理模型/联网搜索时的思考过程文本。null 表示无思考过程。 */
    val thinking: String? = null,
    /** 思考过程是否仍在流式接收中（true=展开+转圈，false=可折叠）。 */
    val thinkingStreaming: Boolean = false,
    /** 仅 [ChatRole.USER] 有效：消息携带的图片 data URL（多模态输入）。 */
    val images: List<String> = emptyList(),
)

@Stable
data class ConfirmationUi(
    val requestId: String,
    val toolName: String,
    val summary: String,
)

@Stable
data class AiChatUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val sessions: List<AiSessionSummary> = emptyList(),
    val currentSessionId: String? = null,
    val isSending: Boolean = false,
    val llmConfigured: Boolean = false,
    val input: String = "",
    val pendingConfirmation: ConfirmationUi? = null,
    /** 待发送图片（data URL），与输入框文字一同发出；上限 [MAX_PENDING_IMAGES] 张。 */
    val pendingImages: List<String> = emptyList(),
    /** 当前聊天模型是否多模态（按模型名启发式探测）；false 时点「加图」给出提示而非静默禁用。 */
    val modelSupportsImages: Boolean = false,
)

@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val repository: AiChatRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeConfigured().collect { configured ->
                _uiState.update { it.copy(llmConfigured = configured) }
            }
        }
        viewModelScope.launch {
            repository.observeMultimodal().collect { multimodal ->
                _uiState.update { it.copy(modelSupportsImages = multimodal) }
            }
        }
        viewModelScope.launch { initSession() }
    }

    private suspend fun initSession() {
        var sessions = repository.listSessions()
        var currentId = sessions.firstOrNull()?.id
        if (currentId == null) {
            currentId = repository.createSession()
            sessions = repository.listSessions()
        }
        val messages = loadMessages(currentId)
        _uiState.update {
            it.copy(sessions = sessions, currentSessionId = currentId, messages = messages)
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            val sessions = repository.listSessions()
            _uiState.update { it.copy(sessions = sessions) }
        }
    }

    fun onNewSession() {
        if (_uiState.value.isSending) return
        viewModelScope.launch {
            val id = repository.createSession()
            refreshSessions()
            _uiState.update {
                it.copy(currentSessionId = id, messages = emptyList(), pendingConfirmation = null, pendingImages = emptyList())
            }
        }
    }

    fun onSelectSession(sessionId: String) {
        if (_uiState.value.isSending) return
        viewModelScope.launch {
            val messages = loadMessages(sessionId)
            _uiState.update {
                it.copy(currentSessionId = sessionId, messages = messages, pendingConfirmation = null, pendingImages = emptyList())
            }
            refreshSessions()
        }
    }

    fun onDeleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            val sessions = repository.listSessions()
            var currentId = _uiState.value.currentSessionId
            var messages = _uiState.value.messages
            if (currentId == sessionId) {
                currentId = sessions.firstOrNull()?.id
                if (currentId == null) {
                    currentId = repository.createSession()
                    messages = emptyList()
                } else {
                    messages = loadMessages(currentId)
                }
            }
            _uiState.update {
                it.copy(
                    sessions = sessions,
                    currentSessionId = currentId,
                    messages = messages,
                    pendingConfirmation = null,
                    pendingImages = emptyList()
                )
            }
        }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    /** 图片读取失败（解码/IO 异常）时给出可见提示，不静默丢弃。 */
    fun onImageLoadFailed() {
        _uiState.update { state ->
            state.copy(messages = state.messages + ChatMessageUi(ChatRole.SYSTEM, "图片读取失败，请重试"))
        }
    }

    /** 当前模型不支持图片时点「加图」入口：给可解释提示而非无反应。 */
    fun onImageUnsupported() {
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessageUi(
                    ChatRole.SYSTEM,
                    "当前对话模型不支持图片输入，请在设置中把 Model 换成多模态模型" +
                        "（如 deepseek-v4-flash-vision-exp、GLM-4.6V、GPT-4o、Qwen-VL、Claude 系列）后再试"
                )
            )
        }
    }

    /** 加入一张待发送图片（data URL，Screen 层已完成下采样与 JPEG 压缩）。 */
    fun onImagePicked(dataUrl: String) {
        _uiState.update { state ->
            if (state.pendingImages.size >= MAX_PENDING_IMAGES) {
                state.copy(
                    messages = state.messages + ChatMessageUi(ChatRole.SYSTEM, "一次最多发送 $MAX_PENDING_IMAGES 张图片")
                )
            } else {
                state.copy(pendingImages = state.pendingImages + dataUrl)
            }
        }
    }

    fun onRemovePendingImage(dataUrl: String) {
        _uiState.update { state ->
            state.copy(pendingImages = state.pendingImages - dataUrl)
        }
    }

    fun onSend() {
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return
        val text = state.input.trim()
        val images = state.pendingImages
        if ((text.isEmpty() && images.isEmpty()) || state.isSending) return
        val titleNeedsGeneration =
            state.sessions.firstOrNull { it.id == sessionId }?.title == DEFAULT_TITLE
        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessageUi(ChatRole.USER, text, images = images),
                input = "",
                pendingImages = emptyList(),
                isSending = true
            )
        }
        collectTurn(
            repository.send(sessionId, text, images),
            sessionId = sessionId,
            userText = text.ifEmpty { "图片" },
            titleOnComplete = titleNeedsGeneration
        )
    }

    fun onConfirm(confirmation: ConfirmationUi) {
        val sessionId = _uiState.value.currentSessionId ?: return
        _uiState.update { it.copy(pendingConfirmation = null, isSending = true) }
        collectTurn(repository.confirm(sessionId, confirmation.requestId, confirmed = true))
    }

    fun onReject(confirmation: ConfirmationUi) {
        val sessionId = _uiState.value.currentSessionId ?: return
        _uiState.update { it.copy(pendingConfirmation = null, isSending = true) }
        collectTurn(repository.confirm(sessionId, confirmation.requestId, confirmed = false))
    }

    private fun collectTurn(
        events: Flow<AiChatEvent>,
        sessionId: String? = null,
        userText: String? = null,
        titleOnComplete: Boolean = false,
    ) {
        var finalText: String? = null
        viewModelScope.launch {
            try {
                events.collect { event ->
                    when (event) {
                        is AiChatEvent.Thinking -> _uiState.update {
                            it.copy(messages = it.messages.appendThinking(event.text))
                        }
                        is AiChatEvent.ThinkingDone -> _uiState.update {
                            // reasoning_text.done：该段思考结束，停转圈（多轮时每轮思考结束都会触发）
                            it.copy(messages = it.messages.finalizeThinking())
                        }
                        is AiChatEvent.Partial -> _uiState.update {
                            it.copy(messages = it.messages.appendAgentText(event.text, replace = false, streaming = true))
                        }
                        is AiChatEvent.Final -> {
                            finalText = event.text
                            _uiState.update {
                                it.copy(messages = it.messages
                                    .finalizeThinking()
                                    .finalizeToolCalls(ToolCallStatus.DONE)
                                    .appendAgentText(event.text, replace = true, streaming = false))
                            }
                        }
                        is AiChatEvent.ToolStatus -> _uiState.update {
                            // 新工具开始 → 之前的进行中工具视为已完成，再追加一条新的工具气泡
                            it.copy(messages = it.messages
                                .finalizeToolCalls(ToolCallStatus.DONE)
                                .appendToolCall(ToolDisplayName.name(event.toolName)))
                        }
                        is AiChatEvent.ConfirmationRequest -> _uiState.update {
                            it.copy(
                                pendingConfirmation = ConfirmationUi(
                                    requestId = event.requestId,
                                    toolName = event.toolName,
                                    summary = event.summary
                                )
                            )
                        }
                        is AiChatEvent.Error -> _uiState.update {
                            it.copy(messages = it.messages
                                .finalizeToolCalls(ToolCallStatus.FAILED)
                                .appendSystemError(event.message))
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(isSending = false) }
                if (titleOnComplete) {
                    val session = sessionId ?: return@launch
                    val user = userText ?: return@launch
                    val reply = finalText ?: return@launch
                    viewModelScope.launch {
                        repository.ensureTitle(session, user, reply)
                        refreshSessions()
                    }
                }
            }
        }
    }

    private suspend fun loadMessages(sessionId: String): List<ChatMessageUi> =
        repository.loadMessages(sessionId).map { message ->
            ChatMessageUi(
                role = if (message.isUser) ChatRole.USER else ChatRole.AGENT,
                text = message.text,
                images = if (message.isUser) message.images else emptyList()
            )
        }

    companion object {
        private const val DEFAULT_TITLE = "新会话"

        /** 单轮最多携带图片数（控制请求体大小与识别噪声）。 */
        const val MAX_PENDING_IMAGES = 3
    }
}

/** 流式气泡：Partial 追加/新建，Final 覆盖最后一条 agent 气泡。 */
private fun List<ChatMessageUi>.appendAgentText(
    text: String,
    replace: Boolean,
    streaming: Boolean,
): List<ChatMessageUi> {
    val last = lastOrNull()
    return if (replace && last?.role == ChatRole.AGENT) {
        dropLast(1) + last.copy(text = text, streaming = streaming)
    } else if (!replace && last?.role == ChatRole.AGENT) {
        dropLast(1) + last.copy(text = last.text + text, streaming = streaming)
    } else {
        this + ChatMessageUi(ChatRole.AGENT, text, streaming = streaming)
    }
}

/**
 * 思考过程增量：追加到最后一条 agent 消息的 thinking 字段（streaming=true）。
 * 若末尾非 agent 消息，则新建一条（思考通常先于答案到达，此时 agent 气泡尚不存在）。
 */
private fun List<ChatMessageUi>.appendThinking(delta: String): List<ChatMessageUi> {
    val last = lastOrNull()
    return if (last?.role == ChatRole.AGENT) {
        dropLast(1) + last.copy(
            thinking = (last.thinking.orEmpty() + delta),
            thinkingStreaming = true,
        )
    } else {
        this + ChatMessageUi(
            role = ChatRole.AGENT,
            text = "",
            streaming = true,
            thinking = delta,
            thinkingStreaming = true,
        )
    }
}

/** 思考结束：把最后一条 agent 消息的 thinkingStreaming 置 false（可折叠）。 */
private fun List<ChatMessageUi>.finalizeThinking(): List<ChatMessageUi> {
    val last = lastOrNull() ?: return this
    return if (last.role == ChatRole.AGENT && last.thinkingStreaming) {
        dropLast(1) + last.copy(thinkingStreaming = false)
    } else this
}

/** 追加一条 RUNNING 的工具调用气泡。 */
private fun List<ChatMessageUi>.appendToolCall(displayName: String): List<ChatMessageUi> =
    this + ChatMessageUi(
        role = ChatRole.TOOL,
        text = "",
        toolCall = ToolCallUi(displayName, ToolCallStatus.RUNNING),
    )

/** 把所有 RUNNING 的工具气泡收尾为 [status]（新工具开始/回复结束/出错时调用）。 */
private fun List<ChatMessageUi>.finalizeToolCalls(status: ToolCallStatus): List<ChatMessageUi> =
    map { msg ->
        if (msg.role == ChatRole.TOOL && msg.toolCall?.status == ToolCallStatus.RUNNING) {
            msg.copy(toolCall = msg.toolCall.copy(status = status))
        } else msg
    }

/** 追加一条 SYSTEM 错误/提示文本（区别于工具气泡）。 */
private fun List<ChatMessageUi>.appendSystemError(message: String): List<ChatMessageUi> =
    this + ChatMessageUi(ChatRole.SYSTEM, message)
