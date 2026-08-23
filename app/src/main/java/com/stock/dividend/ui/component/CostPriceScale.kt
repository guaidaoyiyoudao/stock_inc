package com.stock.dividend.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stock.dividend.data.repository.QuoteSnapshot
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle

/**
 * 成本现价横轴（个股持仓卡第三视图）。与 [BollPriceScale] 视觉对等：
 * **成本价固定在轴正中，现价点按 ±30% 区间比例定位**（0=成本-30%，1=成本+30%，
 * 贴边 = 偏离超 ±30%），成本→现价区间按盈亏着色，一眼看出浮盈浮亏方向与幅度。
 *
 * 数据准确性：轴比例仅用于点位视觉定位（±30% 展示区间），精确数字全部在底部
 * 文字行（[MoneyFormatter]/[PercentFormatter] 原样渲染），不依赖轴比例。
 *
 * @param costPrice 摊薄成本价（null/≤0 → 占位态）
 * @param currentPrice 现价（null/≤0 → 占位态）
 * @param unrealizedPnl 浮动盈亏额（null 时组件按 (现价-成本)×推导展示口径与外部一致前由调用方保证，
 *   优先展示外部传入的精确值）
 * @param unrealizedPnlRate 浮动盈亏率（小数，如 0.111 = +11.1%）
 * @param realizedPnl FIFO 已实现盈亏（null = 无卖出记录，不展示）
 * @param quote 盘口（PE/PB/换手，null 或全缺时不展示估值行）
 */
@Composable
fun CostPriceScale(
    costPrice: Double?,
    currentPrice: Double?,
    unrealizedPnl: Double?,
    unrealizedPnlRate: Double?,
    realizedPnl: Double?,
    quote: QuoteSnapshot?,
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current
    val textMeasurer = rememberTextMeasurer()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "低于成本",
                style = MaterialTheme.typography.labelSmall,
                color = ext.negative,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "成本现价",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "高于成本",
                style = MaterialTheme.typography.labelSmall,
                color = ext.positive,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (costPrice == null || costPrice <= 0.0 || currentPrice == null || currentPrice <= 0.0) {
            // 占位：与其他横轴空态等高，避免切换时卡片高度跳动
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无成本或现价数据",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val cost = costPrice
            val price = currentPrice
            val pnl = unrealizedPnl ?: (price - cost)
            val pnlRate = unrealizedPnlRate ?: ((price - cost) / cost)
            val pnlColor = when {
                pnl > 0 -> ext.positive
                pnl < 0 -> ext.negative
                else -> MaterialTheme.colorScheme.onSurface
            }
            val beyondLow = price < cost * 0.7
            val beyondHigh = price > cost * 1.3

            // 成本 tick（轴正中锚点，主色样式）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "成本",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(26.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Text(
                        text = "%.2f".format(cost),
                        style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Box(modifier = Modifier.size(5.dp))
                }
            }

            // 现价位置轴：成本(0.5) → 现价着色，点贴边 = 偏离超 ±30%
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                val dotSize = 12.dp
                val usable = maxWidth - dotSize
                val span = 0.6 * cost
                val fraction = ((price - cost * 0.7) / span).coerceIn(0.0, 1.0).toFloat()
                val trackY = 32.dp
                val dotX = usable * fraction
                val costX = usable * 0.5f
                val dotCenterX = dotX + dotSize / 2

                // 价签精确量宽 → 在点正上方居中；靠近两端时钳制在边界内
                val labelStyle = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                val labelText = "%.2f".format(price)
                val labelWidth = with(LocalDensity.current) {
                    textMeasurer.measure(AnnotatedString(labelText), labelStyle).size.width.toDp()
                }
                Text(
                    text = labelText,
                    style = labelStyle,
                    color = pnlColor,
                    modifier = Modifier.offset(
                        x = (dotCenterX - labelWidth / 2).coerceIn(0.dp, maxWidth - labelWidth),
                        y = 12.dp
                    )
                )

                // 轨道
                Box(
                    modifier = Modifier
                        .offset(x = dotSize / 2, y = trackY)
                        .width(maxWidth - dotSize)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                // 成本 → 现价着色段（盈绿亏红，半透明）
                val segStart = minOf(costX, dotX)
                val segWidth = if (dotX > costX) dotX - costX else costX - dotX
                if (segWidth > 0.dp) {
                    Box(
                        modifier = Modifier
                            .offset(x = dotSize / 2 + segStart, y = trackY)
                            .width(segWidth)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(pnlColor.copy(alpha = 0.45f))
                    )
                }
                // 成本中点刻痕
                Box(
                    modifier = Modifier
                        .offset(x = dotSize / 2 + costX - 1.dp, y = trackY - 3.dp)
                        .width(2.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                )
                // 现价点
                Box(
                    modifier = Modifier
                        .offset(x = dotX, y = trackY + 2.dp - dotSize / 2)
                        .size(dotSize)
                        .clip(CircleShape)
                        .background(pnlColor)
                        .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }

            // 底部：现价 + 浮盈（精确数字，红涨绿跌）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        beyondHigh -> "现价 ${MoneyFormatter.withSymbol(price)}（超成本 +30%）"
                        beyondLow -> "现价 ${MoneyFormatter.withSymbol(price)}（低于成本 -30%）"
                        else -> "现价 ${MoneyFormatter.withSymbol(price)}"
                    },
                    style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "浮盈/亏 ${MoneyFormatter.withSign(pnl)}（${PercentFormatter.withSign(pnlRate * 100.0, decimals = 1)}）",
                    style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                    fontWeight = FontWeight.SemiBold,
                    color = pnlColor
                )
            }

            // 盘口估值行（PE/PB/换手；三者全缺不展示）
            val hasValuation = quote?.pe != null || quote?.pb != null || quote?.turnoverRate != null
            if (hasValuation) {
                Text(
                    text = buildString {
                        append("PE ")
                        append(quote?.pe?.let { "%.2f".format(it) } ?: "—")
                        append("  PB ")
                        append(quote?.pb?.let { "%.2f".format(it) } ?: "—")
                        append("  换手 ")
                        append(quote?.turnoverRate?.let { "${"%.2f".format(it)}%" } ?: "—")
                    },
                    style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // FIFO 已实现盈亏（有过卖出才展示）
            realizedPnl?.let { realized ->
                val realizedColor = when {
                    realized > 0 -> ext.positive
                    realized < 0 -> ext.negative
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = "已实现盈亏 ${MoneyFormatter.withSign(realized)}（FIFO）",
                    style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                    fontWeight = FontWeight.SemiBold,
                    color = realizedColor
                )
            }
        }
    }
}
