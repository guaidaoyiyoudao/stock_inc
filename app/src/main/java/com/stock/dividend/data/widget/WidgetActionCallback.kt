package com.stock.dividend.data.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState

/** 点 Widget 刷新钮：前台同步拉网，更新 Glance 状态并重渲染。 */
class WidgetActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, params: androidx.glance.action.ActionParameters) {
        val widget = MarketWidget()

        // 1. 标记刷新中并重渲染
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[KEY_REFRESHING] = true
        }
        widget.update(context, glanceId)

        // 2. 前台拉网（Vivo 上靠用户主动点击，比 WorkManager 可靠）
        val repo = context.widgetDataRepository()
        val result = repo.refreshPrices()

        // 3. 写结果并重渲染
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[KEY_REFRESHING] = false
            prefs[KEY_REFRESH_FAILED] = result.isFailure
        }
        widget.update(context, glanceId)
    }

    companion object {
        val KEY_REFRESHING: Preferences.Key<Boolean> = booleanPreferencesKey("key_refreshing")
        val KEY_REFRESH_FAILED: Preferences.Key<Boolean> = booleanPreferencesKey("key_refresh_failed")
    }
}
