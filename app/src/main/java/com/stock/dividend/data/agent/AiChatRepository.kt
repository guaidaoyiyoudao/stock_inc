package com.stock.dividend.data.agent

import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.stock.dividend.data.repository.LlmConfigSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** AI Tab 对外事件。 */
sealed interface AiChatEvent {
    data class Partial(val text: String) : AiChatEvent
    data class Final(val text: String) : AiChatEvent
    data class ToolStatus(val toolName: String) : AiChatEvent
    data class ConfirmationRequest(
        val requestId: String,
        val toolName: String,
        val summary: String,
    ) : AiChatEvent

    data class Error(val message: String) : AiChatEvent
}

/** 会话摘要（列表用）。 */
data class AiSessionSummary(
    val id: String,
    val title: String,
    val updatedAtMs: Long,
)

/** 会话内的历史消息（加载用）。 */
data class AiSessionMessage(
    val isUser: Boolean,
    val text: String,
)

/**
 * 多会话编排：ADK Runner + 持久化 SessionService（生产为 RoomSessionService）。
 * 会话事件由 ADK 落库，重启后可继续；标题由 LLM 生成后写入 session state。
 */
@Singleton
class AiChatRepository @Inject constructor(
    private val configSource: LlmConfigSource,
    private val agentFactory: AiAgentFactory,
    private val sessionService: SessionService,
    private val titleGenerator: AiTitleGenerator,
) {

    fun observeConfigured(): Flow<Boolean> = configSource.observeConfig().map { it.isComplete }

    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun listSessions(): List<AiSessionSummary> = runCatching {
        sessionService.listSessions(APP_NAME, USER_ID).sessions
            .mapNotNull { session ->
                val id = session.key.id ?: return@mapNotNull null
                AiSessionSummary(
                    id = id,
                    title = session.state[TITLE_KEY] as? String ?: DEFAULT_TITLE,
                    updatedAtMs = session.lastUpdateTime.toEpochMilliseconds()
                )
            }
            .sortedByDescending { it.updatedAtMs }
    }.getOrDefault(emptyList())

    suspend fun createSession(): String {
        val session = sessionService.createSession(
            SessionKey(APP_NAME, USER_ID, null),
            state = mapOf(TITLE_KEY to DEFAULT_TITLE)
        )
        return session.key.id ?: error("会话创建失败")
    }

    suspend fun deleteSession(sessionId: String) {
        runCatching { sessionService.deleteSession(SessionKey(APP_NAME, USER_ID, sessionId)) }
    }

    suspend fun loadMessages(sessionId: String): List<AiSessionMessage> = runCatching {
        val session = sessionService.getSession(SessionKey(APP_NAME, USER_ID, sessionId))
            ?: return@runCatching emptyList()
        session.events.mapNotNull { event ->
            val text = event.content?.parts.orEmpty()
                .filter { it.thought != true && it.text != null }
                .joinToString("") { it.text!! }
            when {
                event.author == Role.USER && text.isNotEmpty() ->
                    AiSessionMessage(isUser = true, text = text)
                event.author == AiAgentFactory.AGENT_NAME && !event.partial && text.isNotEmpty() ->
                    AiSessionMessage(isUser = false, text = text)
                else -> null
            }
        }
    }.getOrDefault(emptyList())

    /** 新会话首轮结束后由 LLM 起标题并持久化；已有标题则跳过。 */
    suspend fun ensureTitle(sessionId: String, userText: String, replyText: String) {
        val config = configSource.observeConfig().first()
        if (!config.isComplete) return
        val session = sessionService.getSession(SessionKey(APP_NAME, USER_ID, sessionId)) ?: return
        val current = session.state[TITLE_KEY] as? String
        if (current != null && current != DEFAULT_TITLE) return
        val title = titleGenerator.generate(config, userText, replyText) ?: return
        runCatching {
            sessionService.appendEvent(
                session,
                Event(author = Role.SYSTEM, actions = EventActions(stateDelta = mutableMapOf(TITLE_KEY to title)))
            )
        }
    }

    fun send(sessionId: String, text: String): Flow<AiChatEvent> = runTurn(
        sessionId,
        Content(role = Role.USER, parts = listOf(Part(text = text)))
    )

    fun confirm(sessionId: String, requestId: String, confirmed: Boolean): Flow<AiChatEvent> = runTurn(
        sessionId,
        Content(
            role = Role.USER,
            parts = listOf(
                Part(
                    functionResponse = FunctionResponse(
                        name = "adk_request_confirmation",
                        id = requestId,
                        response = mapOf("confirmed" to confirmed)
                    )
                )
            )
        )
    )

    private fun runTurn(sessionId: String, newMessage: Content): Flow<AiChatEvent> = flow {
        val config = configSource.observeConfig().first()
        if (!config.isComplete) {
            emit(AiChatEvent.Error("未配置 LLM，请先到设置页完成配置"))
            return@flow
        }
        val runner = InMemoryRunner(
            app = App(appName = APP_NAME, rootAgent = agentFactory.create(config)),
            sessionService = sessionService
        )
        try {
            runner.runAsync(
                userId = USER_ID,
                sessionId = sessionId,
                newMessage = newMessage,
                runConfig = RunConfig(streamingMode = StreamingMode.SSE)
            ).collect { event ->
                emitEvent(event)?.let { emit(it) }
            }
        } catch (e: Exception) {
            emit(AiChatEvent.Error(e.message?.takeIf { it.isNotBlank() } ?: "请求失败，请稍后重试"))
        }
    }

    private fun emitEvent(event: Event): AiChatEvent? {
        val parts = event.content?.parts.orEmpty()
        val text = parts.filter { it.thought != true && it.text != null }
            .joinToString("") { it.text!! }
        val functionCalls = parts.mapNotNull { it.functionCall }
        if (functionCalls.isNotEmpty()) {
            val confirmation = functionCalls.firstOrNull { it.name == "adk_request_confirmation" }
            if (confirmation != null) {
                val requestId = confirmation.id ?: return null
                val original = confirmation.args["originalFunctionCall"] as? Map<*, *>
                val toolName = original?.get("name")?.toString() ?: "未知操作"
                val callArgs = (original?.get("args") as? Map<*, *>)
                    ?.mapKeys { it.key.toString() } ?: emptyMap()
                return AiChatEvent.ConfirmationRequest(
                    requestId = requestId,
                    toolName = toolName,
                    summary = ConfirmationSummaryBuilder.summarize(toolName, callArgs)
                )
            }
            return AiChatEvent.ToolStatus(functionCalls.first().name)
        }
        if (event.partial) {
            return if (text.isNotEmpty()) AiChatEvent.Partial(text) else null
        }
        return if (text.isNotEmpty()) AiChatEvent.Final(text) else null
    }

    companion object {
        const val APP_NAME = "stock_dividend_ai"
        const val USER_ID = "local-user"
        const val DEFAULT_TITLE = "新会话"
        private const val TITLE_KEY = "title"
    }
}
