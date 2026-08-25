package com.stock.dividend.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.Motion
import com.stock.dividend.ui.theme.tabularNumberStyle
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun StockCard(
    name: String,
    code: String,
    shares: Int = 0,
    forecastIncomeAmount: Double? = null,
    marketValueAmount: Double? = null,
    lastUpdated: Long? = null,
    currentPrice: Double? = null,
    latestYearlyDividend: Double? = null,
    /** 当日涨跌幅%（A股惯例红涨绿跌）；null 或 0 不展示。 */
    changePct: Double? = null,
    /** 周线 BOLL 带（切到 BOLL 视图时渲染；null 表示未加载/无数据）。 */
    bollBand: BollBand? = null,
    /** 切到 BOLL 视图时回调，ViewModel 据此按需懒加载。 */
    onLoadBoll: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 纯自选股（shares=0）：用更柔和的背景色 + 「自选」标签与持仓股区分。 */
    isWatchOnly: Boolean = false
) {
    // 每张卡片独立切换「股息率 ↔ BOLL」，仅内存状态（不持久化，符合用户决策）。
    var showBoll by remember(code) { mutableStateOf(false) }
    LaunchedEffect(showBoll) {
        if (showBoll) onLoadBoll()
    }

    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .stockCardSharedBounds(code)
            .clickable(onClick = onClick),
        // 自选股用 surfaceVariant（柔和），持仓股用 surface；AppCard 默认 tone=Surface，
        // 自选股在这里用 List tone 复用 surface，再单独覆盖 containerColor 区分。
        tone = if (isWatchOnly) AppCardTone.List else AppCardTone.Surface,
    ) {
        // animateContentSize：BOLL 懒加载二段变高（占位 54dp → 数据 ~150dp）时整卡平滑展开
        Column(modifier = Modifier.animateContentSize(tween(Motion.DurationMedium, easing = Motion.Standard))) {
            // 坐标轴切换按钮：右上角浮在横轴上方。showBoll=false 显示股息率横轴（TrendingUp 图标），
            // showBoll=true 显示 BOLL 横轴（ShowChart 图标）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 4.dp, top = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = { showBoll = !showBoll }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (showBoll) Icons.Default.ShowChart else Icons.Default.TrendingUp,
                        contentDescription = if (showBoll) "切换到股息率横轴" else "切换到 BOLL 横轴",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 坐标轴主体：按切换状态渲染股息率横轴或 BOLL 横轴（滑动+淡入切换）。
            // ⚠️ SizeTransform 必须 clip=false：BOLL 懒加载是两段式（先 54dp 占位、数据到达后
            // 长高到 ~150dp），默认 clip=true 会在尺寸动画期间把底部「现价落点/带内%」行裁掉。
            AnimatedContent(
                targetState = showBoll,
                label = "axisSwitch",
                transitionSpec = {
                    ContentTransform(
                        targetContentEnter = slideInHorizontally(
                            animationSpec = tween(Motion.DurationShort, easing = Motion.EmphasizedDecelerate),
                            initialOffsetX = { it / 4 },
                        ) + fadeIn(tween(Motion.DurationShort)),
                        initialContentExit = slideOutHorizontally(
                            animationSpec = tween(Motion.DurationShort, easing = Motion.EmphasizedAccelerate),
                            targetOffsetX = { -it / 4 },
                        ) + fadeOut(tween(Motion.DurationShort)),
                        sizeTransform = SizeTransform(clip = false),
                    )
                },
            ) { boll ->
                if (boll) {
                    BollPriceScale(
                        currentPrice = currentPrice,
                        band = bollBand,
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp)
                    )
                } else {
                    DividendPriceScale(
                        currentPrice = currentPrice,
                        latestYearlyDividend = latestYearlyDividend,
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                CompanyIcon(
                    stockCode = code,
                    stockName = name,
                    size = 44
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatStockCode(code),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (shares > 0) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        MaterialTheme.shapes.extraSmall
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$shares 股",
                                    style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else if (isWatchOnly) {
                            // 纯自选股：显示「自选」标签，与有持仓的股票区分
                            Box(
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.tertiaryContainer,
                                        MaterialTheme.shapes.extraSmall
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "自选",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        // 当日涨跌幅 pill（红涨绿跌，A股惯例）；null/0 不展示避免噪音
                        changePct?.takeIf { it != 0.0 }?.let { pct ->
                            val ext = LocalExtendedColors.current
                            val color = if (pct > 0) ext.positive else ext.negative
                            Box(
                                modifier = Modifier
                                    .background(color.copy(alpha = 0.12f), MaterialTheme.shapes.extraSmall)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                PercentText(
                                    value = pct,
                                    signed = true,
                                    decimals = 2,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = color,
                                    animated = false, // 行情轮询高频刷新的列表行，关闭滚动/闪色动画
                                )
                            }
                        }
                    }
                    if (lastUpdated != null) {
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = "更新于 ${formatTimestamp(lastUpdated)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            if (marketValueAmount != null || forecastIncomeAmount != null) {
                Column(horizontalAlignment = Alignment.End) {
                    if (marketValueAmount != null) {
                        AmountText(
                            value = marketValueAmount,
                            style = MaterialTheme.typography.titleMedium,
                            colored = false,
                            color = MaterialTheme.colorScheme.tertiary,
                            animated = false, // 行情轮询高频刷新的列表行，关闭滚动/闪色动画
                        )
                        Text(
                            text = "市值",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    if (forecastIncomeAmount != null) {
                        AmountText(
                            value = forecastIncomeAmount,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            colored = false,
                            color = MaterialTheme.colorScheme.primary,
                            animated = false, // 行情轮询高频刷新的列表行，关闭滚动/闪色动画
                        )
                        Text(
                            text = "预测收入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        }
    }
}

private fun formatStockCode(code: String): String {
    return code.replace("sh.", "SH ").replace("sz.", "SZ ")
}

private val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

private fun formatTimestamp(timestamp: Long): String {
    return dateFormat.format(java.util.Date(timestamp))
}
