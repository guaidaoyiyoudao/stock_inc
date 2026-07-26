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

/** 只读 LLM 配置源（抽象出来便于 LlmAnalysisRepository 单测用 fake 绕开 SharedPreferences）。 */
interface LlmConfigSource {
    fun observeConfig(): Flow<LlmConfig>
}

/** 用 SharedPreferences 持久化 [LlmConfig]（key 仅存本机，未加密——见 spec §9）。 */
@Singleton
class LlmConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : LlmConfigSource {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun observeConfig(): Flow<LlmConfig> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in KEYS) trySend(snapshot())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(snapshot()) }.distinctUntilChanged()

    fun snapshot(): LlmConfig = LlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
    )

    suspend fun saveConfig(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL, config.model)
            .apply()
    }

    suspend fun clearConfig() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "llm_prefs"
        private const val KEY_BASE_URL = "llm_base_url"
        private const val KEY_API_KEY = "llm_api_key"
        private const val KEY_MODEL = "llm_model"
        private val KEYS = setOf(KEY_BASE_URL, KEY_API_KEY, KEY_MODEL)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmConfigModule {
    @Binds
    @Singleton
    abstract fun bindLlmConfigSource(impl: LlmConfigRepository): LlmConfigSource
}
