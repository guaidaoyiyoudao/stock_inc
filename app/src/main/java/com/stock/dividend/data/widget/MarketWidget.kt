package com.stock.dividend.data.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.stock.dividend.MainActivity
import java.util.concurrent.TimeUnit

class MarketWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.widgetDataRepository()
        val state = runCatching { repo.loadSnapshot() }.getOrNull() ?: WidgetUiState.EMPTY
        val isRefreshing = readBool(context, id, WidgetActionCallback.KEY_REFRESHING)
        val refreshFailed = readBool(context, id, WidgetActionCallback.KEY_REFRESH_FAILED)
        provideContent {
            GlanceTheme {
                MarketWidgetContent(state, isRefreshing, refreshFailed)
            }
        }
    }

    @Composable
    private fun MarketWidgetContent(state: WidgetUiState, isRefreshing: Boolean, refreshFailed: Boolean) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            if (state.holdingCount == 0) {
                EmptyContent()
            } else {
                Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
                    HeaderRow(isRefreshing)
                    Spacer(GlanceModifier.height(8.dp))
                    Text(
                        "¥ " + formatMoney(state.totalMarketValue),
                        style = TextStyle(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    PnlText(state.costBasisPnl, state.costBasisPnlPercent)
                    if (state.fireGoalAmount > 0.0) {
                        Spacer(GlanceModifier.height(8.dp))
                        Text(
                            "FIRE 进度  " + "%.0f".format(state.fireProgress * 100) + "%",
                            style = TextStyle(fontSize = 12.sp)
                        )
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    FreshnessText(state.lastPriceUpdatedAt, state.pricedCount, state.holdingCount, refreshFailed)
                }
            }
        }
    }

    @Composable
    private fun EmptyContent() {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("暂无持仓", style = TextStyle(fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.height(4.dp))
            Text("打开 App 添加", style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color.Gray)))
        }
    }

    @Composable
    private fun HeaderRow(isRefreshing: Boolean) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("持仓总览", style = TextStyle(fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = if (isRefreshing) "刷新中…" else "↻ 刷新",
                style = TextStyle(fontSize = 12.sp),
                modifier = GlanceModifier.clickable(actionRunCallback<WidgetActionCallback>())
            )
        }
    }

    @Composable
    private fun PnlText(pnl: Double, percent: Double) {
        // A 股红涨绿跌
        val color = if (pnl >= 0) Color(0xFFE53935) else Color(0xFF43A047)
        val sign = if (pnl >= 0) "+" else ""
        Text(
            "$sign${formatMoney(pnl)}  $sign${"%.2f".format(percent * 100)}%",
            style = TextStyle(color = ColorProvider(color))
        )
    }

    @Composable
    private fun FreshnessText(updatedAt: Long, priced: Int, total: Int, refreshFailed: Boolean) {
        val freshness = if (updatedAt == 0L) "无价格缓存" else relativeMinutes(updatedAt)
        val pricedInfo = "$priced/$total 只已更新"
        val text = if (refreshFailed) "刷新失败，显示上次缓存" else "$freshness · $pricedInfo"
        Text(
            text,
            style = TextStyle(fontSize = 11.sp, color = ColorProvider(Color.Gray))
        )
    }

    private fun relativeMinutes(timestamp: Long): String {
        val mins = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - timestamp)
        return if (mins < 1) "刚刚更新" else "$mins 分钟前"
    }

    private fun formatMoney(v: Double): String = "%,.2f".format(v)

    private suspend fun readBool(context: Context, id: GlanceId, key: Preferences.Key<Boolean>): Boolean =
        try {
            val prefs: Preferences = getAppWidgetState(context, id)
            prefs[key] ?: false
        } catch (e: Exception) {
            false
        }
}
