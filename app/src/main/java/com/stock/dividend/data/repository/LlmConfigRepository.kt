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

    /**
     * 视觉识别模型的生效配置（响应式）。
     * 回退依赖全局 key（同属智谱时），故全局 key 变化也重新发射。
     */
    fun observeVisionConfig(): Flow<LlmConfig> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in KEYS || key in VISION_KEYS) trySend(visionSnapshot())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(visionSnapshot()) }.distinctUntilChanged()

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

    /** 视觉识别模型快照：baseUrl 固定智谱；key 为空且全局 LLM 也是智谱时自动复用全局 key。 */
    fun visionSnapshot(): LlmConfig = LlmConfig(
        baseUrl = VISION_BASE_URL,
        apiKey = prefs.getString(KEY_VISION_API_KEY, "").orEmpty().ifBlank {
            val globalBase = prefs.getString(KEY_BASE_URL, "").orEmpty().trimEnd('/')
            if (globalBase.equals(VISION_BASE_URL.trimEnd('/'), ignoreCase = true)) {
                prefs.getString(KEY_API_KEY, "").orEmpty()
            } else ""
        },
        model = prefs.getString(KEY_VISION_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_VISION_MODEL,
    )

    suspend fun saveVisionConfig(apiKey: String, model: String) {
        prefs.edit()
            .putString(KEY_VISION_API_KEY, apiKey.trim())
            .putString(KEY_VISION_MODEL, model.trim())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "llm_prefs"
        private const val KEY_BASE_URL = "llm_base_url"
        private const val KEY_API_KEY = "llm_api_key"
        private const val KEY_MODEL = "llm_model"
        private val KEYS = setOf(KEY_BASE_URL, KEY_API_KEY, KEY_MODEL)

        // ── 视觉识别模型（截图导入，GLM-4.6V-Flash）──
        private const val KEY_VISION_API_KEY = "vision_api_key"
        private const val KEY_VISION_MODEL = "vision_model"
        private val VISION_KEYS = setOf(KEY_VISION_API_KEY, KEY_VISION_MODEL)

        /** 智谱 BigModel OpenAI 兼容端点（视觉模型固定走智谱）。 */
        const val VISION_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/"

        /** 默认视觉模型：GLM-4.6V-Flash（免费）。 */
        const val DEFAULT_VISION_MODEL = "glm-4.6v-flash"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmConfigModule {
    @Binds
    @Singleton
    abstract fun bindLlmConfigSource(impl: LlmConfigRepository): LlmConfigSource
}
