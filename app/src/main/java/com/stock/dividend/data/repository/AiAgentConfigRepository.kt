package com.stock.dividend.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用 SharedPreferences 持久化 [AiAgentConfig]（key 仅存本机，未加密——与 [LlmConfigRepository] 一致）。
 * 与 LLM 端点配置（llm_prefs）分文件存储，互不污染。
 */
@Singleton
class AiAgentConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : AiAgentConfigSource {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun observe(): Flow<AiAgentConfig> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in KEYS) trySend(snapshot())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(snapshot()) }.distinctUntilChanged()

    fun snapshot(): AiAgentConfig = AiAgentConfig(
        systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, "").orEmpty(),
        temperature = prefs.getString(KEY_TEMPERATURE, null)?.toFloatOrNull(),
        maxTokens = prefs.getString(KEY_MAX_TOKENS, null)?.toIntOrNull(),
        webSearch = prefs.getBoolean(KEY_WEB_SEARCH, false),
    )

    suspend fun saveConfig(config: AiAgentConfig) {
        prefs.edit()
            .putString(KEY_SYSTEM_PROMPT, config.systemPrompt)
            .putString(KEY_TEMPERATURE, config.temperature?.toString())
            .putString(KEY_MAX_TOKENS, config.maxTokens?.toString())
            .putBoolean(KEY_WEB_SEARCH, config.webSearch)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ai_agent_prefs"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_WEB_SEARCH = "web_search"
        private val KEYS = setOf(KEY_SYSTEM_PROMPT, KEY_TEMPERATURE, KEY_MAX_TOKENS, KEY_WEB_SEARCH)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AiAgentConfigModule {
    @Binds
    @Singleton
    abstract fun bindAiAgentConfigSource(impl: AiAgentConfigRepository): AiAgentConfigSource
}
