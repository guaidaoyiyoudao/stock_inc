package com.stock.dividend.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同花顺扶摇数据源（https://fuyao.aicubes.cn）API Key 的本机存储。
 *
 * key 仅存 SharedPreferences（未加密，与 [LlmConfigRepository] 一致），由用户在
 * 设置 → 数据 → 数据源 页填写；未填写时 [enabled] 为 false，各数据域直走东财/腾讯
 * 候补源，功能完整可用（同花顺源整体禁用而非报错）。
 */
@Singleton
class FuyaoConfig @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** key 已配置（fuyao 源启用）。每次调用时读取，保存后立即生效。 */
    val enabled: Boolean get() = apiKey.isNotBlank()

    val apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()

    fun observeApiKey(): Flow<String> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_API_KEY) trySend(apiKey)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(apiKey) }.distinctUntilChanged()

    suspend fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    companion object {
        private const val PREFS_NAME = "fuyao_prefs"
        private const val KEY_API_KEY = "fuyao_api_key"
    }
}
